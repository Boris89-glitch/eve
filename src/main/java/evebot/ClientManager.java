package evebot;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import evebot.models.ClientState;
import evebot.models.Mission;

import java.awt.Rectangle;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ClientManager discovers EVE Online client windows, tracks each one by a
 * stable clientId, and drives the per-client state machine during
 * monitorClients() calls (now every 1 second).
 *
 * State machine changes (v0.2):
 *   DECLINING   — new state: dialog is open on a declined mission, waiting to
 *                 re-request.  After requesting, go back to CHECKING_MISSIONS.
 *   ACCEPTED    — after clicking Accept:
 *                   1. Wait for the mission to appear as tracked in the journal
 *                   2. Only then close the dialog
 *                   3. Transition to REPAIRING
 *   REPAIRING   — open hangar, double-click correct ship, repair flow,
 *                 then transition to IN_COMBAT (or IDLE on error)
 */
public class ClientManager {

    // -----------------------------------------------------------------------
    // Dependencies
    // -----------------------------------------------------------------------

    private final StateManager    stateManager;
    private final MissionProcessor missionProcessor;
    private final MissionConfig    missionConfig;
    private final Gson             gson;

    // -----------------------------------------------------------------------
    // Window / area tracking
    // -----------------------------------------------------------------------

    /** Mission-panel coordinates per client (or "default"). */
    private final Map<String, Rectangle> clientMissionAreas = new ConcurrentHashMap<>();

    /** Known EVE windows currently on screen. */
    private final Map<String, ClientWindowInfo> clientWindows = new ConcurrentHashMap<>();

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

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
    // Core monitor loop  (called by the scheduler every 1 second)
    // -----------------------------------------------------------------------

    public void monitorClients() {
        if (!stateManager.isBotRunning()) {
            return;
        }

        refreshClientWindows();

        if (clientWindows.isEmpty()) {
            log("[ClientManager] No EVE clients found on screen.");
            return;
        }

        clientWindows.forEach(this::processClient);
    }

    // -----------------------------------------------------------------------
    // Per-client state machine
    // -----------------------------------------------------------------------

    private void processClient(String clientId, ClientWindowInfo window) {
        ClientState.State state = stateManager.getStateEnum(clientId);

        switch (state) {

            case IDLE:
                handleIdle(clientId, window);
                break;

            case CHECKING_MISSIONS:
                handleCheckingMissions(clientId, window);
                break;

            case DECLINING:
                // Mission was declined — request a new one immediately
                handleDeclining(clientId, window);
                break;

            case ACCEPTED:
                // Mission accepted — wait for journal tracking, then close dialog
                handleAccepted(clientId, window);
                break;

            case REPAIRING:
                // Open hangar, select ship, run repair flow
                handleRepairing(clientId, window);
                break;

            case IN_COMBAT:
                log(clientId, "In combat — TODO: monitor combat state.");
                break;

            case RETURNING:
                log(clientId, "Returning — TODO: monitor warp/dock.");
                break;

            case COMPLETING:
                log(clientId, "Completing mission — TODO: turn-in click.");
                stateManager.setState(clientId, ClientState.State.IDLE);
                break;

            default:
                System.out.println("[" + clientId + "] Unknown state: " + state);
        }
    }

    // -----------------------------------------------------------------------
    // IDLE — detect mission offer panel
    // -----------------------------------------------------------------------

    private void handleIdle(String clientId, ClientWindowInfo window) {
        Rectangle missionArea = getMissionAreaForClient(clientId);
        Mission mission = missionProcessor.readMission(missionArea);

        if (mission != null && mission.getTitle() != null
                && !mission.getTitle().equals("Unknown")) {
            System.out.println("[" + clientId + "] Mission dialog detected: " + mission.getTitle());
            stateManager.getState(clientId).setCurrentMission(mission);
            stateManager.setState(clientId, ClientState.State.CHECKING_MISSIONS);
        }
    }

    // -----------------------------------------------------------------------
    // CHECKING_MISSIONS — evaluate and decide
    // -----------------------------------------------------------------------

    private void handleCheckingMissions(String clientId, ClientWindowInfo window) {
        Mission mission = stateManager.getState(clientId).getCurrentMission();

        if (mission == null) {
            Rectangle missionArea = getMissionAreaForClient(clientId);
            mission = missionProcessor.readMission(missionArea);
            stateManager.getState(clientId).setCurrentMission(mission);
        }

        if (mission == null) {
            log(clientId, "No mission data — returning to IDLE.");
            stateManager.setState(clientId, ClientState.State.IDLE);
            return;
        }

        MissionConfig.Decision decision = missionConfig.evaluate(mission);

        if (decision == MissionConfig.Decision.ACCEPT) {
            // ---- ACCEPT ----
            System.out.println(">>> [" + clientId + "] ACTION: ACCEPT — " + mission.getTitle());
            missionProcessor.clickAcceptButton(); // template match — full screen
            // Do NOT close the dialog yet — wait for tracking in ACCEPTED state
            stateManager.setState(clientId, ClientState.State.ACCEPTED);

        } else {
            // ---- DECLINE ----
            System.out.println(">>> [" + clientId + "] ACTION: DECLINE — " + mission.getTitle());
            missionProcessor.clickDeclineButton(); // template match — full screen
            // Transition to DECLINING so the next tick will request a new mission
            stateManager.setState(clientId, ClientState.State.DECLINING);
        }
    }

