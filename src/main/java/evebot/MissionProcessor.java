package evebot;

import evebot.models.Mission;
import java.awt.Rectangle;

/**
 * MissionProcessor is the single point of contact between the Java state
 * machine and the Python automation layer.
 *
 * All UI interactions (template matching + clicking) are delegated to
 * eve_automation.py via OCRBridge.  The Java side only calls named actions
 * and receives typed results — it never hard-codes pixel coordinates.
 *
 * Public API used by ClientManager:
 *
 *   // IDLE phase
 *   clickRequestMission()          — click "Request Mission" if visible
 *   Mission readMission(window)    — OCR the mission dialog area
 *
 *   // CHECKING_MISSIONS phase
 *   acceptMission()                — click Accept button
 *   clickTrack()                   — click Track button
 *   closeDialog()                  — click X (close active window)
 *   declineMission()               — click Decline button
 */
public class MissionProcessor {

    private final OCRBridge ocrBridge;

    public MissionProcessor() {
        this.ocrBridge = new OCRBridge();
    }

    // -----------------------------------------------------------------------
    // IDLE phase actions
    // -----------------------------------------------------------------------

    /**
     * Asks the Python automation layer to find and click the "Request Mission"
     * button if it is currently visible on screen.
     * Safe to call when the button is not present — it becomes a no-op.
     */
    public void clickRequestMission() {
        ocrBridge.clickTemplate("request_mission");
    }

    /**
     * OCR-reads the mission dialog area derived from the given window bounds.
     * Returns a populated {@link Mission} or {@code null} if no dialog is open
     * or the OCR produced no usable text.
     */
    public Mission readMission(ClientWindowInfo window) {
        Rectangle area = calculateMissionDialogRect(window.getBounds());
        return ocrBridge.readMission(area.x, area.y, area.width, area.height);
    }

    // -----------------------------------------------------------------------
    // CHECKING_MISSIONS phase actions
    // -----------------------------------------------------------------------

    /** Click the Accept button (template match). */
    public void acceptMission() {
        ocrBridge.clickTemplate("accept");
    }

    /** Click the Track button that appears after accepting. */
    public void clickTrack() {
        ocrBridge.clickTemplate("track");
    }

    /** Close the mission dialog via the X button. */
    public void closeDialog() {
        ocrBridge.clickTemplate("close");
    }

    /** Click the Decline button (template match). */
    public void declineMission() {
        ocrBridge.clickTemplate("decline");
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    /**
     * Derives the mission text area from the containing EVE window.
     * Adjust the multipliers here if the OCR region drifts after a UI resize.
     */
    private Rectangle calculateMissionDialogRect(Rectangle windowRect) {
        int x      = windowRect.x + (int)(windowRect.width  * 0.3);
        int y      = windowRect.y + (int)(windowRect.height * 0.2);
        int width  = (int)(windowRect.width  * 0.4);
        int height = (int)(windowRect.height * 0.6);
        return new Rectangle(x, y, width, height);
    }
}