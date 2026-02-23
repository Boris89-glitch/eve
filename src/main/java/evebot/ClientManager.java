package evebot;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import evebot.models.ClientState;
import evebot.models.Mission;

import java.awt.Rectangle;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ClientManager discovers EVE Online client windows, tracks each one by a
 * stable clientId, and drives the per-client state machine during
 * monitorClients() calls.
 *
 * Mission flow:
 *   IDLE
 *     └─► Clicks "Request Mission" button if visible (template match).
 *         Then OCR-reads the dialog. If a mission is found → CHECKING_MISSIONS.
 *
 *   CHECKING_MISSIONS
 *     └─► Evaluates accept/decline rules.
 *         ACCEPT  → Accept → Track → Close → ACCEPTED
 *         DECLINE → Decline → IDLE
 */
public class ClientManager {

    // -----------------------------------------------------------------------
    // Dependencies
    // -----------------------------------------------------------------------

    private final StateManager stateManager;
    private final MissionProcessor missionProcessor;
    private final MissionConfig missionConfig;
    private final Gson gson;

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

        System.out.println("[ClientManager] No coordinate file found — using hardcoded defaults.");
        clientMissionAreas.put("default", new Rectangle(440, 92, 988, 336));
    }

    // -----------------------------------------------------------------------
    // Core monitor loop  (called by the scheduler every 2 seconds)
    // -----------------------------------------------------------------------

    public void monitorClients() {
        if (!stateManager.isBotRunning()) {
            return;
        }

        refreshClientWindows();

        if (clientWindows.isEmpty()) {
            System.out.println("[ClientManager] No EVE clients found on screen.");
            return;
        }

        clientWindows.forEach((clientId, windowInfo) ->
                processClient(clientId, windowInfo));
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

            case ACCEPTED:
                // TODO: ship selection + undock (Phase 2)
                System.out.println("[" + clientId + "] Mission accepted — waiting for undock trigger (TODO).");
                stateManager.setState(clientId, ClientState.State.IDLE);
                break;

            case IN_COMBAT:
                System.out.println("[" + clientId + "] Client is in combat (TODO: monitor combat state).");
                break;

            case RETURNING:
                System.out.println("[" + clientId + "] Client is returning (TODO: monitor warp/dock).");
                break;

            case COMPLETING:
                System.out.println("[" + clientId + "] Completing mission (TODO: turn-in click).");
                stateManager.setState(clientId, ClientState.State.IDLE);
                break;

            default:
                System.out.println("[" + clientId + "] Unknown state: " + state);
        }
    }

    // -----------------------------------------------------------------------
    // IDLE handler
    // -----------------------------------------------------------------------

    /**
     * Two-stage idle handler:
     *
     *  1. Click "Request Mission" button if it's visible on screen (no-op when
     *     the dialog is already open or the button isn't there yet).
     *
     *  2. OCR-read the mission dialog area. If a mission is found store it and
     *     advance to CHECKING_MISSIONS. Otherwise stay IDLE quietly.
     */
    private void handleIdle(String clientId, ClientWindowInfo window) {
        // Stage 1 — click Request Mission button if present (template match)
        missionProcessor.clickRequestMission();

        // Stage 2 — OCR read; passes the full ClientWindowInfo so
        // MissionProcessor can derive the correct screen region internally
        Mission mission = missionProcessor.readMission(window);

        if (mission != null) {
            String title = mission.getTitle() != null ? mission.getTitle() : "Unknown";
            System.out.println("[" + clientId + "] Mission dialog detected: " + title);
            stateManager.getState(clientId).setCurrentMission(mission);
            stateManager.setState(clientId, ClientState.State.CHECKING_MISSIONS);
        }
        // Otherwise stay IDLE silently
    }

    // -----------------------------------------------------------------------
    // CHECKING_MISSIONS handler
    // -----------------------------------------------------------------------

    /**
     * Evaluates the stored mission against the rule config and executes the
     * appropriate click action via the automation layer (template matching).
     *
     * ACCEPT:  Accept → Track → Close dialog → state ACCEPTED
     * DECLINE: Decline → state IDLE
     */
    private void handleCheckingMissions(String clientId, ClientWindowInfo window) {
        Mission mission = stateManager.getState(clientId).getCurrentMission();

        if (mission == null) {
            // Re-read in case we arrived here without a stored mission
            mission = missionProcessor.readMission(window);
            stateManager.getState(clientId).setCurrentMission(mission);
        }

        if (mission == null) {
            System.out.println("[" + clientId + "] No mission data available — returning to IDLE.");
            stateManager.setState(clientId, ClientState.State.IDLE);
            return;
        }

        MissionConfig.Decision decision = missionConfig.evaluate(mission);

        if (decision == MissionConfig.Decision.ACCEPT) {
            System.out.println(">>> [" + clientId + "] ACTION: ACCEPT — " + mission.getTitle());
            missionProcessor.acceptMission();  // template click: Accept button
            missionProcessor.clickTrack();     // template click: Track button
            missionProcessor.closeDialog();    // template click: X button
            stateManager.setState(clientId, ClientState.State.ACCEPTED);

        } else {
            System.out.println(">>> [" + clientId + "] ACTION: DECLINE — " + mission.getTitle());
            missionProcessor.declineMission(); // template click: Decline button
            stateManager.setState(clientId, ClientState.State.IDLE);
        }
    }

    // -----------------------------------------------------------------------
    // Window discovery
    // -----------------------------------------------------------------------

    /**
     * Stub — replace with real JNA EnumWindows enumeration when running
     * against live EVE clients.
     */
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
}