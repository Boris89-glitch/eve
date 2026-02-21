package evebot;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.awt.Rectangle;
import java.util.HashMap;
import java.util.Map;

public class ClientManager {
    private Map<String, Rectangle> clientMissionAreas;
    private Map<String, ClientWindowInfo> clientWindows;
    private final Gson gson;

    public ClientManager() {
        this.clientMissionAreas = new HashMap<>();
        this.clientWindows = new HashMap<>();
        this.gson = new Gson();
        loadCalibratedCoordinates();
    }

    private void loadCalibratedCoordinates() {
        try {
            String content = new String(Files.readAllBytes(
                    Paths.get("config", "mission_coordinates.json")));
            JsonObject json = gson.fromJson(content, JsonObject.class);

            // Default coordinates if calibration file exists
            JsonObject missionArea = json.getAsJsonObject("mission_area");
            if (missionArea != null) {
                Rectangle defaultRect = new Rectangle(
                        missionArea.get("x").getAsInt(),
                        missionArea.get("y").getAsInt(),
                        missionArea.get("width").getAsInt(),
                        missionArea.get("height").getAsInt()
                );
                clientMissionAreas.put("default", defaultRect);
            }
        } catch (Exception e) {
            System.out.println("No calibration file found, using defaults");
            // Default fallback coordinates (adjust as needed)
            clientMissionAreas.put("default", new Rectangle(100, 100, 400, 300));
        }
    }

    public Rectangle getMissionAreaForClient(String clientId) {
        // Return client-specific or default mission area
        return clientMissionAreas.getOrDefault(clientId,
                clientMissionAreas.get("default"));
    }
}