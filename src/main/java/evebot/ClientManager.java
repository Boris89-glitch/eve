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
                    log(clientId, "stargate — E+clicking to jump.");
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
        long checkingElapsed = Duration.between(cs.getLastStateChange(), LocalDateTime.now()).getSeconds();
        if (checkingElapsed >= 35) {
            ClientWindowInfo other = findOtherClient(clientId);
            if (other != null) {
                log(clientId, "CHECKING_MISSIONS: 35s elapsed without accept — returning to IDLE and switching focus to " + other.getClientId());
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
    // IN_COMBAT — stand by; transition to RETURNING when set_destination appears
    //             (signals mission objective complete).
    //             Target-specific combat logic to be added here later.
    // -----------------------------------------------------------------------

    private void handleInCombat(String clientId, ClientWindowInfo window) {
        if (missionProcessor.isSetDestinationVisible()) {
            log(clientId, "IN_COMBAT: set_destination detected — mission complete, transitioning to RETURNING.");
            stateManager.setState(clientId, ClientState.State.RETURNING);
        } else {
            //log(clientId, "IN_COMBAT: standing by.");
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