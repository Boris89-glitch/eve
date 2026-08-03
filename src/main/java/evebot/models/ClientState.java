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

    /** Timestamp when the warp/jump click actually landed — used by WARPING/IN_COMBAT to measure the
     *  spin-up grace. Written by the background click thread (see ClientManager.startWarpClickDeferred)
     *  and read by the poll loop, so it must be volatile for cross-thread visibility. Held briefly in
     *  the future while the click is still pending, so the grace can't start before the click lands. */
    private volatile LocalDateTime warpStartTime = LocalDateTime.MIN;

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

    /** Set when the pod death happened in the mission fight itself and the pod is still flying home
     *  (high sec — no self-destruct, so the death is handled a second time on arrival at the
     *  station). Tells that second handling it was a combat death: keep the mission and re-ship,
     *  no camp cooldown. Cleared as soon as it's consumed. */
    private boolean poddedInCombat = false;

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

    /** TEMPORARILY paused because agent standing dropped below the pause floor. Unlike
     *  standingsLocked/parked this is not for the session: while set, the client yields to the
     *  partner and does no work; the partner turning in a mission clears it (resume checking), and
     *  the next completion re-sets it if standing is still below the floor. NOT reset by setState. */
    private boolean standingsPaused = false;

    /** Set when the standings pause came from the PERSISTED standing at startup (not a live OCR
     *  read). A persisted value is stale by nature — partner turn-ins boost our standing without
     *  us re-reading it — so the pause gate verifies it once with a live read before honoring the
     *  pause for the session. Cleared after that verification. NOT reset by setState. */
    private boolean standingsPauseFromPersisted = false;

    /** Last agent standing read at mission completion, used to gate standings-restricted
     *  missions (recon 1, scarlet, pot-and-kettle — accepted only when standing &lt; 1).
     *  NaN until the first completion populates it. NOT reset by setState.
     *  Shared across clients via {@link evebot.StateManager#getOtherState}: a completing
     *  client compares its own standing against the fleet partner's to decide the fleet reward. */
    private double lastKnownStanding = Double.NaN;

