package evebot;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import evebot.models.ClientState;
import evebot.models.Mission;

import java.awt.Rectangle;
import java.nio.file.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Removed switching clients for now, only when in focus
 */
public class ClientManager {

    private final StateManager     stateManager;
    private final MissionProcessor missionProcessor;
    private final MissionConfig    missionConfig;
    private final Gson             gson;

    private final Map<String, Rectangle>        clientMissionAreas = new ConcurrentHashMap<>();
    private final Map<String, ClientWindowInfo> clientWindows      = new ConcurrentHashMap<>();
    private final Map<String, Integer>          agentRetries       = new ConcurrentHashMap<>();
    private final Map<String, Integer>          gateWarpRetries    = new ConcurrentHashMap<>();

    /** Prevents concurrent monitorClients executions when the thread pool queues up ticks. */
    private final AtomicBoolean monitorRunning = new AtomicBoolean(false);

    /** Wall-clock time of the last actual client switch (any {@link #bringToForeground} call).
     *  Used by the global watchdog in {@link #monitorClients} to force a switch when one client
     *  has hogged the foreground too long (e.g. a long combat) — preventing the idle client from
     *  being podded while AFK in space. */
    private volatile LocalDateTime lastClientSwitch = LocalDateTime.now();

    private static final int  MAX_AGENT_RETRIES        = 2;
    private static final int  MAX_GATE_WARP_RETRIES    = 2;
    private static final long UNDOCK_WAIT_SECS          = 13;
    /** Hard floor: never declare gate warp "landed" before this many seconds, no matter what
     *  the warp_active template reports — guards against a too-early start with no target yet. */
    private static final long WARP_LAND_MIN_SECS        = 10;
    /** Consecutive polls that must BOTH show warp_active gone before landing is confirmed.
     *  warp_active is a solid colour block; a single sub-threshold frame should not end warp. */
    private static final int  WARP_END_MISS_CONFIRM     = 2;
    /** Pixels below the EVE-O Preview thumbnail template centre to click (skips the title bar). */
    private static final int  THUMBNAIL_CLICK_OFFSET_Y = 75;
    /** Global watchdog: if no client switch has happened for this many seconds and a second
     *  client exists, force a switch so the idle client cannot sit AFK in space (and get podded)
     *  while the foreground client is busy in a long combat. */
    private static final long FORCE_SWITCH_SECS         = 60;
    /** Minimum seconds between view-nudges (camera drag to refresh a stale frame) while
     *  waiting on a detection — keeps the nudge from firing every poll tick. */
    private static final long VIEW_NUDGE_THROTTLE_SECS  = 10;

    public ClientManager(StateManager stateManager) {
        this.stateManager     = stateManager;
        this.missionProcessor = new MissionProcessor();
        this.missionConfig    = new MissionConfig();
        this.gson             = new Gson();
        loadCalibratedCoordinates();
    }

    /** Stop the resident Python daemon on shutdown. */
    public void shutdown() {
        missionProcessor.getOcrBridge().shutdown();
    }

    // -----------------------------------------------------------------------
    // Coordinate loading
    // -----------------------------------------------------------------------

    private void loadCalibratedCoordinates() {
        List<Path> candidates = List.of(
                Paths.get("config", "mission_coordinates.json"),
                Paths.get("mission_coordinates.json")
        );
        for (Path path : candidates) {
            if (Files.exists(path)) {
                try {
                    String content = new String(Files.readAllBytes(path));
                    JsonObject json = gson.fromJson(content, JsonObject.class);
                    JsonObject missionArea = json.getAsJsonObject("mission_area");
                    if (missionArea != null) {
                        Rectangle defaultRect = new Rectangle(
                                missionArea.get("x").getAsInt(),
                                missionArea.get("y").getAsInt(),
                                missionArea.get("width").getAsInt(),
                                missionArea.get("height").getAsInt()
                        );
                        clientMissionAreas.put("default", defaultRect);
                        System.out.println("[ClientManager] Loaded coordinates from " + path);
                        return;
                    }
                } catch (Exception e) {
                    System.err.println("[ClientManager] Error reading coordinates: " + e.getMessage());
                }
            }
        }
        log("[ClientManager] No coordinate file found — using hardcoded defaults.");
        clientMissionAreas.put("default", new Rectangle(440, 92, 988, 336));
    }

    // -----------------------------------------------------------------------
    // Core monitor loop
    // -----------------------------------------------------------------------

    public void monitorClients() {
        if (!stateManager.isBotRunning()) return;
        if (!monitorRunning.compareAndSet(false, true)) return; // skip if previous tick still running
        try {
            refreshClientWindows();
            if (clientWindows.isEmpty()) {
                log("[ClientManager] No EVE clients found on screen.");
                return;
            }


            ClientWindowInfo fg = getForegroundClient();

            if (fg == null) {
                log("[ClientManager] No EVE client in foreground — skipping tick.");
                return;
            }

            // Global watchdog: if the foreground client has hogged focus past FORCE_SWITCH_SECS
            // (e.g. a long combat that never yields), force a switch so the idle client gets a
            // tick and cannot sit AFK in space until podded. bringToForeground resets the timer.
            long sinceSwitch = Duration.between(lastClientSwitch, LocalDateTime.now()).getSeconds();
            if (sinceSwitch >= FORCE_SWITCH_SECS) {
                ClientWindowInfo other = findOtherClient(fg.getClientId());
                if (other != null) {
                    log("[ClientManager] Force-switch watchdog: " + sinceSwitch + "s without a switch — handing foreground to " + other.getClientId() + ".");
                    bringToForeground(other);
                    processClient(other.getClientId(), other);
                    return;
                }
                // Only one client — nothing to switch to; reset so we don't log every tick.
                lastClientSwitch = LocalDateTime.now();
            }

            processClient(fg.getClientId(), fg);
        } catch (Exception e) {
            // Never let a tick throw out of here: monitorClients runs via
            // scheduleAtFixedRate, which permanently cancels the task if it throws —
            // one uncaught exception would silently kill the bot. Log and keep ticking.
            System.err.println("[ClientManager] Tick error (continuing): " + e);
            e.printStackTrace();
        } finally {
            monitorRunning.set(false);
        }
    }

    // -----------------------------------------------------------------------
    // Per-client state machine
    // -----------------------------------------------------------------------

    private void processClient(String clientId, ClientWindowInfo window) {
        // Respect pause on every entry, not just at the monitorClients tick gate. Some
        // paths (the pod-respawn driver loop) call processClient repeatedly within a
        // single tick, so a pause that lands mid-loop must take effect here too.
        if (!stateManager.isBotRunning()) return;

        // Parked for the session (agent refused missions — standing too low): do no work,
        // just hand the foreground to the other client so it can keep running. Stays parked
        // until the bot is restarted.
        if (stateManager.getState(clientId).isStandingsLocked()) {
            ClientWindowInfo other = findOtherClient(clientId);
            if (other != null) {
                bringToForeground(other);
            }
            return;
        }

        ClientState.State state = stateManager.getStateEnum(clientId);

        switch (state) {
            case IDLE:              handleIdle(clientId, window);             break;
            case CHECKING_MISSIONS: handleCheckingMissions(clientId, window); break;
            case DECLINING:         handleDeclining(clientId, window);        break;
            case ACCEPTED:          handleAccepted(clientId, window);         break;
            case REPAIRING:         handleRepairing(clientId, window);        break;
            case UNDOCKING: handleUndocking(clientId, window);                     break;
            case TRAVEL:    handleTravel(clientId, window);                      break;
            case WARPING:   handleWarping(clientId, window);                     break;
            case IN_COMBAT: handleInCombat(clientId, window);  break;
            case RETURNING: handleReturning(clientId, window); break;
            case COMPLETING:
                handleCompleting(clientId);
                break;
            case POD_COOLDOWN:
                handlePodCooldown(clientId);
                break;
            default:
                System.out.println("[" + clientId + "] Unknown state: " + state);
        }
    }

    // -----------------------------------------------------------------------
    // POD_COOLDOWN — wait for global 10-minute cooldown after a pod death.
    // -----------------------------------------------------------------------

    private static final int POD_COOLDOWN_MINUTES = 12;

