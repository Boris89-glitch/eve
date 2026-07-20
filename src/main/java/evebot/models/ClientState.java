package evebot.models;

import java.time.LocalDateTime;

/**
 * Tracks the current state of a single EVE Online client window.
 *
 * State flow (happy path):
 *
 *   IDLE
 *     └─► CHECKING_MISSIONS   (mission dialog detected)
 *           ├─► DECLINING      (mission did not meet criteria — re-request loop)
 *           │     └─► CHECKING_MISSIONS  (new offer arrived)
 *           ├─► ACCEPTED       (mission met accept criteria)
 *           │     └─► REPAIRING   (ship repaired + activated, undock clicked)
 *           │           └─► UNDOCKING  (waiting 15s for undock animation)
 *           │                 └─► TRAVEL    (undocked, warping to mission site)
 *           │                       └─► IN_COMBAT  (on grid, fighting)
 *           │                             └─► RETURNING  (combat done, warping/docking back)
 *           │                                   └─► COMPLETING → IDLE
 *           └─► IDLE           (fallback)
 */
public class ClientState {

    public enum State {
        IDLE,
        CHECKING_MISSIONS,
        DECLINING,
        ACCEPTED,
        REPAIRING,
        UNDOCKING,    // undock clicked, waiting 15s for animation to complete
        TRAVEL,       // one-shot setup: close dialogs, reload modules, set destination
        WARPING,      // in warp — polling warp_active until it disappears, then → IDLE
        IN_COMBAT,
        RETURNING,    // one-shot setup: set destination home
        COMPLETING,
        POD_COOLDOWN, // podded — waiting 10min cooldown before resuming
        FACTION_CHECK,      // open the faction agent, classify his offer (every 2h)
        FACTION_TRAVEL,     // undock + right-click station (soe/home) + dock → WARPING
        FACTION_COMPLETING  // turn in the kernite mission at the SOE station
    }

    /** Tracks which leg of navigation IDLE should handle after a warp lands. */
    public enum TravelPhase {
        NONE,            // at station — check missions normally
        TO_MISSION,      // en route to mission — look for stargates / warp_to_location / activate_gate
        RETURNING_HOME   // heading back   — look for stargates / dock
    }

    /** Which leg of the faction kernite run is in progress (drives FACTION_TRAVEL + WARPING dock routing). */
    public enum FactionLeg {
        NONE,     // not on a faction run
        TO_SOE,   // outbound — undock home, dock at the SOE station to complete
        TO_HOME   // returning — undock SOE, dock back home to resume normal checking
    }

    // -----------------------------------------------------------------------

    private final String clientId;
    private State currentState;
    private Mission currentMission;
    private LocalDateTime lastStateChange;
    private LocalDateTime lastMissionCheck;

    /** True once the accepted mission has been registered in the journal / tracking. */
    private boolean missionTracked = false;

    /** True once the Track button has been clicked for this client (per-client, once per session). */
    private boolean trackAttempted = false;

    /** True once set_destination has been clicked for the current travel/returning leg. */
    private boolean destinationSet = false;

    /** True once warp_to_location has been clicked — bot is in the mission system, activate_gate only. */
    private boolean warpedToLocation = false;

    /** Timestamp when the current CHECKING_MISSIONS/DECLINING cycle began — persists across declines. */
    private LocalDateTime missionCheckStartTime = LocalDateTime.MIN;

    /** Timestamp of the last stargate jump click during TRAVEL or RETURNING. */
    private LocalDateTime lastTravelAction = LocalDateTime.MIN;

    /** Timestamp when Undock was clicked — used to track the 15s undock animation wait. */
    private LocalDateTime undockStartTime = LocalDateTime.MIN;

    /** Timestamp when a warp action was initiated — used by WARPING state. */
    private LocalDateTime warpStartTime = LocalDateTime.MIN;

    /** Timestamp of the last warp_active template check in WARPING state. */
    private LocalDateTime lastWarpCheck = LocalDateTime.MIN;

    /** Which navigation leg is active — used by IDLE to know what to do after a warp lands. */
    private TravelPhase travelPhase = TravelPhase.NONE;

    /** True on the first IDLE tick right after exiting WARPING — triggers the 8s jump-settle wait. */
    private boolean justExitedWarp = false;

    /** True when the just-ended warp was long enough that the landing safe wait should be skipped. */
    private boolean skipLandingSettle = false;

