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

    private static final int POST_CLICK_DELAY_MS = 40;
    // Emergency redock confirmation: poll agent_icon up to ~24s while the dock completes.
    private static final int REDOCK_CONFIRM_TRIES = 12;
    private static final int REDOCK_CONFIRM_INTERVAL_MS = 2000;

    // Agent-standings ("Character: Information") window close "X" (Layout.standingsClose()).
    // closeStandingsDialog() matches the close_active_window template ONLY inside
    // Layout.standingsWindow() so it can't hit the hangar / mission-dialog / agent X elsewhere on
    // screen, then falls back to the fixed location if not matched. Box is tight around the X.

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
        log("Clicking REQUEST/VIEW MISSION (whichever is shown) — no agent_icon fallback");
        clickRequestOrViewMission();
        sleep(POST_CLICK_DELAY_MS);
    }

    /**
     * Click the button that surfaces the mission dialog from an open agent window: Request
     * Mission (no mission yet) or View Mission (already have one). No agent_icon double-click
     * fallback — opening the agent window is the double-click's job, not this one's. Returns
     * true if a button was clicked.
     */
    private boolean clickRequestOrViewMission() {
        String btn = ocrBridge.findAnyTemplate("request_mission", "view_mission");
        if (btn == null) {
            log("Neither request_mission nor view_mission visible.");
            return false;
        }
        return ocrBridge.waitAndClickTemplate(btn, 1);
    }

    /**
     * High-sec only: a bare double-click of the agent portrait opens the agent window but no
     * longer the mission dialog itself — you must then click View/Request Mission. In null sec
     * the double-click opens the dialog directly, so this is a no-op there.
     */
    private void surfaceMissionDialogIfHighSec() {
        if (!Environment.isHighSec()) return;
        log("High sec — clicking View/Request Mission to surface the dialog.");
        clickRequestOrViewMission();
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
            // No dialog buttons — check status indicators. not_complete FIRST: the tiny 'completed'
            // checkmark false-matches easily, so if both are 'found' (an in-progress mission whose
            // objective dot is on screen) we must trust not_complete and never read it as done.
            String status = ocrBridge.findAnyTemplate("not_complete", "completed");
            if ("not_complete".equals(status)) {
                log("Dialog state: NOT_COMPLETE (not_complete template visible)");
                return DialogState.NOT_COMPLETE;
            }
            if ("completed".equals(status)) {
                log("Dialog state: COMPLETED (completed template visible)");
                return DialogState.COMPLETED;
            }
            log("Dialog state: NONE");
            return DialogState.NONE;
        }
        if ("complete_mission".equals(found)) {
            // complete_mission button visible — check if mission is actually done or still in progress.
            // not_complete FIRST so a false 'completed' match can't turn in an unfinished mission.
            String status = ocrBridge.findAnyTemplate("not_complete", "completed");
            if ("not_complete".equals(status)) {
                log("Dialog state: NOT_COMPLETE (complete_mission + not_complete visible)");
                return DialogState.NOT_COMPLETE;
            }
            if ("completed".equals(status)) {
                log("Dialog state: COMPLETED (complete_mission + completed visible)");
                return DialogState.COMPLETED;
            }
            // complete_mission button visible but neither status icon matched. Never assume "done"
            // here — a missed 'completed' icon would wrongly turn in an unfinished mission. Only an
            // actual 'completed' match (above) turns in; treat the ambiguous case as in-progress.
            log("Dialog state: NOT_COMPLETE (complete_mission only, no status icon matched — not assuming done)");
            return DialogState.NOT_COMPLETE;
        }
        log("Dialog state: OFFER (" + found + ")");
        return DialogState.OFFER;
    }

    /** Convenience wrapper used in CHECKING_MISSIONS retry logic. */
    public boolean isMissionDialogVisible() {
        return detectDialog() != DialogState.NONE;
    }

    /**
     * Template-first mission classification (no OCR). Returns the matched mission
     * dialog template key (e.g. "mission_hawk") if a known wanted-mission template
     * is visible, or null otherwise.
     */
    public String classifyMissionOffer(Rectangle dialogArea) {
        return ocrBridge.classifyMissionOffer(
                dialogArea.x, dialogArea.y, dialogArea.width, dialogArea.height);
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
     * Click Accept and confirm it took: the accept button must be gone afterwards.
     * Retries once if it's still on screen. Returns false if the offer is still up.
     */
    /**
     * Click ACCEPT, then confirm we actually have a mission. The accept button going away
     * proves nothing (a closed or covered dialog does the same) — 'quit_mission' and
     * 'complete_mission' only exist while a mission is active, so either one is the real
     * confirmation. Click away to mid first so the cursor / lingering offer buttons can't
     * cover them during the scan.
     */
    public boolean clickAcceptButton() {
        for (int attempt = 1; attempt <= 2; attempt++) {
            log("Clicking ACCEPT (attempt " + attempt + ")");
            ocrBridge.clickTemplate("accept");
            sleep(POST_CLICK_DELAY_MS);
            clickEmptySpace();   // swift click away in the middle (empty space)
            String confirm = ocrBridge.waitForAnyTemplate(3, "quit_mission", "complete_mission");
            if (confirm != null) {
                log("ACCEPT confirmed — '" + confirm + "' visible, mission is active.");
                return true;
            }
            log("ACCEPT not confirmed — neither quit_mission nor complete_mission visible.");
        }
        return false;
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
            // 'track' was just confirmed present by the scan above — click with a 1s cap
            // instead of clickTemplate's default 2s re-search (avoids locating it twice).
            log("'track' visible — clicking Track.");
            ocrBridge.waitAndClickTemplate("track", 1);
            sleep(POST_CLICK_DELAY_MS);
        } else {
            log("Neither 'track' nor 'untrack' found — assuming past tracking step.");
        }
    }

    public void doubleClickAgentIcon() {
        log("Double-clicking agent_icon to open agent dialog");
        ocrBridge.doubleClickTemplate("agent_icon");
        sleep(1200);
        surfaceMissionDialogIfHighSec();
    }

    /**
     * Quit the active mission and request a new one. Opens the agent dialog first (so the
     * mission's Quit button is on screen), then quit → Yes → Request Mission. Used after a
     * pod death to abandon the mission we died on and pick a different one.
     */
    public boolean quitMission() {
        log("Quitting current mission (open agent dialog → quit → request new)");
        doubleClickAgentIcon();
        boolean quit = ocrBridge.quitMissionAndRequestNew();
        if (!quit) log("Quit-mission button not found — mission may still be active.");
        sleep(POST_CLICK_DELAY_MS);
        return quit;
    }

    // -----------------------------------------------------------------------
    // Faction agent (every-2h kernite check + run)
    // -----------------------------------------------------------------------

    public void doubleClickFactionAgent() {
        // The faction agent has no View Mission button — a bare double-click opens his dialog
        // directly in both null and high sec, so no surface-click follow-up here.
        log("Double-clicking faction_agent to open faction agent dialog");
        ocrBridge.doubleClickTemplate("faction_agent");
        sleep(1200);
    }

    /** True when the faction agent's portrait is visible (docked at his station / convo reachable). */
    public boolean isFactionAgentVisible() {
        return ocrBridge.findAnyTemplate("faction_agent") != null;
    }

    /**
     * Confirm the faction agent icon is *stably* present across several sweeps, not a single
     * false positive from another client's window (a live EVE-O Preview thumbnail or a stale
     * frame that hasn't repainted yet after the focus switch). Requires EVERY sample to hit;
     * one miss means it isn't really this client's icon. Used to gate FACTION_CHECK so a client
     * with no faction agent can't be dragged into the kernite run by the other client's icon.
     */
    public boolean confirmFactionAgentVisible(int samples, int gapMs) {
        for (int i = 0; i < samples; i++) {
            if (ocrBridge.findAnyTemplate("faction_agent") == null) {
                return false; // missed a scan — not stably present, treat as absent
            }
            if (i < samples - 1) sleep(gapMs);
        }
        return true;
    }

    /** True when the 'kernite' (Resources for War) offer dialog template is on screen. */
    public boolean isKerniteOffer() {
        return ocrBridge.findAnyTemplate("kernite") != null;
    }

    /** True when the given station template (e.g. soe_station / home_station) is visible in space. */
    public boolean isStationVisible(String stationKey) {
        return ocrBridge.findAnyTemplate(stationKey) != null;
    }

    /** Request a mission with NO agent_icon fallback — used for the faction agent so we never
     *  accidentally re-open the regular home agent. */
    public void clickRequestMissionPlain() {
        // Faction agent has no View Mission button and needs no agent_icon fallback (that could
        // re-open the regular home agent) — request_mission only.
        log("Clicking REQUEST MISSION (faction — request_mission only, no fallback)");
        ocrBridge.waitAndClickTemplate("request_mission", 1);
        sleep(POST_CLICK_DELAY_MS);
    }

    public void doubleClickTemplate(String key) {
        log("Double-clicking template: " + key);
        ocrBridge.doubleClickTemplate(key);
        sleep(POST_CLICK_DELAY_MS);
    }

    public String findTemplate(String key) {
        return ocrBridge.findAnyTemplate(key);
    }

    /**
     * Confirm a template is really gone, filtering single-frame false negatives.
     * Re-scans up to {@code samples} times (with {@code gapMs} between scans) and
     * returns true ONLY if every scan misses. Returns false as soon as one scan finds
     * it — so a momentary match-miss on something still on screen won't read as "gone".
     */
    public boolean confirmTemplateGone(String key, int samples, int gapMs) {
        for (int i = 0; i < samples; i++) {
            if (ocrBridge.findAnyTemplate(key) != null) {
                return false; // still there — the earlier miss was a false negative
            }
            if (i < samples - 1) sleep(gapMs);
        }
        return true; // missed every scan — genuinely gone
    }

    public String findAnyTemplate(String... keys) {
        return ocrBridge.findAnyTemplate(keys);
    }

    /** Count distinct on-screen matches of a template. */
    public int countTemplate(String key) {
        return ocrBridge.countTemplate(key);
    }

    /**
     * Confirm a template's on-screen count has dropped to at most {@code max}, filtering
     * single-frame false negatives: re-scans up to {@code samples} times and returns true
     * only if EVERY scan agrees. A single high reading means the count hasn't really dropped.
     */
    public boolean confirmTemplateCountAtMost(String key, int max, int samples, int gapMs) {
        for (int i = 0; i < samples; i++) {
            if (ocrBridge.countTemplate(key) > max) return false;
            if (i < samples - 1) sleep(gapMs);
        }
        return true;
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

    /**
     * Dismiss every dialog showing a 'close' BUTTON (not the window X). A decline leaves two
     * stacked agent dialogs, and clickTemplate only ever presses the single best match, so
     * loop until none are left — bounded so a close that never clears can't spin forever.
     */
    public void clickAllCloseButtons() {
        log("Closing dialogs via their 'close' button");
        int clicked = 0;
        while (clicked < 4 && ocrBridge.findAnyTemplate("close") != null) {
            ocrBridge.clickTemplate("close");
            clicked++;
            sleep(500);
        }
        log("'close' pressed " + clicked + " time(s) — no more close buttons visible.");
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
            System.err.println("[" + OCRBridge.getActiveClient() + "] repairShip failed — aborting.");
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

        // Python's repair_ship already clicks Board Ship (it never clicks Undock —
        // the caller does that), so just transition to UNDOCKING.
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

    /** True while the COMPLETE MISSION button is still on screen — the turn-in hasn't gone through. */
    public boolean isCompleteMissionVisible() {
        return findTemplate("complete_mission") != null;
    }

    // -----------------------------------------------------------------------
    // Undock
    // -----------------------------------------------------------------------

    public void clickUndock() {
        log("Clicking UNDOCK (template)");
        sleep(1000);   // 1s settle before clicking undock
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
    public boolean rightClickAndSelect(String targetKey, String menuKey) {
        return rightClickAndSelect(targetKey, menuKey, 3);
    }

    /**
     * As above, but retries when the context menu never opened (the right-click missed the
     * target, so {@code menuKey} isn't on screen). Without the retry the menu click is a no-op
     * and the caller reads the missing warp as a failed warp — which burns a bubble-evasion
     * attempt for what is really just a missed right-click. Between attempts we left-click
     * empty space to dismiss any half-open menu (never Escape).
     *
     * @return true if the menu item was found and clicked.
     */
    public boolean rightClickAndSelect(String targetKey, String menuKey, int attempts) {
        for (int attempt = 1; attempt <= attempts; attempt++) {
            log("Right-clicking '" + targetKey + "' → clicking '" + menuKey + "' (attempt "
                    + attempt + "/" + attempts + ")");
            ocrBridge.rightClickTemplate(targetKey);
            sleep(100);
            if (ocrBridge.clickTemplateChecked(menuKey)) {
                sleep(POST_CLICK_DELAY_MS);
                return true;
            }
            log("'" + menuKey + "' not in the context menu — clicking empty space and retrying.");
            clickEmptySpace();
            sleep(200);
        }
        log("'" + menuKey + "' never appeared after " + attempts + " right-clicks on '" + targetKey + "'.");
        return false;
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
        // Lock reliability: a single ctrl+click sometimes fails to register the lock. So
        // ctrl+click the target, a swift click in empty space (screen centre) to clear the
        // active selection, then ctrl+click again to make sure the lock takes.
        log("Ctrl+clicking template (lock, double-tap): " + key);
        ocrBridge.ctrlClickTemplate(key);
        clickEmptySpace();   // swift click away in the middle (empty space)
        ocrBridge.ctrlClickTemplate(key);
        sleep(POST_CLICK_DELAY_MS);
    }

    /** Single ctrl+click lock — the original behaviour, no mid-click double-tap. Used by
     *  Enyo/Hawk where several supports are locked in sequence and the {@link #ctrlClickTemplate}
     *  mid-click reset would clear a just-made lock selection. */
    public void ctrlClickTemplateOnce(String key) {
        log("Ctrl+clicking template (lock, single): " + key);
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

    /** Left-click empty space in the middle of the screen — dismisses context menus (never Escape). */
    public void clickEmptySpace() {
        Point p = Layout.emptySpace();
        clickPos(p.x, p.y);
    }

    /** Right-click empty space above centre — avoids right-clicking our own ship. */
    public void rightClickAboveShip() {
        Point p = Layout.spaceAboveShip();
        ocrBridge.rightClickPos(p.x, p.y);
    }

    public void clickPos(int x, int y) {
        log("Left-clicking at (" + x + "," + y + ")");
        ocrBridge.clickPos(x, y);
        sleep(POST_CLICK_DELAY_MS);
    }

    /**
     * Nudge the camera with a small left-drag in empty space (screen centre) to force
     * a fresh frame render before re-running a template detection. Does not move the
     * ship. Leaves the camera slightly rotated (drifts back on its own).
     */
    public void nudgeView() {
        log("Nudging view (camera drag) to refresh frame");
        Point p = Layout.emptySpace();
        ocrBridge.dragPos(p.x, p.y, 40, 0);
        sleep(POST_CLICK_DELAY_MS);
    }

    public void keyPress(String keys) {
        log("Pressing keys: " + keys);
        ocrBridge.keyPress(keys);
        sleep(POST_CLICK_DELAY_MS);
    }

    /**
     * Press a key, wait, then press it again — used to toggle a module off (e.g. 's' to
     * deactivate the warp module). Back-to-back presses fire too fast for EVE to register
     * the second one as a deactivation, so a gap is needed between them.
     */
    public void keyPressTwice(String keys, int gapMs) {
        log("Pressing keys twice (" + gapMs + "ms gap): " + keys);
        ocrBridge.keyPress(keys);
        sleep(gapMs);
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

    /**
     * Alternative set-destination via the agent, for missions where the normal on-screen
     * set_destination prompt isn't used (Dread Pirate Scarlet). Opens the Agency window
     * (alt+J), clicks the 'active' tab, then right-clicks the agent portrait → hovers the
     * agent entry → clicks Set Destination. Location-independent, so it can run at any point.
     */
    public void setDestinationViaAgent() {
        log("Set destination via agent: alt+J → 'active' → right-click agent_portrait → hover lara_mesi → set destination.");
        ocrBridge.altKeyPress("j");          // open the Agency / opportunities window
        sleep(800);
        // Click the 'Active' tab (must precede the portrait right-click), wait 1s for the
        // agent portrait to render, then check. The portrait sometimes isn't ready yet (slow
        // Agency load, or the tab click missed) — re-click 'Active' and recheck, up to 5x.
        int activeRetries = 0;
        while (true) {
            ocrBridge.clickTemplate("active");
            sleep(1000);   // 1s delay before checking for the agent portrait
            if (ocrBridge.findAnyTemplate("agent_portrait") != null) break;
            if (++activeRetries >= 5) break;
            log("agent_portrait not visible — re-clicking 'active' and rechecking (attempt " + activeRetries + "/5).");
        }
        ocrBridge.rightClickTemplate("agent_portrait");
        sleep(800);
        ocrBridge.hoverTemplate("agent_lara_mesi");
        sleep(800);
        ocrBridge.clickTemplate("agent_set_destination");
        sleep(800);
        ocrBridge.clickTemplate("close_active_window");  // close the agency window once, now that destination is set
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

    /** Click DOCK twice (1s apart) — same double-click pattern as warp-to-location. */
    public void clickDockTwice() {
        log("Clicking DOCK (template, twice)");
        ocrBridge.clickTemplate("dock");
        sleep(1000);
        ocrBridge.clickTemplate("dock");
        sleep(POST_CLICK_DELAY_MS);
    }

    /** Returns true if the dock button/template is currently visible. */
    public boolean isDockVisible() {
        return ocrBridge.findAnyTemplate("dock") != null;
    }

    /**
     * Dock when returning to a station, with a backup so a returning dock never silently
     * no-ops. Main option: click the DOCK button (twice) if it's visible. Backup: if the
     * button isn't on screen, right-click the station (soe_station / home_station) → Dock.
     * Returns true if a dock action was issued, false if neither button nor station was found.
     */
    public boolean dockWithStationBackup() {
        if (isDockVisible()) {
            clickDockTwice();
            return true;
        }
        String station = isStationVisible("soe_station")  ? "soe_station"
                       : isStationVisible("home_station") ? "home_station"
                       : null;
        if (station != null) {
            log("DOCK button not visible — right-click " + station + " → Dock (backup).");
            rightClickAndSelect(station, "dock");
            return true;
        }
        log("No DOCK button or station visible — cannot dock.");
        return false;
    }

    /** Returns "dock_safe" if the safe dock button is visible, null otherwise. */
    public String detectDockSafe() {
        return ocrBridge.findAnyTemplate("dock_safe");
    }

    /**
     * Emergency re-dock: right-click home_station → click dock_safe, then confirm we
     * actually made it into the hangar. Docking warps to the station first and then docks,
     * so it is NOT instant — poll for the agent icon before reporting success, otherwise the
     * caller would try to repair/board while still in space (hangar not found).
     * Returns true only when agent_icon is visible (confirmed docked).
     */
    public boolean emergencyRedock() {
        log("EMERGENCY-REDOCK: docking back to station");
        ocrBridge.rightClickTemplate("home_station");
        sleep(800);
        ocrBridge.clickTemplate("dock_safe");
        sleep(POST_CLICK_DELAY_MS);
        for (int i = 0; i < REDOCK_CONFIRM_TRIES; i++) {
            sleep(REDOCK_CONFIRM_INTERVAL_MS);
            if (ocrBridge.findAnyTemplate("agent_icon") != null) {
                log("EMERGENCY-REDOCK: agent_icon visible — confirmed docked in station.");
                return true;
            }
        }
        log("EMERGENCY-REDOCK: agent_icon NOT visible after wait — dock NOT confirmed.");
        return false;
    }

    /** Check if we are in the KLY-C0 system. */
    public boolean isKlySystem() {
        return ocrBridge.findAnyTemplate("kly_system") != null;
    }

    /** Check if enemy icon is visible in local chat. */
    public boolean isEnemyInLocal() {
        return ocrBridge.findAnyTemplate("enemy", "enemy2") != null;
    }

    /** Outcome of an insta-dock attempt, so the caller can distinguish a warp that engaged
     *  from one that never started (and therefore must NOT be treated as a dock-in-progress). */
    public enum InstaDockResult {
        /** Warp to the dock bookmark engaged (autopilot fired) — safe to enter WARPING to verify. */
        ENGAGED,
        /** Dock warp never engaged (bookmark/menu click missed, or bubbled) — retry the insta-dock;
         *  do NOT commit to WARPING, or a missed click escalates into a bubble-escape gate jump. */
        NOT_ENGAGED
    }

    /**
     * Insta-dock sequence for when enemies are in local:
     * right-click empty space → hover Locations → click Dock → Ctrl+S (autopilot).
     */
    public InstaDockResult instaDock() {
        log("INSTA-DOCK: enemy in local — using safe dock sequence");
        rightClickAboveShip();   // right-click above centre (avoid clicking the ship)
        sleep(800);
        // If Locations isn't in the menu but the dock/station is clearly on screen, the
        // right-click likely landed on the ship or a stale frame. Nudge the camera to refresh
        // and re-open the menu (up to twice) so we open Locations instead of missing the dock.
        for (int attempt = 1; attempt <= 2
                && ocrBridge.findAnyTemplate("locations") == null
                && (isDockVisible() || isStationVisible("home_station") || isStationVisible("soe_station"));
                attempt++) {
            log("INSTA-DOCK: Locations not in menu but dock/station visible — nudge + retry right-click (" + attempt + "/2).");
            nudgeView();
            rightClickAboveShip();
            sleep(800);
        }
        ocrBridge.hoverTemplate("locations"); // hover Locations submenu
        sleep(800);
        ocrBridge.clickTemplate("dock_safe");  // click Dock from submenu
        sleep(POST_CLICK_DELAY_MS);
        // Confirm the warp to the dock bookmark has actually engaged before enabling autopilot.
        // Firing Ctrl+S from a standstill (dock click missed / warp not yet spun up) engages
        // autopilot against a ship that isn't warping. Give the warp ~7s, then verify warp_active —
        // if it's not warping, report NOT_ENGAGED so the caller retries (and, after the cap, docks
        // directly). No bubble handling here: if we're bubbled at the dock we just accept getting
        // podded and wait out the cooldown rather than fleeing the system.
        sleep(7000);
        if (!isWarpActive()) {
            log("INSTA-DOCK: warp not active after 5s — skipping Ctrl+S autopilot this pass.");
            return InstaDockResult.NOT_ENGAGED;
        }
        ctrlKeyPress("s");                    // autopilot to insta-dock bookmark
        return InstaDockResult.ENGAGED;
    }

    /**
     * Warp to a safe spot after undocking when enemy is in local.
     * Checks jcv_next / kly_next to determine which safe spot to use.
     * Right-click space → hover Locations → click the matching spot template.
     *
     * @return true if a safe spot was found and clicked, false otherwise
     */
    public boolean warpToSafeSpot() {
        // kly/jcv safe-spot detection disabled for now — skip it, fall back to generic safe spot.
        log("SAFE-SPOT: kly/jcv detection disabled — skipping.");
        return false;
        /*
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
        rightClickAboveShip();   // right-click above centre (avoid clicking the ship)
        sleep(800);
        ocrBridge.hoverTemplate("locations"); // hover Locations submenu
        sleep(800);
        ocrBridge.clickTemplate(spot);        // click the safe spot bookmark
        sleep(POST_CLICK_DELAY_MS);
        return true;
        */
    }

    /**
     * Warp to a generic safe spot when enemy detected in local after a jump.
     * Right-click space → hover Locations → click safe_spot.
     *
     * @return true if safe_spot was found and clicked, false otherwise (menu dismissed with Escape)
     */
    public boolean warpToGenericSafeSpot() {
        log("SAFE-SPOT: enemy in local — warping to safe spot via Locations");
        rightClickAboveShip();   // right-click above centre (avoid clicking the ship)
        sleep(300);
        ocrBridge.hoverTemplate("locations");
        sleep(300);
        if (ocrBridge.findAnyTemplate("safe_spot") != null) {
            ocrBridge.clickTemplate("safe_spot");
            sleep(POST_CLICK_DELAY_MS);
            return true;
        }
        // The right-click menu is still open and would swallow the stargate right-click that
        // follows — dismiss it with a click in empty space (never Escape).
        log("SAFE-SPOT: safe_spot not found — dismissing menu with a click in empty space");
        clickEmptySpace();
        sleep(300);
        return false;
    }

    public void clickOk() {
        log("Clicking OK (template)");
        ocrBridge.clickTemplate("ok");
        sleep(POST_CLICK_DELAY_MS);
    }

    /** @return true if the OK dialog appeared and was clicked. */
    public boolean waitAndClickOk() {
        log("Waiting for OK dialog and clicking");
        boolean found = ocrBridge.waitAndClickTemplate("ok", 3);
        if (!found) {
            log("OK button not found after 3s.");
        }
        sleep(POST_CLICK_DELAY_MS);
        return found;
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

    /** How many times to re-read the standings OCR region before giving up. */
    private static final int STANDINGS_OCR_ATTEMPTS = 4;

    /** OCR-read agent standings from the standings info window. Retries a few times — the
     *  window opens fine but the OCR occasionally comes back empty (text not yet rendered). */
    public double readAgentStandings() {
        // Exact value intentionally not logged — OCR misreads it; only low-standings warnings are kept.
        // Region covers ONLY the first (agent) row of the standings list — a taller box also
        // catches the corp-standing row below it, and psm 7 single-line OCR fails on two lines.
        Rectangle r = Layout.standingsOcr();
        for (int attempt = 1; attempt <= STANDINGS_OCR_ATTEMPTS; attempt++) {
            double s = ocrBridge.readStandings(r.x, r.y, r.width, r.height);
            if (!Double.isNaN(s)) return s;
            if (attempt < STANDINGS_OCR_ATTEMPTS) {
                log("Standings OCR empty (attempt " + attempt + "/" + STANDINGS_OCR_ATTEMPTS
                        + ") — closing and reopening standings.");
                closeStandingsDialog();
                clickAgentStandings();
            }
        }
        log("Standings OCR still unreadable after " + STANDINGS_OCR_ATTEMPTS + " attempts.");
        return Double.NaN;
    }

    /**
     * Close the agent-standings info window. Matches the close "X" ONLY inside the standings
     * window's box (so a full-screen match can't kill the hangar / mission dialog / agent
     * window instead), then falls back to clicking the fixed X location if not matched.
     */
    public void closeStandingsDialog() {
        log("Closing agent standings window (region-scoped close, fixed-location fallback)");
        Rectangle box = Layout.standingsWindow();
        boolean closed = ocrBridge.clickTemplateInRegion("close_active_window",
                box.x, box.y, box.width, box.height);
        if (!closed) {
            Point x = Layout.standingsClose();
            log("Close 'X' not matched in region — clicking fixed location " + x.x + "," + x.y);
            ocrBridge.clickPos(x.x, x.y);
        }
        sleep(POST_CLICK_DELAY_MS);
    }

    /**
     * Returns true if the agent's "standing too low" refusal message is on screen —
     * the agent won't hand out missions (effective standing &lt; 2). Template-based
     * (not the flaky standings OCR), so a match is a reliable refusal signal.
     */
    public boolean isStandingsRefused() {
        return ocrBridge.findAnyTemplate("standings_refused") != null;
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
     * Dock button or agent icon — the home-arrival elements, excluding the always-present
     * stargate. Used to end the RETURNING_HOME landing settle only once we've actually arrived
     * home, so a stargate (which renders before the DOCK button) can't short-circuit the wait.
     */
    public String detectDockOrAgent() {
        return ocrBridge.findAnyTemplate("dock", "agent_icon");
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

    /** What the info-panel Route section says. */
    public static class RouteInfo {
        /** True when the Route section is showing a route — the "did set_destination take?" answer. */
        public final boolean routeSet;
        /** Jumps remaining, or -1 if unreadable. Its tiny font fails OCR more often than the rest. */
        public final int jumps;
        /** Last waypoint system read (e.g. "S-MDYI"), or null. */
        public final String destination;
        /** Raw OCR text of the panel — what the bot actually saw, for diagnosing misreads. */
        public final String raw;

        RouteInfo(boolean routeSet, int jumps, String destination, String raw) {
            this.routeSet = routeSet;
            this.jumps = jumps;
            this.destination = destination;
            this.raw = raw == null ? "" : raw;
        }

        public boolean isSet() { return routeSet; }

        /** One-line dump of the raw panel OCR, newlines flattened. */
        public String rawOneLine() { return raw.replaceAll("\\s*\\R\\s*", " | ").trim(); }

        @Override public String toString() {
            if (!routeSet) return "no route";
            return (jumps >= 0 ? jumps + " jump(s)" : "unknown jumps")
                    + (destination != null ? " to " + destination : "");
        }
    }

    /**
     * OCR the info-panel Route section. Unlike the transient destination_set toast, the Route
     * panel stays up for the whole leg, so this is the reliable "the destination actually took"
     * check. One screenshot; the OCR and parsing happen in Python.
     */
    public RouteInfo readRoute() {
        Rectangle r = Layout.routePanel();
        com.google.gson.JsonObject v = ocrBridge.readRoute(r.x, r.y, r.width, r.height);
        if (v == null) return new RouteInfo(false, -1, null, null);
        String dest = v.has("destination") && !v.get("destination").isJsonNull()
                ? v.get("destination").getAsString() : null;
        String raw = v.has("raw") && !v.get("raw").isJsonNull() ? v.get("raw").getAsString() : null;
        return new RouteInfo(v.get("route_set").getAsBoolean(), v.get("jumps").getAsInt(), dest, raw);
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
        return ocrBridge.findAnyTemplate("warp_active", "warp_active2", "warp_active3") != null;
    }

    /**
     * Returns true if the capsule template is visible — ship was destroyed.
     */
    public boolean isCapsuleVisible() {
        return ocrBridge.findAnyTemplate("capsule") != null;
    }

    /**
     * Pod detection (two-stage, in the daemon). Stage 1: cheap passive match on the
     * "no_ship" module sliver — if visible we're in a ship, return immediately.
     * Stage 2 (only if the sliver is missing): right-click our ship and look for the
     * "Eject" menu item (offered only in a ship) to confirm. Returns true if podded,
     * false if still in a ship.
     */
    public boolean isFlyingPod() {
        return ocrBridge.isFlyingPod();
    }

    /** Max times to re-fire the self-destruct sequence while waiting for the timer to start. */
    private static final int MAX_DESTRUCT_ATTEMPTS = 5;

    /**
     * Self-destructs the capsule: right-click capsule → click destruct → click yes, then
     * verify the "Your Capsule will self-destruct" message ('destruct_confirm') appears — that
     * is the only signal the timer actually started (and we'll be teleported to the station).
     * A missed right-click or a swallowed dialog can drop the sequence, so repeat it until the
     * confirmation shows (up to {@link #MAX_DESTRUCT_ATTEMPTS}).
     *
     * Returns false (without clicking anything) if no capsule is actually on screen, so a false
     * pod detection can't blindly fire the destruct sequence.
     */
    public boolean clickDestruct() {
        if (ocrBridge.findAnyTemplate("capsule") == null) {
            log("Self-destruct aborted — no capsule on screen (not actually podded).");
            return false;
        }
        for (int attempt = 1; attempt <= MAX_DESTRUCT_ATTEMPTS; attempt++) {
            log("Self-destruct attempt " + attempt + "/" + MAX_DESTRUCT_ATTEMPTS
                    + ": right-click capsule → destruct → yes_destruct");
            rightClickAndSelect("capsule", "destruct");
            sleep(POST_CLICK_DELAY_MS);
            // The confirmation dialog takes a moment to render — wait for the 'yes_destruct'
            // button instead of an immediate (and usually-too-early) click.
            if (!ocrBridge.waitAndClickTemplate("yes_destruct", 3)) {
                log("Self-destruct: 'yes_destruct' confirm button not found within 3s.");
            }
            // Confirm the timer started: the "Your Capsule will self-destruct" message must show.
            if (ocrBridge.waitForTemplate("destruct_confirm", 3)) {
                log("Self-destruct confirmed — 'Your Capsule will self-destruct' visible, timer running.");
                return true;
            }
            log("Self-destruct not confirmed (destruct_confirm not visible) — re-firing the sequence.");
        }
        log("Self-destruct: never confirmed after " + MAX_DESTRUCT_ATTEMPTS + " attempts — proceeding anyway.");
        return true;
    }

    /** Generic left-click on any registered template. */
    public void clickTemplate(String key) {
        ocrBridge.clickTemplate(key);
    }

    /** True once the template is no longer on screen (waits up to timeoutSeconds). */
    public boolean waitForTemplateGone(String key, int timeoutSeconds) {
        return ocrBridge.waitForTemplateGone(key, timeoutSeconds);
    }

    /** True once the template appears on screen (waits up to timeoutSeconds). */
    public boolean waitForTemplate(String key, int timeoutSeconds) {
        return ocrBridge.waitForTemplate(key, timeoutSeconds);
    }

    // Kill Report layout (window locked in a fixed spot). The "Final Blow" pilot name link sits at a
    // stable location (Layout.killerClick()); we right-click it to open the pilot context menu.
    /**
     * Open the latest Kill Report from the feed and add its Final Blow pilot (the one who podded
     * us) as a Bad (orange) CORPORATE contact, so they show flagged next time. Pure screen
     * automation — no API. Best-effort: any missing template makes its click a silent no-op.
     * MUST run while docked and in the foreground (the kill report closes on undock).
     *
     * Right-click the Final Blow name (fixed coords) → add_corporate_contact → standing_bad →
     * add_contact_confirm → close the kill report.
     *
     * The report's location header also names the system we died in, which is the only exact
     * source we have for it — so read it here, while the report is open, and hand it back for
     * pod avoidance.
     *
     * @return the system we were podded in (e.g. "X47L-Q"), or null if it couldn't be read.
     */
    public String addKillerFromKillReport() {
        log("Opening kill report to add the killer as a bad (orange) corporate contact.");

        // 1. Open the feed (bottom-right) and the latest kill report.
        if (!openKillReport()) {
            log("Kill report did not open — aborting bad-contact add.");
            return null;
        }

        // 2. Read where we died before touching anything — a context menu opened by the
        //    right-click below can cover the header.
        String poddedSystem = readKillReportSystem();

        // 3. Right-click the Final Blow name. If the menu shows Edit Corporate Contact the pilot
        //    is already added — skip. Otherwise, if it offers Add Corporate Contact, use it.
        Point killer = Layout.killerClick();
        rightClickPos(killer.x, killer.y);
        sleep(500);
        if (findTemplate("edit_corp_contact") != null) {
            log("edit_corp_contact on the name right-click — already a corporate contact, skipping add.");
            EventLog.log("CONTACT_ADD_SKIPPED", "edit_corp_contact on kill-report right-click — already a corporate contact");
            dismissKillerMenu();
        } else if (ocrBridge.waitAndClickTemplate("add_corporate_contact", 2)) {
            log("add_corporate_contact clicked — waiting for the New-Contact dialog.");
            sleep(700);
            confirmBadContactStanding();
        } else {
            // No Add-Contact option on the menu — the pilot is most likely already a contact.
            // Just record it and proceed to close the report.
            log("add_corporate_contact missing from the name right-click — likely already added, proceeding.");
            EventLog.log("CONTACT_ADD_SKIPPED", "add_corporate_contact missing on kill-report right-click — likely already a contact");
            // The context menu may still be open — dismiss it (left-click empty space, never
            // Escape) so the close-X click below hits the report instead of the menu.
            dismissKillerMenu();
        }
        closeKillReport(); // close the kill report on its X (~1240,22), scoped away from the other windows
        return poddedSystem;
    }

    /**
     * OCR the kill report's location header — "X47L-Q -0.3 &lt; MDM8-J &lt; Pure Blind" — and return
     * the system we were killed in, or null if it didn't read as a system on the map. The kill
     * report must already be open.
     *
     * A name that isn't on the map is dropped rather than recorded: avoidance cuts that system out
     * of the graph, and a garbage name would cut out nothing while looking like it worked.
     */
    public String readKillReportSystem() {
        Rectangle r = Layout.killReportSystem();
        com.google.gson.JsonObject v = ocrBridge.readKillSystem(r.x, r.y, r.width, r.height);
        if (v == null) {
            log("Kill report location: OCR call failed — pod system unknown.");
            return null;
        }
        String raw = v.has("raw") && !v.get("raw").isJsonNull() ? v.get("raw").getAsString() : "";
        String system = v.has("system") && !v.get("system").isJsonNull() ? v.get("system").getAsString() : null;
        String flatRaw = raw.replaceAll("\\s*\\R\\s*", " | ").trim();

        if (system == null) {
            log("Kill report location: no system in [" + flatRaw + "] — pod system unknown.");
            return null;
        }
        if (!SystemMap.isKnown(system)) {
            log("Kill report location: '" + system + "' (raw [" + flatRaw + "]) is not on the route map"
                    + " — ignoring it rather than avoiding a system we can't place.");
            return null;
        }
        String onMap = SystemMap.display(SystemMap.resolve(system));
        log("Kill report location: podded in " + onMap + " (OCR '" + system + "', raw [" + flatRaw + "]).");
        return onMap;
    }

    /**
     * Open the latest kill report: feed icon (bottom-right) → kill_report entry.
     * Verifies each template is actually on screen before clicking (clickTemplate is
     * fire-and-forget and would silently no-op on a miss, leaving the caller to act on
     * a report that never opened). Returns true only if both steps matched.
     */
    public boolean openKillReport() {
        int[] feed = ocrBridge.findTemplatePos("feed_icon");
        if (feed == null) {
            log("feed_icon not found — is the client docked with the feed icon visible (bottom-right)? Aborting.");
            return false;
        }
        log("Opening feed (feed_icon at " + feed[0] + "," + feed[1] + ").");
        ocrBridge.clickPos(feed[0], feed[1]);
        sleep(700);

        // The feed lists newest first, so when several kill reports are stacked take the
        // topmost match — the best-scoring one could be any of them.
        int[] report = ocrBridge.findTemplatePosTopmost("kill_report");
        if (report == null) {
            log("kill_report entry not found in the feed — no kill report to open. Aborting.");
            return false;
        }
        log("Clicking the latest kill report (topmost kill_report at " + report[0] + "," + report[1] + ").");
        ocrBridge.clickPos(report[0], report[1]);
        sleep(1300); // let the kill report window open
        return true;
    }

    /**
     * Finish adding a Bad (orange) corporate contact once the pilot's "Add Corporate Contact" has
     * been clicked. If the pilot is already a contact, "Edit Contact" (edit_contact) shows instead —
     * there's nothing to add, so just click it (the caller closes the window afterward). Otherwise
     * pick the orange "Bad" standing via a color-aware click (so it can't grab the adjacent red
     * "Terrible" square) and confirm.
     */
    private void confirmBadContactStanding() {
        if (findTemplate("edit_contact") != null) {
            log("edit_contact present — pilot already a contact; clicking edit (no re-add).");
            ocrBridge.clickTemplate("edit_contact");
            sleep(500);
            return;
        }
        // Anchor on the dialog's Add Contact button BEFORE any standing click. If it isn't there,
        // the dialog never opened — do NOT hunt for the orange square across the whole screen
        // (the low-threshold color scan once matched the orange UNDOCK button and undocked a pod).
        int[] confirm = ocrBridge.findTemplatePos("add_contact_confirm");
        if (confirm == null) {
            log("add_contact_confirm not found — New-Contact dialog did not open; skipping standing clicks, contact NOT saved.");
            return;
        }
        // The standing squares sit above the Add Contact button inside the same dialog — scope
        // the color click to that box so it can't grab similarly-colored UI elsewhere.
        if (ocrBridge.clickColorInRegion("standing_bad",
                confirm[0] - 250, confirm[1] - 330, 500, 310)) {
            log("standing_bad (orange) clicked.");
        } else {
            log("standing_bad (orange) not matched in the dialog — standing left at default.");
        }
        sleep(300);
        ocrBridge.clickPos(confirm[0], confirm[1]);
        log("add_contact_confirm clicked — bad contact saved.");
        sleep(500);
    }

    /**
     * Dismiss the Final-Blow context menu with a left-click (never Escape). The menu opens down-right
     * from the right-click point, so the click goes up-left of it — a fixed mid-screen point lands on
     * the menu itself and can fire a menu item (it was clicking Add Contact on the skip path).
     */
    private void dismissKillerMenu() {
        Point killer = Layout.killerClick();
        clickPos(killer.x - 250, killer.y - 120);
        sleep(300);
    }

    /**
     * Close the kill report on its X. The close 'X' is located by matching the close_active_window
     * template full-screen (like undock's closeAllDialogs) — it closes whichever window is on top,
     * and the kill report is the only window left once the contact dialog has confirm-closed itself.
     */
    private void closeKillReport() {
        if (ocrBridge.findAnyTemplate("close_active_window") != null) {
            ocrBridge.clickTemplate("close_active_window");
            log("Kill report closed (close 'X' template matched).");
        } else {
            log("Kill report: close 'X' template not found.");
        }
        sleep(400);
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
        // ship_infested
        if (lower.contains("infested") || lower.contains("ruins")) return "infested";
        if (lower.contains("recon"))                         return "recon1";
        // Dread Pirate Scarlet (boss "Anire") — also flies ship_infested.
        if (lower.contains("scarlet") || lower.contains("anire"))  return "scarlet";
        // Pot and Kettle — Garmur vs the Prophecy.
        if (lower.contains("pot") || lower.contains("kettle") || lower.contains("prophecy")) return "pot";
        return null;
    }

    private void log(String msg) {
        System.out.printf("[%s] [%s] %s%n",
                java.time.LocalDateTime.now().format(TS), OCRBridge.getActiveClient(), msg);
    }
}