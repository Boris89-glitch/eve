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
 * OCR READS — use ocrBridge.readMission(x, y, w, h) only for the mission
 * dialog text area. Ship selection NO LONGER uses OCR.
 *
 * SHIP SELECTION + REPAIR FLOW (new):
 *   The bot resolves the correct ship template from ship_mission_map.json
 *   based on the accepted mission title, then:
 *     1. Right-clicks the ship icon in the hangar → Get Repair Quote → Repair All → Yes
 *     2. Double-clicks the ship icon to make it the active ship
 *   This is done via ocrBridge.repairShip(missionTitle) in a single Python call.
 *
 * AGENT ICON FALLBACK:
 *   clickRequestMission() first looks for the "Request Mission" button.
 *   If absent (e.g. after completing a mission and agent dialog is already open),
 *   it falls back to doubleClickTemplate("agent_icon") to reopen the conversation.
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
 *     "agent_icon"           — Agent portrait (double-click to open agent dialog)
 *
 *   Hangar / repair (all matched by ship template, not "ship_hangar"):
 *     "ship_garmur"          — Garmur ship icon
 *     "ship_daredevil"       — Daredevil ship icon
 *     "ship_worm"            — Worm ship icon
 *     "ship_venge"           — Vengeance ship icon
 *     "ship_sansha"          — Sansha ship icon
 *     "ship_angel"           — Angel ship icon
 *     "ship_sk"              — SK ship icon
 *     "get_repair_quote"     — Context menu item (right-click ship icon)
 *     "repair"               — Repair All button in repair dialog
 *     "repair_yes"           — Yes / confirm repair button
 *
 *   Undock:
 *     "undock"               — Undock button (station panel)
 */
public class MissionProcessor {

    private final OCRBridge ocrBridge;

    private static final int POST_CLICK_DELAY_MS = 600;

    // -----------------------------------------------------------------------

    public MissionProcessor() {
        this.ocrBridge = new OCRBridge();
    }

    // -----------------------------------------------------------------------
    // OCR reads
    // -----------------------------------------------------------------------

    /**
     * OCR-read the mission dialog text area.
     */
    public Mission readMission(Rectangle missionArea) {
        return ocrBridge.readMission(
                missionArea.x, missionArea.y,
                missionArea.width, missionArea.height);
    }

    // -----------------------------------------------------------------------
    // Mission dialog — template-matched clicks
    // -----------------------------------------------------------------------

    /**
     * Click the "Request Mission" button in the agent dialog.
     *
     * If the "Request Mission" button is not visible (e.g. after completing a mission
     * where the agent dialog is already open and only shows agent dialogue),
     * falls back to double-clicking the agent_icon template to re-open the conversation.
     */
    public void clickRequestMission() {
        log("Clicking REQUEST MISSION (template) — with agent_icon double-click fallback");
        // Try the standard Request Mission button first.
        // waitForTemplate returns false quickly (~1s) if the button isn't there.
        boolean found = ocrBridge.waitForTemplate("request_mission", 3);
        if (found) {
            ocrBridge.clickTemplate("request_mission");
            log("Request Mission button clicked.");
        } else {
            // Fallback: the agent dialog is already open (e.g. after mission completion).
            // Double-clicking the agent portrait re-opens / refreshes the conversation
            // so the "Request Mission" button appears again.
            log("Request Mission not visible — double-clicking agent_icon as fallback.");
            ocrBridge.doubleClickTemplate("agent_icon");
        }
        sleep(POST_CLICK_DELAY_MS);
    }

    /**
     * Wait up to 5 seconds for the mission offer dialog to close.
     */
    public boolean waitForDialogClosed() {
        log("Waiting for offer dialog to close...");
        boolean gone = ocrBridge.waitForTemplateGone("decline", 5);
        if (gone) log("Dialog closed.");
        else      log("Dialog still visible after 5s.");
        return gone;
    }

    /**
     * Wait up to 8 seconds for a new mission offer dialog to appear.
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
     * Safe to call if absent — logs a warning and returns cleanly.
     */
    public void clickTrack() {
        log("Clicking TRACK (template) — if present");
        ocrBridge.clickTemplate("track");
        sleep(POST_CLICK_DELAY_MS);
    }