    /** Current step in the bubble escape sequence (0 = not in bubble escape). Reset on entering WARPING. */
    private int bubbleEscapeStep = 0;

    /** Timestamp when the current bubble escape wait began. */
    private LocalDateTime bubbleEscapeStart = LocalDateTime.MIN;

    /** Full bubble-escape cycles attempted this WARPING (capped at 2). Reset on entering WARPING. */
    private int bubbleEscapeAttempts = 0;

    /** Times the current warp has been re-issued after a failed warp-start check
     *  (bubble present or warp_active not showing). Reset on entering WARPING. */
    private int warpRetryCount = 0;

    /** Consecutive dock re-issues in WARPING where no DOCK button or station was on screen, so
     *  nothing was actually clicked. Reset on entering WARPING and whenever a dock does get
     *  issued; at the cap the client drops to IDLE instead of re-issuing forever. */
    private int dockReissueMissCount = 0;

    /** Consecutive WARPING warp re-issues where no stargate / warp-to-location was on screen — the
     *  usual cause is a session change (gate jump still loading), where warp_active is missing too.
     *  These don't consume the warp retry; at the cap the normal retry/redock path takes over. */
    private int warpReissueMissCount = 0;

    /** Consecutive insta-dock attempts where the dock warp never engaged. Counted in IDLE
     *  while re-trying the insta-dock; once it hits the cap the caller falls back to a direct
     *  dock. Reset on entering WARPING (i.e. once we commit to a dock/warp). */
    private int instaDockMissCount = 0;

    /** True when the current WARPING was entered to evade to a safe spot (enemy in local).
     *  If warp_active never engages, the handler nudges + retries the safe-spot warp twice,
     *  then abandons the evade and jumps the gate normally instead of redocking. */
    private boolean safeSpotWarp = false;

    /** Current step in the combat sequence (0 = first step). Reset on entering IN_COMBAT. */
    private int combatStep = 0;

    /** Effective overview-row template key for the current combat target. Set at the lock
     *  step to the mission-specific template, or 'alt_target' when that can't be found, and
     *  reused by later steps (range-keep etc.) so the whole fight acts on the same row. */
    private String combatTargetKey = null;

    /** Set when THIS client was podded and still owes an in-game "add the killer from the kill
     *  report as a bad contact" once the cooldown ends (we're docked with focus). Only the podded
     *  client sets it, so the other client sharing the global cooldown doesn't run the add. */
    private boolean pendingKillReportAdd = false;

    /** One-shot: has the focus-away click been done for this combat? Reset on entering IN_COMBAT. */
    private boolean combatFocusClicked = false;

    /** Timestamp when the current combat safe-wait began — used to measure elapsed wait time. */
    private LocalDateTime combatWaitStart = LocalDateTime.MIN;

    /** Timestamp of the last dead/gone poll — throttles the every-5s "is the small target gone" check. */
    private LocalDateTime combatPollStart = LocalDateTime.MIN;

    /** Timestamp when the current combat sequence began (step 0) — used for whole-fight timing. */
    private LocalDateTime combatStartTime = LocalDateTime.MIN;

    /** True once the Sansha extra Alt+F1 cap pulse (fight > 35s) has fired this combat. */
    private boolean extraCapPulseDone = false;

    /** Consecutive nudge-retries the current combat target acquisition has spent hunting the
     *  PRIMARY target row. Only after these are exhausted does acquisition fall back to the
     *  generic 'alt_target' row. Reset to 0 the moment a target (primary or alt) is found. */
    private int combatTargetNudges = 0;


    /** True once the one-shot 20s support-frigate lock re-check has run this combat
     *  (team missions excluding Jaguar). Targets can drift out of lock even after the
     *  25s settle wait, so ~20s into the firing safe-wait we verify the frigate lock
     *  once and re-lock+re-fire if it's gone. Reset on entering IN_COMBAT. */
    private boolean frigateLockRechecked = false;

    /** True once the gate warp has been verified and landed — combat can begin after a short wait. */
    private boolean combatWarpLanded = false;

    /** True once warp_active has been observed during the gate-warp verification. Lets the verifier
     *  tell "gate never fired (grayed)" — re-click — from "warp fired, now stopped" — landed, proceed.
     *  Reset on IN_COMBAT. */
    private boolean combatWarpSeen = false;

