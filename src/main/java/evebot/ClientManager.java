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


            ClientWindowInfo fg = getForegroundClient();

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

    /**
     * Shared pod-death handler. Self-destructs capsule (if in space), sets global
     * cooldown, and transitions the client to POD_COOLDOWN.
     *
     * @param clientId       the client that was podded
     * @param inSpace        true if capsule is in space (needs self-destruct); false if already at station
     */
    private void handlePodDeath(String clientId, boolean inSpace) {
        ClientState cs = stateManager.getState(clientId);
        int jumps = cs.getJumpCount();
        String phase = cs.getTravelPhase() == ClientState.TravelPhase.TO_MISSION ? "to mission"
                     : cs.getTravelPhase() == ClientState.TravelPhase.RETURNING_HOME ? "returning"
                     : "unknown";
        if (inSpace) {
            log(clientId, "*** CAPSULE DETECTED — ship destroyed! Self-destructing...");
            EventLog.log("POD_DEATH", clientId + " — ship destroyed in space, self-destructing (" + phase + ", jumps: " + jumps + ")");
            missionProcessor.clickDestruct();
            log(clientId, "Waiting 120s for self-destruct respawn...");
            try { Thread.sleep(120_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        } else {
            log(clientId, "*** PODDED — respawned at station (agent_icon visible).");
            EventLog.log("POD_DEATH", clientId + " — respawned at station (" + phase + ", jumps: " + jumps + ")");
        }

        if (jumps <= 1) {
            LocalDateTime cooldownEnd = LocalDateTime.now().plusMinutes(POD_COOLDOWN_MINUTES);
            stateManager.setPodCooldownEnd(cooldownEnd);
            log(clientId, "POD_COOLDOWN: set " + POD_COOLDOWN_MINUTES + "-minute global cooldown (jumps=" + jumps + ").");
            stateManager.setState(clientId, ClientState.State.POD_COOLDOWN);
        } else {
            log(clientId, "POD_DEATH: jumps=" + jumps + " (>1) — skipping cooldown, resuming immediately.");
            if (cs.getCurrentMission() != null) {
                stateManager.setState(clientId, ClientState.State.REPAIRING);
            } else {
                stateManager.setState(clientId, ClientState.State.IDLE);
            }
        }
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
                            loadAmmoBeforeGate(clientId, cs);
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
                if (missionProcessor.detectCompletingUI() != null) {
                    // Agent icon visible during TO_MISSION = respawned at station after pod death.
                    handlePodDeath(clientId, false);
                    return;
                }
                log(clientId, "IDLE[TO_MISSION]: no navigation element visible — waiting.");
                return;
            }
            switch (found) {
                case "activate_gate":
                    loadAmmoBeforeGate(clientId, cs);
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
                    cs.setDockingToStation(true);
                    cs.setWarpStartTime(LocalDateTime.now());
                    stateManager.setState(clientId, ClientState.State.WARPING);
                    return;
                }

                // Dock not visible — check for hostiles before proceeding to stargate.
                if (missionProcessor.isEnemyInLocal()) {
                    log(clientId, "IDLE[RETURNING_HOME]: enemy in local after jump — warping to safe spot. (jumpCount=" + cs.getJumpCount() + ")");
                    if (missionProcessor.warpToGenericSafeSpot()) {
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
                    cs.setDockingToStation(true);
                    cs.setWarpStartTime(LocalDateTime.now());
                    stateManager.setState(clientId, ClientState.State.WARPING);
                    break;
                case "stargate":
                    cs.incrementJumpCount();
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
        // First thing after undock: check for bubble camp — redock immediately if detected.
        if (missionProcessor.isBubbleCampOnUndock()) {
            log(clientId, "TRAVEL: bubble camp detected on undock — emergency redock!");
            missionProcessor.emergencyRedock();
            LocalDateTime cooldownEnd = LocalDateTime.now().plusMinutes(POD_COOLDOWN_MINUTES);
            stateManager.setPodCooldownEnd(cooldownEnd);
            log(clientId, "BUBBLE_CAMP: set " + POD_COOLDOWN_MINUTES + "-minute global cooldown for both clients.");
            stateManager.setState(clientId, ClientState.State.POD_COOLDOWN);
            return;
        }

        missionProcessor.closeAllDialogs();
        missionProcessor.pressCtrlR();
        missionProcessor.closeAllDialogs();
        missionProcessor.clickSetDestination();

        // After setting destination, check for hostiles — warp to safe spot if needed
        if (missionProcessor.findTemplate("enemy") != null) {
            log(clientId, "TRAVEL: enemy in local after undock — warping to safe spot.");
            missionProcessor.warpToSafeSpot();
        }

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

            // 7s+: poll warp_active every 5s — when it disappears, warp has landed.
            boolean firstCheck = cs.getLastWarpCheck().isBefore(LocalDateTime.MIN.plusSeconds(1));
            long sinceLastCheck = Duration.between(cs.getLastWarpCheck(), LocalDateTime.now()).getSeconds();
            if (!firstCheck && sinceLastCheck < 5) {
                return; // throttle to 5s intervals
            }
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
    // Polls set_destination; after 80s also checks for capsule (ship destroyed).
    // -----------------------------------------------------------------------

    private void pollSetDestinationOrCapsule(String clientId, ClientState cs,
                                              String combatLabel, boolean allowClientSwitch) {
        String completed = missionProcessor.detectCombatComplete();
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

        // After 80s without set_destination, start checking for capsule (ship destroyed).
        if (elapsed >= 80 && missionProcessor.isCapsuleVisible()) {
            handlePodDeath(clientId, true);
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
     *   3 → Check 'orbiting' — if not found, repeat (loop back to 2)
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
                // Check keep_at_range — if not found, retry
                String found = missionProcessor.findTemplate("keep_at_range");
                if (found != null) {
                    log(clientId, "COMBAT[Jaguar] keep_at_range confirmed — proceeding.");
                    cs.setCombatStep(4);
                } else {
                    log(clientId, "COMBAT[Jaguar] keep_at_range NOT found — retrying range click.");
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
     *   0  → Right-click target_vengeance → orbit
     *   1  → Check 'orbiting' — if not found, repeat (loop back to 0)
     *   2  → Keypress 's' (activate module)
     *   3  → SAFE WAIT 25s
     *   4  → Ctrl+click-all 'target_inquisitor' + Ctrl+click 'target_vengeance' (lock all)
     *   5  → WAIT 3s (lock animation) → click 'locked_inquisitor' (select)
     *   6  → Press 'adc' (activate modules), click 'target_inquisitor' (verify)
     *   7  → SAFE WAIT 130s
     *   8  → Ctrl+click 'target_inquisitor' (re-lock)
     *   9  → WAIT 2s → click 'locked_inquisitor' (select) + press 'ad'
     *   10 → SAFE WAIT 60s
     *   11 → Right-click 'launcher' (ammo switch)
     *   12 → Click 'inf_damage' (load infernal/thermal ammo)
     *   13 → SAFE WAIT 10s
     *   14 → Ctrl+click 'target_vengeance' (lock)
     *   15 → WAIT 3s → check 'locked_vengeance' — if not found, retry (loop back to 14); if found, click + press 'ad'
     *   16 → SAFE WAIT 30s
     *   17 → SAFE WAIT for set_destination (mission complete → RETURNING)
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
                // Check orbiting — if not found, retry
                String found = missionProcessor.findTemplate("orbiting");
                if (found != null) {
                    log(clientId, "COMBAT[Vengeance] orbiting confirmed — proceeding.");
                    cs.setCombatStep(2);
                } else {
                    log(clientId, "COMBAT[Vengeance] orbiting NOT found — retrying orbit.");
                    cs.setCombatStep(0);
                }
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
                    return;
                }
                log(clientId, "COMBAT[Vengeance] 25s wait complete — locking inquisitors.");
                cs.setCombatStep(4);
                break;
            }
            case 4: {
                log(clientId, "COMBAT[Vengeance] Step 4: Ctrl+click-all target_inquisitor + target_vengeance (lock all).");
                missionProcessor.ctrlClickAllTemplate("target_inquisitor");
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
                cs.setCombatStep(7);
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "COMBAT[Vengeance] 130s safe wait — switching to: " + other.getClientId());
                    bringToForeground(other);
                }
                break;
            }
            case 7: {
                // SAFE WAIT: 120 seconds
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 130) {
                    log(clientId, "COMBAT[Vengeance] safe wait: " + elapsed + "s / 130s.");
                    if (elapsed > 0 && elapsed % 10 == 0) {
                        ClientWindowInfo other = findOtherClient(clientId);
                        if (other != null) bringToForeground(other);
                    }
                    return;
                }
                log(clientId, "COMBAT[Vengeance] 130s wait complete — re-targeting inquisitor.");
                cs.setCombatStep(8);
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
                    log(clientId, "COMBAT[Vengeance] Step 15: locked_vengeance confirmed — click + press 'ad'.");
                    missionProcessor.clickTemplate("locked_vengeance");
                    missionProcessor.keyPress("ad");
                    cs.setCombatWaitStart(LocalDateTime.now());
                    cs.setCombatStep(16);
                    ClientWindowInfo other = findOtherClient(clientId);
                    if (other != null) {
                        log(clientId, "COMBAT[Vengeance] 30s safe wait — switching to: " + other.getClientId());
                        bringToForeground(other);
                    }
                } else {
                    log(clientId, "COMBAT[Vengeance] Step 15: locked_vengeance NOT found — retrying lock.");
                    cs.setCombatStep(14);
                }
                break;
            }
            case 16: {
                // SAFE WAIT: 30 seconds
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 30) {
                    log(clientId, "COMBAT[Vengeance] safe wait: " + elapsed + "s / 30s.");
                    return;
                }
                log(clientId, "COMBAT[Vengeance] 30s wait complete — waiting for mission complete.");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(17);
                break;
            }
            case 17: {
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
     *   3  → Check 'orbiting' — if not found, repeat (loop back to 2)
     *   4  → Keypress 's' (activate module)
     *   5  → SAFE WAIT 25s
     *   6  → Ctrl+click-all 'target_navitas' + 'target_enyo' (lock all)
     *   7  → WAIT 3s → click 'locked_navitas' (select)
     *   8  → Press 'adc', click 'locked_navitas' (verify)
     *   9  → SAFE WAIT 120s
     *   10 → Ctrl+click 'target_enyo' (lock)
     *   11 → WAIT 3s → check 'locked_enyo' — if not found, retry (loop back to 10); if found, click + press 'ad'
     *   12 → SAFE WAIT 60s
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
                // Check orbiting — if not found, retry
                String found = missionProcessor.findTemplate("orbiting");
                if (found != null) {
                    log(clientId, "COMBAT[Enyo] orbiting confirmed — proceeding.");
                    cs.setCombatStep(4);
                } else {
                    log(clientId, "COMBAT[Enyo] orbiting NOT found — retrying orbit.");
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
                // WAIT 3s for lock animations, then check locked_navitas
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 3) return;
                String lockedN = missionProcessor.findTemplate("locked_navitas");
                if (lockedN != null) {
                    log(clientId, "COMBAT[Enyo] Step 7: locked_navitas confirmed — selecting.");
                    missionProcessor.clickTemplate("locked_navitas");
                    cs.setCombatStep(8);
                } else {
                    log(clientId, "COMBAT[Enyo] Step 7: locked_navitas NOT found — retrying lock.");
                    cs.setCombatStep(6);
                }
                break;
            }
            case 8: {
                log(clientId, "COMBAT[Enyo] Step 8: press 'adc' (activate modules), click locked_navitas (verify).");
                missionProcessor.keyPress("adc");
                missionProcessor.clickTemplate("locked_navitas");
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
                // SAFE WAIT: 120 seconds
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 120) {
                    log(clientId, "COMBAT[Enyo] safe wait: " + elapsed + "s / 120s.");
                    if (elapsed > 0 && elapsed % 10 == 0) {
                        ClientWindowInfo other = findOtherClient(clientId);
                        if (other != null) bringToForeground(other);
                    }
                    return;
                }
                log(clientId, "COMBAT[Enyo] 120s wait complete — selecting enyo.");
                cs.setCombatStep(10);
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
                    log(clientId, "COMBAT[Enyo] Step 11: locked_enyo confirmed — click + press 'ad'.");
                    missionProcessor.clickTemplate("locked_enyo");
                    missionProcessor.keyPress("ad");
                    cs.setCombatWaitStart(LocalDateTime.now());
                    cs.setCombatStep(12);
                    ClientWindowInfo other = findOtherClient(clientId);
                    if (other != null) {
                        log(clientId, "COMBAT[Enyo] 60s safe wait — switching to: " + other.getClientId());
                        bringToForeground(other);
                    }
                } else {
                    log(clientId, "COMBAT[Enyo] Step 11: locked_enyo NOT found — retrying lock.");
                    cs.setCombatStep(10);
                }
                break;
            }
            case 12: {
                // SAFE WAIT: 60 seconds
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 60) {
                    log(clientId, "COMBAT[Enyo] safe wait: " + elapsed + "s / 60s.");
                    if (elapsed > 0 && elapsed % 10 == 0) {
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
     *   2  → Click 'target_hawk' + click 'orbit' (orbit)
     *   3  → Check 'orbiting' — if not found, repeat (loop back to 2)
     *   4  → SAFE WAIT 25s
     *   5  → Ctrl+click-all 'target_hawk' + 'target_bantam' (lock all)
     *   6  → WAIT 3s → click 'locked_bantam' (select)
     *   7  → Press 'adc', click 'locked_bantam' (verify)
     *   8  → SAFE WAIT 90s
     *   9  → Ctrl+click 'target_hawk' (lock)
     *   10 → WAIT 3s → check 'locked_hawk' — if not found, retry (loop back to 9); if found, click + press 'ad'
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
                // Check orbiting — if not found, retry
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
                    log(clientId, "COMBAT[Hawk] orbiting NOT found — retrying orbit.");
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
                // WAIT 3s for lock animations, then check locked_bantam
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 3) return;
                String lockedB = missionProcessor.findTemplate("locked_bantam");
                if (lockedB != null) {
                    log(clientId, "COMBAT[Hawk] Step 6: locked_bantam confirmed — selecting.");
                    missionProcessor.clickTemplate("locked_bantam");
                    cs.setCombatStep(7);
                } else {
                    log(clientId, "COMBAT[Hawk] Step 6: locked_bantam NOT found — retrying lock.");
                    cs.setCombatStep(5);
                }
                break;
            }
            case 7: {
                log(clientId, "COMBAT[Hawk] Step 7: press 'adc' (activate modules), click locked_bantam (verify).");
                missionProcessor.keyPress("adc");
                missionProcessor.clickTemplate("locked_bantam");
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
                // SAFE WAIT: 90 seconds
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 90) {
                    log(clientId, "COMBAT[Hawk] safe wait: " + elapsed + "s / 90s.");
                    if (elapsed > 0 && elapsed % 10 == 0) {
                        ClientWindowInfo other = findOtherClient(clientId);
                        if (other != null) bringToForeground(other);
                    }
                    return;
                }
                log(clientId, "COMBAT[Hawk] 90s wait complete — selecting hawk.");
                cs.setCombatStep(9);
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
                    log(clientId, "COMBAT[Hawk] Step 10: locked_hawk confirmed — click + press 'ad'.");
                    missionProcessor.clickTemplate("locked_hawk");
                    missionProcessor.keyPress("ad");
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
     *   3  → right-click target_sansha → click range (keep at range)
     *   4  → Check 'keep_at_range' — if not found, repeat (loop back to 3)
     *   5  → WAIT 15s
     *   6  → Shift+dsc (stop overloading afterburner + web + nos)
     *   7  → Alt+F1 (burst cap recharge)
     *   8  → WAIT 1s
     *   9  → Alt+F1 (deactivate cap module)
     *  10  → WAIT for set_destination/dock (capsule check after 30s)
     *  11  → press 'xc' (deactivate modules) + handle dock/returning
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
                cs.setCombatStep(3);
                break;
            }
            case 3: {
                log(clientId, "COMBAT[Sansha] Step 3: right-click target_sansha → click range.");
                missionProcessor.rightClickTemplate("target_sansha");
                missionProcessor.clickTemplate("range");
                cs.setCombatStep(4);
                break;
            }
            case 4: {
                // Check keep_at_range — if not found, retry
                String found = missionProcessor.findTemplate("keep_at_range");
                if (found != null) {
                    log(clientId, "COMBAT[Sansha] keep_at_range confirmed — proceeding.");
                    cs.setCombatWaitStart(LocalDateTime.now());
                    cs.setCombatStep(5);
                } else {
                    log(clientId, "COMBAT[Sansha] keep_at_range NOT found — retrying.");
                    cs.setCombatStep(3);
                }
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
                log(clientId, "COMBAT[Sansha] Step 6: Shift+dsc (stop overloading afterburner + web + nos).");
                missionProcessor.shiftKeyPress("dsc");
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

                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();

                // Capsule check after 80s
                if (elapsed >= 80 && missionProcessor.isCapsuleVisible()) {
                    handlePodDeath(clientId, true);//TODO bad template, we need instead a check if ship template modules are missing
                    return;
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
     *   5  → Ctrl+Space (stop ship) + Ctrl+click target_hive/hive1/hive2/hive3 (lock) + press 'a' + switch client
     *   6  → SAFE WAIT 45s
     *   7  → click set_destination + attempt stargate jump
     *   8  → poll warp_active / jamed / capsule
     *   9  → (jamed) double-click stargate + press 's', WAIT 10s
     *   10 → (jamed) try jump stargate → loop back to 8
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
                log(clientId, "COMBAT[Infested] Step 5: Ctrl+Space (stop ship), Ctrl+click hive (lock), press 'a'.");
                missionProcessor.ctrlKeyPress("space");
                String hive5 = missionProcessor.findAnyTemplate("target_hive", "target_hive1", "target_hive2", "target_hive3");
                if (hive5 != null) {
                    missionProcessor.ctrlClickTemplate(hive5);
                    missionProcessor.keyPress("a");
                    missionProcessor.clickTemplate(hive5);
                } else {
                    log(clientId, "COMBAT[Infested] Step 5: no hive target found — skipping lock.");
                    missionProcessor.keyPress("a");
                }
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
                    if (elapsed > 0 && elapsed % 10 == 0) {
                        ClientWindowInfo other = findOtherClient(clientId);
                        if (other != null) bringToForeground(other);
                    }
                    return;
                }
                log(clientId, "COMBAT[Infested] 45s wait complete — clicking set_destination and jumping.");
                cs.setCombatStep(7);
                break;
            }
            case 7: {
                // Click set_destination + attempt first stargate jump
                log(clientId, "COMBAT[Infested] Step 7: click set_destination + jump stargate.");
                missionProcessor.clickSetDestination();
                new Thread(() -> missionProcessor.rightClickAndSelect("stargate", "jump")).start();
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(8);
                break;
            }
            case 8: {
                // Poll warp_active / jamed / capsule
                if (missionProcessor.isWarpActive()) {
                    log(clientId, "COMBAT[Infested] warp_active detected — escaped!");
                    cs.setWarpStartTime(LocalDateTime.now());
                    cs.setTravelPhase(ClientState.TravelPhase.RETURNING_HOME);
                    stateManager.setState(clientId, ClientState.State.WARPING);
                    return;
                }

                long elapsed8 = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();

                if (elapsed8 >= 120 && missionProcessor.isCapsuleVisible()) {
                    handlePodDeath(clientId, true);
                    return;
                }

                // Check for warp jam — need to burn away and retry
                if (missionProcessor.findTemplate("jamed") != null) {
                    log(clientId, "COMBAT[Infested] JAMED — double-click stargate + press 's' to escape.");
                    missionProcessor.doubleClickTemplate("stargate");
                    missionProcessor.keyPress("s");
                    cs.setCombatWaitStart(LocalDateTime.now());
                    cs.setCombatStep(9);
                    break;
                }

                log(clientId, "COMBAT[Infested] waiting for warp (" + elapsed8 + "s)...");
                break;
            }
            case 9: {
                // WAIT 5s after being jamed — let prop mod burn away
                long elapsed9 = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed9 < 5) {
                    log(clientId, "COMBAT[Infested] jamed escape wait: " + elapsed9 + "s / 5s.");
                    return;
                }
                log(clientId, "COMBAT[Infested] 5s escape wait complete — attempting stargate jump.");
                cs.setCombatStep(10);
                break;
            }
            case 10: {
                // Retry jump, then go back to polling
                new Thread(() -> missionProcessor.rightClickAndSelect("stargate", "jump")).start();
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(8);
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
     *   0 → Shift+axc (overload modules)
     *   1 → Ctrl+click target_angel (lock)
     *   2 → poll for 'jamed' template, press 'xc' when found
     *   3 → press 'asd', click target_angel
     *   4 → Right-click target_angel + click range (keep at range)
     *   5 → check keep_at_range — if not found, repeat (loop back to 4)
     *   6 → WAIT for set_destination (mission complete)
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
                log(clientId, "COMBAT[Angel] Step 3: press 'asd', click target_angel.");
                missionProcessor.keyPress("asd");
                missionProcessor.clickTemplate("target_angel");
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
                // Check keep_at_range — if not found, retry
                String found = missionProcessor.findTemplate("keep_at_range");
                if (found != null) {
                    log(clientId, "COMBAT[Angel] keep_at_range confirmed — proceeding.");
                    cs.setCombatWaitStart(LocalDateTime.now());
                    cs.setCombatStep(6);
                } else {
                    log(clientId, "COMBAT[Angel] keep_at_range NOT found — retrying.");
                    cs.setCombatStep(4);
                }
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

                // Capsule check after 90s
                if (elapsed >= 90 && missionProcessor.isCapsuleVisible()) {
                    handlePodDeath(clientId, true);
                    return;
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
     *   0 → Shift+asx (overload modules)
     *   1 → double-click target_worm (approach + lock)
     *   2 → press 's' (activate web)
     *   3 → WAIT 10s
     *   4 → Ctrl+click target_worm (lock target)
     *   5 → press 'sdxac'
     *   6 → Click target_worm + click range (keep at range)
     *   7 → check keep_at_range — if not found, repeat (loop back to 6)
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
                log(clientId, "COMBAT[Worm] Step 5: press 'sdxac' + click target_worm.");
                missionProcessor.keyPress("sdxac");
                missionProcessor.clickTemplate("target_worm");
                cs.setCombatStep(6);
                break;
            }
            case 6: {
                log(clientId, "COMBAT[Worm] Step 6: right-click target_worm → range.");
                missionProcessor.rightClickTemplate("target_worm");
                missionProcessor.clickTemplate("range");
                cs.setCombatStep(7);
                break;
            }
            case 7: {
                // Check keep_at_range — if not found, retry
                String found = missionProcessor.findTemplate("keep_at_range");
                if (found != null) {
                    log(clientId, "COMBAT[Worm] keep_at_range confirmed — waiting 5s before deactivate.");
                    cs.setCombatWaitStart(LocalDateTime.now());
                    cs.setCombatStep(8);
                } else {
                    log(clientId, "COMBAT[Worm] keep_at_range NOT found — retrying.");
                    cs.setCombatStep(6);
                }
                break;
            }
            case 8: {
                // WAIT 8s before deactivating module
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 8) return;
                log(clientId, "COMBAT[Worm] Step 8: Alt+F1 (deactivate module).");
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
            if (standings < -1.5) {
                EventLog.log("STANDINGS_LOW", clientId + String.format(" — standings: %.2f", standings));
            }
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
        // Check for bubble camp immediately after undock completes.
        if (missionProcessor.isBubbleCampOnUndock()) {
            log(clientId, "UNDOCKING: bubble camp detected — emergency redock!");
            missionProcessor.emergencyRedock();
            LocalDateTime cooldownEnd = LocalDateTime.now().plusMinutes(POD_COOLDOWN_MINUTES);
            stateManager.setPodCooldownEnd(cooldownEnd);
            log(clientId, "UNDOCKING: set " + POD_COOLDOWN_MINUTES + "-minute global cooldown for both clients.");
            stateManager.setState(clientId, ClientState.State.POD_COOLDOWN);
            return;
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

        // Wait 4s for the warp/jump action to complete before checking for bubble.
        if (elapsed < 4) {
            return;
        }

        // --- Bubble escape loop (step-based, survives across ticks) ---
        int bubbleStep = cs.getBubbleEscapeStep();
        if (bubbleStep > 0) {
            switch (bubbleStep) {
                case 1: {
                    // Step 1: shift+s (stop), double-click stargate, press s (approach)
                    log(clientId, "WARPING[BUBBLE] step 1: shift+s, double-click stargate, press s.");
                    missionProcessor.shiftKeyPress("s");
                    missionProcessor.doubleClickTemplate("stargate");
                    missionProcessor.keyPress("s");
                    cs.setBubbleEscapeStart(LocalDateTime.now());
                    cs.setBubbleEscapeStep(2);
                    return;
                }
                case 2: {
                    // Step 2: wait 5s
                    long bubbleElapsed = Duration.between(cs.getBubbleEscapeStart(), LocalDateTime.now()).getSeconds();
                    if (bubbleElapsed < 5) {
                        log(clientId, "WARPING[BUBBLE] step 2: waiting (" + bubbleElapsed + "s / 5s).");
                        return;
                    }
                    // Check for pod death while in bubble
                    if (missionProcessor.isCapsuleVisible()) {
                        log(clientId, "WARPING[BUBBLE]: capsule detected — pod death.");
                        handlePodDeath(clientId, true);
                        return;
                    }
                    if (missionProcessor.detectCompletingUI() != null) {
                        log(clientId, "WARPING[BUBBLE]: agent_icon visible — respawned at station.");
                        handlePodDeath(clientId, false);
                        return;
                    }
                    log(clientId, "WARPING[BUBBLE] step 2: 5s wait complete — attempting jump.");
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
            // Check for warp disruption bubble before assuming warp will start.
            if (missionProcessor.findTemplate("bubble_text") != null) {
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
            // Brief pause to let the game UI finish rendering after warp landing
            // before taking a navigation screenshot — avoids false template matches.
            try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            // Process IDLE immediately so this client handles the next action
            // (gate jump, warp-to, etc.) before any switch occurs.
            handleIdle(clientId, window);
        } else {
            if (elapsed % 10 < 2) {
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