/** True when docking to home station after combat — WARPING should transition to COMPLETING.
     *  Set right before each dock's setState(WARPING) and cleared on the docking-completion paths
     *  (finishDockArrival / the dock-reissue give-up). setState also clears it on TRAVEL/RETURNING
     *  entry, so a WARPING that ended without finishDockArrival (e.g. a pod death mid-dock-warp)
     *  can't leave it set and make the next leg's warp landing read as a phantom dock. */
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

    /** Set when a chain mission (Recon 1 of 3, Pot and Kettle) was just turned in: the agent's very
     *  next offer is the follow-up part (Recon 2 of 3 etc.), which we never run. Decline it outright —
     *  no template match, no OCR, no rules — then clear the flag. One-shot. */
    private boolean declineNextOffer = false;

    /** True only when CHECKING_MISSIONS was entered as a cold inspection of the agent dialog
     *  (startup / IDLE opened the agent itself), where a NOT_COMPLETE reading may be a mission
     *  accepted before the bot took control and should be recovered + flown. In the normal
     *  request/decline cycle the dialog is a FRESH offer, so a flaky accept/decline miss can make
     *  detectDialog read the offer's objectives text as NOT_COMPLETE — flying it would undock an
     *  unwanted mission with a mismatched ship. Default false (the safe direction: evaluate, don't
     *  fly). Set true only at the IDLE cold-check entries; cleared on leaving CHECKING_MISSIONS. */
    private boolean allowNotCompleteRecovery = false;

    /** Running count of missions declined since the last standings re-read. Every Nth decline
     *  schedules a standings check so mission gating stays current. */
    private int declineCount = 0;

    /** Set when declineCount reaches the threshold — DECLINING re-reads agent standings. */
    private boolean standingsCheckDue = false;

    /** How many jumps we are from the home station. Counts UP on the way out and back DOWN on
     *  the way home (the return leg retraces the same route), so the number always means
     *  "distance from home" rather than "jumps this leg" — which is what pod-death location
     *  context and the near-station danger checks actually want. Reset to 0 on TRAVEL only;
     *  RETURNING deliberately keeps the outbound total as its starting distance.
     *
     *  Only confirmed gate jumps move it (see {@link #pendingGateJump}); warping to a safe spot,
     *  to the mission site or onto a station is not a jump and leaves it alone. */
    private int jumpCount = 0;

    /** Destination system for this trip, from the offer dialog OCR where possible and the
     *  info-panel route after undocking otherwise, kept for the whole trip (the return leg retraces
     *  the same route, so it stays the reference path). Paired with {@link #jumpCount} it says which
     *  system we are in right now — the fallback for locating a pod death when the kill report can't
     *  be read. Null when neither read placed the destination on the map. */
    private String routeDestination = null;

    /** A gate jump has been issued and is waiting on arrival. The count moves only when the warp
     *  ends and navigation resumes in the next system, so a right-click that missed — or a jump we
     *  never came out of — cannot inflate our idea of where we are. Cleared on arrival. */
    private boolean pendingGateJump = false;

    /** True once KLY system check has been performed after first jump — skip further checks. */
    private boolean klyChecked = false;

    /** True once lingering windows have been cleared after the first jump out of the home station —
     *  one-shot so we only sweep close_active_window once per trip. */
    private boolean windowsClosedAfterJump1 = false;

    /** templates/&lt;name&gt;.png of the system to take on the FIRST jump out of the station, when the
     *  autopilot route would otherwise run through the system we were podded in. Right-clicked and
     *  jumped in place of the stargate; EVE re-routes from wherever we land, so steering the first
     *  hop onto the safe branch is enough to keep the whole trip clear. Null = no detour needed,
     *  and it is cleared the moment the hop is taken (one-shot per trip). */
    private String podRerouteTemplate = null;

    /** True after evading to a safe spot — next landing jumps out instead of re-evading. Cleared on stargate jump. */
    private boolean warpedToSafeSpot = false;

    /** Team combats: times the final completion backstop was extended because the main's locked
     *  bracket was still on screen (the fight is simply taking longer than the backstop window). */
    private int completionBackstopExtensions = 0;

    /** Scarlet: the step-2 alt+J set-destination ran but the info panel showed no route. The retry
     *  is deliberately deferred — Anire is waiting, so we fly the mission and re-set the route after
     *  the gate jump on the way out (combat steps 14/15) rather than fiddling with menus mid-fight. */
    private boolean scarletRouteUnconfirmed = false;

    /** Scarlet: post-mission alt+J set-destination runs whose route the info panel never confirmed.
     *  Bounds that retry loop so a route that can't be read doesn't strand the client at the gate. */
    private int scarletRouteAttempts = 0;

    /** Ticks spent at 0 jumps from home with neither the dock button nor home_station on screen.
     *  Bounds the "we're home, dock on the station instead of jumping a gate" hold: if the station
     *  never shows, the count is off and we fall back to the stargate. Cleared once we dock. */
    private int homeDockMissCount = 0;

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
            this.klyChecked       = false;
            this.warpedToSafeSpot = false;
            this.safeSpotWarp     = false;
            this.windowsClosedAfterJump1 = false;
            this.podRerouteTemplate = null;
            this.pendingGateJump  = false;
            this.homeDockMissCount = 0;
            // A fresh outbound/return leg is never a completion dock. Clear any stale
            // dockingToStation left set by a WARPING that ended without finishDockArrival
            // (e.g. a pod death mid-dock-warp) — otherwise this leg's warp landing is
            // misread as a dock and the client flips into a phantom COMPLETING.
            this.dockingToStation = false;
        }
        if (state == State.TRAVEL) {
            // Outbound leg starts at the station. RETURNING is deliberately excluded: the way home
            // retraces the outbound route, so the distance we ended the outbound leg at IS the
            // distance the return leg starts from, and it counts back down to 0 at the station.
            this.jumpCount = 0;
            // Same reason: the outbound route is the reference path for the whole trip, so it is
            // cleared when a new trip starts, not when the return leg begins.
            this.routeDestination = null;
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
            this.scarletRouteAttempts = 0;
            this.scarletRouteUnconfirmed = false;
            this.completionBackstopExtensions = 0;
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

    public boolean isPoddedInCombat()          { return poddedInCombat; }
    public void    setPoddedInCombat(boolean v) { this.poddedInCombat = v; }

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

    public boolean isStandingsPaused()               { return standingsPaused; }
    public void    setStandingsPaused(boolean v)     { this.standingsPaused = v; }
    public boolean isStandingsPauseFromPersisted()           { return standingsPauseFromPersisted; }
    public void    setStandingsPauseFromPersisted(boolean v) { this.standingsPauseFromPersisted = v; }

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

    public boolean isDeclineNextOffer()            { return declineNextOffer; }
    public void    setDeclineNextOffer(boolean v)  { this.declineNextOffer = v; }

    public boolean isAllowNotCompleteRecovery()           { return allowNotCompleteRecovery; }
    public void    setAllowNotCompleteRecovery(boolean v) { this.allowNotCompleteRecovery = v; }

    public int  getDeclineCount()                  { return declineCount; }
    public void setDeclineCount(int v)             { this.declineCount = v; }

    public boolean isStandingsCheckDue()           { return standingsCheckDue; }
    public void    setStandingsCheckDue(boolean v) { this.standingsCheckDue = v; }

    public int  getJumpCount()          { return jumpCount; }
    public void incrementJumpCount()    { this.jumpCount++; }
    /** Overwrite the counter from the info-panel route on the way home, where "jumps to the
     *  destination" IS our distance from home — the panel outranks the gate-jump tally, which
     *  drifts across restarts and cross-system mission warps. */
    public void setJumpCount(int v)     { this.jumpCount = Math.max(0, v); }
    /** Floors at 0 — the station itself. An extra jump home can't drive the distance negative. */
    public void decrementJumpCount()    { if (this.jumpCount > 0) this.jumpCount--; }

    public int  getCompletionBackstopExtensions()      { return completionBackstopExtensions; }
    public void setCompletionBackstopExtensions(int v) { this.completionBackstopExtensions = v; }

    public boolean isScarletRouteUnconfirmed()      { return scarletRouteUnconfirmed; }
    public void    setScarletRouteUnconfirmed(boolean v) { this.scarletRouteUnconfirmed = v; }

    public int  getScarletRouteAttempts()      { return scarletRouteAttempts; }
    public void setScarletRouteAttempts(int v) { this.scarletRouteAttempts = v; }

    public int  getHomeDockMissCount()      { return homeDockMissCount; }
    public void setHomeDockMissCount(int v) { this.homeDockMissCount = v; }

    public boolean isPendingGateJump()           { return pendingGateJump; }
    public void    setPendingGateJump(boolean v) { this.pendingGateJump = v; }

    public String getRouteDestination()          { return routeDestination; }
    public void   setRouteDestination(String d)  { this.routeDestination = d; }

    public boolean isKlyChecked()           { return klyChecked; }
    public void    setKlyChecked(boolean v) { this.klyChecked = v; }

    public boolean isWindowsClosedAfterJump1()           { return windowsClosedAfterJump1; }
    public void    setWindowsClosedAfterJump1(boolean v) { this.windowsClosedAfterJump1 = v; }

    public String getPodRerouteTemplate()           { return podRerouteTemplate; }
    public void   setPodRerouteTemplate(String t)   { this.podRerouteTemplate = t; }

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