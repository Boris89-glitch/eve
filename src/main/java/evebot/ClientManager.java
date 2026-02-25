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
 */
public class ClientManager {

    // -----------------------------------------------------------------------
    // Dependencies
    // -----------------------------------------------------------------------

    private final StateManager     stateManager;
    private final MissionProcessor missionProcessor;
    private final MissionConfig    missionConfig;
    private final Gson             gson;

    // -----------------------------------------------------------------------
    // Window / area tracking
    // -----------------------------------------------------------------------

    private final Map<String, Rectangle>        clientMissionAreas = new ConcurrentHashMap<>();
    private final Map<String, ClientWindowInfo> clientWindows      = new ConcurrentHashMap<>();

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
        if (!stateManager.isBotRunning()) return;

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
            case IDLE:              handleIdle(clientId, window);             break;
            case CHECKING_MISSIONS: handleCheckingMissions(clientId, window); break;
            case DECLINING:         handleDeclining(clientId, window);        break;
            case ACCEPTED:          handleAccepted(clientId, window);         break;
            case REPAIRING:         handleRepairing(clientId, window);        break;
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
    // IDLE — open agent dialog if needed, then move to CHECKING_MISSIONS
    //
    // If no mission window is visible, double-click the agent icon to open
    // the agent dialog first, then wait briefly before OCR-reading.
    // -----------------------------------------------------------------------

    private void handleIdle(String clientId, ClientWindowInfo window) {
        Rectangle missionArea = getMissionAreaForClient(clientId);
        Mission mission = missionProcessor.readMission(missionArea);

        if (mission != null && mission.getTitle() != null
                && !mission.getTitle().equals("Unknown")) {
            // Mission dialog already open — jump straight to evaluation
            log(clientId, "Mission dialog detected: " + mission.getTitle());
            stateManager.getState(clientId).setCurrentMission(mission);
            stateManager.setState(clientId, ClientState.State.CHECKING_MISSIONS);
        } else {
            // No mission window visible — double-click agent icon to open the dialog,
            // then transition to CHECKING_MISSIONS so the next tick reads it.
            log(clientId, "No mission window visible — double-clicking agent icon to open dialog.");
            missionProcessor.doubleClickAgentIcon();
            // Clear any stale mission and let CHECKING_MISSIONS do the OCR read
            stateManager.getState(clientId).setCurrentMission(null);
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
            // Dialog may not have opened yet — go back to IDLE so it tries
            // the agent icon double-click again on the next tick.
            log(clientId, "No mission data yet — returning to IDLE to retry agent icon.");
            stateManager.setState(clientId, ClientState.State.IDLE);
            return;
        }

        MissionConfig.Decision decision = missionConfig.evaluate(mission);

        if (decision == MissionConfig.Decision.ACCEPT) {
            System.out.println(">>> [" + clientId + "] ACTION: ACCEPT — " + mission.getTitle());
            missionProcessor.clickAcceptButton();
            stateManager.setState(clientId, ClientState.State.ACCEPTED);
        } else {
            System.out.println(">>> [" + clientId + "] ACTION: DECLINE — " + mission.getTitle());
            missionProcessor.clickDeclineButton();
            stateManager.setState(clientId, ClientState.State.DECLINING);
        }
    }

    // -----------------------------------------------------------------------
    // DECLINING — wait for old dialog to close, re-request, wait for new offer
    // -----------------------------------------------------------------------

    private void handleDeclining(String clientId, ClientWindowInfo window) {
        log(clientId, "DECLINING — waiting for offer dialog to close...");

        if (!missionProcessor.waitForDialogClosed()) {
            log(clientId, "Dialog still visible — will retry next tick.");
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
        log(clientId, "New offer: '" + title + "' — moving to rule evaluation.");
        stateManager.getState(clientId).setCurrentMission(newMission);
        stateManager.setState(clientId, ClientState.State.CHECKING_MISSIONS);
    }

    // -----------------------------------------------------------------------
    // ACCEPTED — click Track (first time only), close dialog, go to REPAIRING
    // -----------------------------------------------------------------------

    private void handleAccepted(String clientId, ClientWindowInfo window) {
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
    // REPAIRING — template-based ship repair + activation, then undock
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

    private void log(String clientId, String msg) {
        System.out.printf("[%s] [%s] %s%n", LocalDateTime.now().format(TS), clientId, msg);
    }

    private void log(String msg) {
        System.out.printf("[%s] %s%n", LocalDateTime.now().format(TS), msg);
    }
}