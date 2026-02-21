// src/main/java/evebot/MissionProcessor.java
package evebot;

import evebot.models.Mission;
import java.awt.Rectangle;

public class MissionProcessor {
    private OCRBridge ocrBridge;

    public MissionProcessor() {
        this.ocrBridge = new OCRBridge();
    }

    public Mission readMission(Rectangle windowRect) {
        // Calculate mission dialog position within window
        Rectangle missionRect = calculateMissionDialogRect(windowRect);

        // Use the new combined method that returns a Mission object directly
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
                title.contains("anomicteam") ||
                title.contains("recon") ||
                title.contains("annihilation");
        if (!validType) {
            System.out.println("Reject: title not matching criteria");
            return false;
        }

        // 2. Filter by location (too far systems)
        String location = mission.getLocation().toLowerCase();
        if (location.contains("j0c-") || location.contains("p33-")) {
            System.out.println("Reject: system too far");
            return false;
        }

        // 3. Filter by objective (burner ship type)
        String objective = mission.getObjective().toLowerCase();
        String target = mission.getTarget().toLowerCase();

        // Decline Serpentis or Jaguar burner missions
        if (mission.getType() == Mission.MissionType.BURNER) {
            if (target.contains("serpentis") || target.contains("jaguar")) {
                System.out.println("Reject: unwanted burner ship");
                return false;
            }
        }

        // 4. Accept if all checks pass
        System.out.println("Accepting mission: " + mission.getTitle());
        return true;
    }

    public void acceptMission(Rectangle windowRect) {
        // Click accept button (adjust coordinates as needed)
        clickRelative(windowRect, 0.6, 0.8);
    }

    public void declineMission(Rectangle windowRect) {
        // Click decline button (adjust coordinates as needed)
        clickRelative(windowRect, 0.4, 0.8);
    }

    private void clickRelative(Rectangle windowRect, double relX, double relY) {
        int x = windowRect.x + (int)(windowRect.width * relX);
        int y = windowRect.y + (int)(windowRect.height * relY);

        // TODO: Implement actual mouse click
        System.out.println("Clicking at: " + x + ", " + y);
    }

    private Rectangle calculateMissionDialogRect(Rectangle windowRect) {
        // Calculate mission dialog area (adjust these percentages as needed)
        int missionX = windowRect.x + (int)(windowRect.width * 0.3);
        int missionY = windowRect.y + (int)(windowRect.height * 0.2);
        int missionWidth = (int)(windowRect.width * 0.4);
        int missionHeight = (int)(windowRect.height * 0.6);

        return new Rectangle(missionX, missionY, missionWidth, missionHeight);
    }
}