    /**
     * Double-click the agent portrait to open / re-open the agent dialog.
     * Used when in station but no mission window is visible.
     */
    public void doubleClickAgentIcon() {
        log("Double-clicking agent_icon to open agent dialog");
        ocrBridge.doubleClickTemplate("agent_icon");
        sleep(1200); // give the dialog time to appear before the next OCR read
    }

    /**
     * Close the mission dialog by pressing F (custom EVE shortcut for close window).
     *
     * We intentionally avoid the close_active_window (X button) template here
     * because its icon is identical to every other window's X button — after
     * the hangar opens, template matching finds the hangar's X instead.
     * The F keybind closes only the top-most active dialog, so it's unambiguous.
     */
    public void closeMissionDialog() {
        log("Closing mission dialog (F key)");
        try {
            java.awt.Robot robot = new java.awt.Robot();
            robot.keyPress(java.awt.event.KeyEvent.VK_F);
            robot.keyRelease(java.awt.event.KeyEvent.VK_F);
        } catch (java.awt.AWTException e) {
            log("Robot unavailable for Escape — falling back to close_active_window template");
            ocrBridge.clickTemplate("close_active_window");
        }
        sleep(POST_CLICK_DELAY_MS);
    }

    // -----------------------------------------------------------------------
    // Post-accept flow: repair ship → activate ship → undock
    //
    // OLD flow (removed):
    //   open Ship Hangar → OCR hangar list → double-click row → right-click active ship icon
    //
    // NEW flow:
    //   1. Open Ship Hangar (ship_hangar template — sidebar icon)
    //   2. repairShip(missionTitle) — Python resolves ship template from mission title,
    //      right-clicks ship icon → repair → double-clicks to activate
    //   3. Undock
    // -----------------------------------------------------------------------

    /**
     * Full post-accept sequence:
     *   1. Open Ship Hangar (sidebar template)
     *   2. Repair + activate the correct ship (template-based, no OCR)
     *   3. Undock
     *
     * @param windowRect  Unused (kept for API compatibility). All clicks use full-screen
     *                    template matching.
     * @param mission     The accepted mission; title drives ship template lookup.
     * @return true if the sequence completed, false on critical failure
     */
    public boolean performPostAcceptFlow(Rectangle windowRect, Mission mission) {
        log("=== Starting post-accept flow (template-based) ===");

        // Ship selection is based on target (the enemy ship to destroy), not the mission title.
        // Title OCR is noisy (agent name bleeds in); target is a cleaner single token.
        // Fallback chain: target → title → empty string
        String missionTarget = (mission != null && mission.getTarget() != null
                && !mission.getTarget().equals("Unknown"))
                ? mission.getTarget() : "";
        String missionTitle  = (mission != null && mission.getTitle()  != null)
                ? mission.getTitle()  : "";
        String lookup = !missionTarget.isEmpty() ? missionTarget : missionTitle;
        log("Ship lookup — target: '" + missionTarget + "'  title: '" + missionTitle + "'  using: '" + lookup + "'");

        // 1 — Open Ship Hangar (sidebar icon)
        log("Opening Ship Hangar (template: ship_hangar)");
        ocrBridge.clickTemplate("ship_hangar");
        sleep(1200); // wait for hangar panel to load and ship icons to render

        // 2 — Repair + activate ship (right-click → repair → double-click to make active)
        //     Python resolves which ship template to use from ship_mission_map.json
        log("Repairing and activating ship for target: '" + lookup + "'");
        boolean repaired = ocrBridge.repairShip(lookup);
        if (!repaired) {
            System.err.println("[MissionProcessor] repairShip failed — aborting post-accept flow.");
            return false;
        }
        sleep(1000); // let the ship activation settle

        // 3 — Undock
        clickUndock();

        log("=== Post-accept flow complete — undocking ===");
        return true;
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
    // Helpers
    // -----------------------------------------------------------------------

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