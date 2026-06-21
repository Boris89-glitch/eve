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
        POD_COOLDOWN  // podded — waiting 10min cooldown before resuming
    }

    /** Tracks which leg of navigation IDLE should handle after a warp lands. */
    public enum TravelPhase {
        NONE,            // at station — check missions normally
        TO_MISSION,      // en route to mission — look for stargates / warp_to_location / activate_gate
        RETURNING_HOME   // heading back   — look for stargates / dock
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

    /** Current step in the bubble escape sequence (0 = not in bubble escape). Reset on entering WARPING. */
    private int bubbleEscapeStep = 0;

    /** Timestamp when the current bubble escape wait began. */
    private LocalDateTime bubbleEscapeStart = LocalDateTime.MIN;

    /** Current step in the combat sequence (0 = first step). Reset on entering IN_COMBAT. */
    private int combatStep = 0;

    /** Timestamp when the current combat safe-wait began — used to measure elapsed wait time. */
    private LocalDateTime combatWaitStart = LocalDateTime.MIN;

    /** Timestamp of the last dead/gone poll — throttles the every-5s "is the small target gone" check. */
    private LocalDateTime combatPollStart = LocalDateTime.MIN;

    /** Timestamp when the current combat sequence began (step 0) — used for whole-fight timing. */
    private LocalDateTime combatStartTime = LocalDateTime.MIN;

    /** True once the Sansha extra Alt+F1 cap pulse (fight > 35s) has fired this combat. */
    private boolean extraCapPulseDone = false;

    /** True once the gate warp has been verified and landed — combat can begin after a short wait. */
    private boolean combatWarpLanded = false;

    /** Consecutive IN_COMBAT polls that have seen warp_active gone — must reach WARP_END_MISS_CONFIRM
     *  before landing is declared. Reset to 0 whenever warp_active is seen again. */
    private int warpEndMisses = 0;

    /** Parked for the rest of the session: the agent refused missions (standing too low).
     *  This client yields to the other and does no work until the bot is restarted.
     *  Deliberately NOT reset by setState — it persists across all state transitions. */
    private boolean standingsLocked = false;

    /** Last agent standing read at mission completion, used to gate standings-restricted
     *  missions (recon 1, scarlet, pot-and-kettle — accepted only when standing &lt; 1).
     *  NaN until the first completion populates it. NOT reset by setState. */
    private double lastKnownStanding = Double.NaN;

/** True when docking to home station after combat — WARPING should transition to COMPLETING. */
    private boolean dockingToStation = false;

    /** True for the Infested escape warp — WARPING watches for a jam (3s) instead of the bubble switch. */
    private boolean jamCheckOnWarp = false;

    /** True after set_destination was clicked — verify it's gone after the first jump (else re-click). */
    private boolean verifySetDest = false;

    /** When the pod-death cooldown expires for this client. */
    private LocalDateTime podCooldownEnd = LocalDateTime.MIN;

    /** True after the first re-click of decline in DECLINING — next failure triggers agent double-click. */
    private boolean declineRetried = false;

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

    /** Last time the WARPING test pod check ran — throttles the eject-menu probe. */
    private LocalDateTime lastPodCheck = LocalDateTime.MIN;

    /** Last time COMPLETING clicked the dock fallback — throttles re-clicks while waiting to dock. */
    private LocalDateTime lastCompletingDock = LocalDateTime.MIN;

    /** When the IDLE travel loop first found no navigation UI on screen (MIN = not waiting). Drives the 60s podded-at-station escalation. */
    private LocalDateTime noTravelUiSince = LocalDateTime.MIN;

    /** Last time the ship was confirmed alive and active — entering combat or returning a mission.
     *  Gates the warp-entry pod check: only probe once 5min pass without either event. */
    private LocalDateTime lastCombatOrReturn = LocalDateTime.now();

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
        if (state == State.TRAVEL || state == State.RETURNING) {
            this.destinationSet   = false;
            this.warpedToLocation = false;
            this.lastTravelAction = LocalDateTime.MIN;
            this.jumpCount        = 0;
            this.klyChecked       = false;
            this.warpedToSafeSpot = false;
            this.windowsClosedAfterJump1 = false;
        }
        if (state == State.WARPING) {
            this.lastWarpCheck = LocalDateTime.MIN;
            this.bubbleEscapeStep = 0;
            this.bubbleEscapeStart = LocalDateTime.MIN;
        }
        if (state == State.IN_COMBAT) {
            this.combatStep = 0;
            this.combatWaitStart = LocalDateTime.MIN;
            this.combatPollStart = LocalDateTime.MIN;
            this.combatWarpLanded = false;
            this.warpEndMisses = 0;
            this.lastViewNudge = LocalDateTime.MIN;
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

    public int  getCombatStep()             { return combatStep; }
    public void setCombatStep(int step)     { this.combatStep = step; }

    public LocalDateTime getCombatWaitStart()                { return combatWaitStart; }
    public void          setCombatWaitStart(LocalDateTime t) { this.combatWaitStart = t; }

    public LocalDateTime getCombatPollStart()                { return combatPollStart; }
    public void          setCombatPollStart(LocalDateTime t) { this.combatPollStart = t; }

    public boolean isCombatWarpLanded()              { return combatWarpLanded; }
    public void    setCombatWarpLanded(boolean v)    { this.combatWarpLanded = v; }

    public int  getWarpEndMisses()                   { return warpEndMisses; }
    public void setWarpEndMisses(int v)              { this.warpEndMisses = v; }

    public boolean isStandingsLocked()               { return standingsLocked; }
    public void    setStandingsLocked(boolean v)     { this.standingsLocked = v; }

    public LocalDateTime getCombatStartTime()        { return combatStartTime; }
    public void setCombatStartTime(LocalDateTime t)  { this.combatStartTime = t; }

    public boolean isExtraCapPulseDone()             { return extraCapPulseDone; }
    public void    setExtraCapPulseDone(boolean v)   { this.extraCapPulseDone = v; }

    public int  getBubbleEscapeStep()             { return bubbleEscapeStep; }
    public void setBubbleEscapeStep(int step)     { this.bubbleEscapeStep = step; }

    public LocalDateTime getBubbleEscapeStart()                { return bubbleEscapeStart; }
    public void          setBubbleEscapeStart(LocalDateTime t) { this.bubbleEscapeStart = t; }

public boolean isDockingToStation()             { return dockingToStation; }
    public void    setDockingToStation(boolean v)   { this.dockingToStation = v; }

    public LocalDateTime getPodCooldownEnd()                { return podCooldownEnd; }
    public void          setPodCooldownEnd(LocalDateTime t) { this.podCooldownEnd = t; }

    public boolean isDeclineRetried()              { return declineRetried; }
    public void    setDeclineRetried(boolean v)    { this.declineRetried = v; }

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

    public LocalDateTime getLastPodCheck()                { return lastPodCheck; }
    public void          setLastPodCheck(LocalDateTime t) { this.lastPodCheck = t; }

    public double getLastKnownStanding()             { return lastKnownStanding; }
    public void   setLastKnownStanding(double v)     { this.lastKnownStanding = v; }

    public LocalDateTime getLastCompletingDock()                { return lastCompletingDock; }
    public void          setLastCompletingDock(LocalDateTime t) { this.lastCompletingDock = t; }

    public LocalDateTime getNoTravelUiSince()                { return noTravelUiSince; }
    public void          setNoTravelUiSince(LocalDateTime t) { this.noTravelUiSince = t; }

    public LocalDateTime getLastCombatOrReturn()                { return lastCombatOrReturn; }
    public void          setLastCombatOrReturn(LocalDateTime t) { this.lastCombatOrReturn = t; }

    public boolean isJamCheckOnWarp()           { return jamCheckOnWarp; }
    public void    setJamCheckOnWarp(boolean v) { this.jamCheckOnWarp = v; }

    public boolean isVerifySetDest()           { return verifySetDest; }
    public void    setVerifySetDest(boolean v) { this.verifySetDest = v; }

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