    /** Consecutive gate-warp polls that have read warp_active gone after warp fired — must reach
     *  WARP_END_MISS_CONFIRM before landing is declared. Reset to 0 whenever warp_active is seen. */
    private int warpEndMisses = 0;

    /** Parked for the rest of the session: the agent refused missions (standing too low).
     *  This client yields to the other and does no work until the bot is restarted.
     *  Deliberately NOT reset by setState — it persists across all state transitions. */
    private boolean standingsLocked = false;

    /** Parked for the rest of the session due to an unrecoverable error (e.g. redock not
     *  confirmed, repair failed / hangar not found). Yields to the other client and does no
     *  work until the bot is restarted. Like standingsLocked, NOT reset by setState. */
    private boolean parked = false;

    /** Last agent standing read at mission completion, used to gate standings-restricted
     *  missions (recon 1, scarlet, pot-and-kettle — accepted only when standing &lt; 1).
     *  NaN until the first completion populates it. NOT reset by setState.
     *  Shared across clients via {@link evebot.StateManager#getOtherState}: a completing
     *  client compares its own standing against the fleet partner's to decide the fleet reward. */
    private double lastKnownStanding = Double.NaN;

    /** When this client last grabbed (accepted) a mission. Read by the fleet partner: for 2
     *  minutes after a grab, the partner raises its take-all threshold (fleet reward). MIN until
     *  the first accept. NOT reset by setState. */
    private LocalDateTime lastMissionGrab = LocalDateTime.MIN;

/** True when docking to home station after combat — WARPING should transition to COMPLETING. */
    private boolean dockingToStation = false;

    /** Set when an insta-dock (enemy in local) engaged: instaDock() already slept ~7s and verified
     *  warp_active before firing Ctrl+S, so the dock warp is confirmed on WARPING entry. WARPING
     *  skips its grace + re-verification and hands off focus on the first tick. One-shot. */
    private boolean warpPreVerified = false;

    /** True for the Infested escape warp — WARPING watches for a jam (3s) instead of the bubble switch. */
    private boolean jamCheckOnWarp = false;

    /** True once the prop mod ('s') has been pressed during an Infested jam — press it once only (every other press toggles it off). */
    private boolean jamPropPressed = false;

    /** Set when set_destination was missed on undock and re-clicked one jump late: the route now
     *  points back 1 jump, so the next gate jump lands on top of the gate and shows no warp tunnel.
     *  WARPING skips warp_active verification for that jump (press 's', switch client, 15s safe wait,
     *  then resume normal navigation) instead of misreading the instant jump as a failed warp. One-shot. */
    private boolean simplifyNextWarp = false;

    /** Scarlet: set when warping to the mission location. On the next landing the bot enters
     *  IN_COMBAT directly — the mission is already started (no first activate_gate to trigger it). */
    private boolean enterCombatOnWarpLand = false;

    /** When the pod-death cooldown expires for this client. */
    private LocalDateTime podCooldownEnd = LocalDateTime.MIN;

    /** Set when an emergency redock fired and we switched away: the next time this client
     *  regains focus (in POD_COOLDOWN), check for pod_station before anything else. A match
     *  means the redock failed and we were podded. One-shot, cleared after the check.
     *  NOT reset by setState (a cross-state flag like dockingToStation). */
    private boolean pendingPodStationCheck = false;

    /** True after the first re-click of decline in DECLINING — next failure triggers agent double-click. */
    private boolean declineRetried = false;

    /** True only when CHECKING_MISSIONS was entered as a cold inspection of the agent dialog
     *  (startup / IDLE opened the agent itself), where a NOT_COMPLETE reading may be a mission
     *  accepted before the bot took control and should be recovered + flown. In the normal
     *  request/decline cycle the dialog is a FRESH offer, so a flaky accept/decline miss can make
     *  detectDialog read the offer's objectives text as NOT_COMPLETE — flying it would undock an
     *  unwanted mission with a mismatched ship. Default false (the safe direction: evaluate, don't
     *  fly). Set true only at the IDLE cold-check entries; cleared on leaving CHECKING_MISSIONS. */
    private boolean allowNotCompleteRecovery = false;

    /** Running count of non-"anomic" missions declined (by OCR title) since the last standings
     *  re-read. Every 4th such decline schedules a standings check so mission gating stays current. */
    private int nonAnomicDeclineCount = 0;

    /** Set when nonAnomicDeclineCount reaches the threshold — DECLINING re-reads agent standings. */
    private boolean standingsCheckDue = false;

