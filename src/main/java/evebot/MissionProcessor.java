// src/main/java/evebot/MissionProcessor.java
package evebot;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import evebot.models.Mission;

import java.awt.Rectangle;
import java.nio.file.*;
import java.util.List;

public class MissionProcessor {
    private final OCRBridge ocrBridge;
    private final Rectangle missionRect;

    private static final Rectangle FALLBACK_RECT = new Rectangle(440, 92, 988, 336);

    public MissionProcessor() {
        this.ocrBridge   = new OCRBridge();
        this.missionRect = loadCalibratedRect();
    }

    private Rectangle loadCalibratedRect() {
        List<Path> candidates = List.of(
                Paths.get("ocr", "config", "mission_coordinates.json"),
                Paths.get("config", "mission_coordinates.json"),
                Paths.get("mission_coordinates.json")
        );

        Gson gson = new Gson();
        for (Path path : candidates) {
            if (Files.exists(path)) {
                try {
                    String content = new String(Files.readAllBytes(path));
                    JsonObject json = gson.fromJson(content, JsonObject.class);
                    JsonObject area = json.getAsJsonObject("mission_area");
                    if (area != null) {
                        Rectangle rect = new Rectangle(
                                area.get("x").getAsInt(),
                                area.get("y").getAsInt(),
                                area.get("width").getAsInt(),
                                area.get("height").getAsInt()
                        );
                        System.out.println("[MissionProcessor] Loaded coordinates from " + path + ": " + rect);
                        return rect;
                    }
                } catch (Exception e) {
                    System.err.println("[MissionProcessor] Error reading coordinates: " + e.getMessage());
                }
            }
        }

        System.out.println("[MissionProcessor] No coordinate file found — using fallback " + FALLBACK_RECT);
        return FALLBACK_RECT;
    }
    //TODO: clientWindow isn't used?
    public Mission readMission(ClientWindowInfo clientWindow) {
        System.out.printf("[MissionProcessor] Reading mission at (%d,%d) %dx%d%n",
                missionRect.x, missionRect.y, missionRect.width, missionRect.height);

        return ocrBridge.readMission(
                missionRect.x, missionRect.y,
                missionRect.width, missionRect.height
        );
    }

    public boolean evaluateMission(Mission mission) {
        if (mission == null) return false;

        // 1. Filter by title type
        String title = mission.getTitle().toLowerCase();
        boolean validType = title.contains("anomic") ||
                title.contains("recon") ||
                title.contains("annihilation");
        if (!validType) {
            System.out.println("Reject: title not matching criteria");
            return false;
        }

        // 2. Filter by location (too-far systems)
        String location = mission.getLocation().toLowerCase();
        if (location.contains("j0c-") || location.contains("p33-") || (location.contains("-t2i"))) {
            System.out.println("Reject: system too far");
            return false;
        }

        // 3. Filter burner missions by target ship
        if (mission.getType() == Mission.MissionType.BURNER) {
            String target = mission.getTarget() != null
                    ? mission.getTarget().toLowerCase() : "";
            if (target.contains("serpentis") || target.contains("jaguar")) {
                System.out.println("Reject: unwanted burner ship – " + mission.getTarget());
                return false;
            }
        }

        System.out.println("Accepting mission: " + mission.getTitle());
        return true;
    }

    public void acceptMission(Rectangle windowRect) {
        clickRelative(windowRect, 0.6, 0.8);
    }

    public void declineMission(Rectangle windowRect) {
        clickRelative(windowRect, 0.4, 0.8);
    }

    private void clickRelative(Rectangle windowRect, double relX, double relY) {
        int x = windowRect.x + (int) (windowRect.width  * relX);
        int y = windowRect.y + (int) (windowRect.height * relY);
        // TODO: Implement actual mouse click
        System.out.println("Clicking at: " + x + ", " + y);
    }
}