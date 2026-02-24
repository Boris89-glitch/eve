package evebot;

import evebot.models.Mission;

import java.awt.*;
import java.awt.event.InputEvent;

/**
 * MissionProcessor handles all UI interactions with EVE.
 *
 * BUTTON CLICKS — always use template matching via OCRBridge.clickTemplate().
 * OpenCV scans the full screen for the button image and clicks its centre.
 * Never hardcode pixel coordinates for buttons — the UI moves around.
 *
 * OCR READS — use ocrBridge.readMission(x, y, w, h) with a calibrated region
 * (mission dialog text area, hangar list area, etc.).
 *
 * Template keys (must match filenames in /templates/ and TEMPLATE_FILES in
 * eve_automation.py):
 *
 *   Mission dialog:
 *     "accept"               — Accept button
 *     "decline"              — Decline button
 *     "request_mission"      — Request Mission button (agent dialog)
 *     "track"                — Track button (appears after accepting)
 *     "close_active_window"  — X button to close the dialog
 *
 *   Hangar / repair:
 *     "ship_hangar"          — Ship Hangar sidebar icon
 *     "get_repair_quote"     — Context menu item (right-click active ship)
 *     "repair"               — Repair / Repair All button in repair dialog
 *     "repair_yes"           — Yes / confirm repair button
 *
 *   Undock:
 *     "undock"               — Undock button (station panel)
 */
public class MissionProcessor {

    private final OCRBridge ocrBridge;
    private Robot robot;

    // Pause after each click so EVE has time to react before the next action.
    private static final int POST_CLICK_DELAY_MS = 600;
    private static final int DOUBLE_CLICK_GAP_MS =  80;

    // Hangar list panel bounds — as fractions of the EVE client window.
    // Tune these if your hangar panel is positioned differently.
    private static final double HANGAR_LEFT   = 0.00;
    private static final double HANGAR_TOP    = 0.30;
    private static final double HANGAR_WIDTH  = 0.25;
    private static final double HANGAR_HEIGHT = 0.50;

    // -----------------------------------------------------------------------

