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

    public OCRBridge getOcrBridge() {
        return ocrBridge;
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
        boolean found = ocrBridge.waitAndClickTemplate("request_mission", 1);
        if (!found) {
            log("Request Mission not visible — double-clicking agent_icon as fallback.");
            ocrBridge.doubleClickTemplate("agent_icon");
        }
        sleep(POST_CLICK_DELAY_MS);
    }

    public enum DialogState { NONE, OFFER, ALREADY_ACCEPTED, COMPLETED, NOT_COMPLETE }

    /**
     * Single-call dialog detection — one Python process, one screenshot, checks
     * accept / decline / complete_mission in that order.
     *
     * Returns:
     *   OFFER            — accept or decline button visible (fresh mission offer)
     *   ALREADY_ACCEPTED — complete_mission visible but no accept/decline
     *   COMPLETED        — no dialog buttons, but 'completed' template visible (mission done, turn in)
     *   NOT_COMPLETE     — no dialog buttons, but 'not_complete' template visible (mission in progress)
     *   NONE             — nothing recognisable found
     */
    public DialogState detectDialog() {
        log("Detecting dialog state (single template scan)...");
        // Check accept/decline first so an offer dialog is never mistaken for ALREADY_ACCEPTED.
        String found = ocrBridge.findAnyTemplate("accept", "decline", "complete_mission");
        if (found == null) {
            // No dialog buttons — check for completed/not_complete status indicators.
            String status = ocrBridge.findAnyTemplate("completed", "not_complete");
            if ("completed".equals(status)) {
                log("Dialog state: COMPLETED (completed template visible)");
                return DialogState.COMPLETED;
            }
            if ("not_complete".equals(status)) {
                log("Dialog state: NOT_COMPLETE (not_complete template visible)");
                return DialogState.NOT_COMPLETE;
            }
            log("Dialog state: NONE");
            return DialogState.NONE;
        }
        if ("complete_mission".equals(found)) {
            // complete_mission button visible — check if mission is actually done or still in progress.
            String status = ocrBridge.findAnyTemplate("completed", "not_complete");
            if ("completed".equals(status)) {
                log("Dialog state: COMPLETED (complete_mission + completed visible)");
                return DialogState.COMPLETED;
            }
            if ("not_complete".equals(status)) {
                log("Dialog state: NOT_COMPLETE (complete_mission + not_complete visible)");
                return DialogState.NOT_COMPLETE;
            }
            log("Dialog state: ALREADY_ACCEPTED (complete_mission only)");
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
        boolean gone = ocrBridge.waitForTemplateGone("decline", 3);
        if (gone) log("Dialog closed.");
        else      log("Dialog still visible after 3s.");
        return gone;
    }

    public boolean waitForNewOffer() {
        log("Waiting for new mission offer...");
        String found = ocrBridge.waitForAnyTemplate(4, "accept", "decline");
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

    public void doubleClickTemplate(String key) {
        log("Double-clicking template: " + key);
        ocrBridge.doubleClickTemplate(key);
        sleep(POST_CLICK_DELAY_MS);
    }

    public String findTemplate(String key) {
        return ocrBridge.findAnyTemplate(key);
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
        String dialogMatched = ocrBridge.repairShip(lookup);
        if (dialogMatched == null) {
            System.err.println("[MissionProcessor] repairShip failed — aborting.");
            return false;
        }

        // If Python matched a dialog template (e.g. "mission_hawk"), extract the
        // combat target name and set it on the mission so IN_COMBAT knows what to do.
        if (mission != null && !dialogMatched.isEmpty() && dialogMatched.startsWith("mission_")) {
            String combatTarget = dialogMatched.substring("mission_".length());
            log("Dialog template identified combat target: '" + combatTarget + "'");
            mission.setShipTarget(combatTarget);
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

    /**
     * Right-click a template, then click a context-menu template.
     * Used for stargate jumps (right-click stargate → click "jump")
     * and mission gate activation (right-click activate_gate → click "mission_start").
     */
    public void rightClickAndSelect(String targetKey, String menuKey) {
        log("Right-clicking '" + targetKey + "' → clicking '" + menuKey + "'");
        ocrBridge.rightClickTemplate(targetKey);
        sleep(800);
        ocrBridge.clickTemplate(menuKey);
        sleep(POST_CLICK_DELAY_MS);
    }

    /** @deprecated Use {@link #rightClickAndSelect} instead. Kept for combat sequences. */
    public void eClickTemplate(String key) {
        log("E+clicking template: " + key);
        ocrBridge.eClickTemplate(key);
        sleep(POST_CLICK_DELAY_MS);
    }

    public void wClickTemplate(String key) {
        log("W+clicking template: " + key);
        ocrBridge.wClickTemplate(key);
        sleep(POST_CLICK_DELAY_MS);
    }

    public void qClickTemplate(String key) {
        log("Q+clicking template: " + key);
        ocrBridge.qClickTemplate(key);
        sleep(POST_CLICK_DELAY_MS);
    }

    public void ctrlClickTemplate(String key) {
        log("Ctrl+clicking template: " + key);
        ocrBridge.ctrlClickTemplate(key);
        sleep(POST_CLICK_DELAY_MS);
    }

    /** Ctrl+click ALL matches of a template (lock all visible targets). Returns count locked. */
    public int ctrlClickAllTemplate(String key) {
        log("Ctrl+click-all template: " + key);
        int count = ocrBridge.ctrlClickAllTemplate(key);
        log("Locked " + count + " targets for '" + key + "'");
        sleep(POST_CLICK_DELAY_MS);
        return count;
    }

    public void rightClickTemplate(String key) {
        log("Right-clicking template: " + key);
        ocrBridge.rightClickTemplate(key);
        sleep(POST_CLICK_DELAY_MS);
    }

    public void keyPress(String keys) {
        log("Pressing keys: " + keys);
        ocrBridge.keyPress(keys);
        sleep(POST_CLICK_DELAY_MS);
    }

    public void shiftKeyPress(String keys) {
        log("Shift+pressing keys: " + keys);
        ocrBridge.shiftKeyPress(keys);
        sleep(POST_CLICK_DELAY_MS);
    }

    public void ctrlKeyPress(String keys) {
        log("Ctrl+pressing keys: " + keys);
        ocrBridge.ctrlKeyPress(keys);
        sleep(POST_CLICK_DELAY_MS);
    }

    public void altKeyPress(String key) {
        log("Alt+pressing key: " + key);
        ocrBridge.altKeyPress(key);
        sleep(POST_CLICK_DELAY_MS);
    }

    public void clickTemplateBelow(String key, int offsetY) {
        log("Clicking template: " + key + " (+" + offsetY + "px below centre)");
        ocrBridge.clickTemplateBelow(key, offsetY);
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

    /** Check if enemy icon is visible in local chat. */
    public boolean isEnemyInLocal() {
        return ocrBridge.findAnyTemplate("enemy") != null;
    }

    /**
     * Insta-dock sequence for when enemies are in local:
     * right-click empty space → hover Locations → click Dock → Ctrl+S (autopilot).
     */
    public void instaDock() {
        log("INSTA-DOCK: enemy in local — using safe dock sequence");
        ocrBridge.rightClickPos(960, 540);   // right-click centre of space view
        sleep(800);
        ocrBridge.hoverTemplate("locations"); // hover Locations submenu
        sleep(800);
        ocrBridge.clickTemplate("dock");      // click Dock from submenu
        sleep(POST_CLICK_DELAY_MS);
        ctrlKeyPress("s");                    // autopilot to insta-dock bookmark
    }

    public void clickOk() {
        log("Clicking OK (template)");
        ocrBridge.clickTemplate("ok");
        sleep(POST_CLICK_DELAY_MS);
    }

    public String detectFleetReward() {
        return ocrBridge.findAnyTemplate("fleet_yes", "fleet_no");
    }

    public void clickFleetYes() {
        log("Clicking fleet reward YES");
        ocrBridge.clickTemplate("fleet_yes");
        sleep(POST_CLICK_DELAY_MS);
    }

    public void clickFleetNo() {
        log("Clicking fleet reward NO");
        ocrBridge.clickTemplate("fleet_no");
        sleep(POST_CLICK_DELAY_MS);
    }

    public void clickAgentStandings() {
        log("Clicking agent standings button");
        ocrBridge.clickTemplate("agent_standings");
        sleep(1200); // wait for standings window to open
    }

    /** OCR-read agent standings from the standings info window. */
    public double readAgentStandings() {
        double val = ocrBridge.readStandings(300, 490, 100, 55);
        log("Agent standings OCR: " + val);
        return val;
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
     * Scans for mission-arrival elements only (no stargate).
     * Returns: "activate_gate", "warp_to_location", or null.
     */
    public String detectMissionArrivalUI() {
        return ocrBridge.findAnyTemplate("activate_gate", "warp_to_location");
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
     * Scans for dock only — used on the first tick after warp lands
     * to avoid false stargate matches during the gate tunnel animation.
     */
    public String detectDockOnly() {
        return ocrBridge.findAnyTemplate("dock");
    }

    /**
     * Single screenshot scan for the agent icon.
     * Returns "agent_icon" when docked and the icon is visible, null otherwise.
     */
    public String detectCompletingUI() {
        return ocrBridge.findAnyTemplate("agent_icon");
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

    /**
     * Returns true if the capsule template is visible — ship was destroyed.
     */
    public boolean isCapsuleVisible() {
        return ocrBridge.findAnyTemplate("capsule") != null;
    }

    /**
     * Clicks the self-destruct button on the capsule.
     */
    public void clickDestruct() {
        log("Clicking DESTRUCT (template)");
        ocrBridge.clickTemplate("destruct");
        sleep(POST_CLICK_DELAY_MS);
    }

    /** Generic left-click on any registered template. */
    public void clickTemplate(String key) {
        ocrBridge.clickTemplate(key);
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