    // -----------------------------------------------------------------------
    // DECLINING — wait for old dialog to close, re-request, wait for new offer
    // -----------------------------------------------------------------------

    private void handleDeclining(String clientId, ClientWindowInfo window) {
        log(clientId, "DECLINING — waiting for offer dialog to close...");

        // Step 1: confirm old dialog is gone (Python waits up to 5s internally)
        if (!missionProcessor.waitForDialogClosed()) {
            log(clientId, "Dialog still visible — will retry next tick.");
            return;
        }

        // Step 2: ask agent for a new mission
        log(clientId, "Dialog closed — requesting new mission.");
        missionProcessor.clickRequestMission();

        // Step 3: wait for new offer dialog (Python waits up to 8s internally)
        if (!missionProcessor.waitForNewOffer()) {
            log(clientId, "New offer not visible yet — will retry next tick.");
            return;
        }

        // Step 4: OCR and immediately go to CHECKING_MISSIONS regardless of title.
        // Even "Unknown" title is safe — no accept rule will match it, so it gets declined.
        stateManager.getState(clientId).setCurrentMission(null);
        Rectangle missionArea = getMissionAreaForClient(clientId);
        Mission newMission = missionProcessor.readMission(missionArea);
        String title = (newMission != null && newMission.getTitle() != null)
                ? newMission.getTitle() : "Unknown";
        log(clientId, "New offer: '" + title + "' — moving to rule evaluation.");
        stateManager.getState(clientId).setCurrentMission(newMission);
        stateManager.setState(clientId, ClientState.State.CHECKING_MISSIONS);
    }

    // -----------------------------------------------------------------------
    // ACCEPTED — wait for journal tracking, then close dialog
    // -----------------------------------------------------------------------

    /**
     * After clicking Accept:
     *   1. Click Track (registers in journal) — safe no-op if already tracked
     *   2. Close the dialog
     *   3. Transition to REPAIRING
     */
    private void handleAccepted(String clientId, ClientWindowInfo window) {
        // Track button only appears on the first accept — skip it on subsequent ones
        // to avoid a 6-second wait scanning for a button that won't be there.
        if (!stateManager.isTrackAttempted()) {
            log(clientId, "First accept — clicking Track (will skip on future accepts).");
            missionProcessor.clickTrack();
            stateManager.setTrackAttempted(true);
        } else {
            log(clientId, "Track already attempted — skipping.");
        }
        missionProcessor.closeMissionDialog();
        stateManager.setState(clientId, ClientState.State.REPAIRING);
    }
    // -----------------------------------------------------------------------
    // REPAIRING — hangar + ship selection + repair
    // -----------------------------------------------------------------------

    private void handleRepairing(String clientId, ClientWindowInfo window) {
        Mission mission = stateManager.getState(clientId).getCurrentMission();
        System.out.println("[" + clientId + "] Starting repair + undock flow for: "
                + (mission != null ? mission.getTitle() : "unknown mission"));

        boolean success = missionProcessor.performPostAcceptFlow(window.getBounds(), mission);

        if (success) {
            log(clientId, "Repaired and undocking — transitioning to IN_COMBAT.");
            stateManager.setState(clientId, ClientState.State.IN_COMBAT);
        } else {
            System.err.println("[" + clientId + "] Repair/undock flow failed — returning to IDLE.");
            stateManager.setState(clientId, ClientState.State.IDLE);
        }
    }

    // -----------------------------------------------------------------------
    // Window discovery
    // -----------------------------------------------------------------------

    private void refreshClientWindows() {
        // STUB — replace with real JNA enumeration when running against live clients
        if (clientWindows.isEmpty()) {
            Rectangle defaultArea = clientMissionAreas.getOrDefault("default",
                    new Rectangle(440, 92, 988, 336));

            ClientWindowInfo stub = new ClientWindowInfo(
                    "EVE - Character Name",
                    "ExeFile",
                    0L,
                    defaultArea.x,
                    defaultArea.y,
                    defaultArea.width,
                    defaultArea.height
            );
            clientWindows.put(stub.getClientId(), stub);
            System.out.println("[ClientManager] Registered stub EVE client: " + stub.getClientId());
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

    /** Log with timestamp and client prefix. */
    private void log(String clientId, String msg) {
        System.out.printf("[%s] [%s] %s%n", LocalDateTime.now().format(TS), clientId, msg);
    }

    /** Log with timestamp, no client prefix. */
    private void log(String msg) {
        System.out.printf("[%s] %s%n", LocalDateTime.now().format(TS), msg);
    }
}