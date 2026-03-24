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

    /** Prevents concurrent monitorClients executions when the thread pool queues up ticks. */
    private final AtomicBoolean monitorRunning = new AtomicBoolean(false);

    private static final int  MAX_AGENT_RETRIES        = 2;
    private static final long UNDOCK_WAIT_SECS          = 13;
    /** Pixels below the EVE-O Preview thumbnail template centre to click (skips the title bar). */
    private static final int  THUMBNAIL_CLICK_OFFSET_Y = 75;

    public ClientManager(StateManager stateManager) {
        this.stateManager     = stateManager;
        this.missionProcessor = new MissionProcessor();
        this.missionConfig    = new MissionConfig();
        this.gson             = new Gson();
        loadCalibratedCoordinates();
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

            checkUndockTimers();
            ClientWindowInfo fg = getForegroundClient();
            ClientWindowInfo graceClient = findWarpingClientInGracePeriod();

            if (graceClient != null) {
                // Grace period takes priority — ensure this client stays focused.
                if (fg == null || !fg.getClientId().equals(graceClient.getClientId())) {
                    bringToForeground(graceClient);
                }
            } else if (fg == null || stateManager.getStateEnum(fg.getClientId()) != ClientState.State.IDLE) {
                //TODO: what to do here, loop other clients?  I think not
            }

            if (fg == null) {
                log("[ClientManager] No EVE client in foreground — skipping tick.");
                return;
            }
            processClient(fg.getClientId(), fg);
        } finally {
            monitorRunning.set(false);
        }
    }

    // -----------------------------------------------------------------------
    // Per-client state machine
    // -----------------------------------------------------------------------

    private void processClient(String clientId, ClientWindowInfo window) {
        ClientState.State state = stateManager.getStateEnum(clientId);

        switch (state) {
            case IDLE:              handleIdle(clientId, window);             break;
            case CHECKING_MISSIONS: handleCheckingMissions(clientId, window); break;
            case DECLINING:         handleDeclining(clientId, window);        break;
            case ACCEPTED:          handleAccepted(clientId, window);         break;
            case REPAIRING:         handleRepairing(clientId, window);        break;
            case UNDOCKING: /* timer-driven — handled by checkUndockTimers() */  break;
            case TRAVEL:    handleTravel(clientId, window);                      break;
            case WARPING:   handleWarping(clientId, window);                     break;
            case IN_COMBAT: handleInCombat(clientId, window);  break;
            case RETURNING: handleReturning(clientId, window); break;
            case COMPLETING:
                handleCompleting(clientId);
                break;
            default:
                System.out.println("[" + clientId + "] Unknown state: " + state);
        }
    }

    // -----------------------------------------------------------------------
    // IDLE
    // -----------------------------------------------------------------------

    private void handleIdle(String clientId, ClientWindowInfo window) {
        // 2. Phase-based navigation — handles transit after each warp lands.
        ClientState cs = stateManager.getState(clientId);

        if (cs.getTravelPhase() == ClientState.TravelPhase.TO_MISSION) {
            boolean justLanded = cs.isJustExitedWarp();
            if (justLanded) {
                cs.setJustExitedWarp(false);
                long warpSecs = Duration.between(cs.getWarpStartTime(), LocalDateTime.now()).getSeconds();

                // Just exited warp — check mission-specific elements first (no stargate,
                // it's always visible in-system and would false-match during a gate tunnel).
                String found = missionProcessor.detectMissionArrivalUI();
                if (found != null) {
                    switch (found) {
                        case "activate_gate":
                            log(clientId, "IDLE[TO_MISSION]: activate_gate — right-click → mission_start, entering combat.");
                            cs.setWarpStartTime(LocalDateTime.now());
                            cs.setLastWarpCheck(LocalDateTime.MIN);
                            stateManager.setState(clientId, ClientState.State.IN_COMBAT);
                            new Thread(() -> missionProcessor.rightClickAndSelect("activate_gate", "mission_start")).start();
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

                // Neither activate_gate nor warp_to_location visible.
                // Short warp (<30s) = likely mid-jump through a gate tunnel — wait 8s.
                // Long warp (≥30s) = already jumped, skip wait and check stargate immediately.
                if (warpSecs < 30) {
                    log(clientId, "IDLE[TO_MISSION]: no mission-specific element — waiting 8s (possible jump transition).");
                    // TODO: bubble detection — ship may be caught in an interdictor bubble; needs separate handling
                    try { Thread.sleep(8000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                } else {
                    log(clientId, "IDLE[TO_MISSION]: no mission-specific element after " + warpSecs + "s warp — checking stargate.");
                }
            }

            // Normal tick (or post-8s-wait): full travel UI check including stargate.
            String found = missionProcessor.detectTravelUI();
            if (found == null) {
                log(clientId, "IDLE[TO_MISSION]: no navigation element visible — waiting.");
                return;
            }
            switch (found) {
                case "activate_gate":
                    log(clientId, "IDLE[TO_MISSION]: activate_gate — right-click → mission_start, entering combat.");
                    cs.setWarpStartTime(LocalDateTime.now());
                    cs.setLastWarpCheck(LocalDateTime.MIN);
                    stateManager.setState(clientId, ClientState.State.IN_COMBAT);
                    new Thread(() -> missionProcessor.rightClickAndSelect("activate_gate", "mission_start")).start();
                    break;
                case "warp_to_location":
                    log(clientId, "IDLE[TO_MISSION]: warp_to_location — warping into mission space.");
                    cs.setWarpStartTime(LocalDateTime.now());
                    stateManager.setState(clientId, ClientState.State.WARPING);
                    new Thread(() -> missionProcessor.clickWarpToLocation()).start();
                    break;
                case "stargate":
                    log(clientId, "IDLE[TO_MISSION]: stargate — right-click → jump.");
                    cs.setWarpStartTime(LocalDateTime.now());
                    stateManager.setState(clientId, ClientState.State.WARPING);
                    new Thread(() -> missionProcessor.rightClickAndSelect("stargate", "jump")).start();
                    break;
                    //TODO: case jumping! check if invisible on next gate or jumping, dont just click on some stargate
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

                // Just exited warp — check dock first (no stargate yet, same false-match risk).
                String dockCheck = missionProcessor.detectDockOnly();
                if (dockCheck != null) {
                    if (missionProcessor.isEnemyInLocal()) {
                        log(clientId, "IDLE[RETURNING_HOME]: dock visible + ENEMY in local — insta-docking.");
                        missionProcessor.instaDock();
                    } else {
                        log(clientId, "IDLE[RETURNING_HOME]: dock visible — docking.");
                        missionProcessor.clickDock();
                    }
                    cs.setWarpStartTime(LocalDateTime.now());
                    stateManager.setState(clientId, ClientState.State.WARPING);
                    return;
                }

                // No dock visible — check if we landed in station (agent_icon means docking completed).
                String agentCheck = missionProcessor.detectCompletingUI();
                if (agentCheck != null) {
                    log(clientId, "IDLE[RETURNING_HOME]: agent_icon visible — docked in station.");
                    cs.setTravelPhase(ClientState.TravelPhase.NONE);
                    stateManager.setState(clientId, ClientState.State.COMPLETING);
                    return;
                }

                if (warpSecs < 35) {
                    log(clientId, "IDLE[RETURNING_HOME]: dock not visible — waiting 8s (possible jump transition).");
                    // TODO: bubble detection — ship may be caught in an interdictor bubble; needs separate handling
                    try { Thread.sleep(8000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                } else {
                    log(clientId, "IDLE[RETURNING_HOME]: dock not visible after " + warpSecs + "s warp — checking stargate.");
                }
            }

            // Normal tick (or post-8s-wait): full returning UI check including stargate.
            String found = missionProcessor.detectReturningUI();
            if (found == null) {
                // No nav element — check if we're already docked in station.
                String agentFallback = missionProcessor.detectCompletingUI();
                if (agentFallback != null) {
                    log(clientId, "IDLE[RETURNING_HOME]: agent_icon visible — already docked.");
                    cs.setTravelPhase(ClientState.TravelPhase.NONE);
                    stateManager.setState(clientId, ClientState.State.COMPLETING);
                    return;
                }
                log(clientId, "IDLE[RETURNING_HOME]: no navigation element visible — waiting.");
                return;
            }
            switch (found) {
                case "dock":
                    if (missionProcessor.isEnemyInLocal()) {
                        log(clientId, "IDLE[RETURNING_HOME]: dock visible + ENEMY in local — insta-docking.");
                        missionProcessor.instaDock();
                    } else {
                        log(clientId, "IDLE[RETURNING_HOME]: dock visible — docking.");
                        missionProcessor.clickDock();
                    }
                    cs.setWarpStartTime(LocalDateTime.now());
                    stateManager.setState(clientId, ClientState.State.WARPING);
                    break;
                case "stargate":
                    log(clientId, "stargate — right-click → jump.");
                    cs.setWarpStartTime(LocalDateTime.now());
                    stateManager.setState(clientId, ClientState.State.WARPING);
                    new Thread(() -> missionProcessor.rightClickAndSelect("stargate", "jump")).start();
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
                log(clientId, "Mission accepted but NOT complete — reading mission via OCR before ship selection.");
                Rectangle missionArea = getMissionAreaForClient(clientId);
                Mission mission = missionProcessor.readMission(missionArea);
                stateManager.getState(clientId).setCurrentMission(mission);
                stateManager.setState(clientId, ClientState.State.ACCEPTED);
                break;
            }
            default: { // OFFER
                agentRetries.put(clientId, 0);
                log(clientId, "Mission offer dialog confirmed — reading via OCR.");
                Rectangle missionArea = getMissionAreaForClient(clientId);
                Mission mission = missionProcessor.readMission(missionArea);
                stateManager.getState(clientId).setCurrentMission(mission);
                stateManager.setState(clientId, ClientState.State.CHECKING_MISSIONS);
                break;
            }
        }
    }

    // -----------------------------------------------------------------------
    // CHECKING_MISSIONS
    // -----------------------------------------------------------------------

    private void handleCheckingMissions(String clientId, ClientWindowInfo window) {
        ClientState cs = stateManager.getState(clientId);

        if (cs.getMissionCheckStartTime().equals(LocalDateTime.MIN)) {
            cs.setMissionCheckStartTime(LocalDateTime.now());
        }
        long cycleElapsed = Duration.between(cs.getMissionCheckStartTime(), LocalDateTime.now()).getSeconds();
        if (cycleElapsed >= 35) {
            ClientWindowInfo other = findOtherClient(clientId);
            if (other != null) {
                log(clientId, "CHECKING_MISSIONS: 35s in check/decline cycle — returning to IDLE and switching focus to " + other.getClientId());
                cs.setCurrentMission(null);
                stateManager.setState(clientId, ClientState.State.IDLE);
                bringToForeground(other);
                return;
            }
        }

        Mission mission = cs.getCurrentMission();

        if (mission == null) {
            Rectangle missionArea = getMissionAreaForClient(clientId);
            mission = missionProcessor.readMission(missionArea);
            stateManager.getState(clientId).setCurrentMission(mission);
        }

        if (mission == null) {
            // OCR failed — if dialog is still visible keep retrying OCR (stay in
            // CHECKING_MISSIONS). Only fall back to IDLE if dialog is gone.
            if (missionProcessor.isMissionDialogVisible()) {
                log(clientId, "OCR returned null but dialog still visible — retrying OCR next tick.");
                return;
            }
            log(clientId, "No mission data and dialog gone — returning to IDLE.");
            stateManager.setState(clientId, ClientState.State.IDLE);
            return;
        }

        agentRetries.put(clientId, 0);
        MissionConfig.Decision decision = missionConfig.evaluate(mission);

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
            log(clientId, "Decline still visible — clicking decline to confirm.");
            missionProcessor.clickDeclineButton();
            return;
        }

        log(clientId, "Dialog closed — requesting new mission.");
        missionProcessor.clickRequestMission();

        if (!missionProcessor.waitForNewOffer()) {
            log(clientId, "New offer not visible yet — will retry next tick.");
            return;
        }

        stateManager.getState(clientId).setCurrentMission(null);
        Rectangle missionArea = getMissionAreaForClient(clientId);
        Mission newMission = missionProcessor.readMission(missionArea);
        String title = (newMission != null && newMission.getTitle() != null)
                ? newMission.getTitle() : "Unknown";
        log(clientId, "New offer: '" + title + "' — evaluating.");
        stateManager.getState(clientId).setCurrentMission(newMission);
        stateManager.setState(clientId, ClientState.State.CHECKING_MISSIONS);
    }

    // -----------------------------------------------------------------------
    // ACCEPTED — Track only, dialog stays open for ship template matching
    // -----------------------------------------------------------------------

    private void handleAccepted(String clientId, ClientWindowInfo window) {
        if (!stateManager.isTrackAttempted()) {
            log(clientId, "First accept — clicking Track.");
            missionProcessor.clickTrack();
            stateManager.setTrackAttempted(true);
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

        boolean success = missionProcessor.performUndockStart(mission);

        if (success) {
            stateManager.getState(clientId).setUndockStartTime(LocalDateTime.now());
            stateManager.setState(clientId, ClientState.State.UNDOCKING);
            // Safe-switch: use undock animation time on the other client
            ClientWindowInfo other = findOtherClient(clientId);
            if (other != null) {
                log(clientId, "UNDOCKING: switching to " + other.getClientId() + " during 15s animation.");
                bringToForeground(other);
            }
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
        missionProcessor.closeAllDialogs();
        missionProcessor.pressCtrlR();
        missionProcessor.closeAllDialogs();
        missionProcessor.clickSetDestination();
        stateManager.getState(clientId).setTravelPhase(ClientState.TravelPhase.TO_MISSION);
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

        // ── Warp verification phase (no client switching) ──
        // After clicking activate_gate → mission_start, verify warp started then wait for it to land.
        if (!cs.isCombatWarpLanded()) {
            long elapsed = Duration.between(cs.getWarpStartTime(), LocalDateTime.now()).getSeconds();

            // 0–7s: grace period — right-click+click takes ~3s, then warp needs time to begin.
            if (elapsed < 7) {
                if (cs.getLastWarpCheck().equals(LocalDateTime.MIN)) {
                    log(clientId, "IN_COMBAT: waiting for gate warp to begin.");
                    cs.setLastWarpCheck(LocalDateTime.MIN.plusNanos(1)); // grace-logged marker
                }
                return;
            }

            // 3s+: poll warp_active — when it disappears, warp has landed.
            boolean firstCheck = cs.getLastWarpCheck().isBefore(LocalDateTime.MIN.plusSeconds(1));
            cs.setLastWarpCheck(LocalDateTime.now());

            if (missionProcessor.isWarpActive()) {
                if (firstCheck) {
                    log(clientId, "IN_COMBAT: gate warp confirmed — waiting for landing.");
                }
                return;
            }

            // Warp not active (or was never seen) — proceed to combat.
            if (firstCheck) {
                log(clientId, "IN_COMBAT: no warp detected after " + elapsed + "s — starting combat.");
            } else {
                log(clientId, "IN_COMBAT: gate warp landed after " + elapsed + "s — starting combat.");
            }
            cs.setCombatWarpLanded(true);
            return;
        }

        Mission mission = cs.getCurrentMission();

        String resolvedTarget = mission != null
                ? (mission.getShipTarget() != null ? mission.getShipTarget() : mission.getTarget())
                : null;

        if (resolvedTarget == null) {
            log(clientId, "IN_COMBAT: no mission/shipTarget — falling back to set_destination polling."
                    + " (mission=" + mission + ")");
            if (missionProcessor.isSetDestinationVisible()) {
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
                handleCombatWorm(clientId, cs);
                break;
            default:
                // Unknown target — fall back to set_destination polling.
                if (missionProcessor.isSetDestinationVisible()) {
                    log(clientId, "IN_COMBAT: set_destination detected — mission complete.");
                    stateManager.setState(clientId, ClientState.State.RETURNING);
                }
                break;
        }
    }

    // -----------------------------------------------------------------------
    // Shared final-step logic for all combat sequences.
    // Polls set_destination; after 30s also checks for capsule (ship destroyed).
    // -----------------------------------------------------------------------

    private void pollSetDestinationOrCapsule(String clientId, ClientState cs,
                                              String combatLabel, boolean allowClientSwitch) {
        if (missionProcessor.isSetDestinationVisible()) {
            log(clientId, "COMBAT[" + combatLabel + "] set_destination detected — mission complete!");
            stateManager.setState(clientId, ClientState.State.RETURNING);
            return;
        }

        long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();

        // After 30s without set_destination, start checking for capsule (ship destroyed).
        if (elapsed >= 30 && missionProcessor.isCapsuleVisible()) {
            log(clientId, "COMBAT[" + combatLabel + "] *** CAPSULE DETECTED — ship destroyed! Self-destructing...");
            missionProcessor.clickDestruct();

            // Safe wait 120s — clone jump / respawn animation.
            log(clientId, "COMBAT[" + combatLabel + "] waiting 120s for self-destruct respawn...");
            try { Thread.sleep(120_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

            // Back in station — re-board ship and retry mission.
            log(clientId, "COMBAT[" + combatLabel + "] respawned — transitioning to REPAIRING to re-board ship.");
            stateManager.setState(clientId, ClientState.State.REPAIRING);
            return;
        }

        log(clientId, "COMBAT[" + combatLabel + "] waiting for mission complete (" + elapsed + "s)...");
        if (allowClientSwitch && elapsed > 0 && elapsed % 30 == 0) {
            ClientWindowInfo other = findOtherClient(clientId);
            if (other != null) bringToForeground(other);
        }
    }

    /**
     * Anomic Team — Jaguar combat sequence.
     *
     * Steps:
     *   0 → Right-click 'launcher' (open ammo context menu)
     *   1 → Click 'kin_damage' (load kinetic ammo)
     *   2 → Q+click 'target_jaguar' (orbit target)
     *   3 → Check 'orbiting' — if not found, repeat Q+click (loop back to 2)
     *   4 → Keypress 's' (activate module)
     *   5 → SAFE WAIT 90s (can switch to other clients)
     *   6 → Ctrl+click 'target_jaguar' (lock target)
     *   7 → Keypress 'adc' (activate modules)
     *   8 → SAFE WAIT for set_destination (mission complete → RETURNING)
     */
    private void handleCombatJaguar(String clientId, ClientState cs) {
        int step = cs.getCombatStep();

        switch (step) {
            case 0: {
                log(clientId, "COMBAT[Jaguar] Step 0: right-click launcher (ammo menu).");
                missionProcessor.rightClickTemplate("launcher");
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
                log(clientId, "COMBAT[Jaguar] Step 2: Q+click target_jaguar (orbit).");
                missionProcessor.qClickTemplate("target_jaguar");
                cs.setCombatStep(3);
                break;
            }
            case 3: {
                // Check keep_at_range — if not found, loop back to Q+click
                String found = missionProcessor.findTemplate("keep_at_range");
                if (found != null) {
                    log(clientId, "COMBAT[Jaguar] keep_at_range confirmed — proceeding.");
                    cs.setCombatStep(4);
                } else {
                    log(clientId, "COMBAT[Jaguar] keep_at_range NOT found — retrying Q+click.");
                    cs.setCombatStep(2);
                }
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
                // SAFE WAIT: 90 seconds
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 90) {
                    log(clientId, "COMBAT[Jaguar] safe wait: " + elapsed + "s / 90s.");
                    if (elapsed > 0 && elapsed % 30 == 0) {
                        ClientWindowInfo other = findOtherClient(clientId);
                        if (other != null) bringToForeground(other);
                    }
                    return;
                }
                log(clientId, "COMBAT[Jaguar] 90s wait complete — proceeding to lock target.");
                cs.setCombatStep(6);
                break;
            }
            case 6: {
                log(clientId, "COMBAT[Jaguar] Step 6: Ctrl+click target_jaguar (lock target).");
                missionProcessor.ctrlClickTemplate("target_jaguar");
                cs.setCombatStep(7);
                break;
            }
            case 7: {
                log(clientId, "COMBAT[Jaguar] Step 7: press 'adc' (activate modules).");
                missionProcessor.keyPress("adc");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(8);
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "COMBAT[Jaguar] waiting for mission complete — switching to: " + other.getClientId());
                    bringToForeground(other);
                }
                break;
            }
            case 8: {
                pollSetDestinationOrCapsule(clientId, cs, "Jaguar", true);
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
     *   0  → Right-click 'launcher' (ammo context menu)
     *   1  → Click 'exp_damage' (load explosive ammo)
     *   2  → W+click 'target_vengeance' (orbit)
     *   3  → Check 'orbiting' — if not found, repeat W+click (loop back to 2)
     *   4  → Keypress 's' (activate module)
     *   5  → SAFE WAIT 15s
     *   6  → Ctrl+click-all 'target_inquisitor' (lock all targets)
     *   7  → WAIT 3s (lock animation)
     *   8  → Click 'target_inquisitor' (select as active target)
     *   9  → Keypress 'adc' (activate modules)
     *   10 → SAFE WAIT 130s
     *   11 → Click 'target_inquisitor' (re-select)
     *   12 → Keypress 'ad'
     *   13 → SAFE WAIT 60s
     *   14 → Right-click 'launcher' (ammo context menu)
     *   15 → Click 'inf_damage' (load infernal/thermal ammo)
     *   16 → SAFE WAIT 10s
     *   17 → Keypress 'ad'
     *   18 → SAFE WAIT 30s
     *   19 → SAFE WAIT for set_destination (mission complete → RETURNING)
     */
    private void handleCombatVengeance(String clientId, ClientState cs) {
        int step = cs.getCombatStep();

        switch (step) {
            case 0: {
                log(clientId, "COMBAT[Vengeance] Step 0: right-click launcher (ammo menu).");
                missionProcessor.rightClickTemplate("launcher");
                cs.setCombatStep(1);
                break;
            }
            case 1: {
                log(clientId, "COMBAT[Vengeance] Step 1: click exp_damage (load explosive).");
                missionProcessor.clickTemplate("exp_damage");
                cs.setCombatStep(2);
                break;
            }
            case 2: {
                log(clientId, "COMBAT[Vengeance] Step 2: W+click target_vengeance (keep at range).");
                missionProcessor.wClickTemplate("target_vengeance");
                cs.setCombatStep(3);
                break;
            }
            case 3: {
                // Check orbiting — if not found, loop back to W+click
                String found = missionProcessor.findTemplate("orbiting");
                if (found != null) {
                    log(clientId, "COMBAT[Vengeance] orbiting confirmed — proceeding.");
                    cs.setCombatStep(4);
                } else {
                    log(clientId, "COMBAT[Vengeance] orbiting NOT found — retrying W+click.");
                    cs.setCombatStep(2);
                }
                break;
            }
            case 4: {
                log(clientId, "COMBAT[Vengeance] Step 4: press 's' (activate modules).");
                missionProcessor.keyPress("s");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(5);
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "COMBAT[Vengeance] 25s safe wait — switching to: " + other.getClientId());
                    bringToForeground(other);
                }
                break;
            }
            case 5: {
                // SAFE WAIT: 25 seconds
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 25) {
                    log(clientId, "COMBAT[Vengeance] safe wait: " + elapsed + "s / 25s.");
                    return;
                }
                log(clientId, "COMBAT[Vengeance] 25s wait complete — locking inquisitors.");
                cs.setCombatStep(6);
                break;
            }
            case 6: {
                log(clientId, "COMBAT[Vengeance] Step 6: Ctrl+click-all target_inquisitor (lock all).");
                missionProcessor.ctrlClickAllTemplate("target_inquisitor");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(7);
                break;
            }
            case 7: {
                // WAIT 3s for lock animations to complete
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 3) return;
                log(clientId, "COMBAT[Vengeance] Step 8: click target_inquisitor (select active).");
                missionProcessor.clickTemplate("target_inquisitor");
                cs.setCombatStep(8);
                break;
            }
            case 8: {
                log(clientId, "COMBAT[Vengeance] Step 9: press 'adc' (activate modules).");
                missionProcessor.keyPress("adc");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(9);
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "COMBAT[Vengeance] 100s safe wait — switching to: " + other.getClientId());
                    bringToForeground(other);
                }
                break;
            }
            case 9: {
                // SAFE WAIT: 100 seconds
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 100) {
                    log(clientId, "COMBAT[Vengeance] safe wait: " + elapsed + "s / 100s.");
                    if (elapsed > 0 && elapsed % 30 == 0) {
                        ClientWindowInfo other = findOtherClient(clientId);
                        if (other != null) bringToForeground(other);
                    }
                    return;
                }
                log(clientId, "COMBAT[Vengeance] 100s wait complete — re-targeting inquisitor.");
                cs.setCombatStep(10);
                break;
            }
            case 10: {
                log(clientId, "COMBAT[Vengeance] Step 11: click target_inquisitor (re-select).");
                missionProcessor.clickTemplate("target_inquisitor");
                cs.setCombatStep(11);
                break;
            }
            case 11: {
                log(clientId, "COMBAT[Vengeance] Step 12: press 'ad'.");
                missionProcessor.keyPress("ad");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(12);
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "COMBAT[Vengeance] 60s safe wait — switching to: " + other.getClientId());
                    bringToForeground(other);
                }
                break;
            }
            case 12: {
                // SAFE WAIT: 60 seconds
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 60) {
                    log(clientId, "COMBAT[Vengeance] safe wait: " + elapsed + "s / 60s.");
                    if (elapsed > 0 && elapsed % 30 == 0) {
                        ClientWindowInfo other = findOtherClient(clientId);
                        if (other != null) bringToForeground(other);
                    }
                    return;
                }
                log(clientId, "COMBAT[Vengeance] 60s wait complete — switching ammo.");
                cs.setCombatStep(13);
                break;
            }
            case 13: {
                log(clientId, "COMBAT[Vengeance] Step 14: right-click launcher (ammo menu).");
                missionProcessor.rightClickTemplate("launcher");
                cs.setCombatStep(14);
                break;
            }
            case 14: {
                log(clientId, "COMBAT[Vengeance] Step 15: click inf_damage (load infernal).");
                missionProcessor.clickTemplate("inf_damage");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(15);
                break;
            }
            case 15: {
                // SAFE WAIT: 10 seconds (ammo load)
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 10) {
                    log(clientId, "COMBAT[Vengeance] safe wait: " + elapsed + "s / 10s.");
                    return;
                }
                log(clientId, "COMBAT[Vengeance] 10s wait complete — activating modules.");
                cs.setCombatStep(16);
                break;
            }
            case 16: {
                log(clientId, "COMBAT[Vengeance] Step 17: press 'ad'.");
                missionProcessor.keyPress("ad");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(17);
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "COMBAT[Vengeance] 30s safe wait — switching to: " + other.getClientId());
                    bringToForeground(other);
                }
                break;
            }
            case 17: {
                // SAFE WAIT: 30 seconds
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 30) {
                    log(clientId, "COMBAT[Vengeance] safe wait: " + elapsed + "s / 30s.");
                    return;
                }
                log(clientId, "COMBAT[Vengeance] 30s wait complete — waiting for mission complete.");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(18);
                break;
            }
            case 18: {
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
     *   2  → W+click 'target_enyo' (orbit)
     *   3  → Check 'orbiting' — if not found, repeat W+click (loop back to 2)
     *   4  → Keypress 's' (activate module)
     *   5  → SAFE WAIT 15s
     *   6  → Ctrl+click-all 'target_navitas' (lock all targets)
     *   7  → WAIT 3s (lock animation)
     *   8  → Click 'target_navitas' (select as active target)
     *   9  → Keypress 'adc' (activate modules)
     *   10 → SAFE WAIT 120s
     *   11 → Click 'target_enyo' (select active)
     *   12 → Keypress 'ad'
     *   13 → SAFE WAIT 60s
     *   14 → SAFE WAIT for set_destination (mission complete → RETURNING)
     */
    private void handleCombatEnyo(String clientId, ClientState cs) {
        int step = cs.getCombatStep();

        switch (step) {
            case 0: {
                log(clientId, "COMBAT[Enyo] Step 0: right-click launcher (ammo menu).");
                missionProcessor.rightClickTemplate("launcher");
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
                log(clientId, "COMBAT[Enyo] Step 2: W+click target_enyo (keep at range).");
                missionProcessor.wClickTemplate("target_enyo");
                cs.setCombatStep(3);
                break;
            }
            case 3: {
                // Check orbiting — if not found, loop back to W+click
                String found = missionProcessor.findTemplate("orbiting");
                if (found != null) {
                    log(clientId, "COMBAT[Enyo] orbiting confirmed — proceeding.");
                    cs.setCombatStep(4);
                } else {
                    log(clientId, "COMBAT[Enyo] orbiting NOT found — retrying W+click.");
                    cs.setCombatStep(2);
                }
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
                    return;
                }
                log(clientId, "COMBAT[Enyo] 25s wait complete — locking navitas targets.");
                cs.setCombatStep(6);
                break;
            }
            case 6: {
                log(clientId, "COMBAT[Enyo] Step 6: Ctrl+click-all target_navitas + target_enyo (lock all).");
                missionProcessor.ctrlClickAllTemplate("target_navitas");
                missionProcessor.ctrlClickTemplate("target_enyo");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(7);
                break;
            }
            case 7: {
                // WAIT 3s for lock animations to complete
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 3) return;
                log(clientId, "COMBAT[Enyo] Step 8: click target_navitas (select active).");
                missionProcessor.clickTemplate("target_navitas");
                cs.setCombatStep(8);
                break;
            }
            case 8: {
                log(clientId, "COMBAT[Enyo] Step 9: press 'adc' (activate modules).");
                missionProcessor.keyPress("adc");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(9);
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "COMBAT[Enyo] 100s safe wait — switching to: " + other.getClientId());
                    bringToForeground(other);
                }
                break;
            }
            case 9: {
                // SAFE WAIT: 100 seconds
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 100) {
                    log(clientId, "COMBAT[Enyo] safe wait: " + elapsed + "s / 100s.");
                    if (elapsed > 0 && elapsed % 30 == 0) {
                        ClientWindowInfo other = findOtherClient(clientId);
                        if (other != null) bringToForeground(other);
                    }
                    return;
                }
                log(clientId, "COMBAT[Enyo] 100s wait complete — selecting enyo.");
                cs.setCombatStep(10);
                break;
            }
            case 10: {
                log(clientId, "COMBAT[Enyo] Step 10: click target_enyo (select active).");
                missionProcessor.clickTemplate("target_enyo");
                cs.setCombatStep(11);
                break;
            }
            case 11: {
                log(clientId, "COMBAT[Enyo] Step 12: press 'ad'.");
                missionProcessor.keyPress("ad");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(12);
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "COMBAT[Enyo] 60s safe wait — switching to: " + other.getClientId());
                    bringToForeground(other);
                }
                break;
            }
            case 12: {
                // SAFE WAIT: 60 seconds
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 60) {
                    log(clientId, "COMBAT[Enyo] safe wait: " + elapsed + "s / 60s.");
                    if (elapsed > 0 && elapsed % 30 == 0) {
                        ClientWindowInfo other = findOtherClient(clientId);
                        if (other != null) bringToForeground(other);
                    }
                    return;
                }
                log(clientId, "COMBAT[Enyo] 60s wait complete — waiting for mission complete.");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(13);
                break;
            }
            case 13: {
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
     *   2  → W+click 'target_hawk' (orbit)
     *   3  → Check 'orbiting' — if not found, repeat W+click (loop back to 2)
     *   4  → SAFE WAIT 25s
     *   5  → Ctrl+click-all 'target_hawk' + 'target_bantam' (lock all targets)
     *   6  → WAIT 3s (lock animation)
     *   7  → Click 'target_bantam' (select support ship)
     *   8  → Keypress 'adc' (activate modules)
     *   9  → SAFE WAIT 120s
     *   10 → Click 'target_hawk' (select active)
     *   11 → Keypress 'ad'
     *   12 → SAFE WAIT for set_destination (mission complete → RETURNING)
     */
    private void handleCombatHawk(String clientId, ClientState cs) {
        int step = cs.getCombatStep();

        switch (step) {
            case 0: {
                log(clientId, "COMBAT[Hawk] Step 0: right-click launcher (ammo menu).");
                missionProcessor.rightClickTemplate("launcher");
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
                log(clientId, "COMBAT[Hawk] Step 2: W+click target_hawk (keep at range).");
                missionProcessor.wClickTemplate("target_hawk");
                cs.setCombatStep(3);
                break;
            }
            case 3: {
                // Check orbiting — if not found, loop back to W+click
                String found = missionProcessor.findTemplate("orbiting");
                if (found != null) {
                    log(clientId, "COMBAT[Hawk] orbiting confirmed — pressing 's' and proceeding.");
                    missionProcessor.keyPress("s");
                    cs.setCombatWaitStart(LocalDateTime.now());
                    cs.setCombatStep(4);
                    ClientWindowInfo other = findOtherClient(clientId);
                    if (other != null) {
                        log(clientId, "COMBAT[Hawk] 25s safe wait — switching to: " + other.getClientId());
                        bringToForeground(other);
                    }
                } else {
                    log(clientId, "COMBAT[Hawk] orbiting NOT found — retrying W+click.");
                    cs.setCombatStep(2);
                }
                break;
            }
            case 4: {
                // SAFE WAIT: 25 seconds
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 25) {
                    log(clientId, "COMBAT[Hawk] safe wait: " + elapsed + "s / 25s.");
                    return;
                }
                log(clientId, "COMBAT[Hawk] 25s wait complete — locking targets.");
                cs.setCombatStep(5);
                break;
            }
            case 5: {
                log(clientId, "COMBAT[Hawk] Step 5: Ctrl+click-all target_hawk + target_bantam (lock all).");
                missionProcessor.ctrlClickAllTemplate("target_hawk");
                missionProcessor.ctrlClickAllTemplate("target_bantam");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(6);
                break;
            }
            case 6: {
                // WAIT 3s for lock animations to complete
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 3) return;
                log(clientId, "COMBAT[Hawk] Step 7: click target_bantam (select support ship).");
                missionProcessor.clickTemplate("target_bantam");
                cs.setCombatStep(7);
                break;
            }
            case 7: {
                log(clientId, "COMBAT[Hawk] Step 8: press 'adc' (activate modules).");
                missionProcessor.keyPress("adc");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(8);
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "COMBAT[Hawk] 120s safe wait — switching to: " + other.getClientId());
                    bringToForeground(other);
                }
                break;
            }
            case 8: {
                // SAFE WAIT: 120 seconds
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 120) {
                    log(clientId, "COMBAT[Hawk] safe wait: " + elapsed + "s / 120s.");
                    if (elapsed > 0 && elapsed % 30 == 0) {
                        ClientWindowInfo other = findOtherClient(clientId);
                        if (other != null) bringToForeground(other);
                    }
                    return;
                }
                log(clientId, "COMBAT[Hawk] 120s wait complete — selecting hawk.");
                cs.setCombatStep(9);
                break;
            }
            case 9: {
                log(clientId, "COMBAT[Hawk] Step 10: click target_hawk (select active).");
                missionProcessor.clickTemplate("target_hawk");
                cs.setCombatStep(10);
                break;
            }
            case 10: {
                log(clientId, "COMBAT[Hawk] Step 11: press 'ad'.");
                missionProcessor.keyPress("ad");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(11);
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "COMBAT[Hawk] waiting for mission complete — switching to: " + other.getClientId());
                    bringToForeground(other);
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
     *   0  → Ctrl+click 'target_sansha' (lock) + double-click 'target_sansha' (select)
     *   1  → Keypress 'asdxc' (activate modules)
     *   2  → Q+click 'target_sansha' (keep at range)
     *   3  → Check 'keep_at_range' — if not found, repeat Q+click (loop back to 2)
     *   4  → WAIT 10s
     *   5  → Shift+ds (stop overloading afterburner + web)
     *   6  → Alt+F1 (burst cap recharge)
     *   7  → WAIT 1s
     *   8  → Alt+F1 (deactivate cap module)
     *   9  → WAIT for set_destination (no client switching)
     */
    private void handleCombatSansha(String clientId, ClientState cs) {
        int step = cs.getCombatStep();

        switch (step) {
            case 0: {
                log(clientId, "COMBAT[Sansha] Step 0: Shift+asdx (overload modules).");
                missionProcessor.shiftKeyPress("asdx");
                cs.setCombatStep(1);
                break;
            }
            case 1: {
                log(clientId, "COMBAT[Sansha] Step 1: Ctrl+click target_sansha (lock) + double-click (select).");
                missionProcessor.ctrlClickTemplate("target_sansha");
                missionProcessor.doubleClickTemplate("target_sansha");
                cs.setCombatStep(2);
                break;
            }
            case 2: {
                log(clientId, "COMBAT[Sansha] Step 2: press 'asdxc' (activate modules).");
                missionProcessor.keyPress("asdxc");
                cs.setCombatStep(3);
                break;
            }
            case 3: {
                log(clientId, "COMBAT[Sansha] Step 3: Q+click target_sansha (keep at range).");
                missionProcessor.qClickTemplate("target_sansha");
                cs.setCombatStep(4);
                break;
            }
            case 4: {
                // Check keep_at_range — if not found, loop back to Q+click
                String found = missionProcessor.findTemplate("keep_at_range");
                if (found != null) {
                    log(clientId, "COMBAT[Sansha] keep_at_range confirmed — proceeding.");
                    cs.setCombatWaitStart(LocalDateTime.now());
                    cs.setCombatStep(5);
                } else {
                    log(clientId, "COMBAT[Sansha] keep_at_range NOT found — retrying Q+click.");
                    cs.setCombatStep(3);
                }
                break;
            }
            case 5: {
                // WAIT: 10 seconds
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 10) {
                    log(clientId, "COMBAT[Sansha] wait: " + elapsed + "s / 10s.");
                    return;
                }
                log(clientId, "COMBAT[Sansha] 10s wait complete — stopping overloads.");
                cs.setCombatStep(6);
                break;
            }
            case 6: {
                log(clientId, "COMBAT[Sansha] Step 6: Shift+ds (stop overloading afterburner + web).");
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
                pollSetDestinationOrCapsule(clientId, cs, "Sansha", false);
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
     *   5  → Ctrl+Space (stop ship) + Ctrl+click target_hive (lock) + press 'a' + switch client
     *   6  → SAFE WAIT 45s
     *   7  → poll set_destination / capsule
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
                log(clientId, "COMBAT[Infested] Step 1: double-click target_hive (approach).");
                missionProcessor.doubleClickTemplate("target_hive");
                cs.setCombatStep(2);
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
                // TODO: verify lock_target before activating guns
                log(clientId, "COMBAT[Infested] Step 5: Ctrl+Space (stop ship), Ctrl+click target_hive (lock), press 'a'.");
                missionProcessor.ctrlKeyPress("space");
                missionProcessor.ctrlClickTemplate("target_hive");
                missionProcessor.keyPress("a");
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
                // SAFE WAIT: 45 seconds
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 45) {
                    log(clientId, "COMBAT[Infested] safe wait: " + elapsed + "s / 45s.");
                    if (elapsed > 0 && elapsed % 30 == 0) {
                        ClientWindowInfo other = findOtherClient(clientId);
                        if (other != null) bringToForeground(other);
                    }
                    return;
                }
                log(clientId, "COMBAT[Infested] 45s wait complete — polling completion.");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(7);
                break;
            }
            case 7: {
                pollSetDestinationOrCapsule(clientId, cs, "Infested", true);
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
     *   0  → right-click activate_gate → click mission_start (second gate)
     *   1  → press 's' (activate modules)
     *   2  → SAFE WAIT 20s (gate load) + switch to other client
     *   3  → right-click home_station → click dock
     *   4  → wait 20s for dock → COMPLETING
     */
    private void handleCombatRecon(String clientId, ClientState cs) {
        int step = cs.getCombatStep();

        switch (step) {
            case 0: {
                log(clientId, "COMBAT[Recon] Step 0: right-click activate_gate → mission_start (second gate).");
                new Thread(() -> missionProcessor.rightClickAndSelect("activate_gate", "mission_start")).start();
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(1);
                break;
            }
            case 1: {
                log(clientId, "COMBAT[Recon] Step 1: press 's' (activate modules).");
                missionProcessor.keyPress("s");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(2);
                break;
            }
            case 2: {
                // SAFE WAIT 20s for gate load — switch to other client
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 20) {
                    if (elapsed == 0) {
                        log(clientId, "COMBAT[Recon] gate loading — safe wait 20s.");
                        ClientWindowInfo other = findOtherClient(clientId);
                        if (other != null) {
                            log(clientId, "COMBAT[Recon] switching to: " + other.getClientId());
                            bringToForeground(other);
                        }
                    }
                    return;
                }
                log(clientId, "COMBAT[Recon] 20s gate load complete — docking at home station.");
                cs.setCombatStep(3);
                break;
            }
            case 3: {
                log(clientId, "COMBAT[Recon] Step 3: right-click home_station → dock.");
                new Thread(() -> missionProcessor.rightClickAndSelect("home_station", "dock")).start();
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(4);
                break;
            }
            case 4: {
                // Wait for dock to complete before transitioning
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 20) {
                    if (elapsed == 0) log(clientId, "COMBAT[Recon] docking — waiting for station.");
                    return;
                }
                log(clientId, "COMBAT[Recon] docked — transitioning to COMPLETING.");
                cs.setTravelPhase(ClientState.TravelPhase.NONE);
                stateManager.setState(clientId, ClientState.State.COMPLETING);
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
     *   0 → Shift+axc (overload modules)
     *   1 → Ctrl+click target_angel (lock)
     *   2 → poll for 'jamed' template, press 'xc' when found
     *   3 → press 'asd'
     *   4 → Q+click target_angel (keep at range)
     *   5 → check keep_at_range — if not found, repeat Q+click (loop back to 4)
     *   6 → SAFE WAIT for set_destination (mission complete)
     *   7 → press 'xc' (deactivate modules) → RETURNING
     */
    private void handleCombatAngel(String clientId, ClientState cs) {
        int step = cs.getCombatStep();

        switch (step) {
            case 0: {
                log(clientId, "COMBAT[Angel] Step 0: Shift+axc (overload modules).");
                missionProcessor.shiftKeyPress("axc");
                cs.setCombatStep(1);
                break;
            }
            case 1: {
                log(clientId, "COMBAT[Angel] Step 1: Ctrl+click target_angel (lock), then check jamed.");
                missionProcessor.ctrlClickTemplate("target_angel");
                cs.setCombatStep(2);
                break;
            }
            case 2: {
                // Poll for 'jamed' template — no waiting for lock, just check immediately
                String found = missionProcessor.findTemplate("jamed");
                if (found != null) {
                    log(clientId, "COMBAT[Angel] 'jamed' detected — pressing 'xc'.");
                    missionProcessor.keyPress("xc");
                    cs.setCombatStep(3);
                }
                break;
            }
            case 3: {
                log(clientId, "COMBAT[Angel] Step 3: press 'asd'.");
                missionProcessor.keyPress("asd");
                cs.setCombatStep(4);
                break;
            }
            case 4: {
                log(clientId, "COMBAT[Angel] Step 4: Q+click target_angel (keep at range).");
                missionProcessor.qClickTemplate("target_angel");
                cs.setCombatStep(5);
                break;
            }
            case 5: {
                // Check keep_at_range — if not found, loop back to Q+click
                String found = missionProcessor.findTemplate("keep_at_range");
                if (found != null) {
                    log(clientId, "COMBAT[Angel] keep_at_range confirmed — proceeding.");
                    cs.setCombatWaitStart(LocalDateTime.now());
                    cs.setCombatStep(6);
                } else {
                    log(clientId, "COMBAT[Angel] keep_at_range NOT found — retrying Q+click.");
                    cs.setCombatStep(4);
                }
                break;
            }
            case 6: {
                // SAFE WAIT for set_destination — but intercept to add xc deactivation
                if (missionProcessor.isSetDestinationVisible()) {
                    log(clientId, "COMBAT[Angel] set_destination detected — deactivating modules.");
                    cs.setCombatStep(7);
                    break;
                }

                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();

                // Capsule check after 30s
                if (elapsed >= 30 && missionProcessor.isCapsuleVisible()) {
                    log(clientId, "COMBAT[Angel] *** CAPSULE DETECTED — ship destroyed! Self-destructing...");
                    missionProcessor.clickDestruct();
                    log(clientId, "COMBAT[Angel] waiting 120s for self-destruct respawn...");
                    try { Thread.sleep(120_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    log(clientId, "COMBAT[Angel] respawned — transitioning to REPAIRING.");
                    stateManager.setState(clientId, ClientState.State.REPAIRING);
                    return;
                }

                log(clientId, "COMBAT[Angel] waiting for mission complete (" + elapsed + "s)...");
                if (elapsed > 0 && elapsed % 30 == 0) {
                    ClientWindowInfo other = findOtherClient(clientId);
                    if (other != null) bringToForeground(other);
                }
                break;
            }
            case 7: {
                log(clientId, "COMBAT[Angel] Step 7: press 'xc' (deactivate modules).");
                missionProcessor.keyPress("xc");
                stateManager.setState(clientId, ClientState.State.RETURNING);
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
     *   0 → Shift+asx (overload modules)
     *   1 → double-click target_worm (approach + lock)
     *   2 → press 's' (activate web)
     *   3 → WAIT 10s
     *   4 → Ctrl+click target_worm (lock target)
     *   5 → press 'sdxac'
     *   6 → Q+click target_worm (keep at range)
     *   7 → check keep_at_range — if not found, repeat Q+click (loop back to 6)
     *   8 → Alt+F1 x2 (deactivate module) + switch client
     *   9 → SAFE WAIT for set_destination (mission complete → RETURNING)
     */
    private void handleCombatWorm(String clientId, ClientState cs) {
        int step = cs.getCombatStep();

        switch (step) {
            case 0: {
                log(clientId, "COMBAT[Worm] Step 0: Shift+asx (overload modules).");
                missionProcessor.shiftKeyPress("asx");
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
                // WAIT 12 seconds
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 10) {
                    log(clientId, "COMBAT[Worm] wait: " + elapsed + "s / 10s.");
                    return;
                }
                log(clientId, "COMBAT[Worm] 10s wait complete.");
                cs.setCombatStep(4);
                break;
            }
            case 4: {
                // TODO: verify lock_target before activating
                log(clientId, "COMBAT[Worm] Step 4: Ctrl+click target_worm (lock target).");
                missionProcessor.ctrlClickTemplate("target_worm");
                cs.setCombatStep(5);
                break;
            }
            case 5: {
                log(clientId, "COMBAT[Worm] Step 5: press 'sdxac'.");
                missionProcessor.keyPress("sdxac");
                cs.setCombatStep(6);
                break;
            }
            case 6: {
                log(clientId, "COMBAT[Worm] Step 6: Q+click target_worm (keep at range).");
                missionProcessor.qClickTemplate("target_worm");
                cs.setCombatStep(7);
                break;
            }
            case 7: {
                // Check keep_at_range — if not found, loop back to Q+click
                String found = missionProcessor.findTemplate("keep_at_range");
                if (found != null) {
                    log(clientId, "COMBAT[Worm] keep_at_range confirmed — proceeding.");
                    cs.setCombatStep(8);
                } else {
                    log(clientId, "COMBAT[Worm] keep_at_range NOT found — retrying Q+click.");
                    cs.setCombatStep(6);
                }
                break;
            }
            case 8: {
                log(clientId, "COMBAT[Worm] Step 8: Alt+F1 x2 (deactivate module).");
                missionProcessor.altKeyPress("f1");
                missionProcessor.altKeyPress("f1");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(9);
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "COMBAT[Worm] safe wait — switching to: " + other.getClientId());
                    bringToForeground(other);
                }
                break;
            }
            case 9: {
                pollSetDestinationOrCapsule(clientId, cs, "Worm", true);
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
        missionProcessor.clickSetDestination();
        stateManager.getState(clientId).setTravelPhase(ClientState.TravelPhase.RETURNING_HOME);
        stateManager.setState(clientId, ClientState.State.IDLE);
    }

    // -----------------------------------------------------------------------
    // COMPLETING — turn in mission, collect rewards, return to IDLE
    // -----------------------------------------------------------------------

    private void handleCompleting(String clientId) {
        if (missionProcessor.detectCompletingUI() == null) {
            log(clientId, "COMPLETING: agent icon not visible yet — waiting.");
            try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return;
        }
        missionProcessor.doubleClickAgentIcon();

        // Read agent standings before completing — determines fleet reward choice
        missionProcessor.clickAgentStandings();
        double standings = missionProcessor.readAgentStandings();

        missionProcessor.doubleClickAgentIcon(); // refocus agent dialog (standings covers complete_mission)
        missionProcessor.clickCompleteMission();

        // Fleet reward decision based on agent standings
        // Below 0: take all rewards (fleet_no) to recover standings
        // Healthy: share with fleet (fleet_yes)
        if (Double.isNaN(standings)) {
            log(clientId, "COMPLETING: standings OCR failed — defaulting to fleet YES.");
            missionProcessor.clickFleetYes();
        } else if (standings < -1.0) {
            log(clientId, String.format("COMPLETING: standings %.2f (critical) — fleet NO.", standings));
            missionProcessor.clickFleetNo();
        } else if (standings < 0.0) {
            log(clientId, String.format("COMPLETING: standings %.2f (low) — fleet NO.", standings));
            missionProcessor.clickFleetNo();
        } else {
            log(clientId, String.format("COMPLETING: standings %.2f (healthy) — fleet YES.", standings));
            missionProcessor.clickFleetYes();
        }
        missionProcessor.waitAndClickOk(); // dismiss fleet reward confirmation dialog
        stateManager.getState(clientId).setTravelPhase(ClientState.TravelPhase.NONE);

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
    // UNDOCKING timer
    // -----------------------------------------------------------------------

    /**
     * Checks all registered clients for an expired UNDOCKING timer.
     * No screen interaction — just transitions the state to TRAVEL.
     * closeAllDialogs() runs on the first tick of handleTravel() when the
     * client is back in focus.
     */
    private void checkUndockTimers() {
        for (ClientWindowInfo window : clientWindows.values()) {
            String id = window.getClientId();
            if (stateManager.getStateEnum(id) == ClientState.State.UNDOCKING) {
                long elapsed = Duration.between(
                        stateManager.getState(id).getUndockStartTime(), LocalDateTime.now()
                ).getSeconds();
                if (elapsed >= UNDOCK_WAIT_SECS) {
                    stateManager.setState(id, ClientState.State.TRAVEL);
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // WARPING — poll warp_active until it disappears, then return to IDLE.
    //
    // Timeline:
    //   0–7s   : grace period — warp_active may not have appeared yet, skip check.
    //   5s+    : check warp_active every 30s; when gone → IDLE.
    // -----------------------------------------------------------------------

    private void handleWarping(String clientId, ClientWindowInfo window) {
        ClientState cs = stateManager.getState(clientId);
        long elapsed = Duration.between(cs.getWarpStartTime(), LocalDateTime.now()).getSeconds();

        if (elapsed < 7) {
            // Log only on the very first tick of the grace period to avoid spam.
            if (cs.getLastWarpCheck().equals(LocalDateTime.MIN)) {
                log(clientId, "WARPING: waiting for warp to begin.");
                cs.setLastWarpCheck(LocalDateTime.MIN.plusNanos(1)); // grace-logged marker (not a real check time)
            }
            return;
        }

        boolean firstCheck = cs.getLastWarpCheck().isBefore(LocalDateTime.MIN.plusSeconds(1));
        cs.setLastWarpCheck(LocalDateTime.now());
        if (!missionProcessor.isWarpActive()) {
            log(clientId, "WARPING: warp_active gone after " + elapsed + "s — transitioning to IDLE.");
            stateManager.setState(clientId, ClientState.State.IDLE);
            cs.setJustExitedWarp(true);
            // Brief pause to let the game UI finish rendering after warp landing
            // before taking a navigation screenshot — avoids false template matches.
            try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            // Process IDLE immediately so this client handles the next action
            // (gate jump, warp-to, etc.) before any switch occurs.
            handleIdle(clientId, window);
        } else {
            if (firstCheck) {
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "warp confirmed — switching focus to: " + other.getClientId());
                    bringToForeground(other);
                } else {
                    log(clientId, "warp confirmed — no other clients to switch to.");
                }
            } else if (elapsed >= 30) {
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "WARPING: 30s elapsed — switching focus to: " + other.getClientId());
                    bringToForeground(other);
                } else {
                    log(clientId, "WARPING: 30s elapsed — no other clients to switch to.");
                }
            }
            log(clientId, "still WARPING (" + elapsed + "s).");
        }
    }


    // -----------------------------------------------------------------------
    // Window focus switching
    // -----------------------------------------------------------------------

    /** Returns a WARPING client still in its grace period (< 6s), or null. */
    private ClientWindowInfo findWarpingClientInGracePeriod() {
        for (ClientWindowInfo info : clientWindows.values()) {
            String id = info.getClientId();
            if (stateManager.getStateEnum(id) == ClientState.State.WARPING) {
                long elapsed = java.time.Duration.between(
                        stateManager.getState(id).getWarpStartTime(),
                        java.time.LocalDateTime.now()).getSeconds();
                if (elapsed < 7) return info;
            }
        }
        return null;
    }

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