    /** Number of stargate jumps since undocking — logged on pod death for location context. */
    private int jumpCount = 0;

    /** True once KLY system check has been performed after first jump — skip further checks. */
    private boolean klyChecked = false;

    /** True once lingering windows have been cleared after the first jump out of the home station —
     *  one-shot so we only sweep close_active_window once per trip. */
    private boolean windowsClosedAfterJump1 = false;

    /** True after evading to a safe spot — next landing jumps out instead of re-evading. Cleared on stargate jump. */
    private boolean warpedToSafeSpot = false;

    /** Last time a view-nudge (camera drag) was issued to refresh the frame before a
     *  detection retry — throttles the nudge so it doesn't fire every tick. */
    private LocalDateTime lastViewNudge = LocalDateTime.MIN;

    /** Last time the POD_COOLDOWN handler swapped clients — prevents rapid-fire switching. */
    private LocalDateTime lastCooldownSwap = LocalDateTime.MIN;

    /** Last time the POD_COOLDOWN handler logged the remaining time — throttles the countdown log. */
    private LocalDateTime lastCooldownLog = LocalDateTime.MIN;

    /** Last time the WARPING test pod check ran — throttles the eject-menu probe. */
    private LocalDateTime lastPodCheck = LocalDateTime.MIN;

    /** Last time COMPLETING clicked the dock fallback — throttles re-clicks while waiting to dock. */
    private LocalDateTime lastCompletingDock = LocalDateTime.MIN;

    /** When the IDLE travel loop first found no navigation UI on screen (MIN = not waiting). Drives the 60s podded-at-station escalation. */
    private LocalDateTime noTravelUiSince = LocalDateTime.MIN;

    /** Parked for the rest of the session: the faction agent offered "Evolution".
     *  Per the user's workflow that mission is done manually, so the bot stops checking
     *  the faction agent until restart. Like standingsLocked, NOT reset by setState. */
    private boolean factionLocked = false;

    /** Last time the faction agent was checked — the 2h faction-check cadence. Initialised to
     *  MIN so the very first check fires before anything else (on the first idle tick), then
     *  every 2h after. NOT reset by setState. */
    private LocalDateTime lastFactionCheck = LocalDateTime.MIN;

    /** Last time a faction mission was declined — enforces the "decline at most once per 4h" rule.
     *  MIN until the first decline. NOT reset by setState. */
    private LocalDateTime lastFactionDecline = LocalDateTime.MIN;

    /** Which leg of the faction kernite run is active — drives FACTION_TRAVEL station choice and
     *  the WARPING post-dock routing. NOT reset by setState (a cross-state flag like dockingToStation). */
    private FactionLeg factionLeg = FactionLeg.NONE;

    /** Set when a faction run has docked back home: IDLE must wait for the docked confirmation
     *  (agent_icon) before resuming normal checking, and must NOT run the travel-resume detection
     *  meanwhile — a transient stargate frame during the dock approach would otherwise flip us
     *  into a phantom TO_MISSION. NOT reset by setState. */
    private boolean factionResumePending = false;

    /** Last time the ship was confirmed alive and active — entering combat or checking missions.
     *  (Returning is right after combat, so it's redundant as a liveness signal; mission checking
     *  proves the ship made it home and docked.) Gates the warp-entry pod check: only probe once
     *  5min pass without either event. */
    private LocalDateTime lastCombatOrCheck = LocalDateTime.now();

    // -----------------------------------------------------------------------

    public ClientState(String clientId) {
        this.clientId         = clientId;
        this.currentState     = State.IDLE;
        this.lastStateChange  = LocalDateTime.now();
        this.lastMissionCheck = LocalDateTime.now();
    }

    // -----------------------------------------------------------------------
    // Getters & setters
    // -----------------------------------------------------------------------

    public String getClientId() { return clientId; }

    public State getState() { return currentState; }

