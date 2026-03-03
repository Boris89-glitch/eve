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
        TRAVEL,       // undocked and warping to mission site
        IN_COMBAT,
        RETURNING,
        COMPLETING
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

    /** Timestamp of the last stargate jump click during TRAVEL or RETURNING. */
    private LocalDateTime lastTravelAction = LocalDateTime.MIN;

    /** Timestamp when Undock was clicked — used to track the 15s undock animation wait. */
    private LocalDateTime undockStartTime = LocalDateTime.MIN;

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
        if (state == State.TRAVEL || state == State.RETURNING) {
            this.destinationSet   = false;
            this.warpedToLocation = false;
            this.lastTravelAction = LocalDateTime.MIN;
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

    public LocalDateTime getLastTravelAction()                { return lastTravelAction; }
    public void          setLastTravelAction(LocalDateTime t) { this.lastTravelAction = t; }

    public LocalDateTime getUndockStartTime()                { return undockStartTime; }
    public void          setUndockStartTime(LocalDateTime t) { this.undockStartTime = t; }

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