    private void handlePodCooldown(String clientId) {
        LocalDateTime cooldownEnd = stateManager.getPodCooldownEnd();
        long remaining = Duration.between(LocalDateTime.now(), cooldownEnd).getSeconds();
        if (remaining <= 0) {
            ClientState cs = stateManager.getState(clientId);
            if (cs.getCurrentMission() != null) {
                // Podded client — retry the same mission.
                log(clientId, "POD_COOLDOWN: expired — re-boarding ship for retry.");
                stateManager.setState(clientId, ClientState.State.REPAIRING);
            } else {
                // Other client — resume normal ops.
                log(clientId, "POD_COOLDOWN: expired — resuming normal operations.");
                stateManager.setState(clientId, ClientState.State.IDLE);
            }
        } else {
            // Log and switch to other client every 10s.
            ClientState cs = stateManager.getState(clientId);
            long sinceLast = Duration.between(cs.getLastCooldownSwap(), LocalDateTime.now()).getSeconds();
            if (sinceLast >= 10) {
                cs.setLastCooldownSwap(LocalDateTime.now());
                log(clientId, "POD_COOLDOWN: " + (remaining / 60) + "m " + (remaining % 60) + "s remaining...");
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    bringToForeground(other);
                }
            }
        }
    }

    private static final int POD_CHECK_INTERVAL_MINUTES = 5;

    /**
     * Periodic overview upkeep (WARPING, and IDLE while travelling), throttled to
     * every {@link #POD_CHECK_INTERVAL_MINUTES} minutes: clicks the 'general'
     * overview tab to reset the preset. Pod detection was removed from this timer —
     * the no_ship check now runs on warp entry (see {@link #handleWarping}) instead.
     */
    private void runPeriodicOverviewReset(String clientId, ClientState cs) {
        long sinceLast = Duration.between(cs.getLastPodCheck(), LocalDateTime.now()).toMinutes();
        if (sinceLast < POD_CHECK_INTERVAL_MINUTES) {
            return;
        }
        cs.setLastPodCheck(LocalDateTime.now());
        log(clientId, "Periodic check (every " + POD_CHECK_INTERVAL_MINUTES + "m): click 'general' overview reset.");
        missionProcessor.clickTemplate("general"); // reset overview to the General preset (no-op if not visible)
    }

    private boolean handlePodDeath(String clientId, boolean inSpace) {
        return handlePodDeath(clientId, inSpace, false);
    }

    /**
     * Shared pod-death handler. Self-destructs the capsule (if in space) and either
     * starts the global bubble-camp cooldown or resumes immediately.
     *
     * The 12-minute cooldown is only applied for a bubble-caused pod death within
     * the first jump (the case where re-undocking would warp straight back into the
     * camp). Every other pod death (combat, periodic 5m check, deeper in the route)
     * skips the cooldown and resumes.
     *
     * @param clientId       the client that was podded
     * @param inSpace        true if capsule is in space (needs self-destruct); false if already at station
     * @param bubbleRelated  true if the pod death was caused by a warp-bubble camp
     * @return true if a real pod death was handled; false if it was a false positive
     *         (no capsule on screen) and we aborted without changing state
     */
    private boolean handlePodDeath(String clientId, boolean inSpace, boolean bubbleRelated) {
        ClientState cs = stateManager.getState(clientId);
        int jumps = cs.getJumpCount();
        String phase = cs.getTravelPhase() == ClientState.TravelPhase.TO_MISSION ? "to mission"
                     : cs.getTravelPhase() == ClientState.TravelPhase.RETURNING_HOME ? "returning"
                     : "unknown";
        // What mission we were running when destroyed.
        Mission deadMission = cs.getCurrentMission();
        String missionName = deadMission == null ? "unknown"
                : deadMission.getTitle() != null ? deadMission.getTitle()
                : deadMission.getShipTarget() != null ? deadMission.getShipTarget()
                : "unknown";
        // Estimated death window: the ship was last confirmed alive at lastPodCheck
        // (the per-minute in-combat no_ship check, or the warp-entry check), so the
        // kill happened sometime since then. MIN = no prior check this run.
        LocalDateTime lastCheck = cs.getLastPodCheck();
        String deathWindow = lastCheck.equals(LocalDateTime.MIN)
                ? "death window unknown (no prior pod check)"
                : "died within last " + Duration.between(lastCheck, LocalDateTime.now()).getSeconds() + "s";
        // Jump category: <=1 (the undock warp = jump 0, plus the first stargate jump = 1)
        // is the bubble-camp danger zone that pauses all clients (see below); past that
        // the exact count doesn't matter, so it's logged simply as ">1".
        String jumpsLabel = jumps <= 1 ? String.valueOf(jumps) : ">1";
        if (inSpace) {
            log(clientId, "*** CAPSULE DETECTED — ship destroyed! Self-destructing...");
            if (!missionProcessor.clickDestruct()) {
                // No capsule actually on screen — the pod check was a false positive
                // (e.g. docked at station). Abort: no self-destruct, no 120s wait,
                // no cooldown, no state change. Just continue.
                log(clientId, "Pod check false positive — no capsule found, aborting self-destruct.");
                return false;
            }
            EventLog.log("POD_DEATH", clientId + " — ship destroyed in space, self-destructing (mission: " + missionName + ", " + phase + ", jumps: " + jumpsLabel + ", " + deathWindow + ")");
            // Self-destruct respawn takes ~120s. Instead of blocking idle on the dead
            // ship, hand that time to the other client by driving it each ~2s.
            log(clientId, "Self-destruct respawn ~120s — switching to other client during the wait.");
            ClientWindowInfo other = findOtherClient(clientId);
            LocalDateTime respawnEnd = LocalDateTime.now().plusSeconds(120);
            while (LocalDateTime.now().isBefore(respawnEnd)) {
                // Honor a pause that lands mid-respawn: this loop runs inside one tick, so
                // it's already past monitorClients' isBotRunning gate. Without this check it
                // would keep driving the other client for the full ~120s, ignoring the pause.
                if (!stateManager.isBotRunning()) {
                    log(clientId, "Bot paused during self-destruct respawn — stopping respawn driver.");
                    break;
                }
                if (other != null) {
                    bringToForeground(other);
                    processClient(other.getClientId(), other);
                }
                try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
            // Bring the podded client back to the foreground so it resumes (repair/idle).
            ClientWindowInfo self = clientWindows.get(clientId);
            if (self != null) {
                bringToForeground(self);
            }
        } else {
            log(clientId, "*** PODDED — respawned at station (agent_icon visible).");
            EventLog.log("POD_DEATH", clientId + " — respawned at station (mission: " + missionName + ", " + phase + ", jumps: " + jumpsLabel + ", " + deathWindow + ")");
        }

        // Pause all clients (global cooldown) for any NON-COMBAT pod death within the
        // first jump (undock warp = jump 0, or first stargate = jump 1): re-undocking
        // would warp straight back toward whatever killed us (bubble camp, gate gank),
        // so wait it out. Combat deaths happen at the mission site and resume at once.
        boolean inCombat = stateManager.getStateEnum(clientId) == ClientState.State.IN_COMBAT;
        if (jumps <= 1 && !inCombat) {
            LocalDateTime cooldownEnd = LocalDateTime.now().plusMinutes(POD_COOLDOWN_MINUTES);
            stateManager.setPodCooldownEnd(cooldownEnd);
            log(clientId, "POD_COOLDOWN: set " + POD_COOLDOWN_MINUTES + "-minute global cooldown (non-combat death, jumps=" + jumps + (bubbleRelated ? ", bubble" : "") + ").");
            stateManager.setState(clientId, ClientState.State.POD_COOLDOWN);
        } else {
            log(clientId, "POD_DEATH: " + (inCombat ? "in-combat death" : "jumps>1 (" + jumps + ")")
                    + " — skipping cooldown, resuming immediately.");
            // Clear the travel phase — we respawned at the station, so the next IDLE
            // tick must not keep reading the station agent_icon as another pod death
            // (TO_MISSION + agent_icon loops back into handlePodDeath forever).
            cs.setTravelPhase(ClientState.TravelPhase.NONE);
            if (cs.getCurrentMission() != null) {
                stateManager.setState(clientId, ClientState.State.REPAIRING);
            } else {
                stateManager.setState(clientId, ClientState.State.IDLE);
            }
        }
        return true;
    }

    // -----------------------------------------------------------------------
    // IDLE
    // -----------------------------------------------------------------------

    /**
     * Right-click the launcher module. Tries template matching first;
     * falls back to hardcoded position (1093, 1028) if not found.
     */
    private void rightClickLauncher(String clientId) {
        int[] pos = missionProcessor.findTemplatePos("launcher");
        if (pos != null) {
            missionProcessor.rightClickPos(pos[0], pos[1]);
        } else {
            log(clientId, "launcher template not found — using hardcoded pos (1093,1028).");
            missionProcessor.rightClickPos(1093, 1028);
        }
    }

    /**
     * Load ammo before activating the gate (Vengeance only).
     * Right-clicks launcher → clicks exp_damage so explosive rockets are ready before warp.
     */
    private void loadAmmoBeforeGate(String clientId, ClientState cs) {
        Mission mission = cs.getCurrentMission();
        String target = mission != null
                ? (mission.getShipTarget() != null ? mission.getShipTarget() : mission.getTarget())
                : null;
        if (target == null || !target.equalsIgnoreCase("vengeance")) return;

        log(clientId, "PRE-GATE: loading explosive rockets for Vengeance.");
        rightClickLauncher(clientId);
        try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        missionProcessor.clickTemplate("exp_damage");
        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void handleIdle(String clientId, ClientWindowInfo window) {
        // 2. Phase-based navigation — handles transit after each warp lands.
        ClientState cs = stateManager.getState(clientId);

        // Periodic 'general' overview reset only while outbound in space (TO_MISSION).
        if (cs.getTravelPhase() == ClientState.TravelPhase.TO_MISSION) {
            runPeriodicOverviewReset(clientId, cs);
        }

        if (cs.getTravelPhase() == ClientState.TravelPhase.TO_MISSION) {
            boolean justLanded = cs.isJustExitedWarp();
            if (justLanded) {
                cs.setJustExitedWarp(false);
                long warpSecs = Duration.between(cs.getWarpStartTime(), LocalDateTime.now()).getSeconds();

                // One-time check after the first jump: if set_destination is still on
                // screen, the earlier click missed and we jumped a random gate — click
                // it now to get back on the route.
                if (cs.isVerifySetDest()) {
                    cs.setVerifySetDest(false);
                    if (missionProcessor.isSetDestinationVisible()) {
                        log(clientId, "IDLE[TO_MISSION]: set_destination still visible after first jump — earlier click missed, re-clicking (template only).");
                        missionProcessor.clickTemplate("set_destination");
                        return;
                    }
                }

                // Just exited warp — check mission-specific elements first (no stargate,
                // it's always visible in-system and would false-match during a gate tunnel).
                String found = missionProcessor.detectMissionArrivalUI();
                if (found != null) {
                    switch (found) {
                        case "activate_gate":
                            loadAmmoBeforeGate(clientId, cs);
                            log(clientId, "IDLE[TO_MISSION]: activate_gate — right-click → mission_start, entering combat.");
                            cs.setWarpStartTime(LocalDateTime.now());
                            cs.setLastWarpCheck(LocalDateTime.MIN);
                            stateManager.setState(clientId, ClientState.State.IN_COMBAT);
                            new Thread(() -> {
                                missionProcessor.rightClickAndSelect("activate_gate", "mission_start");
                                missionProcessor.clickPos(960, 540); // click away in space to clear the menu
                            }).start();
                            break;
                        case "warp_to_location":
                            log(clientId, "IDLE[TO_MISSION]: warp_to_location — warping into mission space.");
                            cs.setWarpStartTime(LocalDateTime.now());
                            stateManager.setState(clientId, ClientState.State.WARPING);
                            new Thread(() -> missionProcessor.clickWarpToLocation()).start();
                            break;
                        default:
                            log(clientId, "unexpected '" + found + "' — ignoring.");
                    }
                    return;
                }
            }

            // After the first jump out of the home station, sweep any lingering windows
            // (mission briefing, agent convo, info panels) once so they don't obstruct the
            // overview for the rest of the trip.
            if (cs.getJumpCount() >= 1 && !cs.isWindowsClosedAfterJump1()) {
                cs.setWindowsClosedAfterJump1(true);
                log(clientId, "IDLE[TO_MISSION]: first jump done — closing lingering windows.");
                missionProcessor.closeAllDialogs();
            }

            // After first jump, check for hostiles once — warp to safe spot if enemy found.
            if (cs.getJumpCount() >= 1 && !cs.isKlyChecked()) {
                cs.setKlyChecked(true);
                if (missionProcessor.isEnemyInLocal()) {
                    log(clientId, "IDLE[TO_MISSION]: enemy in local after jump — warping to safe spot. (jumpCount=" + cs.getJumpCount() + ")");
                    if (missionProcessor.warpToGenericSafeSpot()) {
                        cs.setWarpStartTime(LocalDateTime.now());
                        stateManager.setState(clientId, ClientState.State.WARPING);
                        return;
                    }
                    log(clientId, "IDLE[TO_MISSION]: safe spot not found — falling through to stargate.");
                }
            }
            String found = missionProcessor.detectTravelUI();
            if (found == null) {
                // No travel UI on screen. Don't poll the agent every tick (wasteful while
                // mid-travel) — arm a 60s timer instead. Only once nothing has been on
                // screen for 60s do we check agent_icon: if it's there we respawned at
                // station after a pod death (recover); otherwise keep waiting.
                if (cs.getNoTravelUiSince().equals(LocalDateTime.MIN)) {
                    cs.setNoTravelUiSince(LocalDateTime.now());
                    log(clientId, "IDLE[TO_MISSION]: no navigation element visible — waiting.");
                } else if (Duration.between(cs.getNoTravelUiSince(), LocalDateTime.now()).getSeconds() >= 60) {
                    if (missionProcessor.detectCompletingUI() != null) {
                        // Agent icon visible during TO_MISSION = respawned at station after pod death.
                        log(clientId, "IDLE[TO_MISSION]: no travel UI for 60s + agent_icon — podded-at-station, recovering.");
                        cs.setNoTravelUiSince(LocalDateTime.MIN);
                        handlePodDeath(clientId, false);
                        return;
                    }
                    log(clientId, "IDLE[TO_MISSION]: no travel UI for 60s — assuming podded-at-station, but agent_icon not detected. Still waiting.");
                    cs.setNoTravelUiSince(LocalDateTime.now()); // re-arm: re-check + re-log every 60s
                }
                return;
            }
            cs.setNoTravelUiSince(LocalDateTime.MIN);
            switch (found) {
                case "activate_gate":
                    loadAmmoBeforeGate(clientId, cs);
                    log(clientId, "IDLE[TO_MISSION]: activate_gate — right-click → mission_start, entering combat.");
                    cs.setWarpStartTime(LocalDateTime.now());
                    cs.setLastWarpCheck(LocalDateTime.MIN);
                    stateManager.setState(clientId, ClientState.State.IN_COMBAT);
                    new Thread(() -> {
                        missionProcessor.rightClickAndSelect("activate_gate", "mission_start");
                        missionProcessor.clickPos(960, 540); // click away in space to clear the menu
                    }).start();
                    break;
                case "warp_to_location":
                    log(clientId, "IDLE[TO_MISSION]: warp_to_location — warping into mission space.");
                    cs.setWarpStartTime(LocalDateTime.now());
                    stateManager.setState(clientId, ClientState.State.WARPING);
                    new Thread(() -> missionProcessor.clickWarpToLocation()).start();
                    break;
                case "stargate":
                    cs.incrementJumpCount();
                    log(clientId, "IDLE[TO_MISSION]: stargate — right-click → jump. (jump #" + cs.getJumpCount() + ")");
                    cs.setWarpStartTime(LocalDateTime.now());
                    stateManager.setState(clientId, ClientState.State.WARPING);
                    new Thread(() -> missionProcessor.rightClickAndSelect("stargate", "jump")).start();
                    break;
                default:
                    log(clientId, "unexpected '" + found + "' — ignoring.");
            }
            return;
        }

        if (cs.getTravelPhase() == ClientState.TravelPhase.RETURNING_HOME) {
            boolean justLanded = cs.isJustExitedWarp();
            if (justLanded) {
                cs.setJustExitedWarp(false);
                long warpSecs = Duration.between(cs.getWarpStartTime(), LocalDateTime.now()).getSeconds();

                // One-time check after the first jump: if set_destination is still on
                // screen, the earlier click missed and we jumped a random gate — click
                // it now to get back on the route.
                if (cs.isVerifySetDest()) {
                    cs.setVerifySetDest(false);
                    if (missionProcessor.isSetDestinationVisible()) {
                        log(clientId, "IDLE[RETURNING_HOME]: set_destination still visible after first jump — earlier click missed, re-clicking (template only).");
                        missionProcessor.clickTemplate("set_destination");
                        return;
                    }
                }

                // Just exited warp — check dock first (no stargate yet, same false-match risk).
                String dockCheck = missionProcessor.detectDockOnly();
                if (dockCheck != null) {
                    if (missionProcessor.isEnemyInLocal()) {
                        log(clientId, "IDLE[RETURNING_HOME]: dock visible + ENEMY in local — insta-docking.");
                        if (missionProcessor.instaDock()) {
                            // Bubble in the way — escape it in WARPING first (no autopilot);
                            // we'll re-dock from IDLE once the bubble is cleared.
                            log(clientId, "IDLE[RETURNING_HOME]: bubble on insta-dock — escaping before docking.");
                            cs.setWarpStartTime(LocalDateTime.now());
                            stateManager.setState(clientId, ClientState.State.WARPING);
                            return;
                        }
                    } else {
                        log(clientId, "IDLE[RETURNING_HOME]: dock visible — docking (twice).");
                        missionProcessor.clickDockTwice();
                    }
                    cs.setDockingToStation(true);
                    cs.setWarpStartTime(LocalDateTime.now());
                    stateManager.setState(clientId, ClientState.State.WARPING);
                    return;
                }

                // Dock not visible — check for hostiles before proceeding to stargate.
                // Skip if we already evaded to a safe spot this system; jump out instead of re-evading.
                if (!cs.isWarpedToSafeSpot() && missionProcessor.isEnemyInLocal()) {
                    log(clientId, "IDLE[RETURNING_HOME]: enemy in local after jump — warping to safe spot. (jumpCount=" + cs.getJumpCount() + ")");
                    if (missionProcessor.warpToGenericSafeSpot()) {
                        cs.setWarpedToSafeSpot(true);
                        cs.setWarpStartTime(LocalDateTime.now());
                        stateManager.setState(clientId, ClientState.State.WARPING);
                        return;
                    }
                    log(clientId, "IDLE[RETURNING_HOME]: safe spot not found — falling through to stargate.");
                }
                log(clientId, "IDLE[RETURNING_HOME]: dock not visible — checking stargate.");
            }

            // Normal tick: full returning UI check including stargate.
            String found = missionProcessor.detectReturningUI();
            if (found == null) {
                // detectReturningUI already includes agent_icon, so a null result means
                // dock/stargate/agent are all absent. The agent_icon case below recovers
                // (→ COMPLETING) the moment it reappears; this 60s escalation only logs a
                // stuck warning (likely podded but the agent isn't being detected).
                if (cs.getNoTravelUiSince().equals(LocalDateTime.MIN)) {
                    cs.setNoTravelUiSince(LocalDateTime.now());
                    log(clientId, "IDLE[RETURNING_HOME]: no navigation element visible — waiting.");
                } else if (Duration.between(cs.getNoTravelUiSince(), LocalDateTime.now()).getSeconds() >= 60) {
                    log(clientId, "IDLE[RETURNING_HOME]: no travel UI for 60s — assuming podded-at-station, but agent_icon not detected. Still waiting.");
                    cs.setNoTravelUiSince(LocalDateTime.now()); // re-arm: re-check + re-log every 60s
                }
                return;
            }
            cs.setNoTravelUiSince(LocalDateTime.MIN);
            switch (found) {
                case "dock":
                    if (missionProcessor.isEnemyInLocal()) {
                        log(clientId, "IDLE[RETURNING_HOME]: dock visible + ENEMY in local — insta-docking.");
                        missionProcessor.instaDock();
                    } else {
                        log(clientId, "IDLE[RETURNING_HOME]: dock visible — docking (twice).");
                        missionProcessor.clickDockTwice();
                    }
                    cs.setDockingToStation(true);
                    cs.setWarpStartTime(LocalDateTime.now());
                    stateManager.setState(clientId, ClientState.State.WARPING);
                    break;
                case "stargate":
                    cs.incrementJumpCount();
                    cs.setWarpedToSafeSpot(false);
                    log(clientId, "stargate — right-click → jump. (jump #" + cs.getJumpCount() + ")");
                    cs.setWarpStartTime(LocalDateTime.now());
                    stateManager.setState(clientId, ClientState.State.WARPING);
                    new Thread(() -> missionProcessor.rightClickAndSelect("stargate", "jump")).start();
                    break;
                case "agent_icon":
                    log(clientId, "IDLE[RETURNING_HOME]: agent_icon visible — docked in station.");
                    cs.setTravelPhase(ClientState.TravelPhase.NONE);
                    stateManager.setState(clientId, ClientState.State.COMPLETING);
                    break;
                default:
                    log(clientId, "unexpected '" + found + "' — ignoring.");
            }
            return;
        }

        // 3. NONE phase — check for travel UI first (handles restart mid-travel or post-UNDOCKING).
        // If stargate / warp_to_location / activate_gate is visible, the ship is in transit —
        // switch to TO_MISSION phase so navigation resumes correctly next tick.
        String travelUI = missionProcessor.detectTravelUI();
        if (travelUI != null) {
            log(clientId, "IDLE: travel UI '" + travelUI + "' detected — resuming TO_MISSION navigation.");
            cs.setTravelPhase(ClientState.TravelPhase.TO_MISSION);
            return;
        }

        // Check global pod-death cooldown before accepting new missions.
        LocalDateTime podEnd = stateManager.getPodCooldownEnd();
        if (podEnd != null && LocalDateTime.now().isBefore(podEnd)) {
            long remaining = Duration.between(LocalDateTime.now(), podEnd).getSeconds();
            log(clientId, "IDLE: global pod cooldown active (" + (remaining / 60) + "m " + (remaining % 60) + "s) — entering POD_COOLDOWN.");
            stateManager.setState(clientId, ClientState.State.POD_COOLDOWN);
            return;
        }

        // Standard mission dialog logic.
        MissionProcessor.DialogState dialogState = missionProcessor.detectDialog();

        switch (dialogState) {
            case NONE: {
                int attempt = agentRetries.merge(clientId, 1, Integer::sum);
                if (attempt > MAX_AGENT_RETRIES) {
                    log(clientId, "No mission dialog after " + MAX_AGENT_RETRIES
                            + " agent clicks — waiting.");
                    agentRetries.put(clientId, 0);
                    return;
                }
                log(clientId, "No mission dialog — double-clicking agent icon (attempt "
                        + attempt + "/" + MAX_AGENT_RETRIES + ").");
                missionProcessor.doubleClickAgentIcon();
                stateManager.getState(clientId).setCurrentMission(null);
                stateManager.setState(clientId, ClientState.State.CHECKING_MISSIONS);
                break;
            }
            case ALREADY_ACCEPTED: {
                agentRetries.put(clientId, 0);
                log(clientId, "Mission already accepted (complete_mission visible) — skipping to COMPLETING.");
                stateManager.setState(clientId, ClientState.State.COMPLETING);
                break;
            }
            case COMPLETED: {
                agentRetries.put(clientId, 0);
                log(clientId, "Mission completed (completed template visible) — turning in.");
                stateManager.setState(clientId, ClientState.State.COMPLETING);
                break;
            }
            case NOT_COMPLETE: {
                agentRetries.put(clientId, 0);
                // 'not_complete' is only trustworthy as "accepted, in progress" if we
                // actually accepted a mission this session. With no mission on record the
                // match is a false signal (e.g. a fresh OFFER whose accept/decline buttons
                // weren't detected this tick) — don't assume it's accepted. Hand to
                // CHECKING_MISSIONS, which re-scans/classifies the offer (or returns to IDLE).
                if (stateManager.getState(clientId).getCurrentMission() == null) {
                    log(clientId, "NOT_COMPLETE template visible but no accepted mission on record — treating as unaccepted, re-checking via CHECKING_MISSIONS.");
                    stateManager.setState(clientId, ClientState.State.CHECKING_MISSIONS);
                    break;
                }
                log(clientId, "Mission accepted but NOT complete — reading mission via OCR before ship selection.");
                Rectangle missionArea = getMissionAreaForClient(clientId);
                Mission mission = missionProcessor.readMission(missionArea);
                stateManager.getState(clientId).setCurrentMission(mission);
                stateManager.setState(clientId, ClientState.State.ACCEPTED);
                break;
            }
            default: { // OFFER
                agentRetries.put(clientId, 0);
                log(clientId, "Mission offer dialog confirmed — handing to CHECKING_MISSIONS (template-first).");
                // Leave currentMission null so CHECKING_MISSIONS classifies by
                // template first; OCR runs only if a wanted type is matched.
                stateManager.getState(clientId).setCurrentMission(null);
                stateManager.setState(clientId, ClientState.State.CHECKING_MISSIONS);
                break;
            }
        }
    }

    /**
     * If the agent's "standing too low" refusal message is on screen, park this client
     * for the rest of the session (standing won't climb on its own, so retrying is
     * pointless) and hand the foreground to the other client. Returns true if parked.
     *
     * @param where short label of the calling state, for the log line
     */
    private boolean parkIfStandingsRefused(String clientId, ClientState cs, String where) {
        if (!missionProcessor.isStandingsRefused()) return false;
        log(clientId, where + ": agent refused — standing too low (< 2). Parking this client for the session.");
        EventLog.log("STANDINGS_REFUSED", clientId + " — agent refused mission (standing < 2), client parked for session");
        cs.setStandingsLocked(true);
        cs.setCurrentMission(null);
        stateManager.setState(clientId, ClientState.State.IDLE);
        ClientWindowInfo other = findOtherClient(clientId);
        if (other != null) bringToForeground(other);
        return true;
    }

    // -----------------------------------------------------------------------
    // CHECKING_MISSIONS
    // -----------------------------------------------------------------------

    private void handleCheckingMissions(String clientId, ClientWindowInfo window) {
        ClientState cs = stateManager.getState(clientId);
        cs.setLastCombatOrCheck(LocalDateTime.now()); // docked & checking missions — ship is alive, gates the warp pod check

        // Agent refused to hand out a mission because our standing is too low (< 2).
        if (parkIfStandingsRefused(clientId, cs, "CHECKING_MISSIONS")) return;

        if (cs.getMissionCheckStartTime().equals(LocalDateTime.MIN)) {
            cs.setMissionCheckStartTime(LocalDateTime.now());
        }
        long cycleElapsed = Duration.between(cs.getMissionCheckStartTime(), LocalDateTime.now()).getSeconds();
        if (cycleElapsed >= 25) {
            ClientWindowInfo other = findOtherClient(clientId);
            if (other != null) {
                log(clientId, "CHECKING_MISSIONS: 25s in check/decline cycle — returning to IDLE and switching focus to " + other.getClientId());
                cs.setCurrentMission(null);
                stateManager.setState(clientId, ClientState.State.IDLE);
                bringToForeground(other);
                return;
            }
        }

        agentRetries.put(clientId, 0);

        // If a mission was already OCR'd by another entry path, evaluate it
        // directly with the full rule set (rare — most offers take the fast path).
        Mission existing = cs.getCurrentMission();
        if (existing != null) {
            applyDecision(clientId, existing, missionConfig.evaluate(existing, cs.getLastKnownStanding()));
            return;
        }

        // --- Template-first fast path -------------------------------------
        // Identify the offer by its dialog template (one screenshot, no OCR).
        // Scan is restricted to the dialog region for speed (~9x vs full screen).
        String missionTemplate = missionProcessor.classifyMissionOffer(getMissionAreaForClient(clientId));

        if (missionTemplate == null) {
            // No known wanted-mission template visible. Some wanted missions have no
            // dialog template and are identified by title only (e.g. Scarlet, Pot and
            // Kettle, accepted only when agent standing < 1). OCR the offer and run it
            // through the no-template rule set: standings-gated title rules get a chance,
            // everything else still declines (fast-path behavior preserved).
            if (missionProcessor.detectDialog() == MissionProcessor.DialogState.OFFER) {
                Mission noTplMission = missionProcessor.readMission(getMissionAreaForClient(clientId));
                if (noTplMission == null) {
                    System.out.println(">>> [" + clientId + "] DECLINE — no mission template, OCR failed (unwanted)");
                    missionProcessor.clickDeclineButton();
                    stateManager.setState(clientId, ClientState.State.DECLINING);
                    return;
                }
                cs.setCurrentMission(noTplMission);
                MissionConfig.Decision decision = missionConfig.evaluateNoTemplate(
                        noTplMission, cs.getLastKnownStanding());
                applyDecision(clientId, noTplMission, decision);
            } else {
                log(clientId, "No mission template and no offer dialog — returning to IDLE.");
                stateManager.setState(clientId, ClientState.State.IDLE);
            }
            return;
        }

        // A wanted template matched — but template matching also fires on the
        // just-completed mission still shown in the agent window right after
        // turn-in (e.g. mission_worm). Confirm a live OFFER dialog (accept/decline
        // present) before acting; otherwise the on-screen dialog is stale, so
        // request a fresh mission and retry next tick (25s cycle is the backstop).
        if (missionProcessor.detectDialog() != MissionProcessor.DialogState.OFFER) {
            log(clientId, "Template '" + missionTemplate + "' matched but no live OFFER dialog — stale completed mission, requesting new mission.");
            missionProcessor.clickRequestMission();
            return;
        }

        // Wanted type matched — OCR now (only here) to check distance / nuances.
        log(clientId, "Mission template matched: " + missionTemplate + " — OCR for distance check.");
        Rectangle missionArea = getMissionAreaForClient(clientId);
        Mission mission = missionProcessor.readMission(missionArea);
        if (mission == null) {
            // Template still on screen but OCR failed — retry next tick (25s cycle
            // timeout is the backstop that frees the client).
            log(clientId, "OCR returned null on a template-matched offer — retrying next tick.");
            return;
        }
        cs.setCurrentMission(mission);

        // The template match only told us this is a *candidate* wanted type — it
        // can be a false positive (e.g. mission_enyo matching "Smash the Supplier").
        // Run the FULL rule set: an accept rule must match, and any decline rule
        // (too-far / base / blood / Recon2of3) still wins. Default is decline.
        // Standings-gated rules (e.g. recon 1, accepted only when standing < 1) are
        // resolved here against the tracked per-client standing.
        applyDecision(clientId, mission, missionConfig.evaluate(mission, cs.getLastKnownStanding()));
    }

    /** Click accept/decline for a decided mission and transition state. */
    private void applyDecision(String clientId, Mission mission, MissionConfig.Decision decision) {
        if (decision == MissionConfig.Decision.ACCEPT) {
            System.out.println(">>> [" + clientId + "] ACCEPT — " + mission.getTitle());
            missionProcessor.clickAcceptButton();
            stateManager.setState(clientId, ClientState.State.ACCEPTED);
        } else {
            System.out.println(">>> [" + clientId + "] DECLINE — " + mission.getTitle());
            missionProcessor.clickDeclineButton();
            stateManager.setState(clientId, ClientState.State.DECLINING);
        }
    }

    // -----------------------------------------------------------------------
    // DECLINING
    // -----------------------------------------------------------------------

    private void handleDeclining(String clientId, ClientWindowInfo window) {
        log(clientId, "DECLINING — waiting for offer dialog to close...");

        if (!missionProcessor.waitForDialogClosed()) {
            ClientState cs = stateManager.getState(clientId);
            if (cs.isDeclineRetried()) {
                log(clientId, "Decline dialog stuck — double-clicking agent to reset.");
                missionProcessor.doubleClickAgentIcon();
                cs.setCurrentMission(null);
                stateManager.setState(clientId, ClientState.State.CHECKING_MISSIONS);
            } else {
                log(clientId, "Decline still visible — clicking decline to confirm.");
                missionProcessor.clickDeclineButton();
                cs.setDeclineRetried(true);
            }
            return;
        }

        log(clientId, "Dialog closed — requesting new mission.");
        missionProcessor.clickRequestMission();

        if (!missionProcessor.waitForNewOffer()) {
            // No offer appeared — the agent may be refusing because our standing is too
            // low. Check here too: otherwise this request/retry loop spins forever
            // without ever re-entering CHECKING_MISSIONS where the other check lives.
            if (parkIfStandingsRefused(clientId, stateManager.getState(clientId), "DECLINING")) return;
            log(clientId, "New offer not visible yet — will retry next tick.");
            return;
        }

        // Leave currentMission null so CHECKING_MISSIONS classifies the new offer
        // via template matching first (OCR only runs if a wanted type matches).
        stateManager.getState(clientId).setCurrentMission(null);
        log(clientId, "New offer visible — handing to CHECKING_MISSIONS (template-first).");
        stateManager.setState(clientId, ClientState.State.CHECKING_MISSIONS);
    }

    // -----------------------------------------------------------------------
    // ACCEPTED — Track only, dialog stays open for ship template matching
    // -----------------------------------------------------------------------

    private void handleAccepted(String clientId, ClientWindowInfo window) {
        ClientState accState = stateManager.getState(clientId);
        if (!accState.isTrackAttempted()) {
            log(clientId, "First accept — clicking Track.");
            missionProcessor.clickTrack();
            accState.setTrackAttempted(true);
        } else {
            log(clientId, "Track already attempted — skipping.");
        }
        // Dialog stays open intentionally — performPostAcceptFlow needs it
        // visible so Python can match the ship from the dialog content.
        stateManager.setState(clientId, ClientState.State.REPAIRING);
    }

    // -----------------------------------------------------------------------
    // REPAIRING — repair + activate ship, click Undock → UNDOCKING
    // -----------------------------------------------------------------------

    private void handleRepairing(String clientId, ClientWindowInfo window) {
        Mission mission = stateManager.getState(clientId).getCurrentMission();
        log(clientId, "Starting repair + undock flow for: "
                + (mission != null ? mission.getTitle() : "unknown mission"));

        String result = missionProcessor.performUndockStart(mission);

        if (MissionProcessor.UNDOCK_OK.equals(result)) {
            log(clientId, "Clicking undock.");
            missionProcessor.clickUndock();
            stateManager.getState(clientId).setUndockStartTime(LocalDateTime.now());
            stateManager.setState(clientId, ClientState.State.UNDOCKING);
            // Safe-switch: use undock animation time on the other client
            ClientWindowInfo other = findOtherClient(clientId);
            if (other != null) {
                log(clientId, "UNDOCKING: switching to " + other.getClientId() + " during 15s animation.");
                bringToForeground(other);
            }
        } else if (MissionProcessor.UNDOCK_SHIP_NOT_FOUND.equals(result)) {
            String mTitle = mission != null ? mission.getTitle() : "unknown";
            log(clientId, "Ship not found — mission quit, returning to CHECKING_MISSIONS.");
            EventLog.log("SHIP_NOT_FOUND", clientId + " — mission: " + mTitle);
            stateManager.getState(clientId).setCurrentMission(null);
            stateManager.setState(clientId, ClientState.State.CHECKING_MISSIONS);
        } else {
            System.err.println("[" + clientId + "] Repair/undock flow failed — returning to IDLE.");
            stateManager.setState(clientId, ClientState.State.IDLE);
        }
    }

    // -----------------------------------------------------------------------
    // TRAVEL — one-shot setup: close dialogs, reload modules, set destination.
    //          Navigation (stargates / warp_to_location / activate_gate) is
    //          handled by IDLE + WARPING cycling after this.
    // -----------------------------------------------------------------------

    private void handleTravel(String clientId, ClientWindowInfo window) {
        ClientState cs = stateManager.getState(clientId);
        missionProcessor.closeAllDialogs();
        missionProcessor.pressCtrlR();
        missionProcessor.closeAllDialogs();
        missionProcessor.clickSetDestination(281, 515); // undock position
        cs.setVerifySetDest(true); // re-check after first jump that it actually took

        // After setting destination, check for hostiles — warp to safe spot if needed.
        if (missionProcessor.findTemplate("enemy") != null) {
            log(clientId, "TRAVEL: enemy in local after undock — warping to safe spot.");
            if (missionProcessor.warpToSafeSpot()) {
                // Evaded — enter WARPING so the safe-spot warp completes, instead of
                // dropping to IDLE and immediately jumping the next stargate. Mark the
                // enemy check done so IDLE[TO_MISSION] doesn't re-check after landing.
                cs.setKlyChecked(true);
                cs.setWarpStartTime(LocalDateTime.now());
                cs.setTravelPhase(ClientState.TravelPhase.TO_MISSION);
                stateManager.setState(clientId, ClientState.State.WARPING);
                return;
            }
        }

        cs.setTravelPhase(ClientState.TravelPhase.TO_MISSION);
        stateManager.setState(clientId, ClientState.State.IDLE);
    }

    // -----------------------------------------------------------------------
    // IN_COMBAT — dispatches to mission-specific combat sequences.
    //             Each sequence is step-based with safe-wait periods that
    //             allow client switching (mirrors the WARPING pattern).
    //             Unknown targets fall back to set_destination polling.
    // -----------------------------------------------------------------------

    private void handleInCombat(String clientId, ClientWindowInfo window) {
        ClientState cs = stateManager.getState(clientId);
        cs.setLastCombatOrCheck(LocalDateTime.now()); // ship is alive and fighting — gates the warp pod check

        // ── Warp verification phase (no client switching) ──
        // After clicking activate_gate → mission_start, verify warp started then wait for it to land.
        if (!cs.isCombatWarpLanded()) {
            long elapsed = Duration.between(cs.getWarpStartTime(), LocalDateTime.now()).getSeconds();

            // 0–10s: grace — give the right-click+mission_start click time to land and
            // the gate warp to begin before polling warp_active (a grayed-out gate that
            // never warped is caught afterward by the re-click logic).
            if (elapsed < 10) {
                if (cs.getLastWarpCheck().equals(LocalDateTime.MIN)) {
                    log(clientId, "IN_COMBAT: waiting for gate warp to begin.");
                    cs.setLastWarpCheck(LocalDateTime.MIN.plusNanos(1)); // grace-logged marker
                }
                return;
            }

            // 3s+: poll warp_active every 2s — when it disappears (re-confirmed), warp has landed.
            // Tight interval keeps the consecutive-miss confirmation from adding much latency.
            boolean firstCheck = cs.getLastWarpCheck().isBefore(LocalDateTime.MIN.plusSeconds(1));
            long sinceLastCheck = Duration.between(cs.getLastWarpCheck(), LocalDateTime.now()).getSeconds();
            if (!firstCheck && sinceLastCheck < 2) {
                return; // throttle to 2s intervals
            }
            cs.setLastWarpCheck(LocalDateTime.now());

            if (missionProcessor.isWarpActive()) {
                if (firstCheck) {
                    log(clientId, "IN_COMBAT: gate warp confirmed — waiting for landing.");
                }
                gateWarpRetries.put(clientId, 0); // warp started — clear re-click counter
                cs.setWarpEndMisses(0);           // still warping — reset the gone-streak
                return;
            }

            // Warp not active. If we'd already confirmed it (not the first check), the
            // warp may have landed — but guard against a single sub-threshold frame of the
            // solid warp_active block and against landing implausibly early (no target yet):
            //   • require WARP_END_MISS_CONFIRM consecutive polls with warp_active gone, and
            //   • never confirm before the WARP_LAND_MIN_SECS hard floor, no matter what.
            if (!firstCheck) {
                int misses = cs.getWarpEndMisses() + 1;
                cs.setWarpEndMisses(misses);
                if (elapsed < WARP_LAND_MIN_SECS || misses < WARP_END_MISS_CONFIRM) {
                    log(clientId, "IN_COMBAT: warp_active gone (confirm " + misses + "/"
                            + WARP_END_MISS_CONFIRM + ", elapsed " + elapsed + "s/min "
                            + WARP_LAND_MIN_SECS + "s) — re-confirming before combat.");
                    return;
                }
                log(clientId, "IN_COMBAT: gate warp landed after " + elapsed + "s — starting combat.");
                gateWarpRetries.put(clientId, 0);
                cs.setCombatWarpLanded(true);
                // Anchor the 2-minute no_ship timer to combat start. Without this the timer
                // measures from a stale/MIN timestamp and the check fires at ~0s — right when
                // the HUD is still settling after warp-in, producing a false PODDED verdict.
                cs.setLastPodCheck(LocalDateTime.now());
                return;
            }

            // First check and no warp — the gate likely didn't activate (grayed out on
            // session change). Re-click it and retry a couple of times before giving up.
            int retries = gateWarpRetries.merge(clientId, 1, Integer::sum);
            if (retries <= MAX_GATE_WARP_RETRIES) {
                log(clientId, "IN_COMBAT: no warp after " + elapsed + "s — gate grayed, re-clicking (attempt "
                        + retries + "/" + MAX_GATE_WARP_RETRIES + ").");
                new Thread(() -> missionProcessor.rightClickAndSelect("activate_gate", "mission_start")).start();
                cs.setWarpStartTime(LocalDateTime.now());       // restart the 10s grace
                cs.setLastWarpCheck(LocalDateTime.MIN);          // next poll is a fresh first check
                return;
            }

            // Gate still hasn't warped after every re-click. NEVER start combat from here —
            // a grayed/un-activated gate means we never entered the pocket. Drop back to IDLE
            // so the TO_MISSION navigation re-acquires the gate and retries the whole entry;
            // this self-heals once the (usually transient) session-change timer clears.
            log(clientId, "IN_COMBAT: still no warp after " + MAX_GATE_WARP_RETRIES
                    + " gate re-clicks — gate not activating, returning to IDLE to re-attempt (NOT starting combat).");
            gateWarpRetries.put(clientId, 0);
            stateManager.setState(clientId, ClientState.State.IDLE);
            return;
        }

        // ── Mission timer: once every two minutes in combat, run the cheap no_ship
        //    check. isFlyingPod() stage 1 is a passive sliver match (no clicking) when
        //    the ship is alive, so this is safe to fire mid-fight. If we've been podded,
        //    the combat step machine would otherwise keep trying to lock targets from
        //    a capsule until the per-handler hardcap; instead handlePodDeath self-
        //    destructs (guarded by the capsule template) and hands the ~120s respawn
        //    to the other client, so this client stops spinning and yields. ──
        if (Duration.between(cs.getLastPodCheck(), LocalDateTime.now()).toMinutes() >= 2) {
            log(clientId, "IN_COMBAT: 2-minute no_ship check.");
            if (missionProcessor.isFlyingPod()) {
                log(clientId, "IN_COMBAT: no_ship — podded mid-combat, self-destructing.");
                // Leave lastPodCheck at the previous clean check so handlePodDeath can
                // bound the death window (the kill happened sometime since that check).
                if (handlePodDeath(clientId, true)) {
                    return; // real pod death — handled (self-destruct/respawn)
                }
                // False positive (no capsule) — don't re-probe next tick. Reset the
                // throttle and carry on with combat.
                log(clientId, "IN_COMBAT: pod check was a false positive — resuming combat, re-arming 2-min throttle.");
                cs.setLastPodCheck(LocalDateTime.now());
            } else {
                cs.setLastPodCheck(LocalDateTime.now()); // clean check passed — reset the 2-min throttle
            }
        }

        Mission mission = cs.getCurrentMission();

        String resolvedTarget = mission != null
                ? (mission.getShipTarget() != null ? mission.getShipTarget() : mission.getTarget())
                : null;

        if (resolvedTarget == null) {
            log(clientId, "IN_COMBAT: no mission/shipTarget — falling back to completion polling."
                    + " (mission=" + mission + ")");
            String completed = missionProcessor.detectCombatComplete();
            if ("dock".equals(completed)) {
                log(clientId, "IN_COMBAT: dock detected — same system, docking.");
                missionProcessor.clickTemplate("dock");
                cs.setDockingToStation(true);
                cs.setTravelPhase(ClientState.TravelPhase.NONE);
                cs.setWarpStartTime(LocalDateTime.now());
                stateManager.setState(clientId, ClientState.State.WARPING);
            } else if (completed != null) {
                log(clientId, "IN_COMBAT: set_destination detected — mission complete.");
                stateManager.setState(clientId, ClientState.State.RETURNING);
            }
            return;
        }

        String target = resolvedTarget.toLowerCase();
        if (cs.getCombatStep() == 0) {
            log(clientId, "IN_COMBAT: dispatching combat for shipTarget='" + target + "'.");
        }
        switch (target) {
            case "jaguar":
                handleCombatJaguar(clientId, cs);
                break;
            case "vengeance":
                handleCombatVengeance(clientId, cs);
                break;
            case "enyo":
            case "enya":
                handleCombatEnyo(clientId, cs);
                break;
            case "hawk":
                handleCombatHawk(clientId, cs);
                break;
            case "sansha":
                handleCombatSansha(clientId, cs);
                break;
            case "infested":
                handleCombatInfested(clientId, cs);
                break;
            case "recon1":
                handleCombatRecon(clientId, cs);
                break;
            case "angel":
                handleCombatAngel(clientId, cs);
                break;
            case "worm":
            case "guristas":
            case "guristasbumer":
                handleCombatWorm(clientId, cs);
                break;
            default:
                // Unknown target — fall back to completion polling.
                String fallback = missionProcessor.detectCombatComplete();
                if ("dock".equals(fallback)) {
                    log(clientId, "IN_COMBAT: dock detected — same system, docking.");
                    missionProcessor.clickTemplate("dock");
                    cs.setDockingToStation(true);
                    cs.setTravelPhase(ClientState.TravelPhase.NONE);
                    cs.setWarpStartTime(LocalDateTime.now());
                    stateManager.setState(clientId, ClientState.State.WARPING);
                } else if (fallback != null) {
                    log(clientId, "IN_COMBAT: set_destination detected — mission complete.");
                    stateManager.setState(clientId, ClientState.State.RETURNING);
                }
                break;
        }
    }

    // -----------------------------------------------------------------------
    // Shared final-step logic for all combat sequences.
    // Polls set_destination; also runs the eject-menu pod check (ship destroyed).
    // -----------------------------------------------------------------------

    private void pollSetDestinationOrCapsule(String clientId, ClientState cs,
                                              String combatLabel, boolean allowClientSwitch) {
        pollSetDestinationOrCapsule(clientId, cs, combatLabel, allowClientSwitch, null);
    }

    /**
     * Shared final-step completion poll.
     *
     * When {@code lockedTargetKey} is supplied (e.g. "locked_worm"), the locked
     * target bracket disappearing is treated as the primary "target destroyed →
     * mission complete" signal — faster and more reliable than waiting for the
     * set_destination prompt. After 120s with no signal, an eject-menu pod check
     * runs (only there, since it right-clicks our ship) before assuming completion,
     * because being podded clears the locked target and would otherwise look complete.
     */
    private void pollSetDestinationOrCapsule(String clientId, ClientState cs,
                                              String combatLabel, boolean allowClientSwitch,
                                              String lockedTargetKey) {
        String completed = missionProcessor.detectCombatComplete();
        // If nothing detected, nudge the camera (throttled) to force a fresh frame and
        // re-scan once — a stale/idle-dimmed frame can hide the set_destination/dock prompt.
        if (completed == null
                && Duration.between(cs.getLastViewNudge(), LocalDateTime.now()).getSeconds() >= VIEW_NUDGE_THROTTLE_SECS) {
            cs.setLastViewNudge(LocalDateTime.now());
            log(clientId, "COMBAT[" + combatLabel + "] no completion this scan — nudging view + re-scanning.");
            missionProcessor.nudgeView();
            completed = missionProcessor.detectCombatComplete();
        }
        if (completed != null) {
            if ("dock".equals(completed)) {
                log(clientId, "COMBAT[" + combatLabel + "] dock detected — same system, docking.");
                missionProcessor.clickTemplate("dock");
                cs.setDockingToStation(true);
                cs.setTravelPhase(ClientState.TravelPhase.NONE);
                cs.setWarpStartTime(LocalDateTime.now());
                stateManager.setState(clientId, ClientState.State.WARPING);
            } else {
                log(clientId, "COMBAT[" + combatLabel + "] set_destination detected — mission complete!");
                stateManager.setState(clientId, ClientState.State.RETURNING);
            }
            return;
        }

        long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();

        // Primary completion signal: the locked target bracket is gone → destroyed.
        // Guarded by a few seconds so a transient miss right after entering this step
        // (or before combat has applied) can't fire it prematurely.
        if (lockedTargetKey != null && elapsed >= 5
                && missionProcessor.findTemplate(lockedTargetKey) == null) {
            log(clientId, "COMBAT[" + combatLabel + "] " + lockedTargetKey
                    + " gone — target destroyed, mission complete!");
            stateManager.setState(clientId, ClientState.State.RETURNING);
            return;
        }

        // Backstop with no completion signal. Jaguar fights run longer, so they get
        // 300s; every other combat uses 120s. The eject-menu pod check is invasive
        // (right-clicks our ship), so it only runs here — not every tick. Being
        // podded clears the locked target, so a long silence can mean we were
        // destroyed; check for a pod before assuming the mission completed.
        int backstopSecs = "Jaguar".equals(combatLabel) ? 300 : 120;
        if (elapsed >= backstopSecs) {
            if (missionProcessor.isFlyingPod()) {
                handlePodDeath(clientId, true);
                return;
            }
            log(clientId, "COMBAT[" + combatLabel + "] no completion signal after " + backstopSecs + "s — assuming mission complete.");
            stateManager.setState(clientId, ClientState.State.RETURNING);
            return;
        }

        log(clientId, "COMBAT[" + combatLabel + "] waiting for mission complete (" + elapsed + "s)...");
        if (allowClientSwitch) {
            ClientWindowInfo other = findOtherClient(clientId);
            if (other != null) {
                log(clientId, "COMBAT[" + combatLabel + "] safe wait — switching to: " + other.getClientId());
                bringToForeground(other);
            }
        }
    }

    /**
     * Anomic Team — Jaguar combat sequence.
     *
     * Steps:
     *   0 → Right-click 'launcher' (open ammo context menu)
     *   1 → Click 'kin_damage' (load kinetic ammo)
     *   2 → Click 'target_jaguar' + click 'orbit' (orbit target)
     *   3 → Proceed (range template check removed)
     *   4 → Keypress 's' (activate module)
     *   5 → SAFE WAIT 90s (can switch to other clients)
     *   6 → Ctrl+click 'target_jaguar' (lock target)
     *   7 → WAIT 3s → check 'locked_jaguar' — if not found, retry (loop back to 6)
     *   8 → Keypress 'adc' (activate modules), click 'locked_jaguar' (select)
     *   9 → SAFE WAIT for set_destination (mission complete → RETURNING)
     */
    private void handleCombatJaguar(String clientId, ClientState cs) {
        int step = cs.getCombatStep();

        switch (step) {
            case 0: {
                log(clientId, "COMBAT[Jaguar] Step 0: right-click launcher (ammo menu).");
                rightClickLauncher(clientId);
                cs.setCombatStep(1);
                break;
            }
            case 1: {
                log(clientId, "COMBAT[Jaguar] Step 1: click kin_damage (load kinetic).");
                missionProcessor.clickTemplate("kin_damage");
                cs.setCombatStep(2);
                break;
            }
            case 2: {
                log(clientId, "COMBAT[Jaguar] Step 2: right-click target_jaguar → click range (range).");
                missionProcessor.rightClickTemplate("target_jaguar");
                missionProcessor.clickTemplate("range");
                cs.setCombatStep(3);
                break;
            }
            case 3: {
                // Range settle (keep_at_range template check removed)
                cs.setCombatStep(4);
                break;
            }
            case 4: {
                log(clientId, "COMBAT[Jaguar] Step 4: press 's' (activate modules).");
                missionProcessor.keyPress("s");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(5);
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "COMBAT[Jaguar] 90s safe wait — switching to: " + other.getClientId());
                    bringToForeground(other);
                }
                break;
            }
            case 5: {
                // SAFE WAIT: 120 seconds
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 120) {
                    log(clientId, "COMBAT[Jaguar] safe wait: " + elapsed + "s / 120s.");
                    if (elapsed > 0 && elapsed % 10 == 0) {
                        ClientWindowInfo other = findOtherClient(clientId);
                        if (other != null) bringToForeground(other);
                    }
                    return;
                }
                log(clientId, "COMBAT[Jaguar] 120s wait complete — proceeding to lock target.");
                cs.setCombatStep(6);
                break;
            }
            case 6: {
                log(clientId, "COMBAT[Jaguar] Step 6: Ctrl+click target_jaguar (lock target).");
                missionProcessor.ctrlClickTemplate("target_jaguar");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(7);
                break;
            }
            case 7: {
                // WAIT 3s for lock animation, then check locked_jaguar
                long elapsed7 = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed7 < 3) return;
                String lockedJ = missionProcessor.findTemplate("locked_jaguar");
                if (lockedJ != null) {
                    log(clientId, "COMBAT[Jaguar] Step 7: locked_jaguar confirmed — proceeding.");
                    cs.setCombatStep(8);
                } else {
                    log(clientId, "COMBAT[Jaguar] Step 7: locked_jaguar NOT found — retrying lock.");
                    cs.setCombatStep(6);
                }
                break;
            }
            case 8: {
                log(clientId, "COMBAT[Jaguar] Step 8: press 'adc' (activate modules), click locked_jaguar (select).");
                missionProcessor.keyPress("adc");
                missionProcessor.clickTemplate("locked_jaguar");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(9);
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "COMBAT[Jaguar] waiting for mission complete — switching to: " + other.getClientId());
                    bringToForeground(other);
                }
                break;
            }
            case 9: {
                pollSetDestinationOrCapsule(clientId, cs, "Jaguar", true, "locked_jaguar");
                break;
            }
            default:
                log(clientId, "COMBAT[Jaguar] unexpected step " + step + " — resetting.");
                cs.setCombatStep(0);
        }
    }

    /**
     * Anomic Team — Vengeance combat sequence.
     *
     * Steps:
     *   0  → Right-click target_vengeance → orbit
     *   1  → Proceed (orbiting template check removed)
     *   2  → Keypress 's' (activate module)
     *   3  → SAFE WAIT 25s
     *   4  → Ctrl+click 'target_inquisitor' (one) + Ctrl+click 'target_vengeance' (lock)
     *   5  → WAIT 3s (lock animation) → confirm 'locked_inquisitor' — if not found (out of range), relock (loop back to 4); if found, select
     *   6  → Press 'adc' (activate modules), click 'target_inquisitor' (verify)
     *   7  → SAFE WAIT 60s, then poll every 5s for 'locked_inquisitor' gone (dead) → re-target; 130s backup cap
     *   8  → Ctrl+click 'target_inquisitor' (re-lock)
     *   9  → WAIT 2s → click 'locked_inquisitor' (select) + press 'ad'
     *   10 → SAFE WAIT 60s
     *   11 → Right-click 'launcher' (ammo switch)
     *   12 → Click 'inf_damage' (load infernal/thermal ammo)
     *   13 → SAFE WAIT 10s
     *   14 → Ctrl+click 'target_vengeance' (lock)
     *   15 → WAIT 3s → check 'locked_vengeance' — if not found, retry (loop back to 14); if found, click + overload (shift+a, a, d)
     *   16 → SAFE WAIT for set_destination (mission complete → RETURNING)
     */
    private void handleCombatVengeance(String clientId, ClientState cs) {
        int step = cs.getCombatStep();

        switch (step) {
            case 0: {
                log(clientId, "COMBAT[Vengeance] Step 0: right-click target_vengeance → click orbit.");
                missionProcessor.rightClickTemplate("target_vengeance");
                missionProcessor.clickTemplate("orbit");
                cs.setCombatStep(1);
                break;
            }
            case 1: {
                // Orbit settle (orbiting template check removed)
                cs.setCombatStep(2);
                break;
            }
            case 2: {
                log(clientId, "COMBAT[Vengeance] Step 2: press 's' (activate modules).");
                missionProcessor.keyPress("s");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(3);
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "COMBAT[Vengeance] 25s safe wait — switching to: " + other.getClientId());
                    bringToForeground(other);
                }
                break;
            }
            case 3: {
                // SAFE WAIT: 25 seconds
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 25) {
                    log(clientId, "COMBAT[Vengeance] safe wait: " + elapsed + "s / 25s.");
                    if (elapsed > 0 && elapsed % 10 == 0) {
                        ClientWindowInfo other = findOtherClient(clientId);
                        if (other != null) bringToForeground(other);
                    }
                    return;
                }
                log(clientId, "COMBAT[Vengeance] 25s wait complete — locking inquisitors.");
                cs.setCombatStep(4);
                break;
            }
            case 4: {
                log(clientId, "COMBAT[Vengeance] Step 4: Ctrl+click 1 target_inquisitor + target_vengeance (lock one inquisitor).");
                missionProcessor.ctrlClickTemplate("target_inquisitor");
                missionProcessor.ctrlClickTemplate("target_vengeance");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(5);
                break;
            }
            case 5: {
                // WAIT 3s for lock animations, then check locked_inquisitor
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 3) return;
                String lockedI5 = missionProcessor.findTemplate("locked_inquisitor");
                if (lockedI5 != null) {
                    log(clientId, "COMBAT[Vengeance] Step 5: locked_inquisitor confirmed — selecting.");
                    missionProcessor.clickTemplate("locked_inquisitor");
                    cs.setCombatStep(6);
                } else {
                    log(clientId, "COMBAT[Vengeance] Step 5: locked_inquisitor NOT found — retrying lock.");
                    cs.setCombatStep(4);
                }
                break;
            }
            case 6: {
                log(clientId, "COMBAT[Vengeance] Step 6: press 'adc' (activate modules), click locked_inquisitor (verify).");
                missionProcessor.keyPress("adc");
                missionProcessor.clickTemplate("locked_inquisitor");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatPollStart(LocalDateTime.now());
                cs.setCombatStep(7);
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "COMBAT[Vengeance] 90s safe wait — switching to: " + other.getClientId());
                    bringToForeground(other);
                }
                break;
            }
            case 7: {
                // First safe wait 90s (modules firing on the locked inquisitors), then poll
                // every 5s: when locked_inquisitor is gone the target is dead → re-target.
                // 130s backup cap so we never hang if the bracket can't be matched.
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 90) {
                    log(clientId, "COMBAT[Vengeance] safe wait: " + elapsed + "s / 90s.");
                    // One-shot ~20s lock re-check: the inquisitor can drift out of lock even after
                    // the 25s settle wait. Verify the lock once; if gone, re-lock + re-fire without
                    // pausing the wait (keep shooting). Too early to be a kill, so a miss = drift.
                    if (elapsed >= 20 && !cs.isFrigateLockRechecked()) {
                        cs.setFrigateLockRechecked(true);
                        if (missionProcessor.findTemplate("locked_inquisitor") == null) {
                            log(clientId, "COMBAT[Vengeance] 20s lock re-check: locked_inquisitor gone (drifted) — re-locking + re-firing, restarting firing wait.");
                            missionProcessor.ctrlClickTemplate("target_inquisitor");
                            missionProcessor.keyPress("adc");
                            cs.setCombatWaitStart(LocalDateTime.now()); // fire just restarted — reset countdown
                            return;
                        } else {
                            log(clientId, "COMBAT[Vengeance] 20s lock re-check: locked_inquisitor still held — keep firing.");
                        }
                    }
                    if (elapsed > 0 && elapsed % 10 == 0) {
                        ClientWindowInfo other = findOtherClient(clientId);
                        if (other != null) bringToForeground(other);
                    }
                    return;
                }
                long sincePoll = Duration.between(cs.getCombatPollStart(), LocalDateTime.now()).getSeconds();
                if (sincePoll < 5) return;
                cs.setCombatPollStart(LocalDateTime.now());
                String lockedI7 = missionProcessor.findTemplate("locked_inquisitor");
                if (lockedI7 == null) {
                    log(clientId, "COMBAT[Vengeance] Step 7: inquisitor dead/gone — re-targeting.");
                    cs.setCombatStep(8);
                } else if (elapsed >= 130) {
                    log(clientId, "COMBAT[Vengeance] Step 7: 130s backup cap reached — re-targeting.");
                    cs.setCombatStep(8);
                } else {
                    log(clientId, "COMBAT[Vengeance] Step 7: inquisitor still alive (" + elapsed + "s) — keep shooting.");
                }
                break;
            }
            case 8: {
                log(clientId, "COMBAT[Vengeance] Step 8: Ctrl+click target_inquisitor (lock).");
                missionProcessor.ctrlClickTemplate("target_inquisitor");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(9);
                break;
            }
            case 9: {
                // 3s wait for lock, then check locked_inquisitor
                long elapsed9 = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed9 < 3) return;
                String lockedI9 = missionProcessor.findTemplate("locked_inquisitor");
                if (lockedI9 == null) {
                    log(clientId, "COMBAT[Vengeance] Step 9: locked_inquisitor NOT found — retrying lock.");
                    cs.setCombatStep(8);
                    break;
                }
                log(clientId, "COMBAT[Vengeance] Step 9: locked_inquisitor confirmed — click + press 'ad'.");
                missionProcessor.clickTemplate("locked_inquisitor");
                missionProcessor.keyPress("ad");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(10);
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "COMBAT[Vengeance] 60s safe wait — switching to: " + other.getClientId());
                    bringToForeground(other);
                }
                break;
            }
            case 10: {
                // SAFE WAIT: 60 seconds
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 60) {
                    log(clientId, "COMBAT[Vengeance] safe wait: " + elapsed + "s / 60s.");
                    if (elapsed > 0 && elapsed % 10 == 0) {
                        ClientWindowInfo other = findOtherClient(clientId);
                        if (other != null) bringToForeground(other);
                    }
                    return;
                }
                log(clientId, "COMBAT[Vengeance] 60s wait complete — switching ammo.");
                cs.setCombatStep(11);
                break;
            }
            case 11: {
                log(clientId, "COMBAT[Vengeance] Step 11: right-click launcher (ammo switch).");
                rightClickLauncher(clientId);
                cs.setCombatStep(12);
                break;
            }
            case 12: {
                log(clientId, "COMBAT[Vengeance] Step 12: click inf_damage (load infernal).");
                missionProcessor.clickTemplate("inf_damage");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(13);
                break;
            }
            case 13: {
                // SAFE WAIT: 10 seconds (ammo load)
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 10) {
                    log(clientId, "COMBAT[Vengeance] safe wait: " + elapsed + "s / 10s.");
                    return;
                }
                log(clientId, "COMBAT[Vengeance] 10s wait complete — activating modules.");
                cs.setCombatStep(14);
                break;
            }
            case 14: {
                log(clientId, "COMBAT[Vengeance] Step 14: Ctrl+click target_vengeance (lock).");
                missionProcessor.ctrlClickTemplate("target_vengeance");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(15);
                break;
            }
            case 15: {
                String lockedV = missionProcessor.findTemplate("locked_vengeance");
                if (lockedV != null) {
                    log(clientId, "COMBAT[Vengeance] Step 15: locked_vengeance confirmed — click + overload (shift+a, a, d).");
                    missionProcessor.clickTemplate("locked_vengeance");
                    missionProcessor.shiftKeyPress("a");
                    missionProcessor.keyPress("a");
                    missionProcessor.keyPress("d"); // activate 'd' after overloading 'a'
                    cs.setCombatStep(16);
                    ClientWindowInfo other = findOtherClient(clientId);
                    if (other != null) {
                        log(clientId, "COMBAT[Vengeance] shooting vengeance — switching to: " + other.getClientId());
                        bringToForeground(other);
                    }
                } else {
                    log(clientId, "COMBAT[Vengeance] Step 15: locked_vengeance NOT found — retrying lock.");
                    cs.setCombatStep(14);
                }
                break;
            }
            case 16: {
                pollSetDestinationOrCapsule(clientId, cs, "Vengeance", true);
                break;
            }
            default:
                log(clientId, "COMBAT[Vengeance] unexpected step " + step + " — resetting.");
                cs.setCombatStep(0);
        }
    }

    /**
     * Anomic Team — Enyo combat sequence.
     *
     * Steps:
     *   0  → Right-click 'launcher' (ammo context menu)
     *   1  → Click 'exp_damage' (load explosive ammo)
     *   2  → Click 'target_enyo' + click 'orbit' (orbit)
     *   3  → Proceed (orbiting template check removed)
     *   4  → Keypress 's' (activate module)
     *   5  → SAFE WAIT 25s
     *   6  → Ctrl+click ONE 'target_navitas' (lock one)
     *   7  → WAIT 3s → confirm 'locked_navitas' — if not found (out of range), relock (loop back to 6); if found, select
     *   8  → Press 'adc', click 'locked_navitas' (verify)
     *   9  → SAFE WAIT 90s, then poll every 5s for 'locked_navitas' gone (dead) → select enyo; 120s backup cap
     *   10 → Ctrl+click 'target_enyo' (lock)
     *   11 → WAIT 3s → check 'locked_enyo' — if not found, retry (loop back to 10); if found, click + overload (shift+a, a, d)
     *   12 → SAFE WAIT for set_destination (mission complete → RETURNING)
     *   13 → SAFE WAIT for set_destination (mission complete → RETURNING)
     */
    private void handleCombatEnyo(String clientId, ClientState cs) {
        int step = cs.getCombatStep();

        switch (step) {
            case 0: {
                log(clientId, "COMBAT[Enyo] Step 0: right-click launcher (ammo menu).");
                rightClickLauncher(clientId);
                cs.setCombatStep(1);
                break;
            }
            case 1: {
                log(clientId, "COMBAT[Enyo] Step 1: click exp_damage (load explosive).");
                missionProcessor.clickTemplate("exp_damage");
                cs.setCombatStep(2);
                break;
            }
            case 2: {
                log(clientId, "COMBAT[Enyo] Step 2: right-click target_enyo → click orbit.");
                missionProcessor.rightClickTemplate("target_enyo");
                missionProcessor.clickTemplate("orbit");
                cs.setCombatStep(3);
                break;
            }
            case 3: {
                // Orbit settle (orbiting template check removed)
                cs.setCombatStep(4);
                break;
            }
            case 4: {
                log(clientId, "COMBAT[Enyo] Step 4: press 's' (activate modules).");
                missionProcessor.keyPress("s");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(5);
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "COMBAT[Enyo] 25s safe wait — switching to: " + other.getClientId());
                    bringToForeground(other);
                }
                break;
            }
            case 5: {
                // SAFE WAIT: 25 seconds
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 25) {
                    log(clientId, "COMBAT[Enyo] safe wait: " + elapsed + "s / 25s.");
                    if (elapsed > 0 && elapsed % 10 == 0) {
                        ClientWindowInfo other = findOtherClient(clientId);
                        if (other != null) bringToForeground(other);
                    }
                    return;
                }
                log(clientId, "COMBAT[Enyo] 25s wait complete — locking navitas targets.");
                cs.setCombatStep(6);
                break;
            }
            case 6: {
                log(clientId, "COMBAT[Enyo] Step 6: Ctrl+click ONE target_navitas (lock one).");
                missionProcessor.ctrlClickTemplate("target_navitas");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(7);
                break;
            }
            case 7: {
                // WAIT 3s for lock animation, then confirm locked_navitas.
                // If absent the target is out of range / not yet locked — relock and retry.
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 3) return;
                String lockedN = missionProcessor.findTemplate("locked_navitas");
                if (lockedN != null) {
                    log(clientId, "COMBAT[Enyo] Step 7: locked_navitas confirmed — selecting.");
                    missionProcessor.clickTemplate("locked_navitas");
                    cs.setCombatStep(8);
                } else {
                    log(clientId, "COMBAT[Enyo] Step 7: locked_navitas NOT found (out of range) — relocking one.");
                    cs.setCombatStep(6);
                }
                break;
            }
            case 8: {
                log(clientId, "COMBAT[Enyo] Step 8: press 'adc' (activate modules), click locked_navitas (verify).");
                missionProcessor.keyPress("adc");
                missionProcessor.clickTemplate("locked_navitas");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatPollStart(LocalDateTime.now());
                cs.setCombatStep(9);
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "COMBAT[Enyo] 90s safe wait — switching to: " + other.getClientId());
                    bringToForeground(other);
                }
                break;
            }
            case 9: {
                // First safe wait 90s (modules firing on the locked navitas), then poll
                // every 5s: when locked_navitas is gone the target is dead → select enyo.
                // 120s backup cap so we never hang if the bracket can't be matched.
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 90) {
                    log(clientId, "COMBAT[Enyo] safe wait: " + elapsed + "s / 90s.");
                    // One-shot ~20s lock re-check: the navitas can drift out of lock even after
                    // the 25s settle wait. Verify the lock once; if gone, re-lock + re-fire without
                    // pausing the wait (keep shooting). Too early to be a kill, so a miss = drift.
                    if (elapsed >= 20 && !cs.isFrigateLockRechecked()) {
                        cs.setFrigateLockRechecked(true);
                        if (missionProcessor.findTemplate("locked_navitas") == null) {
                            log(clientId, "COMBAT[Enyo] 20s lock re-check: locked_navitas gone (drifted) — re-locking + re-firing, restarting firing wait.");
                            missionProcessor.ctrlClickTemplate("target_navitas");
                            missionProcessor.keyPress("adc");
                            cs.setCombatWaitStart(LocalDateTime.now()); // fire just restarted — reset countdown
                            return;
                        } else {
                            log(clientId, "COMBAT[Enyo] 20s lock re-check: locked_navitas still held — keep firing.");
                        }
                    }
                    if (elapsed > 0 && elapsed % 10 == 0) {
                        ClientWindowInfo other = findOtherClient(clientId);
                        if (other != null) bringToForeground(other);
                    }
                    return;
                }
                long sincePoll = Duration.between(cs.getCombatPollStart(), LocalDateTime.now()).getSeconds();
                if (sincePoll < 5) return;
                cs.setCombatPollStart(LocalDateTime.now());
                String lockedN = missionProcessor.findTemplate("locked_navitas");
                if (lockedN == null) {
                    log(clientId, "COMBAT[Enyo] Step 9: navitas dead/gone — selecting enyo.");
                    cs.setCombatStep(10);
                } else if (elapsed >= 120) {
                    log(clientId, "COMBAT[Enyo] Step 9: 120s backup cap reached — selecting enyo.");
                    cs.setCombatStep(10);
                } else {
                    log(clientId, "COMBAT[Enyo] Step 9: navitas still alive (" + elapsed + "s) — keep shooting.");
                }
                break;
            }
            case 10: {
                log(clientId, "COMBAT[Enyo] Step 10: Ctrl+click target_enyo (lock).");
                missionProcessor.ctrlClickTemplate("target_enyo");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(11);
                break;
            }
            case 11: {
                String lockedE = missionProcessor.findTemplate("locked_enyo");
                if (lockedE != null) {
                    log(clientId, "COMBAT[Enyo] Step 11: locked_enyo confirmed — click + overload (shift+a, a, d).");
                    missionProcessor.clickTemplate("locked_enyo");
                    missionProcessor.shiftKeyPress("a");
                    missionProcessor.keyPress("a");
                    missionProcessor.keyPress("d"); // activate 'd' after overloading 'a'
                    cs.setCombatStep(12);
                    ClientWindowInfo other = findOtherClient(clientId);
                    if (other != null) {
                        log(clientId, "COMBAT[Enyo] shooting enyo — switching to: " + other.getClientId());
                        bringToForeground(other);
                    }
                } else {
                    log(clientId, "COMBAT[Enyo] Step 11: locked_enyo NOT found — retrying lock.");
                    cs.setCombatStep(10);
                }
                break;
            }
            case 12: {
                pollSetDestinationOrCapsule(clientId, cs, "Enyo", true);
                break;
            }
            default:
                log(clientId, "COMBAT[Enyo] unexpected step " + step + " — resetting.");
                cs.setCombatStep(0);
        }
    }

    /**
     * Anomic Base — Hawk combat sequence.
     *
     * Steps:
     *   0  → Right-click 'launcher' (ammo context menu)
     *   1  → Click 'em_damage' (load EM ammo)
     *   2  → Click 'target_hawk' + click 'orbit' (orbit)
     *   3  → Proceed (orbiting template check removed)
     *   4  → SAFE WAIT 25s
     *   5  → Ctrl+click ONE 'target_bantam' (lock one)
     *   6  → WAIT 3s → confirm 'locked_bantam' — if not found (out of range), relock (loop back to 5); if found, select
     *   7  → Press 'adc', click 'locked_bantam' (verify)
     *   8  → SAFE WAIT 60s, then poll every 5s for 'locked_bantam' gone (dead) → switch to hawk; 90s backup cap
     *   9  → Ctrl+click 'target_hawk' (lock)
     *   10 → WAIT 3s → check 'locked_hawk' — if not found, retry (loop back to 9); if found, click + overload (shift+a, a, d)
     *   11 → SAFE WAIT for set_destination (mission complete → RETURNING)
     */
    private void handleCombatHawk(String clientId, ClientState cs) {
        int step = cs.getCombatStep();

        switch (step) {
            case 0: {
                log(clientId, "COMBAT[Hawk] Step 0: right-click launcher (ammo menu).");
                rightClickLauncher(clientId);
                cs.setCombatStep(1);
                break;
            }
            case 1: {
                log(clientId, "COMBAT[Hawk] Step 1: click em_damage (load EM).");
                missionProcessor.clickTemplate("em_damage");
                cs.setCombatStep(2);
                break;
            }
            case 2: {
                log(clientId, "COMBAT[Hawk] Step 2: right-click target_hawk → click orbit.");
                missionProcessor.rightClickTemplate("target_hawk");
                missionProcessor.clickTemplate("orbit");
                cs.setCombatStep(3);
                break;
            }
            case 3: {
                // Orbit settle (orbiting template check removed) — press 's' and proceed
                log(clientId, "COMBAT[Hawk] Step 3: press 's' (activate modules).");
                missionProcessor.keyPress("s");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(4);
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "COMBAT[Hawk] 25s safe wait — switching to: " + other.getClientId());
                    bringToForeground(other);
                }
                break;
            }
            case 4: {
                // SAFE WAIT: 25 seconds
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 25) {
                    log(clientId, "COMBAT[Hawk] safe wait: " + elapsed + "s / 25s.");
                    if (elapsed > 0 && elapsed % 10 == 0) {
                        ClientWindowInfo other = findOtherClient(clientId);
                        if (other != null) bringToForeground(other);
                    }
                    return;
                }
                log(clientId, "COMBAT[Hawk] 25s wait complete — locking targets.");
                cs.setCombatStep(5);
                break;
            }
            case 5: {
                log(clientId, "COMBAT[Hawk] Step 5: Ctrl+click ONE target_bantam (lock one).");
                missionProcessor.ctrlClickTemplate("target_bantam");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(6);
                break;
            }
            case 6: {
                // WAIT 3s for lock animation, then confirm locked_bantam.
                // If absent the target is out of range / not yet locked — relock and retry.
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 3) return;
                String lockedB = missionProcessor.findTemplate("locked_bantam");
                if (lockedB != null) {
                    log(clientId, "COMBAT[Hawk] Step 6: locked_bantam confirmed — selecting.");
                    missionProcessor.clickTemplate("locked_bantam");
                    cs.setCombatStep(7);
                } else {
                    log(clientId, "COMBAT[Hawk] Step 6: locked_bantam NOT found (out of range) — relocking one.");
                    cs.setCombatStep(5);
                }
                break;
            }
            case 7: {
                log(clientId, "COMBAT[Hawk] Step 7: press 'adc' (activate modules), click locked_bantam (verify).");
                missionProcessor.keyPress("adc");
                missionProcessor.clickTemplate("locked_bantam");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatPollStart(LocalDateTime.now());
                cs.setCombatStep(8);
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "COMBAT[Hawk] 60s safe wait — switching to: " + other.getClientId());
                    bringToForeground(other);
                }
                break;
            }
            case 8: {
                // First safe wait 60s (modules firing on the locked bantam), then poll
                // every 5s: when locked_bantam is gone the target is dead → switch to hawk.
                // 90s backup cap so we never hang if the bracket can't be matched.
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 60) {
                    log(clientId, "COMBAT[Hawk] safe wait: " + elapsed + "s / 60s.");
                    // One-shot ~20s lock re-check: the bantam can drift out of lock even after
                    // the 25s settle wait. Verify the lock once; if gone, re-lock + re-fire without
                    // pausing the wait (keep shooting). Too early to be a kill, so a miss = drift.
                    if (elapsed >= 20 && !cs.isFrigateLockRechecked()) {
                        cs.setFrigateLockRechecked(true);
                        if (missionProcessor.findTemplate("locked_bantam") == null) {
                            log(clientId, "COMBAT[Hawk] 20s lock re-check: locked_bantam gone (drifted) — re-locking + re-firing, restarting firing wait.");
                            missionProcessor.ctrlClickTemplate("target_bantam");
                            missionProcessor.keyPress("adc");
                            cs.setCombatWaitStart(LocalDateTime.now()); // fire just restarted — reset countdown
                            return;
                        } else {
                            log(clientId, "COMBAT[Hawk] 20s lock re-check: locked_bantam still held — keep firing.");
                        }
                    }
                    if (elapsed > 0 && elapsed % 10 == 0) {
                        ClientWindowInfo other = findOtherClient(clientId);
                        if (other != null) bringToForeground(other);
                    }
                    return;
                }
                long sincePoll = Duration.between(cs.getCombatPollStart(), LocalDateTime.now()).getSeconds();
                if (sincePoll < 5) return;
                cs.setCombatPollStart(LocalDateTime.now());
                String lockedB = missionProcessor.findTemplate("locked_bantam");
                if (lockedB == null) {
                    log(clientId, "COMBAT[Hawk] Step 8: bantam dead/gone — switching to hawk.");
                    cs.setCombatStep(9);
                } else if (elapsed >= 90) {
                    log(clientId, "COMBAT[Hawk] Step 8: 90s backup cap reached — switching to hawk.");
                    cs.setCombatStep(9);
                } else {
                    log(clientId, "COMBAT[Hawk] Step 8: bantam still alive (" + elapsed + "s) — keep shooting.");
                }
                break;
            }
            case 9: {
                log(clientId, "COMBAT[Hawk] Step 9: Ctrl+click target_hawk (lock).");
                missionProcessor.ctrlClickTemplate("target_hawk");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(10);
                break;
            }
            case 10: {
                String lockedH = missionProcessor.findTemplate("locked_hawk");
                if (lockedH != null) {
                    log(clientId, "COMBAT[Hawk] Step 10: locked_hawk confirmed — click + overload (shift+a, a, d).");
                    missionProcessor.clickTemplate("locked_hawk");
                    missionProcessor.shiftKeyPress("a");
                    missionProcessor.keyPress("a");
                    missionProcessor.keyPress("d"); // activate 'd' after overloading 'a'
                    cs.setCombatWaitStart(LocalDateTime.now());
                    cs.setCombatStep(11);
                    ClientWindowInfo other = findOtherClient(clientId);
                    if (other != null) {
                        log(clientId, "COMBAT[Hawk] waiting for mission complete — switching to: " + other.getClientId());
                        bringToForeground(other);
                    }
                } else {
                    log(clientId, "COMBAT[Hawk] Step 10: locked_hawk NOT found — retrying lock.");
                    cs.setCombatStep(9);
                }
                break;
            }
            case 11: {
                pollSetDestinationOrCapsule(clientId, cs, "Hawk", true);
                break;
            }
            default:
                log(clientId, "COMBAT[Hawk] unexpected step " + step + " — resetting.");
                cs.setCombatStep(0);
        }
    }

    /**
     * Anomic Base — Sansha combat sequence.
     *
     * Steps:
     *   0  → Shift+asdx (overload modules)
     *   1  → Ctrl+click 'target_sansha' (lock) + double-click (select) + press 's'
     *   2  → press 'adxc' (activate modules)
     *   3  → WAIT 4s → right-click target_sansha → click range (keep at range)
     *   4  → Proceed (keep_at_range template check removed)
     *   5  → WAIT 15s
     *   6  → Shift+ds (stop overloading afterburner + web; reps 'c' never overloaded)
     *   7  → Alt+F1 (burst cap recharge)
     *   8  → WAIT 1s
     *   9  → Alt+F1 (deactivate cap module)
     *  10  → WAIT for set_destination/dock; if fight > 60s → Alt+F1 again (once); 180s hardcap (pod check) → step 11
     *  11  → press 'xc' (deactivate modules) + handle dock/returning (RETURNING clicks set_destination coords)
     */
    private void handleCombatSansha(String clientId, ClientState cs) {
        int step = cs.getCombatStep();

        switch (step) {
            case 0: {
                log(clientId, "COMBAT[Sansha] Step 0: Shift+asdx (overload modules).");
                missionProcessor.shiftKeyPress("asdx");
                cs.setCombatStartTime(LocalDateTime.now()); // start of the fight, for the 35s extra-cap check
                cs.setExtraCapPulseDone(false);
                cs.setCombatStep(1);
                break;
            }
            case 1: {
                log(clientId, "COMBAT[Sansha] Step 1: press 's' (pre-lock).");
                missionProcessor.keyPress("s");
                cs.setCombatStep(2);
                break;
            }
            case 2: {
                log(clientId, "COMBAT[Sansha] Step 2: double-click target_sansha (select) + Ctrl+click (lock) + press 'adxc' (activate modules).");
                missionProcessor.doubleClickTemplate("target_sansha");
                missionProcessor.ctrlClickTemplate("target_sansha");
                missionProcessor.keyPress("adxc");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(3);
                break;
            }
            case 3: {
                // WAIT 4s before keeping at range (like Worm) so modules apply first.
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 4) {
                    log(clientId, "COMBAT[Sansha] wait before range: " + elapsed + "s / 4s.");
                    return;
                }
                log(clientId, "COMBAT[Sansha] Step 3: right-click target_sansha → click range.");
                missionProcessor.rightClickTemplate("target_sansha");
                missionProcessor.clickTemplate("range");
                cs.setCombatStep(4);
                break;
            }
            case 4: {
                // Range settle (keep_at_range template check removed)
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(5);
                break;
            }
            case 5: {
                // WAIT: 10 seconds
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 15) {
                    log(clientId, "COMBAT[Sansha] wait: " + elapsed + "s / 15s.");
                    return;
                }
                log(clientId, "COMBAT[Sansha] 15s wait complete — stopping overloads.");
                cs.setCombatStep(6);
                break;
            }
            case 6: {
                log(clientId, "COMBAT[Sansha] Step 6: Shift+ds (stop overloading afterburner + web; reps 'c' not overloaded).");
                missionProcessor.shiftKeyPress("ds");
                cs.setCombatStep(7);
                break;
            }
            case 7: {
                log(clientId, "COMBAT[Sansha] Step 7: Alt+F1 (burst cap recharge).");
                missionProcessor.altKeyPress("f1");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(8);
                break;
            }
            case 8: {
                // WAIT: 1 second
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 1) return;
                cs.setCombatStep(9);
                break;
            }
            case 9: {
                log(clientId, "COMBAT[Sansha] Step 9: Alt+F1 (deactivate cap module).");
                missionProcessor.altKeyPress("f1");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(10);
                break;
            }
            case 10: {
                // WAIT for set_destination/dock
                String completed = missionProcessor.detectCombatComplete();
                if (completed != null) {
                    log(clientId, "COMBAT[Sansha] " + completed + " detected — deactivating modules.");
                    cs.setCombatStep(11);
                    break;
                }

                // Long fight: if the whole combat has run past 50s and we're still not
                // done, give the cap module another Alt+F1 pulse (once).
                if (!cs.isExtraCapPulseDone()
                        && Duration.between(cs.getCombatStartTime(), LocalDateTime.now()).getSeconds() > 50) {
                    log(clientId, "COMBAT[Sansha] fight > 50s — Alt+F1 again (extra cap pulse).");
                    missionProcessor.altKeyPress("f1");
                    cs.setExtraCapPulseDone(true);
                }

                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();

                // 180s hardcap: no set_destination/dock seen — pod check, then force
                // deactivate + return (step 11 → RETURNING clicks the set_destination
                // coords fallback).
                if (elapsed >= 180) {
                    if (missionProcessor.isFlyingPod()) {
                        handlePodDeath(clientId, true);
                        return;
                    }
                    log(clientId, "COMBAT[Sansha] 180s hardcap — no completion, forcing deactivate + return.");
                    cs.setCombatStep(11);
                    break;
                }

                log(clientId, "COMBAT[Sansha] waiting for mission complete (" + elapsed + "s)...");
                break;
            }
            case 11: {
                log(clientId, "COMBAT[Sansha] Step 11: press 'xc' (deactivate modules).");
                missionProcessor.keyPress("xc");
                // Re-detect: dock = same system, set_destination = different system
                String result = missionProcessor.detectCombatComplete();
                if ("dock".equals(result)) {
                    log(clientId, "COMBAT[Sansha] dock detected — same system, docking.");
                    missionProcessor.clickTemplate("dock");
                    cs.setDockingToStation(true);
                    cs.setTravelPhase(ClientState.TravelPhase.NONE);
                    cs.setWarpStartTime(LocalDateTime.now());
                    stateManager.setState(clientId, ClientState.State.WARPING);
                } else {
                    stateManager.setState(clientId, ClientState.State.RETURNING);
                }
                break;
            }
            default:
                log(clientId, "COMBAT[Sansha] unexpected step " + step + " — resetting.");
                cs.setCombatStep(0);
        }
    }

    /**
     * Infested Ruins — combat sequence.
     *
     *   0  → Shift+as (overload modules)
     *   1  → double-click hive (approach)
     *   2  → press 's' (activate web)
     *   3  → WAIT 15s
     *   4  → press 's' (cycle web)
     *   5  → Ctrl+click target_hive/hive1/hive2/hive3 (lock) + press 'a' + (3s) Ctrl+Space (stop ship) + switch client
     *   6  → SAFE WAIT 50s (switch to other client every 10s during the wait)
     *   7  → await set_destination/dock (→ RETURNING/WARPING); 120s backstop → RETURNING; pod check; if jamed → step 8
     *        NOTE: no client switching after the 50s wait — stay on this client until done or jam-escaped
     *   8  → (jamed) burn away (double-click stargate + 's'), WAIT 5s → back to 7
     */
    private void handleCombatInfested(String clientId, ClientState cs) {
        int step = cs.getCombatStep();

        switch (step) {
            case 0: {
                log(clientId, "COMBAT[Infested] Step 0: Shift+as (overload modules).");
                missionProcessor.shiftKeyPress("as");
                cs.setCombatStep(1);
                break;
            }
            case 1: {
                String hive1 = missionProcessor.findAnyTemplate("target_hive", "target_hive1", "target_hive2", "target_hive3");
                if (hive1 != null) {
                    log(clientId, "COMBAT[Infested] Step 1: double-click " + hive1 + " (approach).");
                    missionProcessor.doubleClickTemplate(hive1);
                    cs.setCombatStep(2);
                } else {
                    log(clientId, "COMBAT[Infested] Step 1: no hive target found — retrying.");
                }
                break;
            }
            case 2: {
                log(clientId, "COMBAT[Infested] Step 2: press 's' (activate web).");
                missionProcessor.keyPress("s");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(3);
                break;
            }
            case 3: {
                // WAIT 15 seconds
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 15) {
                    log(clientId, "COMBAT[Infested] wait: " + elapsed + "s / 15s.");
                    return;
                }
                log(clientId, "COMBAT[Infested] 15s wait complete.");
                cs.setCombatStep(4);
                break;
            }
            case 4: {
                log(clientId, "COMBAT[Infested] Step 4: press 's' (cycle web).");
                missionProcessor.keyPress("s");
                cs.setCombatStep(5);
                break;
            }
            case 5: {
                log(clientId, "COMBAT[Infested] Step 5: Ctrl+click hive (lock), press 'a', then Ctrl+Space (stop ship) after 3s.");
                String hive5 = missionProcessor.findAnyTemplate("target_hive", "target_hive1", "target_hive2", "target_hive3");
                if (hive5 != null) {
                    missionProcessor.ctrlClickTemplate(hive5);
                    missionProcessor.keyPress("a");
                    missionProcessor.clickTemplate(hive5);
                } else {
                    log(clientId, "COMBAT[Infested] Step 5: no hive target found — skipping lock.");
                    missionProcessor.keyPress("a");
                }
                // Ctrl+Space (stop ship) moved to the end, after a 3s delay.
                try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                missionProcessor.ctrlKeyPress("space");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(6);
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "COMBAT[Infested] switching to: " + other.getClientId());
                    bringToForeground(other);
                }
                break;
            }
            case 6: {
                // SAFE WAIT: 50 seconds
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 50) {
                    log(clientId, "COMBAT[Infested] safe wait: " + elapsed + "s / 50s.");
                    if (elapsed > 0 && elapsed % 10 == 0) {
                        ClientWindowInfo other = findOtherClient(clientId);
                        if (other != null) bringToForeground(other);
                    }
                    return;
                }
                log(clientId, "COMBAT[Infested] 50s wait complete — awaiting set_destination.");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(7);
                break;
            }
            case 7: {
                // Await completion like other combats: set_destination/dock → leave.
                // Infested also jams your warp, so if 'jamed' shows, burn away (step 8).
                String completed = missionProcessor.detectCombatComplete();
                if ("dock".equals(completed)) {
                    log(clientId, "COMBAT[Infested] dock detected — same system, docking.");
                    missionProcessor.clickTemplate("dock");
                    cs.setDockingToStation(true);
                    cs.setJamCheckOnWarp(true); // escape warp may be jammed
                    cs.setTravelPhase(ClientState.TravelPhase.NONE);
                    cs.setWarpStartTime(LocalDateTime.now());
                    stateManager.setState(clientId, ClientState.State.WARPING);
                    return;
                }
                if (completed != null) { // set_destination
                    log(clientId, "COMBAT[Infested] set_destination detected — mission complete, returning.");
                    cs.setJamCheckOnWarp(true); // escape warp may be jammed
                    cs.setTravelPhase(ClientState.TravelPhase.RETURNING_HOME);
                    stateManager.setState(clientId, ClientState.State.RETURNING);
                    return;
                }

                long elapsed7 = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();

                // Backstop: after 120s with no set_destination prompt it may never
                // appear (or we drifted out without it). Check for a pod first,
                // otherwise go to RETURNING — which clicks set_destination (template,
                // or the hardcoded coords as fallback) so the route home is always set.
                if (elapsed7 >= 120) {
                    if (missionProcessor.isFlyingPod()) {
                        handlePodDeath(clientId, true);
                        return;
                    }
                    log(clientId, "COMBAT[Infested] no set_destination after 120s — returning (set_destination will be clicked).");
                    cs.setJamCheckOnWarp(true); // escape warp may be jammed
                    cs.setTravelPhase(ClientState.TravelPhase.RETURNING_HOME);
                    stateManager.setState(clientId, ClientState.State.RETURNING);
                    return;
                }

                // Warp jam — burn toward the stargate to break it, then wait (step 8).
                if (missionProcessor.findTemplate("jamed") != null) {
                    log(clientId, "COMBAT[Infested] JAMED — double-click stargate + press 's' to burn away.");
                    missionProcessor.doubleClickTemplate("stargate");
                    missionProcessor.keyPress("s");
                    cs.setCombatWaitStart(LocalDateTime.now());
                    cs.setCombatStep(8);
                    break;
                }

                // No client switching — stay here and keep watching for set_destination/dock
                // or a jam to escape until the mission completes (or the 120s backstop).
                log(clientId, "COMBAT[Infested] awaiting set_destination, no switch (" + elapsed7 + "s)...");
                break;
            }
            case 8: {
                // After burning away from a jam, wait 5s then resume awaiting completion.
                long elapsed8 = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed8 < 5) {
                    log(clientId, "COMBAT[Infested] jam burn wait: " + elapsed8 + "s / 5s.");
                    return;
                }
                log(clientId, "COMBAT[Infested] jam burn complete — resuming await.");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(7);
                break;
            }
            default:
                log(clientId, "COMBAT[Infested] unexpected step " + step + " — resetting.");
                cs.setCombatStep(0);
        }
    }

    /**
     * Recon 1 of 3 — combat sequence.
     *
     * Entry: IDLE[TO_MISSION] already activated the first gate (right-click activate_gate
     *        → mission_start). The 20s IN_COMBAT grace period covers the gate load.
     *
     *   0  → right-click activate_gate → click mission_start (second gate) → press 's' twice
     *   1  → SAFE WAIT 30s (gate load) + switch to other client
     *   2  → look for dock → click dock → WARPING → COMPLETING
     */
    private void handleCombatRecon(String clientId, ClientState cs) {
        int step = cs.getCombatStep();

        switch (step) {
            case 0: {
                log(clientId, "COMBAT[Recon] Step 0: right-click activate_gate → mission_start (second gate), press 's'.");
                missionProcessor.rightClickAndSelect("activate_gate", "mission_start");
                missionProcessor.keyPress("s");
                missionProcessor.keyPress("s");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(1);
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "COMBAT[Recon] gate loading — safe wait 20s, switching to: " + other.getClientId());
                    bringToForeground(other);
                }
                break;
            }
            case 1: {
                // SAFE WAIT 30s for gate load
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 30) {
                    return;
                }
                log(clientId, "COMBAT[Recon] 30s gate load complete — looking for dock.");
                cs.setCombatStep(2);
                break;
            }
            case 2: {
                // Look for dock button like normal travel — click it and enter WARPING.
                String dockCheck = missionProcessor.detectDockOnly();
                if (dockCheck != null) {
                    log(clientId, "COMBAT[Recon] Step 2: dock visible — clicking dock.");
                    missionProcessor.clickDock();
                    cs.setDockingToStation(true);
                    cs.setWarpStartTime(LocalDateTime.now());
                    cs.setTravelPhase(ClientState.TravelPhase.RETURNING_HOME);
                    stateManager.setState(clientId, ClientState.State.WARPING);
                } else {
                    log(clientId, "COMBAT[Recon] Step 2: dock not visible yet — waiting.");
                }
                break;
            }
            default:
                log(clientId, "COMBAT[Recon] unexpected step " + step + " — resetting.");
                cs.setCombatStep(0);
        }
    }

    /**
     * Angel / Dramiel — combat sequence.
     *
     * Steps:
     *   0 → double-click target_angel (select) + Ctrl+click (lock)
     *   1 → Shift+axc (overload modules)
     *   2 → press 'asdx', click target_angel
     *   3 → WAIT 5s → press 'c'
     *   4 → Right-click target_angel + click range (keep at range)
     *   5 → Proceed (keep_at_range template check removed)
     *   6 → WAIT for set_destination; 60s hardcap (pod check) → step 7
     *   7 → press 'xc' (deactivate modules) → RETURNING (clicks set_destination coords)
     */
    private void handleCombatAngel(String clientId, ClientState cs) {
        int step = cs.getCombatStep();

        switch (step) {
            case 0: {
                log(clientId, "COMBAT[Angel] Step 0: double-click target_angel (select) + Ctrl+click (lock).");
                missionProcessor.doubleClickTemplate("target_angel");
                missionProcessor.ctrlClickTemplate("target_angel");
                cs.setCombatStep(1);
                break;
            }
            case 1: {
                log(clientId, "COMBAT[Angel] Step 1: Shift+axc (overload modules).");
                missionProcessor.shiftKeyPress("axc");
                cs.setCombatStep(2);
                break;
            }
            case 2: {
                log(clientId, "COMBAT[Angel] Step 2: press 'asdx', click target_angel.");
                missionProcessor.keyPress("asdx");
                missionProcessor.clickTemplate("target_angel");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(3);
                break;
            }
            case 3: {
                // WAIT 5s, then press 'c'
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 5) {
                    log(clientId, "COMBAT[Angel] wait before 'c': " + elapsed + "s / 5s.");
                    return;
                }
                log(clientId, "COMBAT[Angel] Step 3: press 'c'.");
                missionProcessor.keyPress("c");
                cs.setCombatStep(4);
                break;
            }
            case 4: {
                log(clientId, "COMBAT[Angel] Step 4: right-click target_angel → click range.");
                missionProcessor.rightClickTemplate("target_angel");
                missionProcessor.clickTemplate("range");
                cs.setCombatStep(5);
                break;
            }
            case 5: {
                // Range settle (keep_at_range template check removed)
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(6);
                break;
            }
            case 6: {
                // WAIT for set_destination/dock
                String completed = missionProcessor.detectCombatComplete();
                if (completed != null) {
                    log(clientId, "COMBAT[Angel] " + completed + " detected — deactivating modules.");
                    cs.setCombatStep(7);
                    break;
                }

                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();

                // 60s hardcap: no set_destination/dock seen — pod check, then force
                // deactivate + return (step 7 → RETURNING clicks the set_destination
                // coords fallback).
                if (elapsed >= 60) {
                    if (missionProcessor.isFlyingPod()) {
                        handlePodDeath(clientId, true);
                        return;
                    }
                    log(clientId, "COMBAT[Angel] 60s hardcap — no completion, forcing deactivate + return.");
                    cs.setCombatStep(7);
                    break;
                }

                log(clientId, "COMBAT[Angel] waiting for mission complete (" + elapsed + "s)...");
                break;
            }
            case 7: {
                log(clientId, "COMBAT[Angel] Step 7: press 'xc' (deactivate modules).");
                missionProcessor.keyPress("xc");
                // Re-detect: dock = same system, set_destination = different system
                String result = missionProcessor.detectCombatComplete();
                if ("dock".equals(result)) {
                    log(clientId, "COMBAT[Angel] dock detected — same system, docking.");
                    missionProcessor.clickTemplate("dock");
                    cs.setDockingToStation(true);
                    cs.setTravelPhase(ClientState.TravelPhase.NONE);
                    cs.setWarpStartTime(LocalDateTime.now());
                    stateManager.setState(clientId, ClientState.State.WARPING);
                } else {
                    stateManager.setState(clientId, ClientState.State.RETURNING);
                }
                break;
            }
            default:
                log(clientId, "COMBAT[Angel] unexpected step " + step + " — resetting.");
                cs.setCombatStep(0);
        }
    }

    /**
     * Worm / Guristas — combat sequence.
     *
     * Steps:
     *   0 → Shift+asdx (overload modules + web)
     *   1 → double-click target_worm (approach + lock)
     *   2 → press 's' (activate web)
     *   3 → WAIT 9s
     *   4 → locked_worm present? skip lock : Ctrl+click target_worm (lock target)
     *   5 → press 'dxac' (modules, except 's')
     *   6 → press 's' (separate step, after everything else) + Shift+d (stop overloading web)
     *   7 → WAIT 4s → right-click target_worm + click range (keep at range)
     *   8 → Proceed (keep_at_range template check removed) → wait 7s
     *   9 → WAIT 7s → Alt+F1 (cap module ON) + switch client
     *  10 → poll completion (Sansha-style): locked_worm gone / set_destination/dock
     *        → step 11. If fight drags > 50s → Alt+F1 again (extra pulse, once).
     *        180s hardcap (pod check) → step 11. Never exit while locked_worm is up.
     *  11 → Alt+F1 (cap module OFF) + handle dock/returning
     */
    private void handleCombatWorm(String clientId, ClientState cs) {
        int step = cs.getCombatStep();

        switch (step) {
            case 0: {
                log(clientId, "COMBAT[Worm] Step 0: Shift+asdx (overload modules + web).");
                missionProcessor.shiftKeyPress("asdx");
                cs.setCombatStep(1);
                break;
            }
            case 1: {
                log(clientId, "COMBAT[Worm] Step 1: double-click target_worm (approach + lock).");
                missionProcessor.doubleClickTemplate("target_worm");
                cs.setCombatStep(2);
                break;
            }
            case 2: {
                log(clientId, "COMBAT[Worm] Step 2: press 's' (activate web).");
                missionProcessor.keyPress("s");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(3);
                break;
            }
            case 3: {
                // WAIT 13 seconds
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 13) {
                    log(clientId, "COMBAT[Worm] wait: " + elapsed + "s / 13s.");
                    return;
                }
                log(clientId, "COMBAT[Worm] 13s wait complete.");
                cs.setCombatStep(4);
                break;
            }
            case 4: {
                if (missionProcessor.findTemplate("locked_worm") != null) {
                    log(clientId, "COMBAT[Worm] Step 4: locked_worm already present (auto-locked) — skipping ctrl+click.");
                } else {
                    log(clientId, "COMBAT[Worm] Step 4: Ctrl+click target_worm (lock target).");
                    missionProcessor.ctrlClickTemplate("target_worm");
                }
                cs.setCombatStep(5);
                break;
            }
            case 5: {
                log(clientId, "COMBAT[Worm] Step 5: press 'dxac' (modules, except 's').");
                missionProcessor.keyPress("dxac");
                cs.setCombatStep(6);
                break;
            }
            case 6: {
                // Press 's' on its own, after every other module — separate step so it
                // isn't bundled with 'dxac' (avoids the modules getting confused).
                // Then Shift+d to stop overloading the web now that it's active.
                log(clientId, "COMBAT[Worm] Step 6: press 's' (separate), Shift+d (stop overloading web).");
                missionProcessor.keyPress("s");
                missionProcessor.shiftKeyPress("d");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(7);
                break;
            }
            case 7: {
                // WAIT 4s before keeping at range
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 4) {
                    log(clientId, "COMBAT[Worm] wait before range: " + elapsed + "s / 4s.");
                    return;
                }
                log(clientId, "COMBAT[Worm] Step 7: right-click target_worm → range.");
                missionProcessor.rightClickTemplate("target_worm");
                missionProcessor.clickTemplate("range");
                cs.setCombatStep(8);
                break;
            }
            case 8: {
                // Range settle (keep_at_range template check removed) — wait 7s before deactivate.
                log(clientId, "COMBAT[Worm] Step 8: waiting 7s before deactivate.");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(9);
                break;
            }
            case 9: {
                // WAIT 7s, then Alt+F1 once (cap module ON). The worm dies slowly, so
                // there's nothing to babysit here — hand the foreground to the other
                // client. The force-switch watchdog brings us back periodically to check
                // for completion (and the 80s re-pulse in step 10).
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 7) return;
                log(clientId, "COMBAT[Worm] Step 9: Alt+F1 (cap module ON).");
                missionProcessor.altKeyPress("f1");
                cs.setCombatStartTime(LocalDateTime.now()); // fight start, for the 80s extra-pulse check
                cs.setExtraCapPulseDone(false);
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(10);
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "COMBAT[Worm] cap ON — worm won't die fast, switching to: " + other.getClientId());
                    bringToForeground(other);
                }
                break;
            }
            case 10: {
                // Sansha-style completion poll. Primary signal: the locked_worm bracket
                // gone (target destroyed). Also accept set_destination/dock. Crucially,
                // do NOT exit while locked_worm is still visible — the fight can run long.
                String completed = missionProcessor.detectCombatComplete();
                if (completed != null) {
                    log(clientId, "COMBAT[Worm] " + completed + " detected — finishing up.");
                    cs.setCombatStep(11);
                    break;
                }

                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();

                // Primary completion: locked_worm gone (guarded a few seconds so a
                // transient miss right after entering the step can't fire prematurely).
                if (elapsed >= 5 && missionProcessor.findTemplate("locked_worm") == null) {
                    log(clientId, "COMBAT[Worm] locked_worm gone — target destroyed, finishing up.");
                    cs.setCombatStep(11);
                    break;
                }

                // 180s hardcap: no completion signal seen — pod check, then finish up.
                if (elapsed >= 180) {
                    if (missionProcessor.isFlyingPod()) {
                        handlePodDeath(clientId, true);
                        return;
                    }
                    log(clientId, "COMBAT[Worm] 180s hardcap — no completion, finishing up.");
                    cs.setCombatStep(11);
                    break;
                }

                // Long fight: past 80s the cap module's charges are spent and it has
                // reloaded (sitting idle), so a single Alt+F1 switches it back on for
                // another batch. Once only — if combat were over we'd have left above,
                // so re-pulsing here can't start a stray cycle on a dead grid.
                if (!cs.isExtraCapPulseDone()
                        && Duration.between(cs.getCombatStartTime(), LocalDateTime.now()).getSeconds() > 80) {
                    log(clientId, "COMBAT[Worm] fight > 80s — Alt+F1 again (cap module back ON).");
                    missionProcessor.altKeyPress("f1");
                    cs.setExtraCapPulseDone(true);
                }

                // Switched once already in step 9 — now stay on this client and watch the
                // fight to the end (no more switching away) so we can turn the cap module
                // off at the right moment in step 11.
                log(clientId, "COMBAT[Worm] watching for mission complete (" + elapsed + "s)...");
                break;
            }
            case 11: {
                // Turn the cap module OFF. Quick fight (<50s): charges weren't all spent,
                // module is still cycling — shut it down to conserve. Slow fight: we
                // boosted it back ON at >80s, so it's cycling again — same Alt+F1 off.
                log(clientId, "COMBAT[Worm] Step 11: Alt+F1 (cap module OFF).");
                missionProcessor.altKeyPress("f1");
                // Re-detect: dock = same system, set_destination = different system.
                String result = missionProcessor.detectCombatComplete();
                if ("dock".equals(result)) {
                    log(clientId, "COMBAT[Worm] dock detected — same system, docking.");
                    missionProcessor.clickTemplate("dock");
                    cs.setDockingToStation(true);
                    cs.setTravelPhase(ClientState.TravelPhase.NONE);
                    cs.setWarpStartTime(LocalDateTime.now());
                    stateManager.setState(clientId, ClientState.State.WARPING);
                } else {
                    stateManager.setState(clientId, ClientState.State.RETURNING);
                }
                break;
            }
            default:
                log(clientId, "COMBAT[Worm] unexpected step " + step + " — resetting.");
                cs.setCombatStep(0);
        }
    }

    // -----------------------------------------------------------------------
    // RETURNING — one-shot setup: set destination home.
    //             Navigation (stargates / dock) is handled by IDLE + WARPING cycling.
    // -----------------------------------------------------------------------

    private void handleReturning(String clientId, ClientWindowInfo window) {
        missionProcessor.clickSetDestination(280, 568); // returning/combat position
        stateManager.getState(clientId).setVerifySetDest(true); // re-check after first jump
        stateManager.getState(clientId).setTravelPhase(ClientState.TravelPhase.RETURNING_HOME);
        stateManager.setState(clientId, ClientState.State.IDLE);
    }

    // -----------------------------------------------------------------------
    // COMPLETING — turn in mission, collect rewards, return to IDLE
    // -----------------------------------------------------------------------

    private void handleCompleting(String clientId) {
        if (missionProcessor.detectCompletingUI() == null) {
            // Stuck waiting for the agent icon = we may never have docked. After 60s,
            // if the dock button is visible, click it (twice, like warp-to-location) to
            // dock. Throttled to once per 60s so it doesn't spam while warping to dock.
            ClientState cs = stateManager.getState(clientId);
            long waiting   = Duration.between(cs.getLastStateChange(), LocalDateTime.now()).getSeconds();
            long sinceDock = Duration.between(cs.getLastCompletingDock(), LocalDateTime.now()).getSeconds();
            if (waiting >= 60 && sinceDock >= 60 && missionProcessor.isDockVisible()) {
                log(clientId, "COMPLETING: 60s without agent icon — dock visible, clicking dock (twice) in case we never docked.");
                missionProcessor.clickDockTwice();
                cs.setLastCompletingDock(LocalDateTime.now());
                return;
            }
            log(clientId, "COMPLETING: agent icon not visible yet — waiting.");
            try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return;
        }
        missionProcessor.doubleClickAgentIcon();

        // Read agent standings before completing — determines fleet reward choice
        missionProcessor.clickAgentStandings();
        double standings = missionProcessor.readAgentStandings();

        // Track the standing for mission gating (recon 1 / scarlet / pot-and-kettle are
        // accepted only when standing < 1). Only store a successful read — never clobber a
        // known value with a NaN OCR miss, so the gate keeps using the last good reading.
        if (!Double.isNaN(standings)) {
            stateManager.getState(clientId).setLastKnownStanding(standings);
            log(clientId, "COMPLETING: tracked agent standing updated for mission gating (gate: < 1).");
        }

        missionProcessor.doubleClickAgentIcon(); // refocus agent dialog (standings covers complete_mission)
        missionProcessor.clickCompleteMission();

        // Fleet reward decision based on agent standings.
        // Below 0: take all rewards (fleet_no) to recover standings; healthy: share (fleet_yes).
        // Exact standings are not logged — OCR misreads the value; only a low-standings warning is kept.
        if (Double.isNaN(standings)) {
            log(clientId, "COMPLETING: standings OCR failed — defaulting to fleet YES.");
            missionProcessor.clickFleetYes();
        } else if (standings < -1.0) {
            log(clientId, "COMPLETING: standings critical — fleet NO.");
            if (standings < -1.5) {
                EventLog.log("STANDINGS_LOW", clientId + " — low standings warning");
            }
            missionProcessor.clickFleetNo();
        } else if (standings < 0.0) {
            log(clientId, "COMPLETING: standings low — fleet NO.");
            missionProcessor.clickFleetNo();
        } else {
            log(clientId, "COMPLETING: standings healthy — fleet YES.");
            missionProcessor.clickFleetYes();
        }
        missionProcessor.waitAndClickOk(); // dismiss fleet reward confirmation dialog
        stateManager.getState(clientId).setTravelPhase(ClientState.TravelPhase.NONE);

        // Mission turned in — record reward for the end-of-session summary.
        Mission done = stateManager.getState(clientId).getCurrentMission();
        String missionTitle = (done != null && done.getTitle() != null) ? done.getTitle() : "mission";
        int sessionTotal = SessionStats.recordMission();
        EventLog.log("MISSION_COMPLETE", String.format(
                "%s — %s — +%,d ISK, +%,d LP (~%,d ISK value) [session: %d, ~%,d ISK value]",
                clientId, missionTitle,
                SessionStats.ISK_PER_MISSION, SessionStats.LP_PER_MISSION, SessionStats.ISK_VALUE_PER_MISSION,
                sessionTotal, (long) sessionTotal * SessionStats.ISK_VALUE_PER_MISSION));

        // Request next mission immediately and go to CHECKING_MISSIONS
        // instead of IDLE — avoids stale complete_mission being misdetected.
        missionProcessor.clickRequestMission();

        // Switch to other client after requesting — gives the dialog time to load
        ClientWindowInfo other = findOtherClient(clientId);
        if (other != null) {
            log(clientId, "COMPLETING: mission turned in — switching focus to " + other.getClientId());
            bringToForeground(other);
        }
        stateManager.getState(clientId).setCurrentMission(null);
        stateManager.getState(clientId).setMissionCheckStartTime(LocalDateTime.MIN);
        stateManager.setState(clientId, ClientState.State.CHECKING_MISSIONS);
    }

    // -----------------------------------------------------------------------
    // UNDOCKING — wait for undock animation, verify we left the hangar.
    // Runs only when this client is in focus (via processClient).
    // -----------------------------------------------------------------------

    private void handleUndocking(String clientId, ClientWindowInfo window) {
        long elapsed = Duration.between(
                stateManager.getState(clientId).getUndockStartTime(), LocalDateTime.now()
        ).getSeconds();
        if (elapsed < UNDOCK_WAIT_SECS) {
            return; // still waiting for undock animation
        }
        stateManager.setState(clientId, ClientState.State.TRAVEL);
    }

    // -----------------------------------------------------------------------
    // WARPING — poll warp_active until it disappears, then return to IDLE.
    //
    // Timeline:
    //   0–7s   : grace period — warp_active may not have appeared yet, skip check.
    //   7s+    : check warp_active every 5s; when gone → IDLE.
    // -----------------------------------------------------------------------

    private void handleWarping(String clientId, ClientWindowInfo window) {
        ClientState cs = stateManager.getState(clientId);
        long elapsed = Duration.between(cs.getWarpStartTime(), LocalDateTime.now()).getSeconds();

        boolean firstCheck = cs.getLastWarpCheck().isBefore(LocalDateTime.MIN.plusSeconds(1));

        // --- Infested escape jam check ---
        // For the Infested return warp, the rats can jam you as you initiate the warp.
        // Watch for 'jamed' over the first 3s instead of the normal bubble/switch; if
        // jammed, burn away (double-click stargate + 's') and retry the jump.
        if (cs.isJamCheckOnWarp()) {
            if (missionProcessor.findTemplate("jamed") != null) {
                log(clientId, "WARPING[INFESTED]: JAMED — burn away (double-click stargate + 's') + retry jump.");
                missionProcessor.doubleClickTemplate("stargate");
                missionProcessor.keyPress("s");
                try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                new Thread(() -> missionProcessor.rightClickAndSelect("stargate", "jump")).start();
                cs.setWarpStartTime(LocalDateTime.now()); // restart warp + jam window
                return;
            }
            if (elapsed < 3) {
                return; // keep watching for a jam during the 3s window
            }
            cs.setJamCheckOnWarp(false);
            log(clientId, "WARPING[INFESTED]: no jam after 3s — resuming normal warp.");
        }

        // Wait 4s for the warp/jump action to complete before checking for bubble.
        if (elapsed < 4) {
            return;
        }

        // --- Bubble escape loop (step-based, survives across ticks) ---
        int bubbleStep = cs.getBubbleEscapeStep();
        if (bubbleStep > 0) {
            switch (bubbleStep) {
                case 1: {
                    // Step 1: shift+s (stop), double-click stargate, press s (approach).
                    log(clientId, "WARPING[BUBBLE] step 1: shift+s, double-click stargate, press s.");
                    missionProcessor.shiftKeyPress("s");
                    missionProcessor.doubleClickTemplate("stargate");
                    try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    missionProcessor.keyPress("s");
                    cs.setBubbleEscapeStart(LocalDateTime.now());
                    cs.setBubbleEscapeStep(2);
                    return;
                }
                case 2: {
                    // Step 2: wait 6s
                    long bubbleElapsed = Duration.between(cs.getBubbleEscapeStart(), LocalDateTime.now()).getSeconds();
                    if (bubbleElapsed < 6) {
                        log(clientId, "WARPING[BUBBLE] step 2: waiting (" + bubbleElapsed + "s / 6s).");
                        return;
                    }
                    // Check for pod death while in bubble. No agent_icon (respawned-
                    // at-station) check here — if podded we sit through the pod
                    // animation anyway, so it can't have respawned mid-warp.
                    if (missionProcessor.isFlyingPod()) {
                        log(clientId, "WARPING[BUBBLE]: capsule detected — pod death.");
                        handlePodDeath(clientId, true, true); // bubble-related → eligible for cooldown
                        return;
                    }
                    log(clientId, "WARPING[BUBBLE] step 2: 6s wait complete — attempting jump.");
                    cs.setBubbleEscapeStep(3);
                    break;
                }
                case 3: {
                    // Step 3: right-click stargate → jump, then check bubble again
                    new Thread(() -> missionProcessor.rightClickAndSelect("stargate", "jump")).start();
                    cs.setBubbleEscapeStart(LocalDateTime.now());
                    cs.setBubbleEscapeStep(4);
                    return;
                }
                case 4: {
                    // Step 4: wait 4s then check if still in bubble
                    long jumpElapsed = Duration.between(cs.getBubbleEscapeStart(), LocalDateTime.now()).getSeconds();
                    if (jumpElapsed < 4) {
                        return;
                    }
                    if (missionProcessor.findTemplate("bubble_text") != null) {
                        log(clientId, "WARPING[BUBBLE]: still in bubble — repeating escape.");
                        cs.setBubbleEscapeStep(1);
                    } else {
                        log(clientId, "WARPING[BUBBLE]: bubble gone — resuming normal warp check.");
                        cs.setBubbleEscapeStep(0);
                        cs.setLastWarpCheck(LocalDateTime.now());
                    }
                    return;
                }
            }
            return;
        }

        if (firstCheck) {
            cs.setLastWarpCheck(LocalDateTime.now());
            // Warp-entry pod check — no longer run on every warp. While missions are
            // cycling normally the ship is obviously alive, so we only probe once 5min
            // pass without entering combat or checking missions (i.e. something is
            // stuck — possibly podded and looping warps). The check itself is a quick
            // passive sliver match that escalates to the eject-menu probe only if the
            // sliver is missing.
            long sinceActive = Duration.between(cs.getLastCombatOrCheck(), LocalDateTime.now()).toMinutes();
            if (sinceActive >= 5) {
                log(clientId, "WARPING: " + sinceActive + "m without combat/mission-check — running warp-entry pod check.");
                if (missionProcessor.isFlyingPod()) {
                    log(clientId, "WARPING: no_ship on warp entry — podded, self-destructing.");
                    handlePodDeath(clientId, true);
                    return;
                }
            }
            // Check for warp disruption bubble before assuming warp will start.
            if (missionProcessor.findTemplate("bubble_text") != null) {
                // First warp out of the home station (no gate jumps yet) → redock instead of escaping.
                if (cs.getTravelPhase() == ClientState.TravelPhase.TO_MISSION && cs.getJumpCount() == 0) {
                    log(clientId, "WARPING: bubble detected on first warp out of station — emergency redock!");
                    missionProcessor.emergencyRedock();
                    LocalDateTime cooldownEnd = LocalDateTime.now().plusMinutes(POD_COOLDOWN_MINUTES);
                    stateManager.setPodCooldownEnd(cooldownEnd);
                    log(clientId, "BUBBLE_CAMP: set " + POD_COOLDOWN_MINUTES + "-minute global cooldown for both clients.");
                    stateManager.setState(clientId, ClientState.State.POD_COOLDOWN);
                    return;
                }
                log(clientId, "WARPING: bubble detected — starting escape sequence.");
                cs.setBubbleEscapeStep(1);
                return;
            }
            // No bubble — assume warp will activate, switch to other client.
            ClientWindowInfo other = findOtherClient(clientId);
            if (other != null) {
                log(clientId, "WARPING: no bubble — switching focus to: " + other.getClientId());
                bringToForeground(other);
            } else {
                log(clientId, "WARPING: no bubble — no other clients to switch to.");
            }
            return;
        }

        // Periodic 'general' overview reset (every 5m, throttled by lastPodCheck).
        runPeriodicOverviewReset(clientId, cs);

        // Poll every 2s for warp_active.
        long sinceLastCheck = Duration.between(cs.getLastWarpCheck(), LocalDateTime.now()).getSeconds();
        if (sinceLastCheck < 2) {
            return;
        }
        cs.setLastWarpCheck(LocalDateTime.now());

        if (!missionProcessor.isWarpActive()) {
            if (cs.isDockingToStation()) {
                log(clientId, "WARPING: dock warp complete after " + elapsed + "s — transitioning to COMPLETING.");
                cs.setDockingToStation(false);
                stateManager.setState(clientId, ClientState.State.COMPLETING);
                return;
            }
            log(clientId, "WARPING: warp_active gone after " + elapsed + "s — transitioning to IDLE.");
            stateManager.setState(clientId, ClientState.State.IDLE);
            cs.setJustExitedWarp(true);
            // Past 35s the warp has clearly already completed (and likely jumped) —
            // skip the focus switch / settle wait and let this client navigate now.
            if (elapsed > 35) {
                log(clientId, "WARPING: warp landed after " + elapsed + "s (>35s) — assuming already jumped, not switching focus.");
            } else {
                // Let the destination UI settle before this client picks a navigation
                // action — otherwise it can grab a stargate before warp_to_location has
                // rendered and jump a random gate. Prefer switching focus to the other
                // client (the time spent there IS the settle delay); justExitedWarp stays
                // set, so when focus returns the justLanded path runs and gives
                // warp_to_location / activate_gate priority over stargates. With a single
                // client there is nowhere to switch, so wait ~5s in place instead.
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "WARPING: warp landed — switching focus to " + other.getClientId()
                            + " to let the UI settle before navigating.");
                    bringToForeground(other);
                } else {
                    try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    handleIdle(clientId, window);
                }
            }
        } else {
            if (elapsed % 15 < 2) {
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "WARPING: " + elapsed + "s elapsed — switching focus to: " + other.getClientId());
                    bringToForeground(other);
                }
            }
            log(clientId, "still WARPING (" + elapsed + "s).");
        }
    }


    // -----------------------------------------------------------------------
    // Window focus switching
    // -----------------------------------------------------------------------

    /** Returns the first registered client that is not {@code excludeId}. */
    private ClientWindowInfo findOtherClient(String excludeId) {
        for (ClientWindowInfo info : clientWindows.values()) {
            if (!info.getClientId().equals(excludeId)) {
                return info;
            }
        }
        return null;
    }

    /**
     * Brings the given window to the foreground by clicking its EVE-O Preview
     * thumbnail. The template key is derived from the client ID:
     * "eve_Meeshayl_Lancer" → "meeshayl_lancer".
     */
    private void bringToForeground(ClientWindowInfo window) {
        String templateKey = window.getClientId()
                .replaceFirst("^eve_", "")
                .toLowerCase();
        //log("[ClientManager] Clicking EVE-O Preview  '" + templateKey + "'");
        missionProcessor.clickTemplateBelow(templateKey, THUMBNAIL_CLICK_OFFSET_Y);
        lastClientSwitch = LocalDateTime.now();
    }

    // -----------------------------------------------------------------------
    // Window focus detection
    // -----------------------------------------------------------------------

    /**
     * Returns the ClientWindowInfo whose title matches the current foreground window,
     * or null if no registered EVE client is in the foreground.
     * Title-based matching is used because HWND pointer comparison across JNA calls
     * is unreliable — the same window can produce different pointer values depending
     * on how the HWND was obtained.
     */
    private ClientWindowInfo getForegroundClient() {
        try {
            com.sun.jna.platform.win32.User32 user32 =
                    com.sun.jna.platform.win32.User32.INSTANCE;
            com.sun.jna.platform.win32.WinDef.HWND fg = user32.GetForegroundWindow();
            if (fg == null) return null;
            char[] titleBuf = new char[512];
            user32.GetWindowText(fg, titleBuf, titleBuf.length);
            String fgTitle = com.sun.jna.Native.toString(titleBuf);
            for (ClientWindowInfo info : clientWindows.values()) {
                if (fgTitle.equals(info.getTitle())) return info;
            }
        } catch (Exception e) {
            // JNA unavailable — no fallback, null means skip this tick
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Window discovery — real EVE client enumeration via JNA
    // -----------------------------------------------------------------------

    private void refreshClientWindows() {
        Set<String> seenIds = new HashSet<>();
        try {
            com.sun.jna.platform.win32.User32 user32 =
                    com.sun.jna.platform.win32.User32.INSTANCE;

            user32.EnumWindows((hWnd, data) -> {
                // Skip minimized-to-taskbar windows; do NOT skip by IsWindowVisible —
                // background EVE clients in a stacked setup may report not-visible.
                int style = user32.GetWindowLong(hWnd, com.sun.jna.platform.win32.WinUser.GWL_STYLE);
                if ((style & com.sun.jna.platform.win32.WinUser.WS_MINIMIZE) != 0) return true;

                char[] titleBuf = new char[512];
                user32.GetWindowText(hWnd, titleBuf, titleBuf.length);
                String windowTitle = com.sun.jna.Native.toString(titleBuf);

                if (windowTitle.startsWith("EVE - ")) {
                    com.sun.jna.platform.win32.WinDef.RECT rect =
                            new com.sun.jna.platform.win32.WinDef.RECT();
                    user32.GetWindowRect(hWnd, rect);

                    int x = rect.left;
                    int y = rect.top;
                    int w = rect.right - rect.left;
                    int h = rect.bottom - rect.top;

                    long hwnd = com.sun.jna.Pointer.nativeValue(hWnd.getPointer());
                    // Build a temporary info to get the title-based ID
                    ClientWindowInfo info = new ClientWindowInfo(windowTitle, "ExeFile", hwnd, x, y, w, h);
                    String id = info.getClientId(); // title-based, e.g. "eve_Gravitar"
                    seenIds.add(id);

                    if (!clientWindows.containsKey(id)) {
                        clientWindows.put(id, info);
                        log("[ClientManager] EVE client discovered: '" + windowTitle
                                + "' id=" + id + " at " + x + "," + y + " " + w + "x" + h);
                    } else {
                        clientWindows.get(id).updateBounds(x, y, w, h);
                    }
                }
                return true; // continue enumeration
            }, null);

            // Remove any clients that were not seen this pass (closed windows, stale stubs)
            clientWindows.keySet().removeIf(id -> !seenIds.contains(id));

            if (clientWindows.isEmpty()) {
                log("[ClientManager] No EVE windows found.");
            }

        } catch (Exception e) {
            // JNA unavailable or enumeration failed — use stub for single-client testing
            if (clientWindows.isEmpty()) {
                Rectangle defaultArea = clientMissionAreas.getOrDefault("default",
                        new Rectangle(440, 92, 988, 336));
                ClientWindowInfo stub = new ClientWindowInfo(
                        "EVE - Character Name", "ExeFile", 0L,
                        defaultArea.x, defaultArea.y,
                        defaultArea.width, defaultArea.height
                );
                clientWindows.put(stub.getClientId(), stub);
                log("[ClientManager] JNA unavailable — registered stub client: "
                        + stub.getClientId());
            }
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    public Rectangle getMissionAreaForClient(String clientId) {
        return clientMissionAreas.getOrDefault(clientId,
                clientMissionAreas.get("default"));
    }

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private void log(String clientId, String msg) {
        System.out.printf("[%s] [%s] %s%n", LocalDateTime.now().format(TS), clientId, msg);
    }

    private void log(String msg) {
        System.out.printf("[%s] %s%n", LocalDateTime.now().format(TS), msg);
    }
}