    public void setState(State state) {
        this.currentState    = state;
        this.lastStateChange = LocalDateTime.now();
        if (state == State.IDLE || state == State.CHECKING_MISSIONS || state == State.DECLINING) {
            this.missionTracked = false;
        }
        if (state == State.DECLINING) {
            this.declineRetried = false;
        }
        if (state != State.CHECKING_MISSIONS && state != State.DECLINING) {
            this.missionCheckStartTime = LocalDateTime.MIN;
        }
        if (state != State.CHECKING_MISSIONS) {
            // Cold-check recovery is only valid for the CHECKING pass it was set for.
            this.allowNotCompleteRecovery = false;
        }
        if (state == State.TRAVEL || state == State.RETURNING) {
            this.destinationSet   = false;
            this.warpedToLocation = false;
            this.lastTravelAction = LocalDateTime.MIN;
            this.jumpCount        = 0;
            this.klyChecked       = false;
            this.warpedToSafeSpot = false;
            this.safeSpotWarp     = false;
            this.windowsClosedAfterJump1 = false;
        }
        if (state == State.WARPING) {
            this.lastWarpCheck = LocalDateTime.MIN;
            this.bubbleEscapeStep = 0;
            this.bubbleEscapeStart = LocalDateTime.MIN;
            this.bubbleEscapeAttempts = 0;
            this.warpRetryCount = 0;
            this.dockReissueMissCount = 0;
            this.warpReissueMissCount = 0;
            this.instaDockMissCount = 0;
            this.safeSpotWarp = false; // evade entries re-set this true after setState(WARPING)
        }
        if (state == State.IN_COMBAT) {
            this.combatStep = 0;
            this.combatFocusClicked = false;
            this.combatWaitStart = LocalDateTime.MIN;
            this.combatPollStart = LocalDateTime.MIN;
            this.combatWarpLanded = false;
            this.combatWarpSeen = false;
            this.warpEndMisses = 0;
            this.lastViewNudge = LocalDateTime.MIN;
            this.frigateLockRechecked = false;
        }
        if (state == State.FACTION_TRAVEL) {
            // Each FACTION_TRAVEL leg re-undocks and re-spins from scratch.
            this.undockStartTime = LocalDateTime.MIN;
            this.lastViewNudge   = LocalDateTime.MIN;
        }
    }

    public Mission getCurrentMission() { return currentMission; }

    public void setCurrentMission(Mission mission) {
        this.currentMission   = mission;
        this.lastMissionCheck = LocalDateTime.now();
    }

    public boolean isMissionTracked()           { return missionTracked; }
    public void    setMissionTracked(boolean t) { this.missionTracked = t; }

    public boolean isTrackAttempted()           { return trackAttempted; }
    public void    setTrackAttempted(boolean v) { this.trackAttempted = v; }

    public boolean isDestinationSet()           { return destinationSet; }
    public void    setDestinationSet(boolean v) { this.destinationSet = v; }

    public boolean isWarpedToLocation()           { return warpedToLocation; }
    public void    setWarpedToLocation(boolean v) { this.warpedToLocation = v; }

    public LocalDateTime getMissionCheckStartTime()                { return missionCheckStartTime; }
    public void          setMissionCheckStartTime(LocalDateTime t) { this.missionCheckStartTime = t; }

    public LocalDateTime getLastTravelAction()                { return lastTravelAction; }
    public void          setLastTravelAction(LocalDateTime t) { this.lastTravelAction = t; }

    public LocalDateTime getUndockStartTime()                { return undockStartTime; }
    public void          setUndockStartTime(LocalDateTime t) { this.undockStartTime = t; }

    public LocalDateTime getWarpStartTime()                { return warpStartTime; }
    public void          setWarpStartTime(LocalDateTime t) { this.warpStartTime = t; }

    public LocalDateTime getLastWarpCheck()                { return lastWarpCheck; }
    public void          setLastWarpCheck(LocalDateTime t) { this.lastWarpCheck = t; }

    public TravelPhase getTravelPhase()              { return travelPhase; }
    public void        setTravelPhase(TravelPhase p) { this.travelPhase = p; }

    public boolean isJustExitedWarp()              { return justExitedWarp; }
    public void    setJustExitedWarp(boolean v)    { this.justExitedWarp = v; }
    public boolean isSkipLandingSettle()           { return skipLandingSettle; }
    public void    setSkipLandingSettle(boolean v) { this.skipLandingSettle = v; }

    public int  getCombatStep()             { return combatStep; }
    public void setCombatStep(int step)     { this.combatStep = step; }

    public boolean isPendingKillReportAdd()         { return pendingKillReportAdd; }
    public void setPendingKillReportAdd(boolean v)  { this.pendingKillReportAdd = v; }

    public String getCombatTargetKey()          { return combatTargetKey; }
    public void   setCombatTargetKey(String k)  { this.combatTargetKey = k; }

    public boolean isCombatFocusClicked()          { return combatFocusClicked; }
    public void    setCombatFocusClicked(boolean b) { this.combatFocusClicked = b; }

