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

            // If a client is in the WARPING grace period (just clicked a stargate/warp,
            // < 6s ago), it MUST stay in foreground — switch back to it if needed.
            // Otherwise, if a WARPING client's check is due, switch to it — unless
            // the foreground client is IDLE (needs to process its next action first).
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
                            log(clientId, "IDLE[TO_MISSION]: activate_gate — E+clicking, entering combat.");
                            stateManager.setState(clientId, ClientState.State.IN_COMBAT);
                            new Thread(() -> missionProcessor.eClickTemplate("activate_gate")).start();
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
                // Short warp (<35s) = likely mid-jump through a gate tunnel — wait 8s.
                // Long warp (≥35s) = already jumped, skip wait and check stargate immediately.
                if (warpSecs < 35) {
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
                    log(clientId, "IDLE[TO_MISSION]: activate_gate — E+clicking, entering combat.");
                    stateManager.setState(clientId, ClientState.State.IN_COMBAT);
                    new Thread(() -> missionProcessor.eClickTemplate("activate_gate")).start();
                    break;
                case "warp_to_location":
                    log(clientId, "IDLE[TO_MISSION]: warp_to_location — warping into mission space.");
                    cs.setWarpStartTime(LocalDateTime.now());
                    stateManager.setState(clientId, ClientState.State.WARPING);
                    new Thread(() -> missionProcessor.clickWarpToLocation()).start();
                    break;
                case "stargate":
                    log(clientId, "IDLE[TO_MISSION]: stargate — E+clicking to jump.");
                    cs.setWarpStartTime(LocalDateTime.now());
                    stateManager.setState(clientId, ClientState.State.WARPING);
                    new Thread(() -> missionProcessor.eClickTemplate("stargate")).start();
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
                    log(clientId, "IDLE[RETURNING_HOME]: dock visible — docking.");
                    missionProcessor.clickDock();
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
                log(clientId, "IDLE[RETURNING_HOME]: no navigation element visible — waiting.");
                return;
            }
            switch (found) {
                case "dock":
                    log(clientId, "IDLE[RETURNING_HOME]: dock visible — docking.");
                    missionProcessor.clickDock();
                    stateManager.setState(clientId, ClientState.State.COMPLETING);
                    break;
                case "stargate":
                    log(clientId, "stargate — E+clicking to jump.");
                    cs.setWarpStartTime(LocalDateTime.now());
                    stateManager.setState(clientId, ClientState.State.WARPING);
                    new Thread(() -> missionProcessor.eClickTemplate("stargate")).start();
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

        // Grace period: gate animation takes ~15s after activate_gate click.
        // Skip all combat logic until the mission pocket has loaded.
        long sinceEntry = Duration.between(cs.getLastStateChange(), LocalDateTime.now()).getSeconds();
        if (sinceEntry < 15) {
            if (sinceEntry == 0) {
                log(clientId, "IN_COMBAT: gate loading — waiting 15s for mission pocket.");
            }
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
        if (sinceEntry < 17) {
            // Log once after grace period to show what we're dispatching.
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
            // Future missions:
            // case "hawk":      handleCombatHawk(clientId, cs);      break;
            default:
                // Unknown target — fall back to set_destination polling.
                if (missionProcessor.isSetDestinationVisible()) {
                    log(clientId, "IN_COMBAT: set_destination detected — mission complete.");
                    stateManager.setState(clientId, ClientState.State.RETURNING);
                }
                break;
        }
    }

    /**
     * Anomic Team — Jaguar combat sequence.
     *
     * Steps:
     *   0 → Right-click 'launcher' (open ammo context menu)
     *   1 → Click 'kin_damage' (load kinetic ammo)
     *   2 → Q+click 'target_jaguar' (orbit target)
     *   3 → Keypress 's' (activate module)
     *   4 → SAFE WAIT 90s (can switch to other clients)
     *   5 → Ctrl+click 'target_jaguar' (lock target)
     *   6 → Keypress 'adc' (activate modules)
     *   7 → SAFE WAIT for set_destination (mission complete → RETURNING)
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
                log(clientId, "COMBAT[Jaguar] Step 3: press 's' (activate modules).");
                missionProcessor.keyPress("s");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(4);
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "COMBAT[Jaguar] 90s safe wait — switching to: " + other.getClientId());
                    bringToForeground(other);
                }
                break;
            }
            case 4: {
                // SAFE WAIT: 90 seconds
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 90) {
                    if (elapsed > 0 && elapsed % 30 == 0) {
                        log(clientId, "COMBAT[Jaguar] safe wait: " + elapsed + "s / 90s.");
                    }
                    ClientWindowInfo other = findOtherClient(clientId);
                    if (other != null) {
                        bringToForeground(other);
                    }
                    return;
                }
                log(clientId, "COMBAT[Jaguar] 90s wait complete — proceeding to lock target.");
                cs.setCombatStep(5);
                break;
            }
            case 5: {
                log(clientId, "COMBAT[Jaguar] Step 5: Ctrl+click target_jaguar (lock target).");
                missionProcessor.ctrlClickTemplate("target_jaguar");
                cs.setCombatStep(6);
                break;
            }
            case 6: {
                log(clientId, "COMBAT[Jaguar] Step 6: press 'adc' (activate modules).");
                missionProcessor.keyPress("adc");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(7);
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "COMBAT[Jaguar] waiting for mission complete — switching to: " + other.getClientId());
                    bringToForeground(other);
                }
                break;
            }
            case 7: {
                // SAFE WAIT: poll for set_destination (mission complete signal)
                if (missionProcessor.isSetDestinationVisible()) {
                    log(clientId, "COMBAT[Jaguar] set_destination detected — mission complete!");
                    stateManager.setState(clientId, ClientState.State.RETURNING);
                } else {
                    long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                    if (elapsed > 0 && elapsed % 30 == 0) {
                        log(clientId, "COMBAT[Jaguar] waiting for mission complete (" + elapsed + "s)...");
                    }
                    ClientWindowInfo other = findOtherClient(clientId);
                    if (other != null) {
                        bringToForeground(other);
                    }
                }
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
     *   2  → W+click 'target_vengeance' (keep at range)
     *   3  → Keypress 's' (activate module)
     *   4  → SAFE WAIT 15s
     *   5  → Ctrl+click 'target_inquisitor' (lock first)
     *   6  → Ctrl+click 'target_inquisitor' (lock second)
     *   7  → Click 'target_inquisitor' (select as active target)
     *   8  → Keypress 'adc' (activate modules)
     *   9  → SAFE WAIT 130s
     *   10 → Click 'target_inquisitor' (re-select)
     *   11 → Keypress 'ad'
     *   12 → SAFE WAIT 60s
     *   13 → Right-click 'launcher' (ammo context menu)
     *   14 → Click 'inf_damage' (load infernal/thermal ammo)
     *   15 → SAFE WAIT 10s
     *   16 → Keypress 'ad'
     *   17 → SAFE WAIT 30s
     *   18 → SAFE WAIT for set_destination (mission complete → RETURNING)
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
                log(clientId, "COMBAT[Vengeance] Step 3: press 's' (activate modules).");
                missionProcessor.keyPress("s");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(4);
                break;
            }
            case 4: {
                // SAFE WAIT: 15 seconds
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 15) {
                    ClientWindowInfo other = findOtherClient(clientId);
                    if (other != null) bringToForeground(other);
                    return;
                }
                log(clientId, "COMBAT[Vengeance] 15s wait complete — locking inquisitors.");
                cs.setCombatStep(5);
                break;
            }
            case 5: {
                // TODO: replace steps 5+6 with ctrlClickAllTemplate("target_inquisitor")
                //       once findAll is implemented — avoids re-locking the same target.
                log(clientId, "COMBAT[Vengeance] Step 5: Ctrl+click target_inquisitor (lock first).");
                missionProcessor.ctrlClickTemplate("target_inquisitor");
                cs.setCombatStep(6);
                break;
            }
            case 6: {
                log(clientId, "COMBAT[Vengeance] Step 6: Ctrl+click target_inquisitor (lock second).");
                missionProcessor.ctrlClickTemplate("target_inquisitor");
                cs.setCombatStep(7);
                break;
            }
            case 7: {
                log(clientId, "COMBAT[Vengeance] Step 7: click target_inquisitor (select active).");
                missionProcessor.clickTemplate("target_inquisitor");
                cs.setCombatStep(8);
                break;
            }
            case 8: {
                log(clientId, "COMBAT[Vengeance] Step 8: press 'adc' (activate modules).");
                missionProcessor.keyPress("adc");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(9);
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "COMBAT[Vengeance] 130s safe wait — switching to: " + other.getClientId());
                    bringToForeground(other);
                }
                break;
            }
            case 9: {
                // SAFE WAIT: 130 seconds
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 130) {
                    if (elapsed > 0 && elapsed % 30 == 0) {
                        log(clientId, "COMBAT[Vengeance] safe wait: " + elapsed + "s / 130s.");
                    }
                    ClientWindowInfo other = findOtherClient(clientId);
                    if (other != null) bringToForeground(other);
                    return;
                }
                log(clientId, "COMBAT[Vengeance] 130s wait complete — re-targeting inquisitor.");
                cs.setCombatStep(10);
                break;
            }
            case 10: {
                log(clientId, "COMBAT[Vengeance] Step 10: click target_inquisitor (re-select).");
                missionProcessor.clickTemplate("target_inquisitor");
                cs.setCombatStep(11);
                break;
            }
            case 11: {
                log(clientId, "COMBAT[Vengeance] Step 11: press 'ad'.");
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
                    if (elapsed > 0 && elapsed % 30 == 0) {
                        log(clientId, "COMBAT[Vengeance] safe wait: " + elapsed + "s / 60s.");
                    }
                    ClientWindowInfo other = findOtherClient(clientId);
                    if (other != null) bringToForeground(other);
                    return;
                }
                log(clientId, "COMBAT[Vengeance] 60s wait complete — switching ammo.");
                cs.setCombatStep(13);
                break;
            }
            case 13: {
                log(clientId, "COMBAT[Vengeance] Step 13: right-click launcher (ammo menu).");
                missionProcessor.rightClickTemplate("launcher");
                cs.setCombatStep(14);
                break;
            }
            case 14: {
                log(clientId, "COMBAT[Vengeance] Step 14: click inf_damage (load infernal).");
                missionProcessor.clickTemplate("inf_damage");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(15);
                break;
            }
            case 15: {
                // SAFE WAIT: 10 seconds (ammo load)
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 10) return;
                log(clientId, "COMBAT[Vengeance] 10s wait complete — activating modules.");
                cs.setCombatStep(16);
                break;
            }
            case 16: {
                log(clientId, "COMBAT[Vengeance] Step 16: press 'ad'.");
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
                    ClientWindowInfo other = findOtherClient(clientId);
                    if (other != null) bringToForeground(other);
                    return;
                }
                log(clientId, "COMBAT[Vengeance] 30s wait complete — waiting for mission complete.");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(18);
                break;
            }
            case 18: {
                // SAFE WAIT: poll for set_destination (mission complete signal)
                if (missionProcessor.isSetDestinationVisible()) {
                    log(clientId, "COMBAT[Vengeance] set_destination detected — mission complete!");
                    stateManager.setState(clientId, ClientState.State.RETURNING);
                } else {
                    long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                    if (elapsed > 0 && elapsed % 30 == 0) {
                        log(clientId, "COMBAT[Vengeance] waiting for mission complete (" + elapsed + "s)...");
                    }
                    ClientWindowInfo other = findOtherClient(clientId);
                    if (other != null) bringToForeground(other);
                }
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
     *   2  → W+click 'target_enyo' (keep at range)
     *   3  → Keypress 's' (activate module)
     *   4  → SAFE WAIT 15s
     *   5  → Ctrl+click 'target_navitas' (lock first)
     *   6  → Ctrl+click 'target_navitas' (lock second)
     *   7  → Click 'target_navitas' (select as active target)
     *   8  → Keypress 'adc' (activate modules)
     *   9  → SAFE WAIT 120s
     *   10 → Ctrl+click 'target_enyo' (lock enyo)
     *   11 → Click 'target_enyo' (select as active target)
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
                log(clientId, "COMBAT[Enyo] Step 3: press 's' (activate modules).");
                missionProcessor.keyPress("s");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(4);
                break;
            }
            case 4: {
                // SAFE WAIT: 15 seconds
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 15) {
                    ClientWindowInfo other = findOtherClient(clientId);
                    if (other != null) bringToForeground(other);
                    return;
                }
                log(clientId, "COMBAT[Enyo] 15s wait complete — locking navitas targets.");
                cs.setCombatStep(5);
                break;
            }
            case 5: {
                // TODO: replace steps 5+6 with ctrlClickAllTemplate("target_navitas")
                //       once findAll is implemented — avoids re-locking the same target.
                log(clientId, "COMBAT[Enyo] Step 5: Ctrl+click target_navitas (lock first).");
                missionProcessor.ctrlClickTemplate("target_navitas");
                cs.setCombatStep(6);
                break;
            }
            case 6: {
                log(clientId, "COMBAT[Enyo] Step 6: Ctrl+click target_navitas (lock second).");
                missionProcessor.ctrlClickTemplate("target_navitas");
                cs.setCombatStep(7);
                break;
            }
            case 7: {
                log(clientId, "COMBAT[Enyo] Step 7: click target_navitas (select active).");
                missionProcessor.clickTemplate("target_navitas");
                cs.setCombatStep(8);
                break;
            }
            case 8: {
                log(clientId, "COMBAT[Enyo] Step 8: press 'adc' (activate modules).");
                missionProcessor.keyPress("adc");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(9);
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "COMBAT[Enyo] 120s safe wait — switching to: " + other.getClientId());
                    bringToForeground(other);
                }
                break;
            }
            case 9: {
                // SAFE WAIT: 120 seconds
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 120) {
                    if (elapsed > 0 && elapsed % 30 == 0) {
                        log(clientId, "COMBAT[Enyo] safe wait: " + elapsed + "s / 120s.");
                    }
                    ClientWindowInfo other = findOtherClient(clientId);
                    if (other != null) bringToForeground(other);
                    return;
                }
                log(clientId, "COMBAT[Enyo] 120s wait complete — locking enyo.");
                cs.setCombatStep(10);
                break;
            }
            case 10: {
                log(clientId, "COMBAT[Enyo] Step 10: Ctrl+click target_enyo (lock).");
                missionProcessor.ctrlClickTemplate("target_enyo");
                cs.setCombatStep(11);
                break;
            }
            case 11: {
                log(clientId, "COMBAT[Enyo] Step 11: click target_enyo (select active).");
                missionProcessor.clickTemplate("target_enyo");
                cs.setCombatStep(12);
                break;
            }
            case 12: {
                log(clientId, "COMBAT[Enyo] Step 12: press 'ad'.");
                missionProcessor.keyPress("ad");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(13);
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "COMBAT[Enyo] 60s safe wait — switching to: " + other.getClientId());
                    bringToForeground(other);
                }
                break;
            }
            case 13: {
                // SAFE WAIT: 60 seconds
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 60) {
                    if (elapsed > 0 && elapsed % 30 == 0) {
                        log(clientId, "COMBAT[Enyo] safe wait: " + elapsed + "s / 60s.");
                    }
                    ClientWindowInfo other = findOtherClient(clientId);
                    if (other != null) bringToForeground(other);
                    return;
                }
                log(clientId, "COMBAT[Enyo] 60s wait complete — waiting for mission complete.");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(14);
                break;
            }
            case 14: {
                // SAFE WAIT: poll for set_destination (mission complete signal)
                if (missionProcessor.isSetDestinationVisible()) {
                    log(clientId, "COMBAT[Enyo] set_destination detected — mission complete!");
                    stateManager.setState(clientId, ClientState.State.RETURNING);
                } else {
                    long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                    if (elapsed > 0 && elapsed % 30 == 0) {
                        log(clientId, "COMBAT[Enyo] waiting for mission complete (" + elapsed + "s)...");
                    }
                    ClientWindowInfo other = findOtherClient(clientId);
                    if (other != null) bringToForeground(other);
                }
                break;
            }
            default:
                log(clientId, "COMBAT[Enyo] unexpected step " + step + " — resetting.");
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
        missionProcessor.clickCompleteMission();
        missionProcessor.clickOk();
        stateManager.getState(clientId).setTravelPhase(ClientState.TravelPhase.NONE);
        stateManager.setState(clientId, ClientState.State.IDLE);
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
    //   5s+    : check warp_active every 20s; when gone → IDLE.
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
            } else if (elapsed >= 35) {
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "WARPING: 35s elapsed — switching focus to: " + other.getClientId());
                    bringToForeground(other);
                } else {
                    log(clientId, "WARPING: 35s elapsed — no other clients to switch to.");
                }
            }
            log(clientId, "still WARPING (" + elapsed + "s).");
        }
    }

    /**
     * Returns true if a WARPING client is due for a warp_active template check.
     * - Returns false during the 6s grace period.
     * - Returns true immediately after grace ends (first real check).
     * - Returns true every 20s after the first real check.
     *
     * Uses LocalDateTime.MIN.plusNanos(1) as a sentinel meaning "grace logged, no real check yet".
     */


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