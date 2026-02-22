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
        this.stateManager    = stateManager;
        this.missionProcessor = new MissionProcessor();
        this.missionConfig   = new MissionConfig();
        this.gson            = new Gson();
        loadCalibratedCoordinates();
    }

    // -----------------------------------------------------------------------
    // Coordinate loading
    // -----------------------------------------------------------------------

    private void loadCalibratedCoordinates() {
        // Try config/ directory first, then project root
        List<Path> candidates = List.of(
                Paths.get("ocr", "config", "mission_coordinates.json"),
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

    /**
     * Called repeatedly by the scheduler.  Skips all work when the bot is
     * paused so the hotkey toggle immediately halts activity.
     */
    public void monitorClients() {
        if (!stateManager.isBotRunning()) {
            return; // bot is paused — do nothing
        }

        // Discover / refresh EVE windows
        refreshClientWindows();

        if (clientWindows.isEmpty()) {
            System.out.println("[ClientManager] No EVE clients found on screen.");
            return;
        }

        // Drive state machine for each known client
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
                // Waiting for a mission offer to appear — poll for it
                handleIdle(clientId, window);
                break;

            case CHECKING_MISSIONS:
                // Mission screen is open — read and decide
                handleCheckingMissions(clientId, window);
                break;

            case ACCEPTED:
                // Mission was accepted — placeholder for undock / travel logic
                System.out.println("[" + clientId + "] Mission accepted — waiting for undock trigger (TODO).");
                // For now transition back to IDLE so we don't loop forever
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
    // IDLE handler — detect mission offer panel
    // -----------------------------------------------------------------------

    private void handleIdle(String clientId, ClientWindowInfo window) {
        // TODO: Use OCR or pixel-colour check to detect if a mission dialog is
        //       open in this client window.  For now we attempt an OCR read; if
        //       it returns a non-null mission we assume the dialog is open.

        //Rectangle missionArea = getMissionAreaForClient(clientId);
        Mission mission = missionProcessor.readMission(window);

        if (mission != null && mission.getTitle() != null
                && !mission.getTitle().equals("Unknown")) {
            System.out.println("[" + clientId + "] Mission dialog detected: " + mission.getTitle());
            stateManager.getState(clientId).setCurrentMission(mission);
            stateManager.setState(clientId, ClientState.State.CHECKING_MISSIONS);
        }
        // Otherwise stay IDLE silently
    }

    // -----------------------------------------------------------------------
    // CHECKING_MISSIONS handler — evaluate and announce decision
    // -----------------------------------------------------------------------

    private void handleCheckingMissions(String clientId, ClientWindowInfo window) {
        Mission mission = stateManager.getState(clientId).getCurrentMission();

        if (mission == null) {
            // Somehow we got here without a mission — re-read
            //Rectangle missionArea = getMissionAreaForClient(clientId);
            mission = missionProcessor.readMission(window);
            stateManager.getState(clientId).setCurrentMission(mission);
        }

        if (mission == null) {
            System.out.println("[" + clientId + "] No mission data available — returning to IDLE.");
            stateManager.setState(clientId, ClientState.State.IDLE);
            return;
        }

        MissionConfig.Decision decision = missionConfig.evaluate(mission);

        Rectangle windowRect = window.getBounds();

        if (decision == MissionConfig.Decision.ACCEPT) {
            // ---- ACCEPT ----
            System.out.println(">>> [" + clientId + "] ACTION: ACCEPT — " + mission.getTitle());
            missionProcessor.acceptMission(windowRect);  // currently prints coords
            stateManager.setState(clientId, ClientState.State.ACCEPTED);

        } else {
            // ---- DECLINE ----
            System.out.println(">>> [" + clientId + "] ACTION: DECLINE — " + mission.getTitle());
            missionProcessor.declineMission(windowRect); // currently prints coords
            stateManager.setState(clientId, ClientState.State.IDLE);
        }
    }

    // -----------------------------------------------------------------------
    // Window discovery
    // -----------------------------------------------------------------------

    /**
     * Refreshes the map of known EVE client windows.
     *
     * On Windows this would use JNA + EnumWindows to find all windows whose
     * title contains "EVE".  The stub below creates a single fake window at
     * the default screen position so the rest of the state machine can run
     * and be tested without a live EVE client.
     *
     * Replace the body of this method with real JNA window enumeration when
     * you are ready to run against actual clients.
     */
    private void refreshClientWindows() {
        // ----------------------------------------------------------------
        // STUB — replace with real JNA enumeration
        // ----------------------------------------------------------------
        if (clientWindows.isEmpty()) {
            Rectangle defaultArea = clientMissionAreas.getOrDefault("default",
                    new Rectangle(440, 92, 988, 336));

            ClientWindowInfo stub = new ClientWindowInfo(
                    "EVE - Character Name",   // window title
                    "ExeFile",                // window class
                    0L,                       // HWND (unknown in stub)
                    defaultArea.x,
                    defaultArea.y,
                    defaultArea.width,
                    defaultArea.height
            );
            clientWindows.put(stub.getClientId(), stub);
            System.out.println("[ClientManager] Registered stub EVE client: " + stub.getClientId());
        }
        // ----------------------------------------------------------------
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    public Rectangle getMissionAreaForClient(String clientId) {
        return clientMissionAreas.getOrDefault(clientId,
                clientMissionAreas.get("default"));
    }
}