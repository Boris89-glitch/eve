package evebot;

import evebot.models.Mission;

import java.awt.*;

/**
 * MissionProcessor handles all UI interactions with EVE.
 *
 * POST-ACCEPT FLOW (simplified):
 *   1. repairShip() — Python scans the OPEN dialog for a ship template match,
 *                     opens the hangar, repairs, and double-clicks to activate.
 *                     The mission dialog and hangar are both managed inside Python.
 *   2. Undock.
 *
 *   The mission dialog does NOT need to be explicitly closed here — Python opens
 *   the hangar during the repair flow which naturally pushes the dialog away, and
 *   everything clears on undock anyway.
 *
 *   The ship_hangar click is also NOT repeated here — Python already opened it
 *   during repair. Clicking it again would toggle it closed.
 *
 * TRACK / UNTRACK logic:
 *   If "untrack" is visible → already tracked, skip.
 *   If "track" is visible   → click it.
 *   If neither              → assume past tracking step, continue.
 *
 * ACCEPT logic:
 *   Non-blocking: click Accept immediately, treat "not found" as already accepted.
 */
public class MissionProcessor {

    private final OCRBridge ocrBridge;

    private static final int POST_CLICK_DELAY_MS = 600;

    public MissionProcessor() {
        this.ocrBridge = new OCRBridge();
    }

    // -----------------------------------------------------------------------
    // OCR reads
    // -----------------------------------------------------------------------

    public Mission readMission(Rectangle missionArea) {
        return ocrBridge.readMission(
                missionArea.x, missionArea.y,
                missionArea.width, missionArea.height);
    }

    // -----------------------------------------------------------------------
    // Mission dialog — template-matched clicks
    // -----------------------------------------------------------------------

    public void clickRequestMission() {
        log("Clicking REQUEST MISSION (template) — with agent_icon double-click fallback");
        boolean found = ocrBridge.waitAndClickTemplate("request_mission", 3);
        if (!found) {
            log("Request Mission not visible — double-clicking agent_icon as fallback.");
            ocrBridge.doubleClickTemplate("agent_icon");
        }
        sleep(POST_CLICK_DELAY_MS);
    }

    public enum DialogState { NONE, OFFER, ALREADY_ACCEPTED }

    /**
     * Single-call dialog detection — one Python process, one screenshot, checks
     * accept / decline / complete_mission in that order.
     *
     * Returns:
     *   OFFER            — accept or decline button visible (fresh mission offer)
     *   ALREADY_ACCEPTED — complete_mission visible but no accept/decline
     *                      (bot restarted mid-session with mission active)
     *   NONE             — no dialog buttons found
     */
    public DialogState detectDialog() {
        log("Detecting dialog state (single template scan)...");
        // Check accept/decline first so an offer dialog is never mistaken for ALREADY_ACCEPTED.
        String found = ocrBridge.findAnyTemplate("accept", "decline", "complete_mission");
        if (found == null) {
            log("Dialog state: NONE");
            return DialogState.NONE;
        }
        if ("complete_mission".equals(found)) {
            log("Dialog state: ALREADY_ACCEPTED");
            return DialogState.ALREADY_ACCEPTED;
        }
        log("Dialog state: OFFER (" + found + ")");
        return DialogState.OFFER;
    }

    /** Convenience wrapper used in CHECKING_MISSIONS retry logic. */
    public boolean isMissionDialogVisible() {
        return detectDialog() != DialogState.NONE;
    }

    public boolean waitForDialogClosed() {
        log("Waiting for offer dialog to close...");
        boolean gone = ocrBridge.waitForTemplateGone("decline", 5);
        if (gone) log("Dialog closed.");
        else      log("Dialog still visible after 5s.");
        return gone;
    }

    public boolean waitForNewOffer() {
        log("Waiting for new mission offer...");
        String found = ocrBridge.waitForAnyTemplate(8, "accept", "decline");
        if (found != null) log("New offer dialog visible (" + found + ").");
        else               log("No new offer appeared within timeout.");
        return found != null;
    }