    public MissionProcessor() {
        this.ocrBridge = new OCRBridge();
        try {
            this.robot = new Robot();
            this.robot.setAutoDelay(0); // we control timing explicitly
        } catch (AWTException e) {
            System.err.println("[MissionProcessor] Cannot create Robot: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // OCR reads
    // -----------------------------------------------------------------------

    /**
     * OCR-read the mission dialog text area.
     * Pass the calibrated region covering the scrollable text of the mission
     * offer window (set via calibrate_mission_area.py).
     */
    public Mission readMission(Rectangle missionArea) {
        return ocrBridge.readMission(
                missionArea.x, missionArea.y,
                missionArea.width, missionArea.height);
    }

    // -----------------------------------------------------------------------
    // Mission dialog — template-matched clicks
    // -----------------------------------------------------------------------

    /** Click the "Request Mission" button in the agent dialog. */
    public void clickRequestMission() {
        log("Clicking REQUEST MISSION (template)");
        ocrBridge.clickTemplate("request_mission");
        sleep(POST_CLICK_DELAY_MS);
    }

    /**
     * Wait up to 5 seconds for the mission offer dialog to close
     * (i.e. for the accept/decline buttons to disappear from screen).
     * Returns true if the dialog is gone, false if it timed out.
     */
    public boolean waitForDialogClosed() {
        log("Waiting for offer dialog to close...");
        boolean gone = ocrBridge.waitForTemplateGone("decline", 5);
        if (gone) log("Dialog closed.");
        else      log("Dialog still visible after 5s.");
        return gone;
    }

    /**
     * Wait up to 8 seconds for a new mission offer dialog to appear
     * (i.e. for accept or decline buttons to become visible again).
     * Returns true if a new offer is showing.
     */
    public boolean waitForNewOffer() {
        log("Waiting for new mission offer...");
        boolean visible = ocrBridge.waitForTemplate("accept", 8);
        if (!visible) visible = ocrBridge.waitForTemplate("decline", 3);
        if (visible) log("New offer dialog visible.");
        else         log("No new offer appeared within timeout.");
        return visible;
    }

    /** Click the Accept button on the mission offer dialog. */
    public void clickAcceptButton() {
        log("Clicking ACCEPT (template)");
        ocrBridge.clickTemplate("accept");
        sleep(POST_CLICK_DELAY_MS);
    }

    /** Click the Decline button on the mission offer dialog. */
    public void clickDeclineButton() {
        log("Clicking DECLINE (template)");
        ocrBridge.clickTemplate("decline");
        sleep(POST_CLICK_DELAY_MS);
    }

    /**
     * Click the Track button that appears after accepting.
     * Safe to call if the button is absent — OCRBridge logs a warning and
     * returns cleanly without throwing.
     */
    public void clickTrack() {
        log("Clicking TRACK (template) — if present");
        ocrBridge.clickTemplate("track");
        sleep(POST_CLICK_DELAY_MS);
    }

    /**
     * Close the mission dialog via the X button.
     * Only call this AFTER the mission is confirmed accepted and tracked.
     */
    public void closeMissionDialog() {
        log("Closing mission dialog (template: close_active_window)");
        ocrBridge.clickTemplate("close_active_window");
        sleep(POST_CLICK_DELAY_MS);
    }

    // -----------------------------------------------------------------------
    // Post-accept flow: hangar → ship → repair → undock
    // -----------------------------------------------------------------------

    /**
     * Full post-accept sequence:
     *   1. Click Ship Hangar sidebar icon (template)
     *   2. OCR-scan hangar list, double-click matching ship row
     *   3. Right-click active ship icon → Get Repair Quote (template)
     *   4. Repair All (template) → Yes/confirm (template)
     *   5. Click Undock (template)
     *
     * @param windowRect  Full EVE client window bounds — used only for the
     *                    hangar list OCR region; button clicks use full-screen
     *                    template matching and ignore this.
     * @param mission     The accepted mission; provides ship target name.
     * @return true if the sequence completed, false on critical failure
     */
    public boolean performPostAcceptFlow(Rectangle windowRect, Mission mission) {
        log("=== Starting post-accept flow ===");

        // 1 — Open Ship Hangar
        log("Opening Ship Hangar (template: ship_hangar)");
        ocrBridge.clickTemplate("ship_hangar");
        sleep(1200); // wait for panel to load

        // 2 — Select the correct ship in the hangar list
        boolean shipSelected = selectShipInHangar(windowRect, mission);
        if (!shipSelected) {
            System.err.println("[MissionProcessor] Could not select target ship — aborting flow.");
            return false;
        }
        sleep(1000);

        // 3-4 — Repair
        repairActiveShip();
        sleep(1500); // wait for repair transaction to complete

        // 5 — Undock
        clickUndock();

        log("=== Post-accept flow complete — undocking ===");
        return true;
    }

    // -----------------------------------------------------------------------
    // Hangar ship selection (OCR + double-click by pixel row)
    // -----------------------------------------------------------------------

    /**
     * OCR the hangar list panel and double-click the row whose text contains
     * the target ship name from the mission.
     *
     * This is the one place we must use pixel coordinates because the hangar
     * list is a scrollable grid of text rows with no individual button images
     * to template-match.  The OCR region is derived from HANGAR_* fractions
     * of the EVE window — tune those constants if your hangar panel is
     * positioned differently.
     *
     * Falls back to double-clicking the top of the list if OCR misses the name.
     */
    private boolean selectShipInHangar(Rectangle windowRect, Mission mission) {
        String target = resolveShipTarget(mission);

        Rectangle hangarArea = new Rectangle(
                windowRect.x + (int)(windowRect.width  * HANGAR_LEFT),
                windowRect.y + (int)(windowRect.height * HANGAR_TOP),
                (int)(windowRect.width  * HANGAR_WIDTH),
                (int)(windowRect.height * HANGAR_HEIGHT)
        );

        log(String.format("OCR hangar list at (%d,%d %dx%d) for ship '%s'",
                hangarArea.x, hangarArea.y, hangarArea.width, hangarArea.height, target));

        Mission hangarOcr = ocrBridge.readMission(
                hangarArea.x, hangarArea.y, hangarArea.width, hangarArea.height);

        String rawText = (hangarOcr != null && hangarOcr.getRawText() != null)
                ? hangarOcr.getRawText() : "";

        if (target.isEmpty() || !rawText.toLowerCase().contains(target.toLowerCase())) {
            // Fallback: top of list
            int cx = hangarArea.x + hangarArea.width / 2;
            int cy = hangarArea.y + 20;
            log(String.format("Ship '%s' not found in OCR — fallback double-click at (%d,%d)", target, cx, cy));
            doubleClick(cx, cy);
            return true;
        }

        // Map the matching line number to a Y pixel in the hangar area
        String[] lines = rawText.split("\n");
        int matchLine = 0;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].toLowerCase().contains(target.toLowerCase())) {
                matchLine = i;
                break;
            }
        }

        double rowFraction = lines.length > 1 ? (double) matchLine / lines.length : 0.0;
        int shipX = hangarArea.x + hangarArea.width  / 2;
        int shipY = hangarArea.y + (int)(hangarArea.height * rowFraction) + 10;

        log(String.format("Double-clicking '%s' at row %d → screen (%d,%d)", target, matchLine, shipX, shipY));
        doubleClick(shipX, shipY);
        return true;
    }

