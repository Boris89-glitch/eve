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

    private static final int POST_CLICK_DELAY_MS = 100;

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

    public String findAnyTemplate(String... keys) {
        return ocrBridge.findAnyTemplate(keys);
    }

    public void pressCtrlR() {
        log("Pressing Ctrl+R (reload modules)");
        ocrBridge.ctrlR();
        sleep(POST_CLICK_DELAY_MS);
    }

    public void closeAllDialogs() {
        log("Closing all open dialogs");
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

    public static final String UNDOCK_OK = "ok";
    public static final String UNDOCK_SHIP_NOT_FOUND = "ship_not_found";

    /**
     * @return UNDOCK_OK on success, UNDOCK_SHIP_NOT_FOUND if ship was missing
     *         (mission quit + new mission requested by Python), or null on failure.
     */
    public String performUndockStart(Mission mission) {
        log("=== Starting undock sequence ===");

        String shipTarget = (mission != null && mission.getShipTarget() != null)
                ? mission.getShipTarget() : "";
        String missionTarget = (mission != null && mission.getTarget() != null
                && !mission.getTarget().equals("Unknown"))
                ? mission.getTarget() : "";
        String missionTitle = (mission != null && mission.getTitle() != null)
                ? mission.getTitle() : "";
        String lookup = !shipTarget.isEmpty() ? shipTarget
                : !missionTarget.isEmpty() ? missionTarget : missionTitle;
        log("Ship lookup: shipTarget='" + shipTarget + "' target='" + missionTarget + "' title='" + missionTitle + "' using='" + lookup + "'");

        // 1 — Identify ship from open dialog + repair + activate (all in Python)
        log("Step 1: Repair + activate ship");
        String dialogMatched = ocrBridge.repairShip(lookup);
        if (dialogMatched == null) {
            System.err.println("[MissionProcessor] repairShip failed — aborting.");
            return null;
        }
        if (OCRBridge.SHIP_NOT_FOUND.equals(dialogMatched)) {
            log("Ship not found in hangar — mission quit, new mission requested.");
            return UNDOCK_SHIP_NOT_FOUND;
        }

        // If Python matched a dialog template (e.g. "mission_hawk"), extract the
        // combat target name and set it on the mission so IN_COMBAT knows what to do.
        if (mission != null && !dialogMatched.isEmpty() && dialogMatched.startsWith("mission_")) {
            String combatTarget = dialogMatched.substring("mission_".length());
            log("Dialog template identified combat target: '" + combatTarget + "'");
            mission.setShipTarget(combatTarget);
        } else if (mission != null && mission.getShipTarget() == null) {
            // No dialog template match — resolve combat target from OCR keywords
            // using the same logic as ship_mission_map.json.
            String resolved = resolveCombatTargetFromKeywords(lookup);
            if (resolved != null) {
                log("Keyword fallback identified combat target: '" + resolved + "'");
                mission.setShipTarget(resolved);
            }
        }
        sleep(500);

        // Python's repair_ship already clicks Board Ship (or falls back to Undock),
        // so no need to click undock here — just transition to UNDOCKING.
        log("=== Repair done — entering UNDOCKING state ===");
        return UNDOCK_OK;
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

    /** Returns "undock" if the button is still visible, null otherwise. */
    public String findUndockButton() {
        return ocrBridge.findAnyTemplate("undock");
    }

    /** Returns non-null if abort button or undocking text is visible (undock in progress). */
    public String findUndockingIndicator() {
        return ocrBridge.findAnyTemplate("abort", "undocking");
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
        sleep(100);
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

    public int[] findTemplatePos(String key) {
        return ocrBridge.findTemplatePos(key);
    }

    public void rightClickPos(int x, int y) {
        log("Right-clicking at (" + x + "," + y + ")");
        ocrBridge.rightClickPos(x, y);
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
        int[] pos = ocrBridge.findTemplatePos("set_destination");
        if (pos != null) {
            log("Clicking SET DESTINATION (template) at (" + pos[0] + "," + pos[1] + ")");
            ocrBridge.clickPos(pos[0], pos[1]);
        } else {
            log("set_destination template not found — alt+J → right-click agent → hover lara_mesi → click set destination.");
            ocrBridge.altKeyPress("j");          // open opportunities screen
            sleep(800);
            ocrBridge.rightClickTemplate("agent_portrait");
            sleep(800);
            ocrBridge.hoverTemplate("agent_lara_mesi");
            sleep(800);
            ocrBridge.clickTemplate("agent_set_destination");
        }
        sleep(POST_CLICK_DELAY_MS);
    }

    public void clickWarpToLocation() {
        log("Clicking WARP TO LOCATION (template)");
        ocrBridge.clickTemplate("warp_to_location");
        sleep(1000);
        ocrBridge.clickTemplate("warp_to_location");
        sleep(POST_CLICK_DELAY_MS);
    }

    public void clickDock() {
        log("Clicking DOCK (template)");
        ocrBridge.clickTemplate("dock");
        sleep(POST_CLICK_DELAY_MS);
    }

    /** Returns "dock_safe" if the safe dock button is visible, null otherwise. */
    public String detectDockSafe() {
        return ocrBridge.findAnyTemplate("dock_safe");
    }

    /**
     * Emergency re-dock: right-click home_station → click dock_safe.
     */
    public void emergencyRedock() {
        log("EMERGENCY-REDOCK: bubble camp detected — docking back");
        ocrBridge.rightClickTemplate("home_station");
        sleep(800);
        ocrBridge.clickTemplate("dock_safe");
        sleep(POST_CLICK_DELAY_MS);
    }

    /** Check if we are in the KLY-C0 system. */
    public boolean isKlySystem() {
        return ocrBridge.findAnyTemplate("kly_system") != null;
    }

    /** Check if enemy icon is visible in local chat. */
    public boolean isEnemyInLocal() {
        return ocrBridge.findAnyTemplate("enemy", "enemy2") != null;
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
        ocrBridge.clickTemplate("dock_safe");  // click Dock from submenu
        sleep(POST_CLICK_DELAY_MS);
        ctrlKeyPress("s");                    // autopilot to insta-dock bookmark
    }

    /**
     * Warp to a safe spot after undocking when enemy is in local.
     * Checks jcv_next / kly_next to determine which safe spot to use.
     * Right-click space → hover Locations → click the matching spot template.
     *
     * @return true if a safe spot was found and clicked, false otherwise
     */
    public boolean warpToSafeSpot() {
        String spot;
        if (ocrBridge.findAnyTemplate("jcv_next") != null) {
            spot = "jcv_spot";
            log("SAFE-SPOT: jcv_next detected — warping to JCV safe spot");
        } else if (ocrBridge.findAnyTemplate("kly_next") != null) {
            spot = "kly_spot";
            log("SAFE-SPOT: kly_next detected — warping to KLY safe spot");
        } else {
            log("SAFE-SPOT: enemy in local but no jcv_next/kly_next found");
            return false;
        }
        ocrBridge.rightClickPos(960, 540);   // right-click centre of space view
        sleep(800);
        ocrBridge.hoverTemplate("locations"); // hover Locations submenu
        sleep(800);
        ocrBridge.clickTemplate(spot);        // click the safe spot bookmark
        sleep(POST_CLICK_DELAY_MS);
        return true;
    }

    /**
     * Warp to a generic safe spot when enemy detected in local after a jump.
     * Right-click space → hover Locations → click safe_spot.
     *
     * @return true if safe_spot was found and clicked, false otherwise (menu dismissed with Escape)
     */
    public boolean warpToGenericSafeSpot() {
        log("SAFE-SPOT: enemy in local — warping to safe spot via Locations");
        ocrBridge.rightClickPos(960, 540);
        sleep(300);
        ocrBridge.hoverTemplate("locations");
        sleep(300);
        if (ocrBridge.findAnyTemplate("safe_spot") != null) {
            ocrBridge.clickTemplate("safe_spot");
            sleep(POST_CLICK_DELAY_MS);
            return true;
        }
        log("SAFE-SPOT: safe_spot not found — dismissing menu");
        sleep(100);
        return false;
    }

    public void clickOk() {
        log("Clicking OK (template)");
        ocrBridge.clickTemplate("ok");
        sleep(POST_CLICK_DELAY_MS);
    }

    public void waitAndClickOk() {
        log("Waiting for OK dialog and clicking");
        boolean found = ocrBridge.waitAndClickTemplate("ok", 3);
        if (!found) {
            log("OK button not found after 3s — may have already been dismissed.");
        }
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
        // Exact value intentionally not logged — OCR misreads it; only low-standings warnings are kept.
        return ocrBridge.readStandings(300, 490, 100, 55);
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
     * Returns: "dock", "stargate", "agent_icon", or null.
     */
    public String detectReturningUI() {
        return ocrBridge.findAnyTemplate("dock", "stargate", "agent_icon");
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
     * Checks for mission completion after combat — returns "set_destination" (different system)
     * or "dock" (same system as station), or null if neither visible.
     */
    public String detectCombatComplete() {
        return ocrBridge.findAnyTemplate("set_destination", "dock");
    }

    /**
     * Returns true if the warp_active template is visible — i.e. the ship is currently in warp.
     * Used by the WARPING state to know when warp has ended.
     */
    public boolean isWarpActive() {
        return ocrBridge.findAnyTemplate("warp_active", "warp_active2") != null;
    }

    /**
     * Returns true if the capsule template is visible — ship was destroyed.
     */
    public boolean isCapsuleVisible() {
        return ocrBridge.findAnyTemplate("capsule") != null;
    }

    /**
     * Self-destructs the capsule: right-click capsule → click destruct → click yes.
     */
    public void clickDestruct() {
        log("Self-destruct: right-click capsule → destruct → yes");
        rightClickAndSelect("capsule", "destruct");
        sleep(POST_CLICK_DELAY_MS);
        ocrBridge.clickTemplate("yes");
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

    /**
     * Keyword → combat target fallback, mirrors ship_mission_map.json logic.
     * Returns the combat target string (e.g. "worm", "sansha") or null.
     */
    private String resolveCombatTargetFromKeywords(String text) {
        if (text == null || text.isEmpty()) return null;
        String lower = text.toLowerCase();
        // Order matters — more specific keywords first, same as ship_mission_map.json.
        // ship_garmur missions: each keyword maps to a distinct combat target.
        if (lower.contains("jaguar"))                        return "jaguar";
        if (lower.contains("enyo"))                          return "enyo";
        if (lower.contains("hawk"))                          return "hawk";
        if (lower.contains("vengeance"))                     return "vengeance";
        // ship_worm
        if (lower.contains("worm") || lower.contains("guristas")) return "worm";
        // ship_sansha
        if (lower.contains("sansha") || lower.contains("sansa"))   return "sansha";
        // ship_angel
        if (lower.contains("angel") || lower.contains("dramiel"))  return "angel";
        // ship_sk
        if (lower.contains("infested") || lower.contains("ruins")) return "infested";
        if (lower.contains("recon"))                         return "recon1";
        return null;
    }

    private void log(String msg) {
        System.out.printf("[%s] [MissionProcessor] %s%n",
                java.time.LocalDateTime.now().format(TS), msg);
    }
}