    /**
     * Non-blocking accept: click immediately, treat "not found" as already accepted.
     */
    public void clickAcceptButton() {
        log("Clicking ACCEPT (non-blocking — not found = already accepted)");
        ocrBridge.clickTemplate("accept");
        sleep(POST_CLICK_DELAY_MS);
    }

    public void clickDeclineButton() {
        log("Clicking DECLINE (template)");
        ocrBridge.clickTemplate("decline");
        sleep(POST_CLICK_DELAY_MS);
    }

    /**
     * Track / Untrack logic:
     *   1. "untrack" visible → already tracked, skip.
     *   2. "track" visible   → click it.
     *   3. Neither           → assume past tracking step, continue.
     */
    public void clickTrack() {
        log("Checking Track state (looking for untrack / track)...");
        String found = ocrBridge.waitForAnyTemplate(3, "untrack", "track");
        if ("untrack".equals(found)) {
            log("'untrack' visible — already tracked, skipping.");
        } else if ("track".equals(found)) {
            log("'track' visible — clicking Track.");
            ocrBridge.clickTemplate("track");
            sleep(POST_CLICK_DELAY_MS);
        } else {
            log("Neither 'track' nor 'untrack' found — assuming past tracking step.");
        }
    }

    public void doubleClickAgentIcon() {
        log("Double-clicking agent_icon to open agent dialog");
        ocrBridge.doubleClickTemplate("agent_icon");
        sleep(1200);
    }

    public void pressCtrlR() {
        log("Pressing Ctrl+R (reload modules)");
        ocrBridge.ctrlR();
        sleep(POST_CLICK_DELAY_MS);
    }

    public void closeAllDialogs() {
        log("Closing all open dialogs (loop until X gone)");
        while (ocrBridge.findAnyTemplate("close_active_window") != null) {
            ocrBridge.clickTemplate("close_active_window");
            sleep(500);
        }
        log("No more dialogs visible.");
    }

    public void closeMissionDialog() {
        log("Closing mission dialog (F key)");
        try {
            java.awt.Robot robot = new java.awt.Robot();
            robot.keyPress(java.awt.event.KeyEvent.VK_F);
            robot.keyRelease(java.awt.event.KeyEvent.VK_F);
        } catch (java.awt.AWTException e) {
            log("Robot unavailable — falling back to close_active_window template");
            ocrBridge.clickTemplate("close_active_window");
        }
        sleep(POST_CLICK_DELAY_MS);
    }

    // -----------------------------------------------------------------------
    // Post-accept flow (split into start + finish for non-blocking UNDOCKING state)
    //
    // performUndockStart  — steps 1 + 2: repair ship, click Undock, return.
    //                       Caller transitions to UNDOCKING and records timestamp.
    // performUndockFinish — step 3: close dialogs once the 15s timer expires.
    //
    // DO NOT close the mission dialog or click ship_hangar here:
    //   - The dialog is already gone after Python opens the hangar.
    //   - Clicking ship_hangar again would close it.
    // -----------------------------------------------------------------------

    public boolean performUndockStart(Mission mission) {
        log("=== Starting undock sequence ===");

        String missionTarget = (mission != null && mission.getTarget() != null
                && !mission.getTarget().equals("Unknown"))
                ? mission.getTarget() : "";
        String missionTitle = (mission != null && mission.getTitle() != null)
                ? mission.getTitle() : "";
        String lookup = !missionTarget.isEmpty() ? missionTarget : missionTitle;
        log("Ship OCR fallback: target='" + missionTarget + "' title='" + missionTitle + "' using='" + lookup + "'");

        // 1 — Identify ship from open dialog + repair + activate (all in Python)
        log("Step 1: Repair + activate ship");
        boolean repaired = ocrBridge.repairShip(lookup);
        if (!repaired) {
            System.err.println("[MissionProcessor] repairShip failed — aborting.");
            return false;
        }
        sleep(500);

        // 2 — Undock: only click the button if it is still visible.
        //     Python may have already triggered undock via context menu during repairShip,
        //     in which case the button is gone and there is nothing to do here.
        log("Step 2: Clicking Undock (if visible)");
        if (ocrBridge.findAnyTemplate("undock") != null) {
            clickUndock();
        } else {
            log("Undock button not visible — Python already triggered undock, skipping.");
        }
        // No sleep — ClientManager transitions to UNDOCKING and waits via timestamp timer.

        log("=== Undock clicked — entering UNDOCKING state ===");
        return true;
    }

