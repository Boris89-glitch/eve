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

    /** True once the gate warp has been verified and landed — combat can begin after a short wait. */
    private boolean combatWarpLanded = false;

/** True when docking to home station after combat — WARPING should transition to COMPLETING. */
    private boolean dockingToStation = false;

    /** When the pod-death cooldown expires for this client. */
    private LocalDateTime podCooldownEnd = LocalDateTime.MIN;

    /** True after the first re-click of decline in DECLINING — next failure triggers agent double-click. */
    private boolean declineRetried = false;

    /** Number of stargate jumps since undocking — logged on pod death for location context. */
    private int jumpCount = 0;

    /** True once KLY system check has been performed after first jump — skip further checks. */
    private boolean klyChecked = false;

    /** Last time the POD_COOLDOWN handler swapped clients — prevents rapid-fire switching. */
    private LocalDateTime lastCooldownSwap = LocalDateTime.MIN;

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
        }
        if (state == State.WARPING) {
            this.lastWarpCheck = LocalDateTime.MIN;
            this.bubbleEscapeStep = 0;
            this.bubbleEscapeStart = LocalDateTime.MIN;
        }
        if (state == State.IN_COMBAT) {
            this.combatStep = 0;
            this.combatWaitStart = LocalDateTime.MIN;
            this.combatWarpLanded = false;
        }
    }

    public Mission getCurrentMission() { return currentMission; }

    public void setCurrentMission(Mission mission) {
        this.currentMission   = mission;
        this.lastMissionCheck = LocalDateTime.now();
    }

    public boolean isMissionTracked()           { return missionTracked; }
    public void    setMissionTracked(boolean t) { this.missionTracked = t; }

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

    public boolean isCombatWarpLanded()              { return combatWarpLanded; }
    public void    setCombatWarpLanded(boolean v)    { this.combatWarpLanded = v; }

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

    public LocalDateTime getLastCooldownSwap()                { return lastCooldownSwap; }
    public void          setLastCooldownSwap(LocalDateTime t) { this.lastCooldownSwap = t; }

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