    public LocalDateTime getCombatWaitStart()                { return combatWaitStart; }
    public void          setCombatWaitStart(LocalDateTime t) { this.combatWaitStart = t; }

    public LocalDateTime getCombatPollStart()                { return combatPollStart; }
    public void          setCombatPollStart(LocalDateTime t) { this.combatPollStart = t; }

    public boolean isCombatWarpLanded()              { return combatWarpLanded; }
    public void    setCombatWarpLanded(boolean v)    { this.combatWarpLanded = v; }

    public boolean isCombatWarpSeen()                { return combatWarpSeen; }
    public void    setCombatWarpSeen(boolean v)      { this.combatWarpSeen = v; }

    public int  getWarpEndMisses()                   { return warpEndMisses; }
    public void setWarpEndMisses(int v)              { this.warpEndMisses = v; }


    public boolean isStandingsLocked()               { return standingsLocked; }
    public void    setStandingsLocked(boolean v)     { this.standingsLocked = v; }
    public boolean isParked()                        { return parked; }
    public void    setParked(boolean v)              { this.parked = v; }

    public boolean isFactionLocked()                 { return factionLocked; }
    public void    setFactionLocked(boolean v)       { this.factionLocked = v; }

    public LocalDateTime getLastFactionCheck()                { return lastFactionCheck; }
    public void          setLastFactionCheck(LocalDateTime t) { this.lastFactionCheck = t; }

    public LocalDateTime getLastFactionDecline()                { return lastFactionDecline; }
    public void          setLastFactionDecline(LocalDateTime t) { this.lastFactionDecline = t; }

    public FactionLeg getFactionLeg()             { return factionLeg; }
    public void       setFactionLeg(FactionLeg l) { this.factionLeg = l; }

    public boolean isFactionResumePending()           { return factionResumePending; }
    public void    setFactionResumePending(boolean v) { this.factionResumePending = v; }

    public LocalDateTime getCombatStartTime()        { return combatStartTime; }
    public void setCombatStartTime(LocalDateTime t)  { this.combatStartTime = t; }

    public boolean isExtraCapPulseDone()             { return extraCapPulseDone; }
    public void    setExtraCapPulseDone(boolean v)   { this.extraCapPulseDone = v; }

    public int  getCombatTargetNudges()             { return combatTargetNudges; }
    public void setCombatTargetNudges(int v)        { this.combatTargetNudges = v; }


    public boolean isFrigateLockRechecked()           { return frigateLockRechecked; }
    public void    setFrigateLockRechecked(boolean v) { this.frigateLockRechecked = v; }

    public int  getBubbleEscapeStep()             { return bubbleEscapeStep; }
    public void setBubbleEscapeStep(int step)     { this.bubbleEscapeStep = step; }

    public LocalDateTime getBubbleEscapeStart()                { return bubbleEscapeStart; }
    public void          setBubbleEscapeStart(LocalDateTime t) { this.bubbleEscapeStart = t; }

    public int  getBubbleEscapeAttempts()          { return bubbleEscapeAttempts; }
    public void setBubbleEscapeAttempts(int n)     { this.bubbleEscapeAttempts = n; }

    public int  getWarpRetryCount()         { return warpRetryCount; }
    public void setWarpRetryCount(int v)    { this.warpRetryCount = v; }

    public int  getDockReissueMissCount()      { return dockReissueMissCount; }
    public void setDockReissueMissCount(int v) { this.dockReissueMissCount = v; }

    public int  getWarpReissueMissCount()      { return warpReissueMissCount; }
    public void setWarpReissueMissCount(int v) { this.warpReissueMissCount = v; }

    public int  getInstaDockMissCount()      { return instaDockMissCount; }
    public void setInstaDockMissCount(int v) { this.instaDockMissCount = v; }

    public boolean isSafeSpotWarp()         { return safeSpotWarp; }
    public void    setSafeSpotWarp(boolean v) { this.safeSpotWarp = v; }

public boolean isDockingToStation()             { return dockingToStation; }
    public void    setDockingToStation(boolean v)   { this.dockingToStation = v; }

    public LocalDateTime getPodCooldownEnd()                { return podCooldownEnd; }
    public void          setPodCooldownEnd(LocalDateTime t) { this.podCooldownEnd = t; }