    private String resolveShipTarget(Mission mission) {
        if (mission == null) return "";
        if (mission.getShipTarget() != null && !mission.getShipTarget().isEmpty())
            return mission.getShipTarget();
        if (mission.getTarget() != null && !mission.getTarget().isEmpty())
            return mission.getTarget();
        return "";
    }

    // -----------------------------------------------------------------------
    // Repair — all buttons via template matching
    // -----------------------------------------------------------------------

    /**
     * Right-click the active ship to open the context menu, then template-match
     * each subsequent button in the repair flow.
     *
     * Because every click after the right-click uses template matching on the
     * full screen, it doesn't matter whether the context menu opened upward or
     * downward — OpenCV will find "get_repair_quote" wherever it appears.
     */
    private void repairActiveShip() {
        log("Repair: right-clicking active ship icon (template: ship_hangar)");
        // Right-click the ship hangar icon area — this is where the active ship
        // icon lives after double-clicking a ship in the list.
        // If you add a dedicated "active_ship_icon" template, swap the key here.
        ocrBridge.rightClickTemplate("ship_hangar");
        sleep(800); // wait for context menu to render

        log("Repair: clicking 'Get Repair Quote' (template)");
        ocrBridge.clickTemplate("get_repair_quote");
        sleep(1200); // repair dialog loads

        log("Repair: clicking 'Repair' (template)");
        ocrBridge.clickTemplate("repair");
        sleep(800);

        log("Repair: clicking 'Yes' / confirm (template)");
        ocrBridge.clickTemplate("repair_yes");
        sleep(600);

        log("Repair complete.");
    }

    // -----------------------------------------------------------------------
    // Undock
    // -----------------------------------------------------------------------

    /** Click the Undock button via template matching. */
    public void clickUndock() {
        log("Clicking UNDOCK (template)");
        ocrBridge.clickTemplate("undock");
        sleep(POST_CLICK_DELAY_MS);
    }

    // -----------------------------------------------------------------------
    // Low-level Robot primitives
    // -----------------------------------------------------------------------

    /** Double-click a specific pixel — only used for hangar row selection. */
    private void doubleClick(int x, int y) {
        if (robot == null) {
            log(String.format("(no Robot) would double-click (%d,%d)", x, y));
            return;
        }
        robot.mouseMove(x, y);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        sleep(DOUBLE_CLICK_GAP_MS);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        sleep(POST_CLICK_DELAY_MS);
    }

    private void sleep(int ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static final java.time.format.DateTimeFormatter TS =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss");

    private void log(String msg) {
        System.out.printf("[%s] [MissionProcessor] %s%n",
                java.time.LocalDateTime.now().format(TS), msg);
    }
}