    /**
     * Step 3 of the post-accept flow: close any lingering dialogs so travel
     * templates are visible. Called by ClientManager once the 15s undock timer expires.
     */
    // -----------------------------------------------------------------------
    // Mission turn-in
    // -----------------------------------------------------------------------

    public void clickCompleteMission() {
        log("Clicking COMPLETE MISSION (template)");
        ocrBridge.clickTemplate("complete_mission");
        sleep(POST_CLICK_DELAY_MS);
    }

    // -----------------------------------------------------------------------
    // Undock
    // -----------------------------------------------------------------------

    public void clickUndock() {
        log("Clicking UNDOCK (template)");
        ocrBridge.clickTemplate("undock");
        sleep(POST_CLICK_DELAY_MS);
    }

    // -----------------------------------------------------------------------
    // Travel / returning navigation
    // -----------------------------------------------------------------------

    public void eClickTemplate(String key) {
        log("E+clicking template: " + key);
        ocrBridge.eClickTemplate(key);
        sleep(POST_CLICK_DELAY_MS);
    }

    public void clickSetDestination() {
        log("Clicking SET DESTINATION (template)");
        ocrBridge.clickTemplate("set_destination");
        sleep(POST_CLICK_DELAY_MS);
    }

    public void clickWarpToLocation() {
        log("Clicking WARP TO LOCATION (template)");
        ocrBridge.clickTemplate("warp_to_location");
        sleep(POST_CLICK_DELAY_MS);
    }

    public void clickDock() {
        log("Clicking DOCK (template)");
        ocrBridge.clickTemplate("dock");
        sleep(POST_CLICK_DELAY_MS);
    }

    public void clickOk() {
        log("Clicking OK (template)");
        ocrBridge.clickTemplate("ok");
        sleep(POST_CLICK_DELAY_MS);
    }

    /**
     * Single screenshot scan for travel UI elements.
     * Returns: "activate_gate", "warp_to_location", "stargate", or null.
     * Priority order: activate_gate first (mission entrance), then warp_to_location,
     * then stargate (still in transit).
     */
    public String detectTravelUI() {
        return ocrBridge.findAnyTemplate("activate_gate", "warp_to_location", "stargate");
    }

    /**
     * Scans only for activate_gate — used after warp_to_location has been clicked
     * (we are in the mission system; no more regular stargates to jump).
     */
    public String detectActivateGateOnly() {
        return ocrBridge.findAnyTemplate("activate_gate");
    }

    /**
     * Single screenshot scan for returning UI elements.
     * Returns: "dock", "stargate", or null.
     */
    public String detectReturningUI() {
        return ocrBridge.findAnyTemplate("dock", "stargate");
    }

    /**
     * Returns true if set_destination is currently visible on screen.
     * Used in IN_COMBAT to detect mission completion.
     */
    public boolean isSetDestinationVisible() {
        return ocrBridge.findAnyTemplate("set_destination") != null;
    }

    /**
     * Returns true if the warp_active template is visible — i.e. the ship is currently in warp.
     * Used by the WARPING state to know when warp has ended.
     */
    public boolean isWarpActive() {
        return ocrBridge.findAnyTemplate("warp_active") != null;
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