    public boolean isPendingPodStationCheck()           { return pendingPodStationCheck; }
    public void    setPendingPodStationCheck(boolean v) { this.pendingPodStationCheck = v; }

    public boolean isDeclineRetried()              { return declineRetried; }
    public void    setDeclineRetried(boolean v)    { this.declineRetried = v; }

    public boolean isAllowNotCompleteRecovery()           { return allowNotCompleteRecovery; }
    public void    setAllowNotCompleteRecovery(boolean v) { this.allowNotCompleteRecovery = v; }

    public int  getNonAnomicDeclineCount()         { return nonAnomicDeclineCount; }
    public void setNonAnomicDeclineCount(int v)    { this.nonAnomicDeclineCount = v; }

    public boolean isStandingsCheckDue()           { return standingsCheckDue; }
    public void    setStandingsCheckDue(boolean v) { this.standingsCheckDue = v; }

    public int  getJumpCount()          { return jumpCount; }
    public void incrementJumpCount()    { this.jumpCount++; }

    public boolean isKlyChecked()           { return klyChecked; }
    public void    setKlyChecked(boolean v) { this.klyChecked = v; }

    public boolean isWindowsClosedAfterJump1()           { return windowsClosedAfterJump1; }
    public void    setWindowsClosedAfterJump1(boolean v) { this.windowsClosedAfterJump1 = v; }

    public LocalDateTime getLastViewNudge()             { return lastViewNudge; }
    public void          setLastViewNudge(LocalDateTime v) { this.lastViewNudge = v; }

    public boolean isWarpedToSafeSpot()           { return warpedToSafeSpot; }
    public void    setWarpedToSafeSpot(boolean v) { this.warpedToSafeSpot = v; }

    public LocalDateTime getLastCooldownSwap()                { return lastCooldownSwap; }
    public void          setLastCooldownSwap(LocalDateTime t) { this.lastCooldownSwap = t; }

    public LocalDateTime getLastCooldownLog()                 { return lastCooldownLog; }
    public void          setLastCooldownLog(LocalDateTime t)  { this.lastCooldownLog = t; }

    public LocalDateTime getLastPodCheck()                { return lastPodCheck; }
    public void          setLastPodCheck(LocalDateTime t) { this.lastPodCheck = t; }

    public double getLastKnownStanding()             { return lastKnownStanding; }
    public void   setLastKnownStanding(double v)     { this.lastKnownStanding = v; }

    public LocalDateTime getLastMissionGrab()                { return lastMissionGrab; }
    public void          setLastMissionGrab(LocalDateTime t) { this.lastMissionGrab = t; }

    public boolean isEnterCombatOnWarpLand()           { return enterCombatOnWarpLand; }
    public void    setEnterCombatOnWarpLand(boolean v) { this.enterCombatOnWarpLand = v; }

    public LocalDateTime getLastCompletingDock()                { return lastCompletingDock; }
    public void          setLastCompletingDock(LocalDateTime t) { this.lastCompletingDock = t; }

    public LocalDateTime getNoTravelUiSince()                { return noTravelUiSince; }
    public void          setNoTravelUiSince(LocalDateTime t) { this.noTravelUiSince = t; }

    public LocalDateTime getLastCombatOrCheck()                { return lastCombatOrCheck; }
    public void          setLastCombatOrCheck(LocalDateTime t) { this.lastCombatOrCheck = t; }

    public boolean isJamCheckOnWarp()           { return jamCheckOnWarp; }
    public void    setJamCheckOnWarp(boolean v) { this.jamCheckOnWarp = v; }

    public boolean isJamPropPressed()           { return jamPropPressed; }
    public void    setJamPropPressed(boolean v) { this.jamPropPressed = v; }

    public boolean isWarpPreVerified()           { return warpPreVerified; }
    public void    setWarpPreVerified(boolean v) { this.warpPreVerified = v; }

    public boolean isSimplifyNextWarp()           { return simplifyNextWarp; }
    public void    setSimplifyNextWarp(boolean v) { this.simplifyNextWarp = v; }

    public LocalDateTime getLastStateChange()  { return lastStateChange;  }
    public LocalDateTime getLastMissionCheck() { return lastMissionCheck; }

    // -----------------------------------------------------------------------

    @Override
    public String toString() {
        return String.format("ClientState{id='%s', state=%s, mission=%s, tracked=%b}",
                clientId, currentState,
                currentMission != null ? currentMission.getTitle() : "none",
                missionTracked);
    }
}