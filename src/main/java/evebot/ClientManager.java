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
 * monitorClients() calls (every 1 second).
 */
public class ClientManager {

    private final StateManager     stateManager;
    private final MissionProcessor missionProcessor;
    private final MissionConfig    missionConfig;
    private final Gson             gson;

    private final Map<String, Rectangle>        clientMissionAreas = new ConcurrentHashMap<>();
    private final Map<String, ClientWindowInfo> clientWindows      = new ConcurrentHashMap<>();
    private final Map<String, Integer>          agentRetries       = new ConcurrentHashMap<>();

    private static final int MAX_AGENT_RETRIES = 3;

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
            case TRAVEL:
                // Waiting to arrive at mission site — nothing to do yet.
                // TODO: detect arrival (e.g. overview enemies visible) → IN_COMBAT
                log(clientId, "Travelling to mission site — waiting for arrival.");
                break;
            case IN_COMBAT:
                // TODO: monitor combat state → RETURNING when site clear
                log(clientId, "In combat.");
                break;
            case RETURNING:
                // TODO: monitor warp/dock → COMPLETING
                log(clientId, "Returning to station.");
                break;
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
        // Single Python call for all dialog state detection — replaces the old
        // chain of 3-6 sequential waitForTemplate calls (was ~18s overhead).
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
        Mission mission = stateManager.getState(clientId).getCurrentMission();

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
    // REPAIRING — repair + activate ship, then undock → TRAVEL
    // -----------------------------------------------------------------------

    private void handleRepairing(String clientId, ClientWindowInfo window) {
        Mission mission = stateManager.getState(clientId).getCurrentMission();
        log(clientId, "Starting repair + undock flow for: "
                + (mission != null ? mission.getTitle() : "unknown mission"));

        boolean success = missionProcessor.performPostAcceptFlow(window.getBounds(), mission);

        if (success) {
            log(clientId, "Undocked — transitioning to TRAVEL.");
            stateManager.setState(clientId, ClientState.State.TRAVEL);
        } else {
            System.err.println("[" + clientId + "] Repair/undock flow failed — returning to IDLE.");
            stateManager.setState(clientId, ClientState.State.IDLE);
        }
    }

    // -----------------------------------------------------------------------
    // COMPLETING — click turn-in button, then return to IDLE
    // -----------------------------------------------------------------------

    private void handleCompleting(String clientId) {
        log(clientId, "Clicking Complete Mission (turn-in).");
        missionProcessor.clickCompleteMission();
        stateManager.setState(clientId, ClientState.State.IDLE);
    }

    // -----------------------------------------------------------------------
    // Window discovery
    // -----------------------------------------------------------------------

    private void refreshClientWindows() {
        if (clientWindows.isEmpty()) {
            Rectangle defaultArea = clientMissionAreas.getOrDefault("default",
                    new Rectangle(440, 92, 988, 336));
            ClientWindowInfo stub = new ClientWindowInfo(
                    "EVE - Character Name", "ExeFile", 0L,
                    defaultArea.x, defaultArea.y,
                    defaultArea.width, defaultArea.height
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