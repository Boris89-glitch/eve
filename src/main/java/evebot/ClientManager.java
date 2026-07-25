package evebot;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import evebot.models.ClientState;
import evebot.models.Mission;

import java.awt.Point;
import java.awt.Rectangle;
import java.nio.file.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Removed switching clients for now, only when in focus
 */
public class ClientManager {

    private final StateManager     stateManager;
    private final MissionProcessor missionProcessor;
    private final MissionConfig    missionConfig;
    private final Gson             gson;

    private final Map<String, Rectangle>        clientMissionAreas = new ConcurrentHashMap<>();
    private final Map<String, ClientWindowInfo> clientWindows      = new ConcurrentHashMap<>();
    private final Map<String, Integer>          agentRetries       = new ConcurrentHashMap<>();
    private final Map<String, Integer>          gateWarpRetries    = new ConcurrentHashMap<>();
    /** Counts the IDLE<->CHECKING_MISSIONS bounces caused by a stuck 'not_complete' dialog with
     *  no mission on record. Survives the bounce (unlike agentRetries, which CHECKING resets) so
     *  we can double-click the agent to reset the dialog after a few cycles. */
    private final Map<String, Integer>          notCompleteRetries = new ConcurrentHashMap<>();
    /** Clients whose agent dialog has been evaluated at least once this run. A COMPLETED dialog
     *  with no mission on record is only trusted as a real turn-in on the FIRST such check (a
     *  genuine leftover from a previous session); after that it's a stale/false signal. In-memory
     *  only — resets each process start, which is exactly "this session". */
    private final java.util.Set<String>         agentDialogChecked = ConcurrentHashMap.newKeySet();
    /** Counts agent-refresh attempts for a stale COMPLETED dialog with no mission on record (past
     *  the first check). Bounds the double-click-agent refresh so it can't spin forever. */
    private final Map<String, Integer>          staleCompletedRetries = new ConcurrentHashMap<>();
    /** Counts DECLINING ticks where the request for a new mission produced no offer. After a couple
     *  of these we probe the dialog for an accidentally-accepted mission (the decline click landed on
     *  accept) and quit it — otherwise Request Mission is a no-op while a mission is active and this
     *  loops forever. */
    private final Map<String, Integer>          declineNoOfferRetries = new ConcurrentHashMap<>();

    /** Prevents concurrent monitorClients executions when the thread pool queues up ticks. */
    private final AtomicBoolean monitorRunning = new AtomicBoolean(false);

    /** Wall-clock time of the last actual client switch (any {@link #bringToForeground} call).
     *  Used by the global watchdog in {@link #monitorClients} to force a switch when one client
     *  has hogged the foreground too long (e.g. a long combat) — preventing the idle client from
     *  being podded while AFK in space. */
    private volatile LocalDateTime lastClientSwitch = LocalDateTime.now();

    /** Client id processed on the previous {@link #processClient} call, and whether the client
     *  processed this call differs from it (i.e. it just regained the foreground). A refocused
     *  WARPING client is allowed one immediate warp_active check instead of waiting out the 2s
     *  poll throttle it left behind when it yielded focus. */
    private String lastProcessedClientId = null;
    private boolean clientRefocused = false;

    private static final int  MAX_AGENT_RETRIES        = 2;
    /** Stuck 'not_complete'-with-no-mission bounces before double-clicking the agent to reset. */
    private static final int  MAX_NOT_COMPLETE_RETRIES = 3;
    private static final long UNDOCK_WAIT_SECS          = 15;
    /** Consecutive polls that must show warp_active gone before a gate-warp landing is confirmed —
     *  guards against a single sub-threshold frame ending the warp early. */
    private static final int  WARP_END_MISS_CONFIRM     = 2;
    /** Safe settle window (seconds) held in IDLE after a warp lands before this client navigates,
     *  so it doesn't grab a stargate before warp_to_location / dock has rendered. Exits early once
     *  the expected landing element is on screen. Non-blocking (ticks per focus, like combat). */
    private static final long LANDING_SETTLE_SECS        = 6;
    // A warp this long means the grid has already rendered/settled by the time it ends —
    // skip the landing safe wait and don't hand focus away on land.
    private static final long LONG_WARP_NO_SETTLE_SECS    = 50;
    /** Pixels below the EVE-O Preview thumbnail template centre to click (skips the title bar). */
    private static final int  THUMBNAIL_CLICK_OFFSET_Y = 75;
    /** Scarlet lock-confirm: seconds to wait for 'locked_anire' before re-attempting the ctrl-click. */
    private static final long SCARLET_LOCK_RETRY_SECS  = 2;
    /** Re-read agent standings after this many declined missions (keeps gating current). */
    private static final int  STANDINGS_RECHECK_DECLINES = 5;
    /** Consecutive WARPING dock re-issues that found no DOCK button and no station before we
     *  stop re-issuing and drop the client back to IDLE. */
    private static final int  DOCK_REISSUE_MISS_LIMIT = 3;

    /** Consecutive WARPING checks where no warp control was on screen to re-issue (session change
     *  mid gate jump). Each one restarts the warp wait without consuming the warp retry. */
    private static final int  WARP_REISSUE_MISS_LIMIT = 3;
    /** Global watchdog: if no client switch has happened for this many seconds and a second
     *  client exists, force a switch so the idle client cannot sit AFK in space (and get podded)
     *  while the foreground client is busy in a long combat. 130s (not 120s) so a full pod /
     *  self-destruct death+respawn animation completes before the watchdog can interrupt it. */
    private static final long FORCE_SWITCH_SECS         = 130;
    /** When the foreground client is warping and the OTHER client is ALSO warping, hold focus
     *  on the current one for this many seconds before switching, instead of ping-ponging every
     *  poll. Neither has anything to do mid-warp, so a longer hold just looks more natural. */
    private static final long WARP_HOLD_SECS            = 6;
    /** Minimum seconds between view-nudges (camera drag to refresh a stale frame) while
     *  waiting on a detection — keeps the nudge from firing every poll tick. */
    private static final long VIEW_NUDGE_THROTTLE_SECS  = 10;
    /** Team combats (not Jaguar): once we've shot a target and waited this long with it
     *  still locked, verify the launcher is actually firing ('shooting_luncher') and re-fire
     *  if it isn't — a target can stop taking damage without the fight ever progressing. */
    private static final long TEAM_REFIRE_AFTER_SECS    = 120;
    /** Minimum seconds between those shooting_luncher re-fire checks. */
    private static final long TEAM_REFIRE_THROTTLE_SECS = 15;
    /** How often to check the faction agent for an offer (the kernite/Evolution run). */
    private static final long FACTION_CHECK_HOURS       = 2;
    /** Faction missions may be declined at most once per this many hours (EVE decline cooldown). */
    private static final long FACTION_DECLINE_HOURS     = 4;

    public ClientManager(StateManager stateManager) {
        this.stateManager     = stateManager;
        this.missionProcessor = new MissionProcessor();
        this.missionConfig    = new MissionConfig();
        this.gson             = new Gson();
        loadCalibratedCoordinates();
    }

    /** Stop the resident Python daemon on shutdown. */
    public void shutdown() {
        missionProcessor.getOcrBridge().shutdown();
    }

    // -----------------------------------------------------------------------
    // Coordinate loading
    // -----------------------------------------------------------------------

    private void loadCalibratedCoordinates() {
        clientMissionAreas.put("default", Layout.missionArea());
    }

    // -----------------------------------------------------------------------
    // Core monitor loop
    // -----------------------------------------------------------------------

    public void monitorClients() {
        if (!stateManager.isBotRunning()) return;
        if (!monitorRunning.compareAndSet(false, true)) return; // skip if previous tick still running
        try {
            refreshClientWindows();
            if (clientWindows.isEmpty()) {
                log("[ClientManager] No EVE clients found on screen.");
                return;
            }


            ClientWindowInfo fg = getForegroundClient();

            if (fg == null) {
                log("[ClientManager] No EVE client in foreground — skipping tick.");
                return;
            }

            // Global watchdog: if the foreground client has hogged focus past FORCE_SWITCH_SECS
            // (e.g. a long combat that never yields), force a switch so the idle client gets a
            // tick and cannot sit AFK in space until podded. bringToForeground resets the timer.
            long sinceSwitch = Duration.between(lastClientSwitch, LocalDateTime.now()).getSeconds();
            if (sinceSwitch >= FORCE_SWITCH_SECS
                    && stateManager.getStateEnum(fg.getClientId()) == ClientState.State.UNDOCKING) {
                // Never yank focus mid-undock. Switching away leaves the undock animation to finish
                // in the background; when focus returns the ship HUD is still rendering, isFlyingPod()
                // false-positives, and we redock + quit a mission we actually have a ship for. Let the
                // undock complete on this client — reset the timer so the watchdog re-arms afterwards.
                log("[ClientManager] Force-switch watchdog held: " + fg.getClientId() + " is UNDOCKING — not interrupting the undock animation.");
                lastClientSwitch = LocalDateTime.now();
            } else if (sinceSwitch >= FORCE_SWITCH_SECS) {
                ClientWindowInfo other = findOtherClient(fg.getClientId());
                if (other != null) {
                    log("[ClientManager] Force-switch watchdog: " + sinceSwitch + "s without a switch — handing foreground to " + other.getClientId() + ".");
                    bringToForeground(other);
                    processClient(other.getClientId(), other);
                    return;
                }
                // Only one client — nothing to switch to; reset so we don't log every tick.
                lastClientSwitch = LocalDateTime.now();
            }

            processClient(fg.getClientId(), fg);
        } catch (Exception e) {
            // Never let a tick throw out of here: monitorClients runs via
            // scheduleAtFixedRate, which permanently cancels the task if it throws —
            // one uncaught exception would silently kill the bot. Log and keep ticking.
            System.err.println("[ClientManager] Tick error (continuing): " + e);
            e.printStackTrace();
        } finally {
            monitorRunning.set(false);
        }
    }

    // -----------------------------------------------------------------------
    // Per-client state machine
    // -----------------------------------------------------------------------

    private void processClient(String clientId, ClientWindowInfo window) {
        // Respect pause on every entry, not just at the monitorClients tick gate. Some
        // paths (the pod-respawn driver loop) call processClient repeatedly within a
        // single tick, so a pause that lands mid-loop must take effect here too.
        if (!stateManager.isBotRunning()) return;

        // Tag all OCRBridge log lines with the client we're processing this tick.
        OCRBridge.setActiveClient(clientId);

        // Did this client just regain the foreground (different from the last one processed)?
        // WARPING uses this to take one immediate warp check instead of waiting out its 2s poll.
        clientRefocused = !clientId.equals(lastProcessedClientId);
        lastProcessedClientId = clientId;

        // Parked for the session (agent refused missions — standing too low): do no work,
        // just hand the foreground to the other client so it can keep running. Stays parked
        // until the bot is restarted.
        if (stateManager.getState(clientId).isStandingsLocked()
                || stateManager.getState(clientId).isParked()) {
            ClientWindowInfo other = findOtherClient(clientId);
            // Only hand off to a client that can still work. If the other is also parked/
            // standings-locked, switching just ping-pongs the foreground back and forth
            // forever (both clients clicking each other's thumbnail) — do nothing instead.
            if (other != null && !isParkedOrLocked(other.getClientId())) {
                bringToForeground(other);
            }
            return;
        }

        ClientState.State state = stateManager.getStateEnum(clientId);

        // Graceful session end (Ctrl+Shift+R): stop taking on NEW work. Clients mid-mission
        // keep running — they funnel through COMPLETING, which turns the mission in and then
        // parks. A client merely at its agent deciding on a new mission (CHECKING_MISSIONS /
        // DECLINING, or docked IDLE with no travel leg in progress) parks now, so it ends the
        // session docked at the agent.
        if (stateManager.isSessionEnding()) {
            boolean awaitingNewWork =
                    state == ClientState.State.CHECKING_MISSIONS
                 || state == ClientState.State.DECLINING
                 || (state == ClientState.State.IDLE
                     && stateManager.getState(clientId).getTravelPhase() == ClientState.TravelPhase.NONE);
            if (awaitingNewWork) {
                parkForSessionEnd(clientId, "docked at agent, not requesting new missions");
                return;
            }
        }

        switch (state) {
            case IDLE:              handleIdle(clientId, window);             break;
            case CHECKING_MISSIONS: handleCheckingMissions(clientId, window); break;
            case DECLINING:         handleDeclining(clientId, window);        break;
            case ACCEPTED:          handleAccepted(clientId, window);         break;
            case REPAIRING:         handleRepairing(clientId, window);        break;
            case UNDOCKING: handleUndocking(clientId, window);                     break;
            case TRAVEL:    handleTravel(clientId, window);                      break;
            case WARPING:   handleWarping(clientId, window);                     break;
            case IN_COMBAT: handleInCombat(clientId, window);  break;
            case RETURNING: handleReturning(clientId, window); break;
            case COMPLETING:
                handleCompleting(clientId);
                break;
            case POD_COOLDOWN:
                handlePodCooldown(clientId);
                break;
            case FACTION_CHECK:      handleFactionCheck(clientId, window);      break;
            case FACTION_TRAVEL:     handleFactionTravel(clientId, window);     break;
            case FACTION_COMPLETING: handleFactionCompleting(clientId, window); break;
            default:
                System.out.println("[" + clientId + "] Unknown state: " + state);
        }
    }

    /**
     * Stop a client for the rest of the session after an unrecoverable error. It yields the
     * foreground to the other client and does no further work until the bot is restarted.
     */
    private void parkClient(String clientId, String reason) {
        log(clientId, "PARKED for session: " + reason + " — yielding to other client until restart.");
        EventLog.log("PARKED", clientId + " — " + reason);
        stateManager.getState(clientId).setParked(true);
    }

    /**
     * Park a client for a graceful session end (Ctrl+Shift+R). Resets it to a clean docked
     * IDLE state (persisted, so the next launch starts cleanly from the agent) and marks it
     * parked so it does no further work and yields the foreground for the rest of the session.
     */
    private void parkForSessionEnd(String clientId, String reason) {
        ClientState cs = stateManager.getState(clientId);
        cs.setCurrentMission(null);
        cs.setTravelPhase(ClientState.TravelPhase.NONE);
        stateManager.setState(clientId, ClientState.State.IDLE); // persist a clean docked state
        cs.setParked(true);
        log(clientId, "SESSION END: " + reason + " — parked, docked at agent until restart.");
        EventLog.log("SESSION_END", clientId + " — " + reason);
        ClientWindowInfo other = findOtherClient(clientId);
        if (other != null && !isParkedOrLocked(other.getClientId())) bringToForeground(other);
    }

    // -----------------------------------------------------------------------
    // POD_COOLDOWN — wait for global 10-minute cooldown after a pod death.
    // -----------------------------------------------------------------------

    private static final int POD_COOLDOWN_MINUTES = 12;

    /** Insta-dock attempts (dock warp never engaging) before we stop retrying and dock directly
     *  (DOCK button / right-click station → Dock). */
    private static final int INSTA_DOCK_MAX_MISSES = 3;

    private void handlePodCooldown(String clientId) {
        ClientState cs = stateManager.getState(clientId);

        // Right after an emergency redock we switched away; now that this client has focus
        // again, verify whether we actually saved the ship. emergencyRedock only confirms
        // we're in station — a pod_station match means the ship was destroyed and we docked
        // as a capsule (redock failed). Either way the global cooldown is already running;
        // this just records that we were podded. One-shot.
        if (cs.isPendingPodStationCheck()) {
            cs.setPendingPodStationCheck(false);
            if (missionProcessor.findTemplate("pod_station") != null) {
                log(clientId, "POD_COOLDOWN: pod_station detected — emergency redock failed, we were podded.");
                EventLog.log("POD_DEATH", clientId + " — pod_station in hangar after emergency redock (got podded).");
            } else {
                log(clientId, "POD_COOLDOWN: no pod_station — ship survived the emergency redock.");
            }
        }

        // Add the pilot who podded us as a bad contact shortly after we're back in station — we're
        // docked and in the foreground here with the kill report still open (it closes on undock).
        // Do it now rather than waiting out the cooldown, so the flagging happens even on a long (or
        // zero) cooldown. Give the station UI ~10s to load first (the feed/report aren't
        // interactable on the first tick), holding focus on this client during the wait.
        if (cs.isPendingKillReportAdd()) {
            if (Duration.between(cs.getLastStateChange(), LocalDateTime.now()).getSeconds() < 10) {
                return; // station still loading — wait, keep this client in the foreground
            }
            cs.setPendingKillReportAdd(false);
            // Returns the system named in the report's location header — the only exact record of
            // where we died. Everything the map says is only reachable through it is refused from
            // here on, so we don't take the next mission straight back into the same camp.
            String poddedSystem = missionProcessor.addKillerFromKillReport();
            String source = "kill report";
            if (poddedSystem == null) {
                // Header didn't read (or its region isn't calibrated here) — fall back to placing
                // ourselves on the route by how far from home we got.
                poddedSystem = inferPoddedSystem(clientId, cs);
                source = "jump count";
            }
            EventLog.log("POD_KILLER", clientId + " — killer flagged from kill report; podded in "
                    + (poddedSystem != null ? poddedSystem + " (via " + source + ")" : "an unknown system"));
            if (poddedSystem != null) {
                stateManager.recordPoddedSystem(poddedSystem);
                Set<String> blocked = SystemMap.blockedBy(poddedSystem);
                if (blocked.isEmpty()) {
                    // Every destination keeps an alternate path around it (or it is home) — the
                    // system is still recorded so detours route around it, but nothing is refused.
                    log(clientId, "POD_AVOID: podded in " + poddedSystem + " (via " + source
                            + ") — nothing sits behind it, so no missions are refused; routes will detour around it.");
                    EventLog.log("POD_AVOID", clientId + " — avoiding " + poddedSystem
                            + " (via " + source + "); nothing is only reachable through it");
                } else {
                    String blockedNames = blocked.stream().map(SystemMap::display).sorted()
                            .collect(java.util.stream.Collectors.joining(", "));
                    log(clientId, "POD_AVOID: podded in " + poddedSystem + " (via " + source
                            + ") — refusing " + blocked.size() + " system(s): " + blockedNames);
                    EventLog.log("POD_AVOID", clientId + " — avoiding " + poddedSystem
                            + " (via " + source + ") and everything behind it ("
                            + blocked.size() + "): " + blockedNames);
                }
            }
        }

        LocalDateTime cooldownEnd = stateManager.getPodCooldownEnd();
        long remaining = Duration.between(LocalDateTime.now(), cooldownEnd).getSeconds();
        if (remaining <= 0) {
            if (cs.getCurrentMission() != null) {
                // Before quitting, check whether the mission is actually already complete — e.g. we
                // killed the burner and only got podded on the way back. If we respawned/redocked at
                // the agent (agent_icon visible) and its dialog reads COMPLETED, turn it in via
                // COMPLETING rather than throwing away a finished mission. Otherwise quit and re-pick.
                if (missionProcessor.detectCompletingUI() != null) {
                    missionProcessor.doubleClickAgentIcon();
                    if (missionProcessor.detectDialog() == MissionProcessor.DialogState.COMPLETED) {
                        log(clientId, "POD_COOLDOWN: expired — mission reads COMPLETED (target down before we were podded), turning it in.");
                        stateManager.setState(clientId, ClientState.State.COMPLETING);
                        return;
                    }
                }
                // Died on this mission and it isn't complete — quit it and pick a different one rather than flying back in.
                log(clientId, "POD_COOLDOWN: expired — quitting the mission we died on, picking a different one.");
                missionProcessor.quitMission();
                cs.setCurrentMission(null);
                stateManager.setState(clientId, ClientState.State.CHECKING_MISSIONS);
            } else {
                // Other client — resume normal ops.
                log(clientId, "POD_COOLDOWN: expired — resuming normal operations.");
                stateManager.setState(clientId, ClientState.State.IDLE);
            }
        } else {
            // This client is podded and waiting out the global cooldown, but the other client
            // may be mid-mission (WARPING / IN_COMBAT / RETURNING / COMPLETING) — those states
            // never hit the IDLE pod-cooldown gate, so the other client can still finish if it
            // keeps getting ticks. Hand focus back to it every 10s so it isn't stranded while we
            // sit here. Log the remaining time only once a minute to avoid spam.
            long sinceSwap = Duration.between(cs.getLastCooldownSwap(), LocalDateTime.now()).getSeconds();
            if (sinceSwap >= 10) {
                cs.setLastCooldownSwap(LocalDateTime.now());
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    bringToForeground(other);
                }
            }
            long sinceLog = Duration.between(cs.getLastCooldownLog(), LocalDateTime.now()).getSeconds();
            if (sinceLog >= 60) {
                cs.setLastCooldownLog(LocalDateTime.now());
                log(clientId, "POD_COOLDOWN: " + (remaining / 60) + "m " + (remaining % 60) + "s remaining...");
            }
        }
    }

    /**
     * Work out where we were podded from the route and how far along it we were — the fallback for
     * when the kill report's location header doesn't read (or its region isn't calibrated on this
     * machine).
     *
     * jumpCount is our distance from the home station and counts back down on the way in, so it
     * indexes the outbound path directly on both legs: 2 jumps from home is path[2] whether we were
     * heading out or coming back. Deferring the count to warp confirmation helps here — podded
     * while warping to a gate, the count still names the system we were actually in.
     *
     * Less exact than the kill report: it assumes we flew the map's shortest route, and being one
     * short reports the previous system. Returns null rather than guess when the destination is
     * unknown, off-map, or we never left the station — avoiding the wrong system is worse than
     * avoiding none, and "podded at home" would otherwise cut out the entire map.
     */
    private String inferPoddedSystem(String clientId, ClientState cs) {
        String destination = cs.getRouteDestination();
        if (destination == null && cs.getCurrentMission() != null) {
            destination = cs.getCurrentMission().getLocation();   // route never OCR'd — try the offer
        }
        if (destination == null || !SystemMap.isKnown(destination)) {
            log(clientId, "POD_AVOID: no usable destination on record — cannot place the pod death by jumps.");
            return null;
        }

        List<String> path = SystemMap.pathFromStation(destination);
        if (path.size() < 2) {
            log(clientId, "POD_AVOID: no route to " + destination + " on the map — cannot place the pod death by jumps.");
            return null;
        }

        int jumps = cs.getJumpCount();
        if (jumps <= 0) {
            log(clientId, "POD_AVOID: podded 0 jumps out (still at the home station) — nothing to avoid.");
            return null;
        }
        int index = Math.min(jumps, path.size() - 1);
        String system = path.get(index);
        log(clientId, "POD_AVOID: kill report unreadable — placing the pod death by jumps instead: "
                + jumps + " jump(s) from home toward " + destination + " puts us in " + system
                + " (route: " + String.join(" => ", path) + ")."
                + (index < jumps ? " Count ran past the destination; clamped to it." : ""));
        return system;
    }

    private static final int POD_CHECK_INTERVAL_MINUTES = 5;

    /**
     * Periodic overview upkeep (WARPING, and IDLE while travelling), throttled to
     * every {@link #POD_CHECK_INTERVAL_MINUTES} minutes: clicks the 'general'
     * overview tab to reset the preset. Pod detection was removed from this timer —
     * the no_ship check now runs on warp entry (see {@link #handleWarping}) instead.
     */
    private void runPeriodicOverviewReset(String clientId, ClientState cs) {
        long sinceLast = Duration.between(cs.getLastPodCheck(), LocalDateTime.now()).toMinutes();
        if (sinceLast < POD_CHECK_INTERVAL_MINUTES) {
            return;
        }
        cs.setLastPodCheck(LocalDateTime.now());
        log(clientId, "Periodic check (every " + POD_CHECK_INTERVAL_MINUTES + "m): click 'general' overview reset.");
        missionProcessor.clickTemplate("general"); // reset overview to the General preset (no-op if not visible)
    }

    private boolean handlePodDeath(String clientId, boolean inSpace) {
        return handlePodDeath(clientId, inSpace, false);
    }

    /**
     * Shared pod-death handler. Self-destructs the capsule (if in space) and either
     * starts the global bubble-camp cooldown or resumes immediately.
     *
     * The 12-minute cooldown is only applied for a bubble-caused pod death within
     * the first jump (the case where re-undocking would warp straight back into the
     * camp). Every other pod death (combat, periodic 5m check, deeper in the route)
     * skips the cooldown and resumes.
     *
     * @param clientId       the client that was podded
     * @param inSpace        true if capsule is in space (needs self-destruct); false if already at station
     * @param bubbleRelated  true if the pod death was caused by a warp-bubble camp
     * @return true if a real pod death was handled; false if it was a false positive
     *         (no capsule on screen) and we aborted without changing state
     */
    private boolean handlePodDeath(String clientId, boolean inSpace, boolean bubbleRelated) {
        ClientState cs = stateManager.getState(clientId);
        int jumps = cs.getJumpCount();
        String phase = cs.getTravelPhase() == ClientState.TravelPhase.TO_MISSION ? "to mission"
                     : cs.getTravelPhase() == ClientState.TravelPhase.RETURNING_HOME ? "returning"
                     : "unknown";
        // What mission we were running when destroyed.
        Mission deadMission = cs.getCurrentMission();
        String missionName = deadMission == null ? "unknown"
                : deadMission.getTitle() != null ? deadMission.getTitle()
                : deadMission.getShipTarget() != null ? deadMission.getShipTarget()
                : "unknown";
        // Estimated death window: the ship was last confirmed alive at lastPodCheck
        // (the per-minute in-combat no_ship check, or the warp-entry check), so the
        // kill happened sometime since then. MIN = no prior check this run.
        LocalDateTime lastCheck = cs.getLastPodCheck();
        String deathWindow = lastCheck.equals(LocalDateTime.MIN)
                ? "death window unknown (no prior pod check)"
                : "died within last " + Duration.between(lastCheck, LocalDateTime.now()).getSeconds() + "s";
        // Jump category: <=2 is the near-station danger zone that pauses all clients (see below).
        // jumpCount is our distance from home in both directions (it counts back down on the way
        // in), and only confirmed gate jumps move it, so this now reads as an actual distance
        // rather than "jumps this leg". Past 2 the exact number doesn't matter — logged as ">2".
        String jumpsLabel = jumps <= 2 ? String.valueOf(jumps) : ">2";
        if (inSpace) {
            log(clientId, "*** CAPSULE DETECTED — ship destroyed! Self-destructing...");
            if (!missionProcessor.clickDestruct()) {
                // No capsule actually on screen — the pod check was a false positive
                // (e.g. docked at station). Abort: no self-destruct, no 120s wait,
                // no cooldown, no state change. Just continue.
                log(clientId, "Pod check false positive — no capsule found, aborting self-destruct.");
                return false;
            }
            EventLog.log("POD_DEATH", clientId + " — ship destroyed in space, self-destructing (mission: " + missionName + ", " + phase + ", jumps: " + jumpsLabel + ", " + deathWindow + ")");
            // Self-destruct respawn takes ~120s. Instead of blocking idle on the dead
            // ship, hand that time to the other client by driving it each ~2s.
            log(clientId, "Self-destruct respawn ~120s — switching to other client during the wait.");
            ClientWindowInfo other = findOtherClient(clientId);
            LocalDateTime respawnEnd = LocalDateTime.now().plusSeconds(120);
            while (LocalDateTime.now().isBefore(respawnEnd)) {
                // Honor a pause that lands mid-respawn: this loop runs inside one tick, so
                // it's already past monitorClients' isBotRunning gate. Without this check it
                // would keep driving the other client for the full ~120s, ignoring the pause.
                if (!stateManager.isBotRunning()) {
                    log(clientId, "Bot paused during self-destruct respawn — stopping respawn driver.");
                    break;
                }
                if (other != null) {
                    bringToForeground(other);
                    processClient(other.getClientId(), other);
                }
                try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
            // Bring the podded client back to the foreground so it resumes (repair/idle).
            ClientWindowInfo self = clientWindows.get(clientId);
            if (self != null) {
                bringToForeground(self);
            }
        } else {
            log(clientId, "*** PODDED — respawned at station (agent_icon visible).");
            EventLog.log("POD_DEATH", clientId + " — respawned at station (mission: " + missionName + ", " + phase + ", jumps: " + jumpsLabel + ", " + deathWindow + ")");
        }

        // The one thing that decides keep-vs-quit is WHERE we died. Only an in-combat death
        // (killed at the mission site by the mission NPCs) keeps the mission and flies back in.
        // Any death OUTSIDE combat — bubble camp, gate gank, AFK in space while travelling — must
        // NOT return to whatever killed us: quit the mission, add the killer as a bad contact, and
        // pick a different one. jumps only decides whether we wait out the camp first.
        boolean inCombat = stateManager.getStateEnum(clientId) == ClientState.State.IN_COMBAT;
        if (inCombat) {
            // Died in the mission fight itself — keep the mission and go finish it. Resume at once.
            // Clear the travel phase so the next IDLE tick doesn't re-read the station agent_icon
            // as another pod death (TO_MISSION + agent_icon loops back into handlePodDeath forever).
            cs.setTravelPhase(ClientState.TravelPhase.NONE);
            if (cs.getCurrentMission() != null) {
                // Re-board the correct ship (REPAIRING → UNDOCKING → TRAVEL re-flies to the site).
                log(clientId, "POD_DEATH: in-combat death — keeping the mission, re-boarding and heading back.");
                stateManager.setState(clientId, ClientState.State.REPAIRING);
            } else {
                log(clientId, "POD_DEATH: in-combat death, no active mission — resuming idle.");
                stateManager.setState(clientId, ClientState.State.IDLE);
            }
            return true;
        }

        // Podded OUTSIDE combat — the surprising case worth an audible heads-up.
        log(clientId, "*** ALERT: podded outside of combat — sounding alarm.");
        soundAlert(POD_ALERT);
        // Flag this client to add the killer (from the still-open kill report) as a bad contact,
        // then route through POD_COOLDOWN: its expiry quits the mission we died on and picks a new
        // one (so we go somewhere else, never back into the camp). Near the station (jumps <= 2 —
        // the count runs high because the undock warp and a phantom post-death jump inflate it, so
        // a kill right by the station reads as ~2) we wait out the full cooldown before doing so;
        // deeper out we set a zero cooldown so the very next tick expires and quits/re-picks now.
        cs.setPendingKillReportAdd(true);
        // Clear the travel phase (same reason as the in-combat branch): we're docked now, so a later
        // IDLE tick with TO_MISSION + agent_icon would read the station agent as another pod death.
        cs.setTravelPhase(ClientState.TravelPhase.NONE);
        if (jumps <= 2) {
            LocalDateTime cooldownEnd = LocalDateTime.now().plusMinutes(POD_COOLDOWN_MINUTES);
            stateManager.setPodCooldownEnd(cooldownEnd);
            log(clientId, "POD_COOLDOWN: set " + POD_COOLDOWN_MINUTES + "-minute global cooldown (non-combat death, jumps=" + jumps + (bubbleRelated ? ", bubble" : "") + ").");
        } else {
            stateManager.setPodCooldownEnd(LocalDateTime.now());
            log(clientId, "POD_DEATH: non-combat death, jumps>2 (" + jumps + ") — no cooldown, but quitting the mission and adding bad standings.");
        }
        stateManager.setState(clientId, ClientState.State.POD_COOLDOWN);
        return true;
    }

    // -----------------------------------------------------------------------
    // IDLE
    // -----------------------------------------------------------------------

    /**
     * Locate and right-click the launcher module to open its context menu. Tries the 'launcher'
     * template once, falling back to the hardcoded position if it isn't matched.
     */
    private void rightClickLauncher(String clientId) {
        int[] pos = missionProcessor.findTemplatePos("launcher");
        if (pos != null) {
            missionProcessor.rightClickPos(pos[0], pos[1]);
        } else {
            Point l = Layout.launcher();
            log(clientId, "launcher template not found — using configured pos (" + l.x + "," + l.y + ").");
            missionProcessor.rightClickPos(l.x, l.y);
        }
    }

    /**
     * Verified ammo switch: right-click the launcher and load the given ammo, confirming the menu
     * actually opened instead of firing blind. A missed right-click would otherwise silently leave
     * the wrong ammo loaded, so this re-opens the menu and re-checks the ammo template once more
     * (2 quick attempts, re-searching the launcher each time), clicking only once the ammo item is
     * on screen. Kept lean — this runs inline on the poll tick, so it must not stall for seconds.
     * A miss after both attempts is usually just the correct ammo already being loaded (the item
     * isn't offered), so it's treated as benign — no alarm.
     *
     * @return true if the ammo item was found and clicked, false if it never appeared.
     */
    private boolean switchAmmo(String clientId, String ammoKey) {
        final int MENU_ATTEMPTS = 2;
        for (int attempt = 1; attempt <= MENU_ATTEMPTS; attempt++) {
            rightClickLauncher(clientId);
            // Confirm the menu opened: the ammo item is only on screen once the launcher menu is up.
            if (missionProcessor.findTemplate(ammoKey) != null) {
                missionProcessor.clickTemplate(ammoKey);
                log(clientId, "switchAmmo: loaded '" + ammoKey + "' (attempt " + attempt + "/" + MENU_ATTEMPTS + ").");
                return true;
            }
            if (attempt < MENU_ATTEMPTS) {
                log(clientId, "switchAmmo: '" + ammoKey + "' not visible after right-click (attempt "
                        + attempt + "/" + MENU_ATTEMPTS + ") — right-click may have missed; retrying once.");
            }
        }
        // Not necessarily an error: if the correct ammo is already loaded the item won't
        // show as a selectable option, so a miss here is expected — carry on, no alarm.
        log(clientId, "switchAmmo: '" + ammoKey + "' not found — likely already loaded, continuing.");
        return false;
    }

    /**
     * Load ammo before activating the gate (Vengeance only).
     * Right-clicks launcher → clicks exp_damage so explosive rockets are ready before warp.
     */
    private void loadAmmoBeforeGate(String clientId, ClientState cs) {
        Mission mission = cs.getCurrentMission();
        String target = mission != null
                ? (mission.getShipTarget() != null ? mission.getShipTarget() : mission.getTarget())
                : null;
        if (target == null || !target.equalsIgnoreCase("vengeance")) return;

        log(clientId, "PRE-GATE: loading explosive rockets for Vengeance.");
        switchAmmo(clientId, "exp_damage");
        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void handleIdle(String clientId, ClientWindowInfo window) {
        // 2. Phase-based navigation — handles transit after each warp lands.
        ClientState cs = stateManager.getState(clientId);

        // Landing safe wait — after a warp lands, hold LANDING_SETTLE_SECS for the grid/UI to
        // render before this client navigates, so it doesn't grab a stargate before
        // warp_to_location / dock has appeared and jump a random gate. Non-blocking and tick-based
        // (like the combat waits): each tick logs the elapsed time and returns, so the monitor and
        // the watchdog keep running. Holds foreground on this client throughout — only it can see
        // its own grid render — and exits early the instant the expected landing element is on
        // screen. Anchored to lastStateChange (set when WARPING → IDLE).
        if (cs.isJustExitedWarp() && cs.getTravelPhase() != ClientState.TravelPhase.NONE
                && !cs.isSkipLandingSettle()) {
            long sinceLanding = Duration.between(cs.getLastStateChange(), LocalDateTime.now()).getSeconds();
            if (sinceLanding < LANDING_SETTLE_SECS && detectLandingElement(cs) == null) {
                log(clientId, "IDLE: landing safe wait (" + sinceLanding + "s / " + LANDING_SETTLE_SECS + "s, no element yet).");
                // Hold focus and keep scanning — only this client, foregrounded, can see its own
                // grid render, so we must stay here to catch the landing element and exit early the
                // instant it appears. Yielding to the other client here would blind this scan and
                // degrade the settle into a blind wall-clock wait. Bounded by LANDING_SETTLE_SECS
                // (6s), well under the 120s force-switch watchdog.
                return;
            }
        }

        // Periodic 'general' overview reset only while outbound in space (TO_MISSION).
        if (cs.getTravelPhase() == ClientState.TravelPhase.TO_MISSION) {
            runPeriodicOverviewReset(clientId, cs);
        }

        if (cs.getTravelPhase() == ClientState.TravelPhase.TO_MISSION) {
            boolean justLanded = cs.isJustExitedWarp();
            if (justLanded) {
                cs.setJustExitedWarp(false);
                long warpSecs = Duration.between(cs.getWarpStartTime(), LocalDateTime.now()).getSeconds();

                // Scarlet: warping to the mission location dropped us into the pocket — the
                // mission is already started (no first activate_gate). Enter combat directly;
                // handleCombatScarlet does the land wait, sets destination, then works the gates.
                if (cs.isEnterCombatOnWarpLand()) {
                    cs.setEnterCombatOnWarpLand(false);
                    log(clientId, "IDLE[TO_MISSION]: Scarlet warp-to-location landed — entering combat (mission already started).");
                    stateManager.setState(clientId, ClientState.State.IN_COMBAT);
                    // The warp already landed during WARPING — there is no second gate warp to
                    // wait for. setState(IN_COMBAT) reset combatWarpLanded to false, so mark it
                    // landed now to skip the gate-warp verification phase (which would otherwise
                    // re-confirm a landing that already happened, timing from the warp_to_location
                    // press). Combat Step 0 does its own 20s land settle. Anchor the pod check to
                    // now so the 2-minute no_ship timer doesn't fire at ~0s and false-PODDED.
                    cs.setCombatWarpLanded(true);
                    cs.setLastPodCheck(LocalDateTime.now());
                    // setState(IN_COMBAT) reset combatStep to 0; Scarlet has no land-settle step,
                    // so start directly at step 1 (set destination home via agent).
                    cs.setCombatStep(1);
                    return;
                }

                // After the first jump, sweep any lingering windows (mission briefing, agent
                // convo, info panels) BEFORE the set_destination double-check below — those
                // windows can cover the set_destination button and make the poll read a false
                // "not visible", skipping the route correction.
                if (cs.getJumpCount() >= 1 && !cs.isWindowsClosedAfterJump1()) {
                    cs.setWindowsClosedAfterJump1(true);
                    log(clientId, "IDLE[TO_MISSION]: first jump done — closing lingering windows.");
                    missionProcessor.closeAllDialogs();
                }

                // Just exited warp — check mission-specific elements first (no stargate,
                // it's always visible in-system and would false-match during a gate tunnel).
                String found = missionProcessor.detectMissionArrivalUI();
                if (found != null) {
                    switch (found) {
                        case "activate_gate":
                            loadAmmoBeforeGate(clientId, cs);
                            log(clientId, "IDLE[TO_MISSION]: activate_gate — right-click → mission_start, entering combat.");
                            cs.setWarpStartTime(LocalDateTime.now());
                            cs.setLastWarpCheck(LocalDateTime.MIN);
                            stateManager.setState(clientId, ClientState.State.IN_COMBAT);
                            new Thread(() -> {
                                missionProcessor.rightClickAndSelect("activate_gate", "mission_start");
                                missionProcessor.clickEmptySpace(); // click away in space to clear the menu
                            }).start();
                            break;
                        case "warp_to_location":
                            log(clientId, "IDLE[TO_MISSION]: warp_to_location — warping into mission space.");
                            cs.setWarpStartTime(LocalDateTime.now());
                            stateManager.setState(clientId, ClientState.State.WARPING);
                            // Scarlet lands straight in the pocket (mission already started) — flag so
                            // the next landing enters combat directly instead of seeking an activate_gate.
                            if (isScarletMission(cs)) cs.setEnterCombatOnWarpLand(true);
                            new Thread(() -> missionProcessor.clickWarpToLocation()).start();
                            break;
                        default:
                            log(clientId, "unexpected '" + found + "' — ignoring.");
                    }
                    return;
                }
            }

            // Each system after the first jump, check for hostiles once — warp to safe spot if
            // enemy found. klyChecked latches per system and is reset on every gate jump below,
            // so this re-evaluates in each new system (same as RETURNING_HOME), not just once.
            if (cs.getJumpCount() >= 1 && !cs.isKlyChecked()) {
                cs.setKlyChecked(true);
                if (missionProcessor.isEnemyInLocal()) {
                    log(clientId, "IDLE[TO_MISSION]: enemy in local after jump — warping to safe spot. (jumpCount=" + cs.getJumpCount() + ")");
                    if (missionProcessor.warpToGenericSafeSpot()) {
                        cs.setWarpStartTime(LocalDateTime.now());
                        stateManager.setState(clientId, ClientState.State.WARPING);
                        cs.setSafeSpotWarp(true); // after setState: WARPING reset doesn't clear this
                        return;
                    }
                    log(clientId, "IDLE[TO_MISSION]: safe spot not found — falling through to stargate.");
                }
            }
            String found = missionProcessor.detectTravelUI();
            if (found == null) {
                // No travel UI on screen. Don't poll the agent every tick (wasteful while
                // mid-travel) — arm a 10s timer instead. Once nothing has been on screen
                // for 10s we check agent_icon: if it's there we respawned at station after
                // a pod death (recover); otherwise keep waiting and re-check every 10s.
                if (cs.getNoTravelUiSince().equals(LocalDateTime.MIN)) {
                    cs.setNoTravelUiSince(LocalDateTime.now());
                    log(clientId, "IDLE[TO_MISSION]: no navigation element visible — waiting.");
                } else if (Duration.between(cs.getNoTravelUiSince(), LocalDateTime.now()).getSeconds() >= 10) {
                    if (missionProcessor.detectCompletingUI() != null) {
                        // Agent icon visible during TO_MISSION = respawned at station after pod death.
                        log(clientId, "IDLE[TO_MISSION]: no travel UI for 10s + agent_icon — podded-at-station, recovering.");
                        cs.setNoTravelUiSince(LocalDateTime.MIN);
                        handlePodDeath(clientId, false);
                        return;
                    }
                    log(clientId, "IDLE[TO_MISSION]: no travel UI for 10s — assuming podded-at-station, but agent_icon not detected. Still waiting.");
                    cs.setNoTravelUiSince(LocalDateTime.now()); // re-arm: re-check + re-log every 10s
                }
                return;
            }
            cs.setNoTravelUiSince(LocalDateTime.MIN);
            switch (found) {
                case "activate_gate":
                    loadAmmoBeforeGate(clientId, cs);
                    log(clientId, "IDLE[TO_MISSION]: activate_gate — right-click → mission_start, entering combat.");
                    cs.setWarpStartTime(LocalDateTime.now());
                    cs.setLastWarpCheck(LocalDateTime.MIN);
                    stateManager.setState(clientId, ClientState.State.IN_COMBAT);
                    new Thread(() -> {
                        missionProcessor.rightClickAndSelect("activate_gate", "mission_start");
                        missionProcessor.clickEmptySpace(); // click away in space to clear the menu
                    }).start();
                    break;
                case "warp_to_location":
                    log(clientId, "IDLE[TO_MISSION]: warp_to_location — warping into mission space.");
                    cs.setWarpStartTime(LocalDateTime.now());
                    stateManager.setState(clientId, ClientState.State.WARPING);
                    // Scarlet lands straight in the pocket (mission already started) — flag so the
                    // next landing enters combat directly instead of looking for an activate_gate.
                    if (isScarletMission(cs)) cs.setEnterCombatOnWarpLand(true);
                    new Thread(() -> missionProcessor.clickWarpToLocation()).start();
                    break;
                case "stargate":
                    // Once we've passed the missed-warp_to_location safety threshold (jump #6+),
                    // a stale frame may be hiding warp_to_location — nudge the camera and
                    // re-scan before burning another jump. Before that, trust detectTravelUI.
                    if (cs.getJumpCount() >= 6) {
                        log(clientId, "IDLE[TO_MISSION]: jump #" + cs.getJumpCount() + " — nudging view to double-check for warp_to_location.");
                        missionProcessor.nudgeView();
                        if (missionProcessor.findTemplate("warp_to_location") != null) {
                            log(clientId, "IDLE[TO_MISSION]: warp_to_location revealed after nudge — warping into mission space.");
                            cs.setWarpStartTime(LocalDateTime.now());
                            stateManager.setState(clientId, ClientState.State.WARPING);
                            if (isScarletMission(cs)) cs.setEnterCombatOnWarpLand(true);
                            new Thread(() -> missionProcessor.clickWarpToLocation()).start();
                            break;
                        }
                    }
                    // Distance from home only moves once warp actually engages (see confirmGateJump),
                    // so a right-click that missed the gate can't make us think we've travelled.
                    cs.setPendingGateJump(true);
                    cs.setKlyChecked(false); // new system next landing — re-arm the enemy-in-local evade check
                    // Pod detour: take the safe branch on this jump by right-clicking that system
                    // instead of the autopilot's stargate (see planPodDetour). One-shot — EVE
                    // re-plots from where we land, so the rest of the trip is normal jumps. If the
                    // template isn't on screen, fall through to the stargate rather than sit still.
                    String detourTemplate = cs.getPodRerouteTemplate();
                    if (detourTemplate != null) {
                        cs.setPodRerouteTemplate(null);
                        if (missionProcessor.findTemplate(detourTemplate) != null) {
                            log(clientId, "IDLE[TO_MISSION]: POD_AVOID detour — right-click '"
                                    + detourTemplate + "' → jump. (jump #" + cs.getJumpCount() + ")");
                            EventLog.log("POD_AVOID_REROUTE", clientId + " — first jump taken via "
                                    + detourTemplate + " to route around the podded system");
                            cs.setWarpStartTime(LocalDateTime.now());
                            stateManager.setState(clientId, ClientState.State.WARPING);
                            new Thread(() -> missionProcessor.rightClickAndSelect(detourTemplate, "jump")).start();
                            break;
                        }
                        log(clientId, "IDLE[TO_MISSION]: POD_AVOID detour wanted '" + detourTemplate
                                + "' but it isn't on screen — taking the normal stargate.");
                    }
                    log(clientId, "IDLE[TO_MISSION]: stargate — right-click → jump. (jump #" + cs.getJumpCount() + ")");
                    cs.setWarpStartTime(LocalDateTime.now());
                    stateManager.setState(clientId, ClientState.State.WARPING);
                    new Thread(() -> missionProcessor.rightClickAndSelect("stargate", "jump")).start();
                    break;
                default:
                    log(clientId, "unexpected '" + found + "' — ignoring.");
            }
            return;
        }

        if (cs.getTravelPhase() == ClientState.TravelPhase.RETURNING_HOME) {
            // Scarlet docks via the home_station object (right-click → Dock) rather than the dock
            // button: once the home station is on screen we're home, so dock straight away. Gate
            // jumps en route stay on the generic flow below — home_station only matches at home.
            if (isScarletMission(cs) && missionProcessor.isStationVisible("home_station")) {
                log(clientId, "IDLE[RETURNING_HOME][Scarlet]: home_station visible — right-click → Dock.");
                cs.setJustExitedWarp(false);
                cs.setDockingToStation(true);
                cs.setWarpStartTime(LocalDateTime.now());
                stateManager.setState(clientId, ClientState.State.WARPING);
                new Thread(() -> missionProcessor.rightClickAndSelect("home_station", "dock")).start();
                return;
            }

            boolean justLanded = cs.isJustExitedWarp();
            if (justLanded) {
                cs.setJustExitedWarp(false);

            }

            // Single returning-UI scan (dock/stargate/agent), reused for the just-landed
            // handling AND the dispatch below — one screenshot instead of re-scanning dock
            // (detectDockOnly) and then dock/stargate/agent again. detectReturningUI checks
            // dock before stargate, so a real dock still wins over a background stargate.
            String found = missionProcessor.detectReturningUI();

            if (justLanded) {
                // Just exited warp — dock first (no stargate false-match during the gate tunnel).
                if ("dock".equals(found)) {
                    boolean instaEngaged = false;
                    if (missionProcessor.isEnemyInLocal()) {
                        log(clientId, "IDLE[RETURNING_HOME]: dock visible + ENEMY in local — insta-docking.");
                        MissionProcessor.InstaDockResult r = missionProcessor.instaDock();
                        if (r == MissionProcessor.InstaDockResult.NOT_ENGAGED
                                && !instaDockFallback(clientId, cs)) {
                            return; // still retrying the insta-dock — stay in IDLE, don't commit to WARPING
                        }
                        // ENGAGED, or NOT_ENGAGED past the retry cap (direct dock issued) — commit to WARPING.
                        instaEngaged = r == MissionProcessor.InstaDockResult.ENGAGED;
                    } else {
                        log(clientId, "IDLE[RETURNING_HOME]: dock visible — docking (button, station backup).");
                        missionProcessor.dockWithStationBackup();
                    }
                    cs.setDockingToStation(true);
                    cs.setWarpStartTime(LocalDateTime.now());
                    stateManager.setState(clientId, ClientState.State.WARPING);
                    // instaDock() already verified warp_active — skip WARPING's grace (set after setState).
                    if (instaEngaged) cs.setWarpPreVerified(true);
                    return;
                }

                // Dock not visible — check for hostiles before proceeding to stargate.
                // Skip if we already evaded to a safe spot this system; jump out instead of re-evading.
                if (!cs.isWarpedToSafeSpot() && missionProcessor.isEnemyInLocal()) {
                    log(clientId, "IDLE[RETURNING_HOME]: enemy in local after jump — warping to safe spot. (jumpCount=" + cs.getJumpCount() + ")");
                    if (missionProcessor.warpToGenericSafeSpot()) {
                        cs.setWarpedToSafeSpot(true);
                        cs.setWarpStartTime(LocalDateTime.now());
                        stateManager.setState(clientId, ClientState.State.WARPING);
                        cs.setSafeSpotWarp(true); // after setState: WARPING reset doesn't clear this
                        return;
                    }
                    log(clientId, "IDLE[RETURNING_HOME]: safe spot not found — falling through to stargate.");
                }
                log(clientId, "IDLE[RETURNING_HOME]: dock not visible — checking stargate.");
            }

            if (found == null) {
                // detectReturningUI already includes agent_icon, so a null result means
                // dock/stargate/agent are all absent. The agent_icon case below recovers
                // (→ COMPLETING) the moment it reappears; this 60s escalation only logs a
                // stuck warning (likely podded but the agent isn't being detected).
                if (cs.getNoTravelUiSince().equals(LocalDateTime.MIN)) {
                    cs.setNoTravelUiSince(LocalDateTime.now());
                    log(clientId, "IDLE[RETURNING_HOME]: no navigation element visible — waiting.");
                } else if (Duration.between(cs.getNoTravelUiSince(), LocalDateTime.now()).getSeconds() >= 60) {
                    log(clientId, "IDLE[RETURNING_HOME]: no travel UI for 60s — assuming podded-at-station, but agent_icon not detected. Still waiting.");
                    cs.setNoTravelUiSince(LocalDateTime.now()); // re-arm: re-check + re-log every 60s
                }
                return;
            }
            cs.setNoTravelUiSince(LocalDateTime.MIN);
            switch (found) {
                case "dock":
                    boolean instaEngagedDock = false;
                    if (missionProcessor.isEnemyInLocal()) {
                        log(clientId, "IDLE[RETURNING_HOME]: dock visible + ENEMY in local — insta-docking.");
                        MissionProcessor.InstaDockResult r = missionProcessor.instaDock();
                        if (r == MissionProcessor.InstaDockResult.NOT_ENGAGED
                                && !instaDockFallback(clientId, cs)) {
                            return; // still retrying the insta-dock — stay in IDLE
                        }
                        instaEngagedDock = r == MissionProcessor.InstaDockResult.ENGAGED;
                    } else {
                        log(clientId, "IDLE[RETURNING_HOME]: dock visible — docking (button, station backup).");
                        missionProcessor.dockWithStationBackup();
                    }
                    cs.setDockingToStation(true);
                    cs.setWarpStartTime(LocalDateTime.now());
                    stateManager.setState(clientId, ClientState.State.WARPING);
                    // instaDock() already verified warp_active — skip WARPING's grace (set after setState).
                    if (instaEngagedDock) cs.setWarpPreVerified(true);
                    break;
                case "stargate":
                    // Counts DOWN on the way home — see confirmGateJump; applied on warp confirmation.
                    cs.setPendingGateJump(true);
                    cs.setWarpedToSafeSpot(false);
                    log(clientId, "stargate — right-click → jump. (" + cs.getJumpCount() + " jump(s) from home)");
                    cs.setWarpStartTime(LocalDateTime.now());
                    stateManager.setState(clientId, ClientState.State.WARPING);
                    new Thread(() -> missionProcessor.rightClickAndSelect("stargate", "jump")).start();
                    break;
                case "agent_icon":
                    // A pod respawn at the station looks identical to a normal dock arrival — only
                    // a capsule in the hangar tells them apart. Check before handing off to
                    // COMPLETING, which would double-click the agent for a mission we died on.
                    if (missionProcessor.findTemplate("pod_station") != null
                            || missionProcessor.findTemplate("capsule") != null) {
                        log(clientId, "IDLE[RETURNING_HOME]: agent_icon + capsule in hangar — podded/respawned at station.");
                        cs.setTravelPhase(ClientState.TravelPhase.NONE);
                        handlePodDeath(clientId, false);
                        return;
                    }
                    log(clientId, "IDLE[RETURNING_HOME]: agent_icon visible — docked in station.");
                    cs.setTravelPhase(ClientState.TravelPhase.NONE);
                    stateManager.setState(clientId, ClientState.State.COMPLETING);
                    break;
                default:
                    log(clientId, "unexpected '" + found + "' — ignoring.");
            }
            return;
        }

        // 3. NONE phase.
        // After a faction run we land just outside home and dock; until the dock completes the
        // space view (with stargates) is briefly visible, which would false-trigger the
        // travel-resume below into a phantom TO_MISSION. So while a faction resume is pending,
        // wait for the docked confirmation (agent_icon) and skip the travel-resume check.
        boolean justResumedFaction = false;
        if (cs.isFactionResumePending()) {
            if (missionProcessor.detectCompletingUI() != null) {
                log(clientId, "IDLE: docked home after faction run — resuming normal checking.");
                cs.setFactionResumePending(false);
                justResumedFaction = true;
            } else if (Duration.between(cs.getLastStateChange(), LocalDateTime.now()).getSeconds() >= 60) {
                log(clientId, "IDLE: faction resume — no agent_icon after 60s, giving up the wait and proceeding normally.");
                cs.setFactionResumePending(false);
            } else {
                // Don't hog the foreground for the whole dock wait — yield to the other
                // client between checks. We stop being foreground after the switch, so this
                // fires at most once per foreground session; the force-switch watchdog (and
                // the other client's own yields) rotate us back to re-check agent_icon, and
                // the 60s timeout above is the backstop.
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "IDLE: faction run docked — waiting for agent_icon; yielding foreground to " + other.getClientId() + ".");
                    bringToForeground(other);
                } else {
                    log(clientId, "IDLE: faction run docked — waiting for agent_icon before resuming checking.");
                }
                return;
            }
        }

        // Check for travel UI first (handles restart mid-travel or post-UNDOCKING). If
        // stargate / warp_to_location / activate_gate is visible, the ship is in transit —
        // switch to TO_MISSION phase so navigation resumes correctly next tick. Skipped on the
        // tick we just confirmed a faction dock (transient stargate frames would mislead it).
        if (!justResumedFaction) {
            String travelUI = missionProcessor.detectTravelUI();
            if (travelUI != null) {
                log(clientId, "IDLE: travel UI '" + travelUI + "' detected — resuming TO_MISSION navigation.");
                cs.setTravelPhase(ClientState.TravelPhase.TO_MISSION);
                return;
            }
        }

        // Check global pod-death cooldown before accepting new missions.
        LocalDateTime podEnd = stateManager.getPodCooldownEnd();
        if (podEnd != null && LocalDateTime.now().isBefore(podEnd)) {
            long remaining = Duration.between(LocalDateTime.now(), podEnd).getSeconds();
            log(clientId, "IDLE: global pod cooldown active (" + (remaining / 60) + "m " + (remaining % 60) + "s) — entering POD_COOLDOWN.");
            stateManager.setState(clientId, ClientState.State.POD_COOLDOWN);
            return;
        }

        // Faction agent check — every 2h, while docked & idle at home (no regular mission in
        // progress). Skipped for the rest of the session once the agent has offered "Evolution"
        // (factionLocked — handled manually by the user).
        if (!cs.isFactionLocked()
                && cs.getCurrentMission() == null
                && Duration.between(cs.getLastFactionCheck(), LocalDateTime.now()).toHours() >= FACTION_CHECK_HOURS) {
            log(clientId, "IDLE: faction agent check due (>= " + FACTION_CHECK_HOURS + "h) — entering FACTION_CHECK.");
            cs.setLastFactionCheck(LocalDateTime.now());
            cs.setLastCombatOrCheck(LocalDateTime.now()); // docked at home, ship alive — gates the warp pod check
            stateManager.setState(clientId, ClientState.State.FACTION_CHECK);
            return;
        }

        // Standard mission dialog logic.
        MissionProcessor.DialogState dialogState = missionProcessor.detectDialog();

        // First time this run we actually SEE an agent mission dialog? Only then is a COMPLETED /
        // NOT_COMPLETE dialog with no accepted mission on record trusted as a real leftover from a
        // previous session (turn it in / recover it). Consume the flag only on a non-NONE state —
        // a NONE tick means the agent conversation isn't open yet (typical on the very first tick
        // after a restart), and burning "first" there leaves the real leftover mission — which
        // appears a tick or two later — mis-classified as stale and stuck.
        boolean firstDialogCheck = dialogState != MissionProcessor.DialogState.NONE
                && agentDialogChecked.add(clientId);

        switch (dialogState) {
            case NONE: {
                int attempt = agentRetries.merge(clientId, 1, Integer::sum);
                if (attempt > MAX_AGENT_RETRIES) {
                    log(clientId, "No mission dialog after " + MAX_AGENT_RETRIES
                            + " agent clicks — waiting.");
                    agentRetries.put(clientId, 0);
                    return;
                }
                log(clientId, "No mission dialog — double-clicking agent icon (attempt "
                        + attempt + "/" + MAX_AGENT_RETRIES + ").");
                missionProcessor.doubleClickAgentIcon();
                stateManager.getState(clientId).setCurrentMission(null);
                stateManager.setState(clientId, ClientState.State.CHECKING_MISSIONS);
                // Cold inspection: we opened the agent ourselves — a NOT_COMPLETE here may be a
                // pre-accepted in-progress mission to recover, not a fresh offer.
                stateManager.getState(clientId).setAllowNotCompleteRecovery(true);
                break;
            }
            case ALREADY_ACCEPTED: {
                agentRetries.put(clientId, 0);
                notCompleteRetries.put(clientId, 0);
                log(clientId, "Mission already accepted (complete_mission visible) — skipping to COMPLETING.");
                stateManager.setState(clientId, ClientState.State.COMPLETING);
                break;
            }
            case COMPLETED: {
                agentRetries.put(clientId, 0);
                notCompleteRetries.put(clientId, 0);
                // Before assuming a turn-in is needed, look for the request-mission button. If it's
                // on screen the agent is ready to hand out a new mission (nothing left to turn in —
                // the 'completed' template is stale), so request one and proceed via CHECKING_MISSIONS
                // instead of running the COMPLETING turn-in flow.
                if (missionProcessor.findTemplate("request_mission") != null) {
                    log(clientId, "COMPLETED template visible but request-mission is available — requesting a new mission instead of turning in.");
                    missionProcessor.clickRequestMission();
                    stateManager.getState(clientId).setCurrentMission(null);
                    stateManager.setState(clientId, ClientState.State.CHECKING_MISSIONS);
                    break;
                }
                // Don't turn in a mission we never accepted this session. The only time a COMPLETED
                // dialog with no mission on record is real is the FIRST agent check of the run — a
                // leftover from a previous session. Any later one is stale art (we already turned in
                // and cleared, or a misread), so refresh the conversation (double-click the agent)
                // and re-check instead of running the turn-in flow. Bounded so it can't spin.
                if (stateManager.getState(clientId).getCurrentMission() == null && !firstDialogCheck) {
                    int attempt = staleCompletedRetries.merge(clientId, 1, Integer::sum);
                    if (attempt > MAX_NOT_COMPLETE_RETRIES) {
                        log(clientId, "COMPLETED with no accepted mission persisted " + MAX_NOT_COMPLETE_RETRIES
                                + " refreshes — waiting instead of turning in.");
                        staleCompletedRetries.put(clientId, 0);
                        break;
                    }
                    log(clientId, "COMPLETED dialog but no accepted mission on record and not the first check — stale, double-clicking agent to refresh, re-checking (attempt "
                            + attempt + "/" + MAX_NOT_COMPLETE_RETRIES + ").");
                    missionProcessor.doubleClickAgentIcon();
                    stateManager.setState(clientId, ClientState.State.CHECKING_MISSIONS);
                    break;
                }
                staleCompletedRetries.put(clientId, 0);
                log(clientId, "Mission completed (completed template visible) — turning in.");
                stateManager.setState(clientId, ClientState.State.COMPLETING);
                break;
            }
            case NOT_COMPLETE: {
                agentRetries.put(clientId, 0);
                // 'not_complete' is only trustworthy as "accepted, in progress" if we
                // actually accepted a mission this session. With no mission on record the
                // match is a false signal (e.g. a fresh OFFER whose accept/decline buttons
                // weren't detected this tick) — don't assume it's accepted. Hand to
                // CHECKING_MISSIONS, which re-scans/classifies the offer (or returns to IDLE).
                if (stateManager.getState(clientId).getCurrentMission() == null) {
                    // First agent-dialog sighting of the run with NOT_COMPLETE and no mission on
                    // record: this is a genuine in-progress mission left over from a previous session
                    // (bot restarted mid-mission), not a flaky fresh-offer misread. Hand to CHECKING_
                    // MISSIONS in recovery mode so it OCRs the title and flies it, rather than bouncing
                    // it as "unaccepted".
                    if (firstDialogCheck) {
                        log(clientId, "NOT_COMPLETE on the first agent-dialog check with no mission on record — treating as a leftover in-progress mission from a previous session, recovering via CHECKING_MISSIONS.");
                        notCompleteRetries.put(clientId, 0);
                        stateManager.getState(clientId).setAllowNotCompleteRecovery(true);
                        stateManager.setState(clientId, ClientState.State.CHECKING_MISSIONS);
                        break;
                    }
                    // This can wedge into an IDLE<->CHECKING_MISSIONS loop when the agent window
                    // is showing a stale 'not_complete' that CHECKING can't classify. After a few
                    // bounces, double-click the agent icon to reset the conversation/dialog.
                    int attempt = notCompleteRetries.merge(clientId, 1, Integer::sum);
                    if (attempt > MAX_NOT_COMPLETE_RETRIES) {
                        log(clientId, "NOT_COMPLETE with no accepted mission persisted " + MAX_NOT_COMPLETE_RETRIES
                                + " bounces — double-clicking agent icon to reset the dialog.");
                        notCompleteRetries.put(clientId, 0);
                        missionProcessor.doubleClickAgentIcon();
                        stateManager.setState(clientId, ClientState.State.CHECKING_MISSIONS);
                        stateManager.getState(clientId).setAllowNotCompleteRecovery(true);
                        break;
                    }
                    log(clientId, "NOT_COMPLETE template visible but no accepted mission on record — treating as unaccepted, re-checking via CHECKING_MISSIONS (bounce " + attempt + "/" + MAX_NOT_COMPLETE_RETRIES + ").");
                    stateManager.setState(clientId, ClientState.State.CHECKING_MISSIONS);
                    stateManager.getState(clientId).setAllowNotCompleteRecovery(true);
                    break;
                }
                notCompleteRetries.put(clientId, 0);
                log(clientId, "Mission accepted but NOT complete — reading mission via OCR before ship selection.");
                Rectangle missionArea = getMissionAreaForClient(clientId);
                Mission mission = missionProcessor.readMission(missionArea);
                stateManager.getState(clientId).setCurrentMission(mission);
                stateManager.setState(clientId, ClientState.State.ACCEPTED);
                break;
            }
            default: { // OFFER
                agentRetries.put(clientId, 0);
                notCompleteRetries.put(clientId, 0);
                log(clientId, "Mission offer dialog confirmed — handing to CHECKING_MISSIONS (template-first).");
                // Leave currentMission null so CHECKING_MISSIONS classifies by
                // template first; OCR runs only if a wanted type is matched.
                stateManager.getState(clientId).setCurrentMission(null);
                stateManager.setState(clientId, ClientState.State.CHECKING_MISSIONS);
                break;
            }
        }
    }

    /**
     * If the agent's "standing too low" refusal message is on screen, park this client
     * for the rest of the session (standing won't climb on its own, so retrying is
     * pointless) and hand the foreground to the other client. Returns true if parked.
     *
     * @param where short label of the calling state, for the log line
     */
    private boolean parkIfStandingsRefused(String clientId, ClientState cs, String where) {
        if (!missionProcessor.isStandingsRefused()) return false;
        log(clientId, where + ": agent refused — standing too low (< 2). Parking this client for the session.");
        EventLog.log("STANDINGS_REFUSED", clientId + " — agent refused mission (standing < 2), client parked for session");
        cs.setStandingsLocked(true);
        cs.setCurrentMission(null);
        stateManager.setState(clientId, ClientState.State.IDLE);
        ClientWindowInfo other = findOtherClient(clientId);
        if (other != null) bringToForeground(other);
        return true;
    }

    // -----------------------------------------------------------------------
    // CHECKING_MISSIONS
    // -----------------------------------------------------------------------

    private void handleCheckingMissions(String clientId, ClientWindowInfo window) {
        ClientState cs = stateManager.getState(clientId);
        cs.setLastCombatOrCheck(LocalDateTime.now()); // docked & checking missions — ship is alive, gates the warp pod check

        // Agent refused to hand out a mission because our standing is too low (< 2).
        if (parkIfStandingsRefused(clientId, cs, "CHECKING_MISSIONS")) return;

        if (cs.getMissionCheckStartTime().equals(LocalDateTime.MIN)) {
            cs.setMissionCheckStartTime(LocalDateTime.now());
        }
        long cycleElapsed = Duration.between(cs.getMissionCheckStartTime(), LocalDateTime.now()).getSeconds();
        if (cycleElapsed >= 45) {
            ClientWindowInfo other = findOtherClient(clientId);
            if (other != null) {
                log(clientId, "CHECKING_MISSIONS: 45s in check/decline cycle — returning to IDLE and switching focus to " + other.getClientId());
                cs.setCurrentMission(null);
                stateManager.setState(clientId, ClientState.State.IDLE);
                bringToForeground(other);
                return;
            }
        }

        agentRetries.put(clientId, 0);

        // If a mission was already OCR'd by another entry path, evaluate it
        // directly with the full rule set (rare — most offers take the fast path).
        Mission existing = cs.getCurrentMission();
        if (existing != null) {
            applyDecision(clientId, existing, missionConfig.evaluate(existing, cs.getLastKnownStanding()));
            return;
        }

        // --- Template-first fast path -------------------------------------
        // Identify the offer by its dialog template (one screenshot, no OCR).
        // Scan is restricted to the dialog region for speed (~9x vs full screen).
        String missionTemplate = missionProcessor.classifyMissionOffer(getMissionAreaForClient(clientId));

        // --- Auto-reject fast path ---------------------------------------
        // A 'mission_reject_*' template matched: decline immediately, no OCR.
        // Confirm a live OFFER dialog first so a stale match can't mis-click.
        if (missionTemplate != null && missionTemplate.startsWith("reject:")) {
            MissionProcessor.DialogState rejectDialog = missionProcessor.detectDialog();
            if (rejectDialog != MissionProcessor.DialogState.OFFER) {
                // Reject template on an ACCEPTED (not yet complete) mission: an unwanted
                // offer was accepted by mistake. Quit it and re-pick — clicking Request
                // Mission does nothing while a mission is active, so that path just loops
                // until the 45s bailout.
                if (rejectDialog == MissionProcessor.DialogState.NOT_COMPLETE
                        || rejectDialog == MissionProcessor.DialogState.ALREADY_ACCEPTED) {
                    log(clientId, "Auto-reject template '" + missionTemplate.substring(7)
                            + "' matched on an accepted mission — quitting it and requesting a new one.");
                    cs.setCurrentMission(null);
                    missionProcessor.quitMission();
                    return;
                }
                log(clientId, "Auto-reject template '" + missionTemplate.substring(7)
                        + "' matched but no live OFFER dialog — requesting new mission.");
                missionProcessor.clickRequestMission();
                return;
            }
            System.out.println(">>> [" + clientId + "] DECLINE — auto-reject template "
                    + missionTemplate.substring(7) + " (no OCR)");
            countDeclineForStandings(clientId, cs);
            missionProcessor.clickDeclineButton();
            stateManager.setState(clientId, ClientState.State.DECLINING);
            return;
        }

        // --- Evolution fast path -----------------------------------------
        // The 'evolution' template matched: never decline. Confirm a live OFFER
        // first so a stale match doesn't act, then park it (leave + close + lock).
        if ("evolution".equals(missionTemplate)) {
            if (missionProcessor.detectDialog() != MissionProcessor.DialogState.OFFER) {
                log(clientId, "Evolution template matched but no live OFFER dialog — requesting new mission.");
                missionProcessor.clickRequestMission();
                return;
            }
            parkEvolution(clientId, cs, "CHECKING_MISSIONS (template)");
            return;
        }

        if (missionTemplate == null) {
            // No known wanted-mission template visible — fall back to OCR. Some wanted
            // missions have no dialog template (e.g. Scarlet, Pot and Kettle), and template
            // matching can also miss a frame for missions that normally do match (e.g.
            // Recon 1). OCR the offer and run it through the full rule set so a recognizable
            // title is still accepted; unmatched offers still default to decline.
            if (missionProcessor.detectDialog() == MissionProcessor.DialogState.OFFER) {
                Mission noTplMission = missionProcessor.readMission(getMissionAreaForClient(clientId));
                if (noTplMission == null) {
                    System.out.println(">>> [" + clientId + "] DECLINE — no mission template, OCR failed (unwanted)");
                    countDeclineForStandings(clientId, cs);
                    missionProcessor.clickDeclineButton();
                    stateManager.setState(clientId, ClientState.State.DECLINING);
                    return;
                }
                cs.setCurrentMission(noTplMission);

                // Refuse anything at (or behind) the system we were podded in, before the rules run.
                if (declineIfPodAvoided(clientId, cs, noTplMission)) return;

                // Evolution — NEVER decline. The user runs this mission manually, so
                // leave the offer on screen untouched, persist that it was offered, and
                // stop checking the faction agent for the rest of the session. OCR reads
                // the title reliably ("Evolution"), so this catch doesn't depend on the
                // (unwired) evolution.png template matching.
                String evoTitle = noTplMission.getTitle()   != null ? noTplMission.getTitle().toLowerCase()   : "";
                String evoRaw   = noTplMission.getRawText() != null ? noTplMission.getRawText().toLowerCase() : "";
                if (evoTitle.contains("evolution") || evoRaw.contains("evolution")) {
                    parkEvolution(clientId, cs, "CHECKING_MISSIONS (OCR)");
                    return;
                }

                MissionConfig.Decision decision = missionConfig.evaluateNoTemplate(
                        noTplMission, cs.getLastKnownStanding());
                applyDecision(clientId, noTplMission, decision);
            } else {
                log(clientId, "No mission template and no offer dialog — returning to IDLE.");
                stateManager.setState(clientId, ClientState.State.IDLE);
            }
            return;
        }

        // A wanted template matched — but the same dialog art also shows on a
        // mission that's already accepted/in-progress or just turned in. Only a
        // live OFFER (accept/decline present) is a fresh offer to evaluate; route
        // every other dialog state to the handler for that state instead of
        // treating it as stale and re-requesting (which loops forever when the
        // mission is actually accepted and in progress).
        MissionProcessor.DialogState tplDialog = missionProcessor.detectDialog();
        switch (tplDialog) {
            case NOT_COMPLETE: {
                // A wanted template + NOT_COMPLETE normally means a mission accepted earlier and
                // still in progress. But the same 'not_complete' art (the offer's "must be completed
                // to finish the mission" objectives text) also shows on a FRESH offer, so a flaky
                // accept/decline miss can make detectDialog read a brand-new offer as NOT_COMPLETE.
                // Only honour it as an accepted mission on a cold/startup inspection of the agent
                // dialog (allowNotCompleteRecovery). In the normal request/decline cycle it's a fresh
                // offer — fall through and evaluate it (declining if unwanted) instead of blindly
                // flying + undocking it with a mismatched ship.
                if (!cs.isAllowNotCompleteRecovery()) {
                    log(clientId, "Template '" + missionTemplate + "' matched with NOT_COMPLETE, but this is a fresh offer (not a startup recovery) — evaluating as an offer instead of flying it.");
                    break; // exit switch → OCR + rule evaluation below
                }
                // Mission accepted and in progress but no currentMission on record
                // (e.g. bot restarted mid-mission). OCR the title and fly it.
                log(clientId, "Template '" + missionTemplate + "' matched with NOT_COMPLETE dialog — accepted mission in progress (startup recovery), reading via OCR and flying it.");
                Mission accepted = missionProcessor.readMission(getMissionAreaForClient(clientId));
                // Set the combat target from the template here too — this recovery path skips the
                // normal template-target set below, so without this it would depend solely on the
                // flaky repair-time dialog re-match (and could loop on shipTarget='unknown').
                if (accepted != null && missionTemplate.startsWith("mission_")) {
                    accepted.setShipTarget(missionTemplate.substring("mission_".length()));
                }
                cs.setCurrentMission(accepted);
                stateManager.setState(clientId, ClientState.State.ACCEPTED);
                return;
            }
            case ALREADY_ACCEPTED: {
                log(clientId, "Template '" + missionTemplate + "' matched with complete_mission visible — skipping to COMPLETING.");
                stateManager.setState(clientId, ClientState.State.COMPLETING);
                return;
            }
            case COMPLETED: {
                log(clientId, "Template '" + missionTemplate + "' matched with COMPLETED dialog — turning in via COMPLETING.");
                stateManager.setState(clientId, ClientState.State.COMPLETING);
                return;
            }
            case NONE: {
                // No dialog at all — the template art is stale (e.g. just-completed
                // mission still shown after turn-in). Request a fresh mission.
                log(clientId, "Template '" + missionTemplate + "' matched but no dialog on screen — stale, requesting new mission.");
                missionProcessor.clickRequestMission();
                return;
            }
            default: // OFFER — fall through to evaluate the fresh offer.
                break;
        }

        // Scarlet: its dialog text OCRs unreliably (the title comes through as "Sc a rlet"),
        // so skip OCR — set the title ourselves and run the standings gate. The config rule
        // (title_contains "scarlet", max_standing 1.0) accepts it only when standing < 1.
        if ("mission_scarlet".equals(missionTemplate)) {
            Mission scarlet = new Mission();
            scarlet.setTitle("scarlet");
            scarlet.setShipTarget("scarlet"); // so IN_COMBAT routes to handleCombatScarlet
            cs.setCurrentMission(scarlet);
            log(clientId, "Scarlet template matched — evaluating against standings (accept only if < 1).");
            applyDecision(clientId, scarlet, missionConfig.evaluate(scarlet, cs.getLastKnownStanding()));
            return;
        }

        // Wanted type matched — OCR now (only here) to check distance / nuances.
        log(clientId, "Mission template matched: " + missionTemplate + " — OCR for distance check.");
        Rectangle missionArea = getMissionAreaForClient(clientId);
        Mission mission = missionProcessor.readMission(missionArea);
        if (mission == null) {
            // Template still on screen but OCR failed — retry next tick (45s cycle
            // timeout is the backstop that frees the client).
            log(clientId, "OCR returned null on a template-matched offer — retrying next tick.");
            return;
        }
        cs.setCurrentMission(mission);

        // Refuse anything at (or behind) the system we were podded in — a wanted mission type is
        // still not worth flying back into the camp that killed us.
        if (declineIfPodAvoided(clientId, cs, mission)) return;

        // The matched wanted template already identifies the combat ship type, so set it as the
        // combat target now. The repair-time dialog re-match (performUndockStart) still overrides
        // this if it succeeds; but when that re-match misses (flaky), this keeps IN_COMBAT from
        // falling back to the OCR'd "Unknown" target and looping forever on shipTarget='unknown'.
        if (missionTemplate.startsWith("mission_")) {
            String combatTarget = missionTemplate.substring("mission_".length());
            mission.setShipTarget(combatTarget);
            log(clientId, "Combat target set from template: '" + combatTarget + "'.");
        }

        // The template match only told us this is a *candidate* wanted type — it
        // can be a false positive (e.g. mission_enyo matching "Smash the Supplier").
        // Run the FULL rule set: an accept rule must match, and any decline rule
        // (too-far / base / blood / Recon2of3) still wins. Default is decline.
        // Standings-gated rules (e.g. recon 1, accepted only when standing < 1) are
        // resolved here against the tracked per-client standing.
        applyDecision(clientId, mission, missionConfig.evaluate(mission, cs.getLastKnownStanding()));
    }

    /**
     * Count one declined offer; every {@link #STANDINGS_RECHECK_DECLINES}th decline schedules a
     * standings re-read so the tracked per-client standing (used for mission gating) doesn't drift
     * stale during a decline streak. Called from EVERY real offer-decline path, not just the
     * OCR-evaluated one, so the count matches the declines actually happening on screen.
     */
    private void countDeclineForStandings(String clientId, ClientState cs) {
        int n = cs.getDeclineCount() + 1;
        if (n >= STANDINGS_RECHECK_DECLINES) {
            cs.setDeclineCount(0);
            cs.setStandingsCheckDue(true);
            log(clientId, STANDINGS_RECHECK_DECLINES + " declines — standings re-check scheduled.");
        } else {
            cs.setDeclineCount(n);
        }
    }

    /**
     * Pod-avoidance gate for a freshly OCR'd offer: decline it outright if its Location is the
     * system we were last podded in, or anything the map can only reach through that system.
     *
     * Runs before the normal rule set so a mission we'd otherwise want still gets refused — the
     * point is to never fly back into the camp that killed us. An unreadable or off-map location
     * passes: {@link SystemMap#isBlocked} only refuses what the map positively places behind the
     * podded system, and the undock route-panel check (see {@link #handleTravel}) is the backstop
     * for anything that slips through here.
     *
     * @return true if the mission was declined (caller must stop).
     */
    private boolean declineIfPodAvoided(String clientId, ClientState cs, Mission mission) {
        String avoided = stateManager.getAvoidedSystem();
        if (avoided == null || mission == null) return false;

        String location = mission.getLocation();
        if (!SystemMap.isBlocked(location, avoided)) return false;

        long left = stateManager.getAvoidMinutesRemaining();
        String why = SystemMap.normalize(location).equals(SystemMap.normalize(avoided))
                ? "the system we were podded in"
                : "only reachable through " + avoided + ", where we were podded";
        System.out.println(">>> [" + clientId + "] DECLINE — " + mission.getTitle()
                + " (location " + location + " is " + why + ")");
        log(clientId, "POD_AVOID: declining '" + mission.getTitle() + "' — " + location + " is " + why
                + (left >= 0 ? " (" + left + "m of avoidance left)" : " (avoided for the session)"));
        EventLog.log("POD_AVOID_DECLINE", clientId + " — declined '" + mission.getTitle()
                + "' at " + location + " (" + why + ")");

        cs.setCurrentMission(null);
        countDeclineForStandings(clientId, cs);
        missionProcessor.clickDeclineButton();
        stateManager.setState(clientId, ClientState.State.DECLINING);
        return true;
    }

    /** Click accept/decline for a decided mission and transition state. */
    private void applyDecision(String clientId, Mission mission, MissionConfig.Decision decision) {
        if (decision == MissionConfig.Decision.ACCEPT) {
            System.out.println(">>> [" + clientId + "] ACCEPT — " + mission.getTitle());
            if (!missionProcessor.clickAcceptButton()) {
                log(clientId, "ACCEPT failed — offer still on screen, staying put to retry next tick.");
                return;
            }
            // Record the grab time so the fleet partner can raise its take-all threshold
            // (fleet reward) for the next 2 minutes — see chooseFleetReward.
            stateManager.getState(clientId).setLastMissionGrab(LocalDateTime.now());
            stateManager.setState(clientId, ClientState.State.ACCEPTED);
        } else {
            System.out.println(">>> [" + clientId + "] DECLINE — " + mission.getTitle());
            countDeclineForStandings(clientId, stateManager.getState(clientId));
            missionProcessor.clickDeclineButton();
            stateManager.setState(clientId, ClientState.State.DECLINING);
        }
    }

    /**
     * Evolution offered — NEVER decline. The user runs this mission manually, so leave
     * the offer intact, click 'close' once to dismiss the agent dialog, persist that it
     * was offered, and lock the faction agent for the rest of the session.
     */
    private void parkEvolution(String clientId, ClientState cs, String where) {
        log(clientId, where + ": 'Evolution' offered — never decline, pressing Delay to defer it (leaves it intact for manual handling), closing dialog and locking faction agent for the session.");
        EventLog.log("FACTION_EVOLUTION", clientId + " — Evolution offered, faction agent locked for session");
        // Press 'Delay' first: it clears the offer out of the agent dialog without declining,
        // so IDLE's next detectDialog() no longer re-sees OFFER and bounces us straight back
        // into CHECKING_MISSIONS -> parkEvolution forever. Then close the (now offer-less) window.
        missionProcessor.clickTemplate("delay");
        missionProcessor.clickTemplate("close");
        cs.setFactionLocked(true);
        stateManager.setState(clientId, ClientState.State.IDLE);
    }

    // -----------------------------------------------------------------------
    // DECLINING
    // -----------------------------------------------------------------------

    private void handleDeclining(String clientId, ClientWindowInfo window) {
        log(clientId, "DECLINING — waiting for offer dialog to close...");

        if (!missionProcessor.waitForDialogClosed()) {
            ClientState cs = stateManager.getState(clientId);
            if (cs.isDeclineRetried()) {
                log(clientId, "Decline dialog stuck — double-clicking agent to reset.");
                missionProcessor.doubleClickAgentIcon();
                cs.setCurrentMission(null);
                stateManager.setState(clientId, ClientState.State.CHECKING_MISSIONS);
            } else {
                log(clientId, "Decline still visible — clicking decline to confirm.");
                missionProcessor.clickDeclineButton();
                cs.setDeclineRetried(true);
            }
            return;
        }

        // Scheduled standings re-read (every Nth non-anomic decline): the agent dialog is open
        // now that the offer closed, so read standings, persist, then refocus the agent dialog
        // before requesting the next mission. NaN OCR misses are ignored by recordStanding.
        ClientState cs = stateManager.getState(clientId);
        if (cs.isStandingsCheckDue()) {
            cs.setStandingsCheckDue(false);
            log(clientId, "DECLINING: standings re-check due — reading agent standings.");
            missionProcessor.clickAgentStandings();
            double standings = missionProcessor.readAgentStandings();
            if (!Double.isNaN(standings)) {
                stateManager.recordStanding(clientId, standings);
                log(clientId, "DECLINING: tracked agent standing updated for mission gating.");
            }
            missionProcessor.closeStandingsDialog();  // close the standings window (region-scoped 'X')
            missionProcessor.doubleClickAgentIcon(); // refocus agent dialog after the standings window
        }

        log(clientId, "Dialog closed — requesting new mission.");
        missionProcessor.clickRequestMission();

        if (!missionProcessor.waitForNewOffer()) {
            // No offer appeared — the agent may be refusing because our standing is too
            // low. Check here too: otherwise this request/retry loop spins forever
            // without ever re-entering CHECKING_MISSIONS where the other check lives.
            if (parkIfStandingsRefused(clientId, stateManager.getState(clientId), "DECLINING")) return;

            // Accidental accept: the "decline" click can land on Accept, so the mission we meant to
            // reject is now active. Request Mission is a no-op while a mission is in progress, hence
            // no new offer ever appears. Probe the dialog after a couple of dry retries — if it shows
            // an accepted (NOT_COMPLETE / ALREADY_ACCEPTED) mission, quit it and re-check. Bounded so
            // a genuinely slow agent (offer just late) isn't treated as an accidental accept.
            int noOffer = declineNoOfferRetries.merge(clientId, 1, Integer::sum);
            if (noOffer >= 2) {
                MissionProcessor.DialogState ds = missionProcessor.detectDialog();
                if (ds == MissionProcessor.DialogState.NOT_COMPLETE
                        || ds == MissionProcessor.DialogState.ALREADY_ACCEPTED) {
                    log(clientId, "DECLINING: no new offer and dialog shows an accepted mission — decline likely landed on Accept; quitting the wrong mission and re-checking.");
                    declineNoOfferRetries.put(clientId, 0);
                    cs.setCurrentMission(null);
                    missionProcessor.quitMission();
                    stateManager.setState(clientId, ClientState.State.CHECKING_MISSIONS);
                    return;
                }
            }
            log(clientId, "New offer not visible yet — will retry next tick.");
            return;
        }
        declineNoOfferRetries.put(clientId, 0);

        // Leave currentMission null so CHECKING_MISSIONS classifies the new offer
        // via template matching first (OCR only runs if a wanted type matches).
        stateManager.getState(clientId).setCurrentMission(null);
        log(clientId, "New offer visible — handing to CHECKING_MISSIONS (template-first).");
        stateManager.setState(clientId, ClientState.State.CHECKING_MISSIONS);
    }

    // -----------------------------------------------------------------------
    // ACCEPTED — Track only, dialog stays open for ship template matching
    // -----------------------------------------------------------------------

    private void handleAccepted(String clientId, ClientWindowInfo window) {
        ClientState accState = stateManager.getState(clientId);
        if (!accState.isTrackAttempted()) {
            log(clientId, "First accept — clicking Track.");
            missionProcessor.clickTrack();
            accState.setTrackAttempted(true);
        } else {
            log(clientId, "Track already attempted — skipping.");
        }
        // Dialog stays open intentionally — performPostAcceptFlow needs it
        // visible so Python can match the ship from the dialog content.
        stateManager.setState(clientId, ClientState.State.REPAIRING);
    }

    // -----------------------------------------------------------------------
    // REPAIRING — repair + activate ship, click Undock → UNDOCKING
    // -----------------------------------------------------------------------

    private void handleRepairing(String clientId, ClientWindowInfo window) {
        Mission mission = stateManager.getState(clientId).getCurrentMission();
        log(clientId, "Starting repair + undock flow for: "
                + (mission != null ? mission.getTitle() : "unknown mission"));

        String result = missionProcessor.performUndockStart(mission);

        if (MissionProcessor.UNDOCK_OK.equals(result)) {
            log(clientId, "Clicking undock.");
            missionProcessor.clickUndock();
            stateManager.getState(clientId).setUndockStartTime(LocalDateTime.now());
            stateManager.setState(clientId, ClientState.State.UNDOCKING);
            // Stay on this client through the undock animation so it can jump out as
            // fast as possible — no switching to the other client during undock.
        } else if (MissionProcessor.UNDOCK_SHIP_NOT_FOUND.equals(result)) {
            String mTitle = mission != null ? mission.getTitle() : "unknown";
            log(clientId, "*** ALERT: no ship for this mission — sounding alarm (may be stuck).");
            EventLog.log("SHIP_NOT_FOUND", clientId + " — mission: " + mTitle);
            soundAlert(NO_SHIP_ALERT);
            stateManager.getState(clientId).setCurrentMission(null);
            stateManager.setState(clientId, ClientState.State.CHECKING_MISSIONS);
        } else {
            // repairShip failed (e.g. 'Ship Hangar' not found — we're not actually in station).
            // Do NOT fall back to IDLE: that resumes travel and jumps a gate in an un-repaired,
            // un-boarded state. Stop this client until restart.
            System.err.println("[" + clientId + "] Repair/undock flow failed — parking client.");
            parkClient(clientId, "repair/undock failed (hangar not found)");
        }
    }

    // -----------------------------------------------------------------------
    // TRAVEL — one-shot setup: close dialogs, reload modules, set destination.
    //          Navigation (stargates / warp_to_location / activate_gate) is
    //          handled by IDLE + WARPING cycling after this.
    // -----------------------------------------------------------------------

    /**
     * Click set_destination only if its template is on screen (no hardcoded fallback), then confirm
     * the click landed by OCR-reading the info-panel Route section ("Route  N Jumps"). The
     * set_destination prompt itself looks the same before and after the click, so its presence
     * proves nothing — and its *absence* proves nothing either, since a miss also closes the menu.
     * The route panel is the ONLY confirmation: no route logged means it wasn't clicked.
     * Returns the confirmed route (never null when set), or null if no route showed up.
     *
     * The returned destination is also what the pod-avoidance check reads in
     * {@link #handleTravel} — the route panel is the first place the actual destination system
     * is visible when the mission dialog's Location line failed to OCR at pickup.
     */
    private MissionProcessor.RouteInfo clickSetDestinationByTemplate(String clientId) {
        int[] pos = missionProcessor.findTemplatePos("set_destination");
        if (pos == null) return null;
        log(clientId, "ROUTE: set_destination found at (" + pos[0] + "," + pos[1] + ") — clicking.");
        missionProcessor.clickPos(pos[0], pos[1]);
        // Move the cursor off the prompt (click in empty space) so it can't cover either template
        // during the confirmation scan.
        missionProcessor.clickEmptySpace();
        log(clientId, "ROUTE: reading the route panel to confirm the destination took.");
        MissionProcessor.RouteInfo route = missionProcessor.readRoute();
        log(clientId, "ROUTE: panel OCR: [" + route.rawOneLine() + "]");
        if (route.isSet()) {
            log(clientId, "ROUTE = " + route + " — destination confirmed.");
            return route;
        }
        log(clientId, "ROUTE = none — set_destination was not clicked.");
        return null;
    }

    /**
     * Second pod-avoidance gate, on the route panel after undock. The mission dialog's Location
     * line is small and OCRs badly, so an offer at a system we're avoiding can get past
     * {@link #declineIfPodAvoided}. The route panel names the destination in a much cleaner font,
     * so re-check it here — and if it IS behind the podded system, get back in the hangar and quit
     * the mission rather than fly the route.
     *
     * @return true if we redocked and quit (caller must stop).
     */
    private boolean quitIfRoutePodAvoided(String clientId, ClientState cs,
                                          MissionProcessor.RouteInfo route) {
        String avoided = stateManager.getAvoidedSystem();
        if (avoided == null || route == null || route.destination == null) return false;
        if (!SystemMap.isBlocked(route.destination, avoided)) return false;

        String dest = SystemMap.display(SystemMap.normalize(route.destination));
        log(clientId, "POD_AVOID: route destination " + dest + " is at/behind " + avoided
                + " where we were podded — redocking and quitting this mission.");
        EventLog.log("POD_AVOID_QUIT", clientId + " — undocked onto a route to " + dest
                + ", at/behind the podded system " + avoided + "; redocked and quit");

        if (missionProcessor.emergencyRedock()) {
            missionProcessor.quitMission();
            cs.setCurrentMission(null);
            cs.setTravelPhase(ClientState.TravelPhase.NONE);
            stateManager.setState(clientId, ClientState.State.CHECKING_MISSIONS);
        } else {
            // Couldn't get back in. Flying the route anyway is exactly what avoidance exists to
            // prevent, so stop the client and let the user sort it out.
            parkClient(clientId, "route runs through the podded system " + avoided + " and redock failed");
        }
        return true;
    }

    /**
     * Plan the detour around the podded system for a destination we ARE still allowed to reach.
     *
     * The autopilot always picks the shortest route, which after a pod death may still run through
     * the system that killed us even when a longer way round exists (die in KLY-C0 heading for
     * ROIR-Y: the safe path is J-CIJV => G95-VZ => ROIR-Y). EVE re-plots the route from wherever we
     * are, so we only have to steer the FIRST jump onto the safe branch — from there the autopilot's
     * own shortest path no longer touches the podded system. That first hop is taken by right-clicking
     * the system and jumping instead of the stargate the autopilot would have used.
     *
     * Stores the template on the client state; a null (or absent template) just means the trip runs
     * normally. Missing template PNGs match nothing, so an unbuilt template degrades to a normal jump.
     */
    private void planPodDetour(String clientId, ClientState cs, MissionProcessor.RouteInfo route) {
        cs.setPodRerouteTemplate(null);
        String avoided = stateManager.getAvoidedSystem();
        if (avoided == null || route == null || route.destination == null) return;

        List<String> detour = SystemMap.detour(route.destination, avoided);
        if (detour.size() < 2) return; // no way round, or destination isn't on the map

        String firstHop = detour.get(1);
        String template = SystemMap.rerouteTemplate(firstHop);
        if (template == null) {
            log(clientId, "POD_AVOID: safe route to " + SystemMap.display(SystemMap.normalize(route.destination))
                    + " starts at " + firstHop + ", but that system has no reroute template"
                    + " — flying the autopilot route as-is.");
            return;
        }
        cs.setPodRerouteTemplate(template);
        log(clientId, "POD_AVOID: avoiding " + avoided + " — first jump goes to " + firstHop
                + " via right-click on '" + template + "' instead of the autopilot stargate."
                + " Safe path: " + String.join(" => ", detour));
    }

    private void handleTravel(String clientId, ClientWindowInfo window) {
        ClientState cs = stateManager.getState(clientId);
        missionProcessor.closeAllDialogs();
        missionProcessor.pressCtrlR();
        missionProcessor.closeAllDialogs();
        if (isReconMission(cs)) {
            // Recon is always the same system — the destination is already set. Skip set_destination
            // and its re-route safety nets entirely; just reveal warp_to_location (nudge a stale
            // frame if it isn't on screen yet). IDLE[TO_MISSION] then warps to the mission location.
            log(clientId, "TRAVEL[Recon]: same-system mission — skipping set_destination, looking for warp_to_location.");
            boolean seen = missionProcessor.findTemplate("warp_to_location") != null;
            for (int attempt = 1; !seen && attempt <= 2; attempt++) {
                log(clientId, "TRAVEL[Recon]: warp_to_location not visible — nudging view (" + attempt + "/2).");
                missionProcessor.nudgeView();
                seen = missionProcessor.findTemplate("warp_to_location") != null;
            }
        } else {
            // Set destination by template only, confirmed by the route panel. We do NOT jump
            // without a route — staying in TRAVEL re-runs this whole post-undock routine on the
            // next tick, and it keeps repeating until the route panel actually reads a route.
            // missionProcessor.clickSetDestination(281, 515); // undock position (hardcoded — disabled for now)
            MissionProcessor.RouteInfo route = clickSetDestinationByTemplate(clientId);
            for (int attempt = 1; route == null && attempt <= 2; attempt++) {
                log(clientId, "TRAVEL: no route yet — nudging view (" + attempt + "/2).");
                missionProcessor.nudgeView();
                route = clickSetDestinationByTemplate(clientId);
            }
            // The route panel is the first clean read of the destination — refuse it here if it
            // sits at or behind the system we were podded in, or plan a detour if it's still
            // reachable the long way round.
            if (quitIfRoutePodAvoided(clientId, cs, route)) return;
            // Remember where this trip is going: with jumpCount (our distance from home) it locates
            // us on the map, which is how a pod death is placed when the kill report can't be read.
            if (route != null && route.destination != null && SystemMap.isKnown(route.destination)) {
                String dest = SystemMap.display(SystemMap.normalize(route.destination));
                cs.setRouteDestination(dest);
                log(clientId, "TRAVEL: destination " + dest + " — route: "
                        + String.join(" => ", SystemMap.pathFromStation(dest)));
            }
            planPodDetour(clientId, cs, route);
            if (route == null) {
                // No route — but a same-system mission has no destination to set at all: the
                // agent gives warp_to_location straight away. If that's on screen, we're already
                // where we need to be, so carry on instead of looping for a route that'll never
                // appear. Otherwise repeat the whole post-undock routine next tick.
                if (missionProcessor.findTemplate("warp_to_location") != null) {
                    log(clientId, "TRAVEL: no route but warp_to_location is up — same-system mission, proceeding.");
                } else {
                    log(clientId, "TRAVEL: still no route and no warp_to_location after 2 nudges — NOT jumping, repeating the undock routine next tick.");
                    return;
                }
            }
        }

        // After setting destination, check for hostiles — warp to safe spot if needed.
        if (missionProcessor.findTemplate("enemy") != null) {
            log(clientId, "TRAVEL: enemy in local after undock — warping to safe spot.");
            if (missionProcessor.warpToSafeSpot()) {
                // Evaded — enter WARPING so the safe-spot warp completes, instead of
                // dropping to IDLE and immediately jumping the next stargate. Mark the
                // enemy check done so IDLE[TO_MISSION] doesn't re-check after landing.
                cs.setKlyChecked(true);
                cs.setWarpStartTime(LocalDateTime.now());
                cs.setTravelPhase(ClientState.TravelPhase.TO_MISSION);
                stateManager.setState(clientId, ClientState.State.WARPING);
                cs.setSafeSpotWarp(true); // after setState: WARPING reset doesn't clear this
                return;
            }
        }

        cs.setTravelPhase(ClientState.TravelPhase.TO_MISSION);
        stateManager.setState(clientId, ClientState.State.IDLE);
    }

    // -----------------------------------------------------------------------
    // IN_COMBAT — dispatches to mission-specific combat sequences.
    //             Each sequence is step-based with safe-wait periods that
    //             allow client switching (mirrors the WARPING pattern).
    //             Unknown targets fall back to set_destination polling.
    // -----------------------------------------------------------------------

    private void handleInCombat(String clientId, ClientWindowInfo window) {
        ClientState cs = stateManager.getState(clientId);
        cs.setLastCombatOrCheck(LocalDateTime.now()); // ship is alive and fighting — gates the warp pod check

        // ── Warp verification phase (no client switching) ──
        // After clicking activate_gate → mission_start, verify warp started then wait for it to land.
        if (!cs.isCombatWarpLanded()) {
            long elapsed = Duration.between(cs.getWarpStartTime(), LocalDateTime.now()).getSeconds();

            // 0–5s: grace — give the right-click+mission_start click time to land and
            // the gate warp to begin before polling warp_active (a grayed-out gate that
            // never warped is caught afterward by the re-click logic).
            if (elapsed < 5) {
                if (cs.getLastWarpCheck().equals(LocalDateTime.MIN)) {
                    log(clientId, "IN_COMBAT: waiting for gate warp to begin.");
                    cs.setLastWarpCheck(LocalDateTime.MIN.plusNanos(1)); // grace-logged marker
                }
                return;
            }

            // 3s+: poll warp_active every 2s — when it disappears (re-confirmed), warp has landed.
            // Tight interval keeps the consecutive-miss confirmation from adding much latency.
            boolean firstCheck = cs.getLastWarpCheck().isBefore(LocalDateTime.MIN.plusSeconds(1));
            long sinceLastCheck = Duration.between(cs.getLastWarpCheck(), LocalDateTime.now()).getSeconds();
            if (!firstCheck && sinceLastCheck < 2) {
                return; // throttle to 2s intervals
            }
            cs.setLastWarpCheck(LocalDateTime.now());

            if (missionProcessor.isWarpActive()) {
                if (firstCheck) {
                    log(clientId, "IN_COMBAT: gate warp confirmed — waiting for landing.");
                }
                cs.setCombatWarpSeen(true);       // warp fired — through the gate, waiting to land
                cs.setWarpEndMisses(0);           // still warping — reset the gone-streak
                gateWarpRetries.put(clientId, 0); // warp started — clear re-click counter
                return;
            }

            // Warp not active this poll.
            if (cs.isCombatWarpSeen()) {
                // We were warping and now read gone. Require WARP_END_MISS_CONFIRM consecutive gone
                // polls before declaring the landing, so a single dropped warp_active frame mid-warp
                // doesn't start combat early.
                int misses = cs.getWarpEndMisses() + 1;
                cs.setWarpEndMisses(misses);
                if (misses < WARP_END_MISS_CONFIRM) {
                    log(clientId, "IN_COMBAT: warp reads gone (confirm " + misses + "/" + WARP_END_MISS_CONFIRM
                            + ") — re-checking before combat.");
                    return;
                }
                // Confirmed stopped — landed. Proceed to combat (Recon step 0 etc.).
                log(clientId, "IN_COMBAT: warp ended after " + elapsed + "s — landed, starting combat.");
                gateWarpRetries.put(clientId, 0);
                cs.setCombatWarpLanded(true);
                // Anchor the 2-minute no_ship timer to combat start. Without this the timer measures
                // from a stale/MIN timestamp and the check fires at ~0s — right when the HUD is still
                // settling after warp-in, producing a false PODDED verdict.
                cs.setLastPodCheck(LocalDateTime.now());
                return;
            }

            // Never saw warp this run. Before re-clicking, make sure the gate is actually still there:
            // if it's gone, the warp just fired too quick to catch — we've warped through, so treat it
            // as a landing and start combat rather than re-clicking a gate that no longer exists.
            if (missionProcessor.findTemplate("activate_gate") == null) {
                log(clientId, "IN_COMBAT: no warp detected but gate is gone — warped through, landed, starting combat.");
                gateWarpRetries.put(clientId, 0);
                cs.setCombatWarpLanded(true);
                cs.setLastPodCheck(LocalDateTime.now());
                return;
            }

            // Before another blind re-click, run the periodic no_ship check. The landed-combat
            // pod check further down is unreachable while we're stuck here (this whole warp-
            // verification block returns every tick until warp lands), so a pod death at the gate
            // would otherwise loop forever — exactly the "231 attempts, no pod check" wedge. The
            // ship is sitting still at the gate, so the HUD is stable and isFlyingPod is accurate.
            if (Duration.between(cs.getLastPodCheck(), LocalDateTime.now()).toMinutes() >= 2) {
                log(clientId, "IN_COMBAT: gate not firing — running 2-minute no_ship check.");
                if (missionProcessor.isFlyingPod()) {
                    log(clientId, "IN_COMBAT: no_ship while stuck at gate — podded, self-destructing.");
                    if (handlePodDeath(clientId, true)) {
                        return; // real pod death — handled (self-destruct/respawn), overrides the loop
                    }
                    log(clientId, "IN_COMBAT: pod check false positive — resuming gate retries.");
                }
                cs.setLastPodCheck(LocalDateTime.now()); // re-arm the 2-min throttle
            }

            // Gate still present but never fired (grayed on session change / out of range). Right-click
            // the gate → mission_start again (with the clear-menu mid-click) and wait for the warp to
            // begin. Never drop to IDLE (IDLE[TO_MISSION] would mis-read the mission gate as a travel
            // stargate and jump it off-route). Self-heals once the session-change timer clears.
            int retries = gateWarpRetries.merge(clientId, 1, Integer::sum);
            log(clientId, "IN_COMBAT: no warp after " + elapsed + "s — re-clicking activate_gate (attempt "
                    + retries + ").");
            new Thread(() -> {
                missionProcessor.rightClickAndSelect("activate_gate", "mission_start");
                missionProcessor.clickEmptySpace(); // click away in space to clear the menu
            }).start();
            cs.setWarpStartTime(LocalDateTime.now());       // restart the 5s grace
            cs.setLastWarpCheck(LocalDateTime.MIN);          // next poll is a fresh first check
            return;
        }

        // ── Mission timer: once every two minutes in combat, run the cheap no_ship
        //    check. isFlyingPod() stage 1 is a passive sliver match (no clicking) when
        //    the ship is alive, so this is safe to fire mid-fight. If we've been podded,
        //    the combat step machine would otherwise keep trying to lock targets from
        //    a capsule until the per-handler hardcap; instead handlePodDeath self-
        //    destructs (guarded by the capsule template) and hands the ~120s respawn
        //    to the other client, so this client stops spinning and yields. ──
        if (Duration.between(cs.getLastPodCheck(), LocalDateTime.now()).toMinutes() >= 2) {
            log(clientId, "IN_COMBAT: 2-minute no_ship check.");
            if (missionProcessor.isFlyingPod()) {
                log(clientId, "IN_COMBAT: no_ship — podded mid-combat, self-destructing.");
                // Leave lastPodCheck at the previous clean check so handlePodDeath can
                // bound the death window (the kill happened sometime since that check).
                if (handlePodDeath(clientId, true)) {
                    return; // real pod death — handled (self-destruct/respawn)
                }
                // False positive (no capsule) — don't re-probe next tick. Reset the
                // throttle and carry on with combat.
                log(clientId, "IN_COMBAT: pod check was a false positive — resuming combat, re-arming 2-min throttle.");
                cs.setLastPodCheck(LocalDateTime.now());
            } else {
                cs.setLastPodCheck(LocalDateTime.now()); // clean check passed — reset the 2-min throttle
            }
        }

        Mission mission = cs.getCurrentMission();

        String resolvedTarget = mission != null
                ? (mission.getShipTarget() != null ? mission.getShipTarget() : mission.getTarget())
                : null;

        if (resolvedTarget == null) {
            log(clientId, "IN_COMBAT: no mission/shipTarget — falling back to completion polling."
                    + " (mission=" + mission + ")");
            String completed = missionProcessor.detectCombatComplete();
            if ("dock".equals(completed)) {
                log(clientId, "IN_COMBAT: dock detected — same system, docking.");
                missionProcessor.clickTemplate("dock");
                cs.setDockingToStation(true);
                cs.setTravelPhase(ClientState.TravelPhase.NONE);
                cs.setWarpStartTime(LocalDateTime.now());
                stateManager.setState(clientId, ClientState.State.WARPING);
            } else if (completed != null) {
                log(clientId, "IN_COMBAT: set_destination detected — mission complete.");
                stateManager.setState(clientId, ClientState.State.RETURNING);
            }
            return;
        }

        String target = resolvedTarget.toLowerCase();
        if (cs.getCombatStep() == 0) {
            log(clientId, "IN_COMBAT: dispatching combat for shipTarget='" + target + "'.");
        }
        // One-shot before any combat handler: single left-click in space (screen centre) to move
        // focus off a potential chat/other window, so combat key presses land in the game.
        if (!cs.isCombatFocusClicked()) {
            log(clientId, "IN_COMBAT: focus-away click in space (centre) before combat.");
            missionProcessor.clickEmptySpace();
            cs.setCombatFocusClicked(true);
        }
        switch (target) {
            case "jaguar":
                handleCombatJaguar(clientId, cs);
                break;
            case "vengeance":
                handleCombatVengeance(clientId, cs);
                break;
            case "enyo":
            case "enya":
                handleCombatEnyo(clientId, cs);
                break;
            case "hawk":
                handleCombatHawk(clientId, cs);
                break;
            case "sansha":
                handleCombatSansha(clientId, cs);
                break;
            case "infested":
                handleCombatInfested(clientId, cs);
                break;
            case "recon1":
                handleCombatRecon(clientId, cs);
                break;
            case "scarlet":
                handleCombatScarlet(clientId, cs);
                break;
            case "angel":
                handleCombatAngel(clientId, cs);
                break;
            case "worm":
            case "guristas":
            case "guristasbumer":
                handleCombatWorm(clientId, cs);
                break;
            default:
                // Unresolved target — we have no combat sequence to run for it. Poll for completion
                // in case the mission ends on its own, but never spin here forever: a recognised
                // target always routes to its handler above, so landing here means the target is
                // genuinely unknown. After a bounded wait with no dock/set_destination, alert + park
                // so a mis-resolved target surfaces instead of looping silently ('unknown' forever).
                String fallback = missionProcessor.detectCombatComplete();
                if ("dock".equals(fallback)) {
                    log(clientId, "IN_COMBAT: dock detected — same system, docking.");
                    missionProcessor.clickTemplate("dock");
                    cs.setDockingToStation(true);
                    cs.setTravelPhase(ClientState.TravelPhase.NONE);
                    cs.setWarpStartTime(LocalDateTime.now());
                    stateManager.setState(clientId, ClientState.State.WARPING);
                } else if (fallback != null) {
                    log(clientId, "IN_COMBAT: set_destination detected — mission complete.");
                    stateManager.setState(clientId, ClientState.State.RETURNING);
                } else if (Duration.between(cs.getLastStateChange(), LocalDateTime.now()).getSeconds() >= 120) {
                    log(clientId, "*** ALERT: IN_COMBAT stuck 120s with unresolved target '" + target + "' — parking client.");
                    soundAlert(NO_SHIP_ALERT);
                    parkClient(clientId, "IN_COMBAT unresolved target '" + target + "'");
                }
                break;
        }
    }

    // -----------------------------------------------------------------------
    // Shared final-step logic for all combat sequences.
    // Polls set_destination; also runs the eject-menu pod check (ship destroyed).
    // -----------------------------------------------------------------------

    /**
     * Shared final-step completion poll. Waits for the set_destination / dock
     * prompt to appear (mission complete). After the backstop window with no
     * signal, an eject-menu pod check runs (only here, since it right-clicks our
     * ship) before assuming completion — being podded ends combat quietly and
     * would otherwise look like a normal completion.
     */
    private void pollSetDestinationOrCapsule(String clientId, ClientState cs,
                                              String combatLabel, boolean allowClientSwitch) {
        String completed = missionProcessor.detectCombatComplete();
        // If nothing detected, nudge the camera (throttled) to force a fresh frame and
        // re-scan once — a stale/idle-dimmed frame can hide the set_destination/dock prompt.
        if (completed == null
                && Duration.between(cs.getLastViewNudge(), LocalDateTime.now()).getSeconds() >= VIEW_NUDGE_THROTTLE_SECS) {
            cs.setLastViewNudge(LocalDateTime.now());
            log(clientId, "COMBAT[" + combatLabel + "] no completion this scan — nudging view + re-scanning.");
            missionProcessor.nudgeView();
            completed = missionProcessor.detectCombatComplete();
        }
        if (completed != null) {
            if ("dock".equals(completed)) {
                log(clientId, "COMBAT[" + combatLabel + "] dock detected — same system, docking.");
                missionProcessor.clickTemplate("dock");
                cs.setDockingToStation(true);
                cs.setTravelPhase(ClientState.TravelPhase.NONE);
                cs.setWarpStartTime(LocalDateTime.now());
                stateManager.setState(clientId, ClientState.State.WARPING);
            } else {
                log(clientId, "COMBAT[" + combatLabel + "] set_destination detected — mission complete!");
                stateManager.setState(clientId, ClientState.State.RETURNING);
            }
            return;
        }

        long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();

        // Team combats (not Jaguar): if the completion wait drags on past 120s with the main
        // still locked but the launcher not firing ('shooting_luncher' absent), the main isn't
        // taking damage — re-run the kill sequence (select the locked main, overload:
        // shift+a, a, d) and reset the completion timer so the backstop doesn't give up early.
        String lockedMain = "Vengeance".equals(combatLabel) ? "locked_vengeance"
                          : "Enyo".equals(combatLabel)      ? "locked_enyo"
                          : "Hawk".equals(combatLabel)      ? "locked_hawk"
                          : null;
        if (lockedMain != null && elapsed >= TEAM_REFIRE_AFTER_SECS
                && Duration.between(cs.getCombatPollStart(), LocalDateTime.now()).getSeconds() >= TEAM_REFIRE_THROTTLE_SECS) {
            cs.setCombatPollStart(LocalDateTime.now());
            // Only re-fire if the main is still locked AND the launcher is confirmed idle across
            // several scans — 'a'/'d' are toggles, so acting on a single missed 'shooting_luncher'
            // frame would switch a firing launcher OFF.
            if (missionProcessor.findTemplate(lockedMain) != null
                    && missionProcessor.confirmTemplateGone("shooting_luncher", 3, 300)) {
                log(clientId, "COMBAT[" + combatLabel + "] launcher not shooting after " + elapsed + "s, main still locked — re-firing (click + overload: shift+a, 3s, a, d).");
                missionProcessor.clickTemplate(lockedMain);
                missionProcessor.shiftKeyPress("a");
                try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                missionProcessor.keyPress("a");
                missionProcessor.keyPress("d");
                cs.setCombatWaitStart(LocalDateTime.now()); // extend the backstop after a re-fire
                return;
            }
        }

        // Backstop with no completion signal. Jaguar fights run longer, so they get
        // 300s; every other combat uses 120s. The eject-menu pod check is invasive
        // (right-clicks our ship), so it only runs here — not every tick. A long
        // silence with no set_destination can mean we were podded; check for a pod
        // before assuming the mission completed.
        int backstopSecs = "Jaguar".equals(combatLabel) ? 300 : 120;
        if (elapsed >= backstopSecs) {
            if (missionProcessor.isFlyingPod()) {
                handlePodDeath(clientId, true);
                return;
            }
            log(clientId, "COMBAT[" + combatLabel + "] no completion signal after " + backstopSecs + "s — assuming mission complete.");
            stateManager.setState(clientId, ClientState.State.RETURNING);
            return;
        }

        log(clientId, "COMBAT[" + combatLabel + "] waiting for mission complete (" + elapsed + "s)...");
        if (allowClientSwitch) {
            ClientWindowInfo other = findOtherClient(clientId);
            if (other != null) {
                log(clientId, "COMBAT[" + combatLabel + "] safe wait — switching to: " + other.getClientId());
                bringToForeground(other);
            }
        }
    }

    /**
     * View-nudge fallback for the inline combat completion loops (Sansha, Angel, Worm,
     * Infested) that don't go through {@link #pollSetDestinationOrCapsule}. Once the
     * completion wait has run past {@code afterSecs}, drag the camera every tick to force
     * a fresh frame — a stale/idle-dimmed view can hide the set_destination/dock prompt.
     * Returns the (possibly newly-revealed) completion string, or null if still nothing.
     */
    private String nudgeForCompletion(String clientId, ClientState cs, String combatLabel,
                                      long waitElapsed, long afterSecs) {
        if (waitElapsed < afterSecs) {
            return null;
        }
        cs.setLastViewNudge(LocalDateTime.now());
        log(clientId, "COMBAT[" + combatLabel + "] no completion after " + waitElapsed + "s — nudging view + re-scanning.");
        missionProcessor.nudgeView();
        return missionProcessor.detectCombatComplete();
    }

    /**
     * Anomic Team — Jaguar combat sequence.
     *
     * Steps:
     *   0 → Right-click 'launcher' (open ammo context menu)
     *   1 → Click 'kin_damage' (load kinetic ammo)
     *   2 → Click 'target_jaguar' + click 'orbit' (orbit target)
     *   3 → Proceed (range template check removed)
     *   4 → Keypress 's' (activate module)
     *   5 → SAFE WAIT 90s (can switch to other clients)
     *   6 → Ctrl+click 'target_jaguar' (lock target)
     *   7 → WAIT 3s → check 'locked_jaguar' — if not found, retry (loop back to 6)
     *   8 → Keypress 'adc' (activate modules), click 'locked_jaguar' (select)
     *   9 → SAFE WAIT for set_destination (mission complete → RETURNING)
     */
    private void handleCombatJaguar(String clientId, ClientState cs) {
        int step = cs.getCombatStep();

        switch (step) {
            case 0: {
                log(clientId, "COMBAT[Jaguar] Step 0: switch ammo → kin_damage (verified).");
                switchAmmo(clientId, "kin_damage");
                cs.setCombatStep(1);
                break;
            }
            case 1: {
                // Ammo loaded+verified in step 0 — this tick just lets the selection settle.
                log(clientId, "COMBAT[Jaguar] Step 1: kinetic loaded — settling.");
                cs.setCombatStep(2);
                break;
            }
            case 2: {
                String tk = awaitCombatTarget(clientId, cs, "target_jaguar");
                if (tk == null) return; // target not visible — nudged, hold step and retry next tick
                log(clientId, "COMBAT[Jaguar] Step 2: right-click " + tk + " → click range (range).");
                missionProcessor.rightClickTemplate(tk);
                missionProcessor.clickTemplate("range");
                cs.setCombatStep(3);
                break;
            }
            case 3: {
                // Range settle (keep_at_range template check removed)
                cs.setCombatStep(4);
                break;
            }
            case 4: {
                log(clientId, "COMBAT[Jaguar] Step 4: press 's' (activate modules).");
                missionProcessor.keyPress("s");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(5);
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "COMBAT[Jaguar] 90s safe wait — switching to: " + other.getClientId());
                    bringToForeground(other);
                }
                break;
            }
            case 5: {
                // SAFE WAIT: 100 seconds
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 100) {
                    log(clientId, "COMBAT[Jaguar] safe wait: " + elapsed + "s / 100s.");
                    if (elapsed > 0 && elapsed % 10 == 0) {
                        ClientWindowInfo other = findOtherClient(clientId);
                        if (other != null) bringToForeground(other);
                    }
                    return;
                }
                log(clientId, "COMBAT[Jaguar] 100s wait complete — proceeding to lock target.");
                cs.setCombatStep(6);
                break;
            }
            case 6: {
                log(clientId, "COMBAT[Jaguar] Step 6: Ctrl+click target_jaguar (lock target).");
                missionProcessor.ctrlClickTemplate("target_jaguar");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(7);
                break;
            }
            case 7: {
                // WAIT 3s for lock animation, then check locked_jaguar
                long elapsed7 = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed7 < 3) return;
                String lockedJ = missionProcessor.findTemplate("locked_jaguar");
                if (lockedJ != null) {
                    log(clientId, "COMBAT[Jaguar] Step 7: locked_jaguar confirmed — proceeding.");
                    cs.setCombatStep(8);
                } else {
                    log(clientId, "COMBAT[Jaguar] Step 7: locked_jaguar NOT found — retrying lock.");
                    cs.setCombatStep(6);
                }
                break;
            }
            case 8: {
                log(clientId, "COMBAT[Jaguar] Step 8: press 'adc' (activate modules), click locked_jaguar (select).");
                missionProcessor.keyPress("adc");
                missionProcessor.clickTemplate("locked_jaguar");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(9);
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "COMBAT[Jaguar] waiting for mission complete — switching to: " + other.getClientId());
                    bringToForeground(other);
                }
                break;
            }
            case 9: {
                pollSetDestinationOrCapsule(clientId, cs, "Jaguar", true);
                break;
            }
            default:
                log(clientId, "COMBAT[Jaguar] unexpected step " + step + " — resetting.");
                cs.setCombatStep(0);
        }
    }

    /**
     * Anomic Team — Vengeance combat sequence.
     *
     * Steps:
     *   0  → Right-click target_vengeance → orbit
     *   1  → Proceed (orbiting template check removed)
     *   2  → Keypress 's' (activate module)
     *   3  → SAFE WAIT 5s
     *   4  → Ctrl+click ALL 'target_inquisitor' (expect 2, retry up to 20s) + Ctrl+click 'target_vengeance' — everything locked up front
     *   5  → WAIT 3s (lock animation) → confirm 'locked_inquisitor' — if not found (out of range), relock (loop back to 4); if found, select
     *   6  → Press 'adc' (activate modules), click 'target_inquisitor' (verify)
     *   7  → SAFE WAIT 90s (one-shot 30s drift re-check), then poll every 5s until only ONE 'locked_inquisitor' bracket is left (first kill, 150s failsafe)
     *   8  → Second inquisitor already locked from step 4 — only re-lock if it drifted
     *   9  → WAIT 3s → click 'locked_inquisitor' (select) + press 'ad'
     *   10 → SAFE WAIT 60s (drift re-lock + re-fire), then poll until ZERO 'locked_inquisitor' brackets and no 'target_inquisitor' left to lock (no timeout — both must be confirmed dead)
     *   11 → Right-click 'launcher' (ammo switch)
     *   12 → Click 'inf_damage' (load infernal/thermal ammo)
     *   13 → SAFE WAIT 10s
     *   14 → Ctrl+click 'target_vengeance' (lock)
     *   15 → check 'locked_vengeance' — if not found, relock (3s between attempts); if found, click + press 'a', 'd' (no overload)
     *   16 → SAFE WAIT for set_destination (mission complete → RETURNING)
     */
    private void handleCombatVengeance(String clientId, ClientState cs) {
        int step = cs.getCombatStep();

        switch (step) {
            case 0: {
                String tk = awaitCombatTarget(clientId, cs, "target_vengeance");
                if (tk == null) return; // target not visible — nudged, hold step and retry next tick
                log(clientId, "COMBAT[Vengeance] Step 0: right-click " + tk + " → click orbit.");
                missionProcessor.rightClickTemplate(tk);
                missionProcessor.clickTemplate("orbit");
                cs.setCombatStep(1);
                break;
            }
            case 1: {
                // Orbit settle (orbiting template check removed)
                cs.setCombatStep(2);
                break;
            }
            case 2: {
                log(clientId, "COMBAT[Vengeance] Step 2: press 's' (activate modules).");
                missionProcessor.keyPress("s");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(3);
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "COMBAT[Vengeance] 5s safe wait — switching to: " + other.getClientId());
                    bringToForeground(other);
                }
                break;
            }
            case 3: {
                // SAFE WAIT: 5 seconds
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 5) {
                    log(clientId, "COMBAT[Vengeance] safe wait: " + elapsed + "s / 5s.");
                    return;
                }
                log(clientId, "COMBAT[Vengeance] 5s wait complete — locking inquisitors.");
                cs.setCombatWaitStart(LocalDateTime.now()); // anchors step 4's lock-all retry window
                cs.setCombatStep(4);
                break;
            }
            case 4: {
                // Lock EVERYTHING: both inquisitors + the vengeance. We expect exactly two
                // inquisitor brackets — if fewer are seen the overview hasn't fully populated,
                // so retry the lock-all until both are confirmed (bounded by a 20s window).
                log(clientId, "COMBAT[Vengeance] Step 4: Ctrl+click ALL target_inquisitor + target_vengeance (lock all).");
                int locked = missionProcessor.ctrlClickAllTemplate("target_inquisitor");
                missionProcessor.ctrlClickTemplateOnce("target_vengeance");
                if (locked < 2) {
                    if (Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds() < 20) {
                        log(clientId, "COMBAT[Vengeance] Step 4: only " + locked + " inquisitor(s) seen — expecting 2, retrying lock-all.");
                        return;
                    }
                    log(clientId, "COMBAT[Vengeance] Step 4: still only " + locked + " inquisitor(s) after 20s — proceeding anyway.");
                } else {
                    log(clientId, "COMBAT[Vengeance] Step 4: both inquisitors confirmed locked.");
                }
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(5);
                break;
            }
            case 5: {
                // WAIT 3s for lock animations, then check locked_inquisitor
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 3) return;
                String lockedI5 = missionProcessor.findTemplate("locked_inquisitor");
                if (lockedI5 != null) {
                    log(clientId, "COMBAT[Vengeance] Step 5: locked_inquisitor confirmed — selecting.");
                    missionProcessor.clickTemplate("locked_inquisitor");
                    cs.setCombatStep(6);
                } else {
                    log(clientId, "COMBAT[Vengeance] Step 5: locked_inquisitor NOT found — retrying lock.");
                    cs.setCombatStep(4);
                }
                break;
            }
            case 6: {
                log(clientId, "COMBAT[Vengeance] Step 6: press 'adc' (activate modules), click locked_inquisitor (verify).");
                missionProcessor.keyPress("adc");
                missionProcessor.clickTemplate("locked_inquisitor");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatPollStart(LocalDateTime.now());
                cs.setCombatStep(7);
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "COMBAT[Vengeance] 90s safe wait — switching to: " + other.getClientId());
                    bringToForeground(other);
                }
                break;
            }
            case 7: {
                // Safe wait 90s (modules firing on the locked inquisitor), then poll every 5s:
                // when locked_inquisitor is gone the target is dead → re-target. Never switch while
                // the bracket is still up — keep shooting until it is actually gone.
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                // One-shot ~30s drift re-check: the inquisitor can drift out of lock even after the
                // settle wait. Run it on the first combat tick at/after 30s REGARDLESS of the
                // safe-wait window — if this client was switched out through the whole window it must
                // still fire once when it regains focus. Gone this early = drift (not a kill): re-lock
                // + re-fire and restart the countdown.
                if (!cs.isFrigateLockRechecked() && elapsed >= 30) {
                    cs.setFrigateLockRechecked(true);
                    // 'adc' are toggle keys — re-firing when the modules are actually still on
                    // turns them OFF. So a single missed frame must NOT trigger a re-fire: confirm
                    // the lock is really gone across several scans before re-locking + re-firing.
                    if (missionProcessor.confirmTemplateGone("locked_inquisitor", 4, 300)) {
                        log(clientId, "COMBAT[Vengeance] 30s lock re-check: locked_inquisitor gone (drifted, confirmed) — re-locking, waiting for lock, then re-firing.");
                        missionProcessor.ctrlClickTemplateOnce("target_inquisitor");
                        // Wait for the lock to take, then SELECT the locked bracket BEFORE firing —
                        // firing straight after the ctrl-click applies the modules to nothing.
                        try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        if (missionProcessor.findTemplate("locked_inquisitor") != null) {
                            missionProcessor.clickTemplate("locked_inquisitor"); // select first
                            missionProcessor.keyPress("adc");                     // then fire
                        } else {
                            log(clientId, "COMBAT[Vengeance] 30s lock re-check: re-lock not confirmed (no locked_inquisitor) — not firing, re-polling.");
                        }
                        cs.setCombatWaitStart(LocalDateTime.now()); // fire (re)started — reset countdown
                        return;
                    } else {
                        log(clientId, "COMBAT[Vengeance] 30s lock re-check: locked_inquisitor still held (false-negative filtered) — keep firing.");
                    }
                }
                if (elapsed < 90) {
                    log(clientId, "COMBAT[Vengeance] safe wait: " + elapsed + "s / 90s.");
                    if (elapsed > 0 && elapsed % 10 == 0) {
                        ClientWindowInfo other = findOtherClient(clientId);
                        if (other != null) bringToForeground(other);
                    }
                    return;
                }
                long sincePoll = Duration.between(cs.getCombatPollStart(), LocalDateTime.now()).getSeconds();
                if (sincePoll < 5) return;
                cs.setCombatPollStart(LocalDateTime.now());
                // The first kill is confirmed by the LOCKED bracket count dropping from two to one.
                // ('target_*' is only for acquiring locks; 'locked_*' is what we confirm against.)
                int inqs7 = missionProcessor.countTemplate("locked_inquisitor");
                if (inqs7 <= 1) {
                    // Double-check across multiple scans — a single missed frame would send us to
                    // the second inquisitor while the first is still alive.
                    if (missionProcessor.confirmTemplateCountAtMost("locked_inquisitor", 1, 4, 300)) {
                        log(clientId, "COMBAT[Vengeance] Step 7: one locked_inquisitor left (confirmed) — first dead, switching to the second.");
                        cs.setCombatStep(8);
                    } else {
                        log(clientId, "COMBAT[Vengeance] Step 7: locked_inquisitor count dipped for one frame (false-negative filtered) — keep shooting.");
                    }
                } else if (elapsed >= 150) {
                    // Failsafe: if the count never drops (bad match, wreck still rendering) don't stall forever.
                    log(clientId, "COMBAT[Vengeance] Step 7: " + inqs7 + " locked inquisitors still up after " + elapsed + "s — 150s failsafe, assuming dead — switching to the second.");
                    cs.setCombatStep(8);
                } else {
                    log(clientId, "COMBAT[Vengeance] Step 7: " + inqs7 + " locked inquisitors still up (" + elapsed + "s) — keep shooting.");
                }
                break;
            }
            case 8: {
                // Both inquisitors were locked in step 4, so the survivor's bracket should still
                // be up — only re-lock if it drifted off.
                if (missionProcessor.findTemplate("locked_inquisitor") != null) {
                    log(clientId, "COMBAT[Vengeance] Step 8: second inquisitor still locked (step 4) — no re-lock needed.");
                } else {
                    log(clientId, "COMBAT[Vengeance] Step 8: second inquisitor not locked (drifted) — Ctrl+click target_inquisitor.");
                    missionProcessor.ctrlClickTemplateOnce("target_inquisitor");
                }
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(9);
                break;
            }
            case 9: {
                // 3s wait for lock, then check locked_inquisitor
                long elapsed9 = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed9 < 3) return;
                String lockedI9 = missionProcessor.findTemplate("locked_inquisitor");
                if (lockedI9 == null) {
                    log(clientId, "COMBAT[Vengeance] Step 9: locked_inquisitor NOT found — retrying lock.");
                    cs.setCombatStep(8);
                    break;
                }
                log(clientId, "COMBAT[Vengeance] Step 9: locked_inquisitor confirmed — click + press 'ad'.");
                missionProcessor.clickTemplate("locked_inquisitor");
                missionProcessor.keyPress("ad");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(10);
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "COMBAT[Vengeance] 60s safe wait — switching to: " + other.getClientId());
                    bringToForeground(other);
                }
                break;
            }
            case 10: {
                // SAFE WAIT: 60 seconds
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 60) {
                    log(clientId, "COMBAT[Vengeance] safe wait: " + elapsed + "s / 60s.");
                    // Second inquisitor still locked but launcher confirmed idle → re-fire (select + 'ad').
                    if (Duration.between(cs.getCombatPollStart(), LocalDateTime.now()).getSeconds() >= TEAM_REFIRE_THROTTLE_SECS) {
                        cs.setCombatPollStart(LocalDateTime.now());
                        if (missionProcessor.findTemplate("locked_inquisitor") != null) {
                            if (missionProcessor.confirmTemplateGone("shooting_luncher", 3, 300)) {
                                log(clientId, "COMBAT[Vengeance] Step 10: launcher not shooting, second inquisitor still locked — re-firing (click + 'ad').");
                                missionProcessor.clickTemplate("locked_inquisitor");
                                missionProcessor.keyPress("ad");
                                return;
                            }
                        } else if (missionProcessor.confirmTemplateGone("locked_inquisitor", 4, 300)
                                && missionProcessor.findTemplate("target_inquisitor") != null) {
                            // Bracket gone but the inquisitor is still on the overview → it drifted
                            // out of lock rather than died. Re-lock, select, re-fire, restart the 60s.
                            log(clientId, "COMBAT[Vengeance] Step 10: second inquisitor drifted out of lock — re-locking and re-firing.");
                            missionProcessor.ctrlClickTemplateOnce("target_inquisitor");
                            try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                            if (missionProcessor.findTemplate("locked_inquisitor") != null) {
                                missionProcessor.clickTemplate("locked_inquisitor");
                                missionProcessor.keyPress("ad");
                                cs.setCombatWaitStart(LocalDateTime.now());
                            }
                            return;
                        }
                    }
                    if (elapsed > 0 && elapsed % 10 == 0) {
                        ClientWindowInfo other = findOtherClient(clientId);
                        if (other != null) bringToForeground(other);
                    }
                    return;
                }
                // 60s up — the second inquisitor should be dead. Confirm NO inquisitors are left on
                // screen before moving to the vengeance; if one is still up, re-lock + re-fire.
                long sincePoll10 = Duration.between(cs.getCombatPollStart(), LocalDateTime.now()).getSeconds();
                if (sincePoll10 < 5) return;
                cs.setCombatPollStart(LocalDateTime.now());
                int inqs10 = missionProcessor.countTemplate("locked_inquisitor");
                if (inqs10 == 0 && missionProcessor.confirmTemplateCountAtMost("locked_inquisitor", 0, 4, 300)
                        && missionProcessor.findTemplate("target_inquisitor") == null) {
                    log(clientId, "COMBAT[Vengeance] 60s wait complete — no locked inquisitors and none left to lock (confirmed) — switching ammo.");
                    cs.setCombatStep(11);
                    break;
                }
                log(clientId, "COMBAT[Vengeance] Step 10: " + inqs10 + " locked inquisitor(s) / target still on screen (" + elapsed + "s) — re-locking and keeping fire on.");
                if (missionProcessor.findTemplate("locked_inquisitor") == null) {
                    missionProcessor.ctrlClickTemplateOnce("target_inquisitor");
                    try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                if (missionProcessor.findTemplate("locked_inquisitor") != null
                        && missionProcessor.confirmTemplateGone("shooting_luncher", 3, 300)) {
                    missionProcessor.clickTemplate("locked_inquisitor");
                    missionProcessor.keyPress("ad");
                }
                // Fire is on and unattended — hand the foreground to the other client between polls.
                ClientWindowInfo other10 = findOtherClient(clientId);
                if (other10 != null) bringToForeground(other10);
                return;
            }
            case 11: {
                log(clientId, "COMBAT[Vengeance] Step 11: switch ammo → inf_damage (verified).");
                switchAmmo(clientId, "inf_damage");
                cs.setCombatStep(12);
                break;
            }
            case 12: {
                // Ammo loaded+verified in step 11 — arm the load safe-wait.
                log(clientId, "COMBAT[Vengeance] Step 12: infernal loaded — starting load wait.");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(13);
                break;
            }
            case 13: {
                // SAFE WAIT: 10 seconds (ammo load)
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 10) {
                    log(clientId, "COMBAT[Vengeance] safe wait: " + elapsed + "s / 10s.");
                    return;
                }
                log(clientId, "COMBAT[Vengeance] 10s wait complete — activating modules.");
                cs.setCombatStep(14);
                break;
            }
            case 14: {
                // Vengeance was locked back in step 4 and stays locked through the inquisitor
                // kills — no need to relock. Go straight to select + fire; step 15 only relocks
                // as a fallback if the bracket somehow drifted. Anchor the timer for that fallback.
                log(clientId, "COMBAT[Vengeance] Step 14: vengeance already locked (step 4) — selecting + firing.");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(15);
                break;
            }
            case 15: {
                String lockedV = missionProcessor.findTemplate("locked_vengeance");
                if (lockedV != null) {
                    log(clientId, "COMBAT[Vengeance] Step 15: locked_vengeance confirmed — click + press 'a', 'd' (no overload).");
                    missionProcessor.clickTemplate("locked_vengeance");
                    missionProcessor.keyPress("a");
                    missionProcessor.keyPress("d");
                    cs.setCombatStep(16);
                    ClientWindowInfo other = findOtherClient(clientId);
                    if (other != null) {
                        log(clientId, "COMBAT[Vengeance] shooting vengeance — switching to: " + other.getClientId());
                        bringToForeground(other);
                    }
                } else {
                    // Fallback: the step-4 lock drifted off. Relock, then wait 3s between attempts.
                    long elapsed15 = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                    if (elapsed15 >= 3) {
                        log(clientId, "COMBAT[Vengeance] Step 15: locked_vengeance NOT found (drifted) — relocking.");
                        missionProcessor.ctrlClickTemplateOnce("target_vengeance");
                        cs.setCombatWaitStart(LocalDateTime.now());
                    }
                }
                break;
            }
            case 16: {
                pollSetDestinationOrCapsule(clientId, cs, "Vengeance", true);
                break;
            }
            default:
                log(clientId, "COMBAT[Vengeance] unexpected step " + step + " — resetting.");
                cs.setCombatStep(0);
        }
    }

    /**
     * Anomic Team — Enyo combat sequence.
     *
     * Steps:
     *   0  → Right-click 'launcher' (ammo context menu)
     *   1  → Click 'exp_damage' (load explosive ammo)
     *   2  → Click 'target_enyo' + click 'orbit' (orbit)
     *   3  → Proceed (orbiting template check removed)
     *   4  → Keypress 's' (activate module)
     *   5  → SAFE WAIT 5s
     *   6  → Ctrl+click ONE 'target_navitas' (support) + 'target_enyo' (main) — only ever one support locked
     *   7  → WAIT 3s → confirm 'locked_navitas' — if not found, relock ONE support (stay); if found, select
     *   8  → Press 'adc', click 'locked_navitas' (verify), start firing wait
     *   9  → SAFE WAIT 90s (one-shot 20s drift re-check), then poll every 5s: 'locked_navitas' still up → keep shooting; gone → support dead → step 10 (engage enyo)
     *   10 → 'locked_enyo' present → click + overload (shift+a, a, d); absent → relock the enyo
     *   11 → SAFE WAIT for set_destination (mission complete → RETURNING)
     */
    private void handleCombatEnyo(String clientId, ClientState cs) {
        int step = cs.getCombatStep();

        switch (step) {
            case 0: {
                log(clientId, "COMBAT[Enyo] Step 0: switch ammo → exp_damage (verified).");
                switchAmmo(clientId, "exp_damage");
                cs.setCombatStep(1);
                break;
            }
            case 1: {
                // Ammo loaded+verified in step 0 — this tick just lets the selection settle.
                log(clientId, "COMBAT[Enyo] Step 1: explosive loaded — settling.");
                cs.setCombatStep(2);
                break;
            }
            case 2: {
                String tk = awaitCombatTarget(clientId, cs, "target_enyo");
                if (tk == null) return; // target not visible — nudged, hold step and retry next tick
                log(clientId, "COMBAT[Enyo] Step 2: right-click " + tk + " → click orbit.");
                missionProcessor.rightClickTemplate(tk);
                missionProcessor.clickTemplate("orbit");
                cs.setCombatStep(3);
                break;
            }
            case 3: {
                // Orbit settle (orbiting template check removed)
                cs.setCombatStep(4);
                break;
            }
            case 4: {
                log(clientId, "COMBAT[Enyo] Step 4: press 's' (activate modules).");
                missionProcessor.keyPress("s");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(5);
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "COMBAT[Enyo] 5s safe wait — switching to: " + other.getClientId());
                    bringToForeground(other);
                }
                break;
            }
            case 5: {
                // SAFE WAIT: 5 seconds
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 5) {
                    log(clientId, "COMBAT[Enyo] safe wait: " + elapsed + "s / 5s.");
                    return;
                }
                log(clientId, "COMBAT[Enyo] 5s wait complete — locking navitas targets.");
                cs.setCombatStep(6);
                break;
            }
            case 6: {
                // Lock exactly ONE support (navitas) + the main ship (enyo). Locking a single
                // support at a time means there is never a second support bracket to stall on;
                // the main stays locked throughout — we only ever re-lock supports.
                log(clientId, "COMBAT[Enyo] Step 6: Ctrl+click ONE target_navitas (support) + target_enyo (main).");
                missionProcessor.ctrlClickTemplateOnce("target_navitas");
                missionProcessor.ctrlClickTemplateOnce("target_enyo");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(7);
                break;
            }
            case 7: {
                // WAIT 3s for lock animation, then confirm locked_navitas. If absent the support
                // is out of range / not yet locked — relock ONE (don't touch the main) and retry.
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 3) return;
                String lockedN = missionProcessor.findTemplate("locked_navitas");
                if (lockedN != null) {
                    log(clientId, "COMBAT[Enyo] Step 7: locked_navitas confirmed — selecting.");
                    missionProcessor.clickTemplate("locked_navitas");
                    cs.setCombatStep(8);
                } else {
                    log(clientId, "COMBAT[Enyo] Step 7: locked_navitas NOT found (out of range) — relocking one support.");
                    missionProcessor.ctrlClickTemplateOnce("target_navitas");
                    cs.setCombatWaitStart(LocalDateTime.now());
                }
                break;
            }
            case 8: {
                log(clientId, "COMBAT[Enyo] Step 8: press 'adc' (activate modules), click locked_navitas (verify).");
                missionProcessor.keyPress("adc");
                missionProcessor.clickTemplate("locked_navitas");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatPollStart(LocalDateTime.now());
                cs.setFrigateLockRechecked(false);
                cs.setCombatStep(9);
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "COMBAT[Enyo] 90s safe wait — switching to: " + other.getClientId());
                    bringToForeground(other);
                }
                break;
            }
            case 9: {
                // Safe wait 90s (modules firing on the single locked support), then poll every 5s:
                // bracket still up → keep shooting; gone → support dead → engage the main (step 10).
                // Never switch while the bracket is still up.
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                // One-shot ~30s drift re-check: gone this early = drift (not a kill) — re-lock the
                // support + re-fire and restart the countdown.
                if (!cs.isFrigateLockRechecked() && elapsed >= 30) {
                    cs.setFrigateLockRechecked(true);
                    // 'adc' are toggle keys — re-firing when the modules are actually still on
                    // turns them OFF. So a single missed frame must NOT trigger a re-fire: confirm
                    // the lock is really gone across several scans before re-locking + re-firing.
                    if (missionProcessor.confirmTemplateGone("locked_navitas", 4, 300)) {
                        log(clientId, "COMBAT[Enyo] 30s lock re-check: locked_navitas gone (drifted, confirmed) — re-locking, waiting for lock, then re-firing.");
                        missionProcessor.ctrlClickTemplateOnce("target_navitas");
                        // Wait for the lock to take, then SELECT the locked bracket BEFORE firing —
                        // firing straight after the ctrl-click applies the modules to nothing.
                        try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        if (missionProcessor.findTemplate("locked_navitas") != null) {
                            missionProcessor.clickTemplate("locked_navitas"); // select first
                            missionProcessor.keyPress("adc");                 // then fire
                        } else {
                            log(clientId, "COMBAT[Enyo] 30s lock re-check: re-lock not confirmed (no locked_navitas) — not firing, re-polling.");
                        }
                        cs.setCombatWaitStart(LocalDateTime.now()); // fire (re)started — reset countdown
                        return;
                    } else {
                        log(clientId, "COMBAT[Enyo] 30s lock re-check: locked_navitas still held (false-negative filtered) — keep firing.");
                    }
                }
                if (elapsed < 90) {
                    log(clientId, "COMBAT[Enyo] safe wait: " + elapsed + "s / 90s.");
                    if (elapsed > 0 && elapsed % 10 == 0) {
                        ClientWindowInfo other = findOtherClient(clientId);
                        if (other != null) bringToForeground(other);
                    }
                    return;
                }
                long sincePoll = Duration.between(cs.getCombatPollStart(), LocalDateTime.now()).getSeconds();
                if (sincePoll < 5) return;
                cs.setCombatPollStart(LocalDateTime.now());
                if (missionProcessor.findTemplate("locked_navitas") != null) {
                    if (elapsed >= 150) {
                        // Failsafe: a stale bracket can keep "still alive" up forever — after
                        // 150s assume dead and move on to the main.
                        log(clientId, "COMBAT[Enyo] Step 9: still alive after " + elapsed + "s — 150s failsafe, assuming dead — engaging the enyo.");
                        cs.setCombatWaitStart(LocalDateTime.now());
                        cs.setCombatStep(10);
                        break;
                    }
                    log(clientId, "COMBAT[Enyo] Step 9: support still alive (" + elapsed + "s) — keep shooting.");
                    break;
                }
                // Double-check across multiple scans before declaring the kill — a single
                // missed frame would prematurely burn the overload on the main.
                if (!missionProcessor.confirmTemplateGone("locked_navitas", 4, 300)) {
                    log(clientId, "COMBAT[Enyo] Step 9: locked_navitas missed frame (false-negative filtered) — keep shooting.");
                    break;
                }
                // Support dead — move to the main (already locked in step 6).
                log(clientId, "COMBAT[Enyo] Step 9: support dead (confirmed) — engaging the enyo.");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(10);
                break;
            }
            case 10: {
                // Kill the main (locked back in step 6). Select + overload; only relock if its
                // bracket is somehow gone (drifted), waiting 3s between relock attempts.
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                String lockedE = missionProcessor.findTemplate("locked_enyo");
                if (lockedE != null) {
                    log(clientId, "COMBAT[Enyo] Step 10: locked_enyo confirmed — click + overload (shift+a, 3s, a, d).");
                    missionProcessor.clickTemplate("locked_enyo");
                    missionProcessor.shiftKeyPress("a");
                    try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    missionProcessor.keyPress("a");
                    missionProcessor.keyPress("d"); // activate 'd' after overloading 'a'
                    cs.setCombatStep(11);
                    ClientWindowInfo other = findOtherClient(clientId);
                    if (other != null) {
                        log(clientId, "COMBAT[Enyo] shooting enyo — switching to: " + other.getClientId());
                        bringToForeground(other);
                    }
                } else if (elapsed >= 3) {
                    log(clientId, "COMBAT[Enyo] Step 10: locked_enyo NOT found — relocking the enyo.");
                    missionProcessor.ctrlClickTemplateOnce("target_enyo");
                    cs.setCombatWaitStart(LocalDateTime.now());
                }
                break;
            }
            case 11: {
                pollSetDestinationOrCapsule(clientId, cs, "Enyo", true);
                break;
            }
            default:
                log(clientId, "COMBAT[Enyo] unexpected step " + step + " — resetting.");
                cs.setCombatStep(0);
        }
    }

    /**
     * Anomic Base — Hawk combat sequence.
     *
     * Steps:
     *   0  → Right-click 'launcher' (ammo context menu)
     *   1  → Click 'em_damage' (load EM ammo)
     *   2  → Click 'target_hawk' + click 'orbit' (orbit)
     *   3  → Proceed (orbiting template check removed)
     *   4  → SAFE WAIT 5s
     *   5  → Ctrl+click ONE 'target_bantam' (support) + 'target_hawk' (main) — only ever one support locked
     *   6  → WAIT 3s → confirm 'locked_bantam' — if not found, relock ONE support (stay); if found, select
     *   7  → Press 'adc', click 'locked_bantam' (verify), start firing wait
     *   8  → SAFE WAIT 60s (one-shot 20s drift re-check), then poll every 5s: 'locked_bantam' still up → keep shooting; gone → support dead → step 9 (engage hawk)
     *   9  → 'locked_hawk' present → click + overload (shift+a, a, d); absent → relock the hawk
     *   10 → SAFE WAIT for set_destination (mission complete → RETURNING)
     */
    private void handleCombatHawk(String clientId, ClientState cs) {
        int step = cs.getCombatStep();

        switch (step) {
            case 0: {
                log(clientId, "COMBAT[Hawk] Step 0: switch ammo → em_damage (verified).");
                switchAmmo(clientId, "em_damage");
                cs.setCombatStep(1);
                break;
            }
            case 1: {
                // Ammo loaded+verified in step 0 — this tick just lets the selection settle.
                log(clientId, "COMBAT[Hawk] Step 1: EM loaded — settling.");
                cs.setCombatStep(2);
                break;
            }
            case 2: {
                String tk = awaitCombatTarget(clientId, cs, "target_hawk");
                if (tk == null) return; // target not visible — nudged, hold step and retry next tick
                log(clientId, "COMBAT[Hawk] Step 2: right-click " + tk + " → click orbit.");
                missionProcessor.rightClickTemplate(tk);
                missionProcessor.clickTemplate("orbit");
                cs.setCombatStep(3);
                break;
            }
            case 3: {
                // Orbit settle (orbiting template check removed) — press 's' and proceed
                log(clientId, "COMBAT[Hawk] Step 3: press 's' (activate modules).");
                missionProcessor.keyPress("s");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(4);
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "COMBAT[Hawk] 5s safe wait — switching to: " + other.getClientId());
                    bringToForeground(other);
                }
                break;
            }
            case 4: {
                // SAFE WAIT: 5 seconds
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 5) {
                    log(clientId, "COMBAT[Hawk] safe wait: " + elapsed + "s / 5s.");
                    return;
                }
                log(clientId, "COMBAT[Hawk] 5s wait complete — locking targets.");
                cs.setCombatStep(5);
                break;
            }
            case 5: {
                // Lock exactly ONE support (bantam) + the main ship (hawk). Only ever one support
                // bracket at a time, so a survivor can't stall the kill loop; the main stays locked.
                log(clientId, "COMBAT[Hawk] Step 5: Ctrl+click ONE target_bantam (support) + target_hawk (main).");
                missionProcessor.ctrlClickTemplateOnce("target_bantam");
                missionProcessor.ctrlClickTemplateOnce("target_hawk");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(6);
                break;
            }
            case 6: {
                // WAIT 3s for lock animation, then confirm locked_bantam. If absent the support is
                // out of range / not yet locked — relock ONE (don't touch the main) and retry.
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 3) return;
                String lockedB = missionProcessor.findTemplate("locked_bantam");
                if (lockedB != null) {
                    log(clientId, "COMBAT[Hawk] Step 6: locked_bantam confirmed — selecting.");
                    missionProcessor.clickTemplate("locked_bantam");
                    cs.setCombatStep(7);
                } else {
                    log(clientId, "COMBAT[Hawk] Step 6: locked_bantam NOT found (out of range) — relocking one support.");
                    missionProcessor.ctrlClickTemplateOnce("target_bantam");
                    cs.setCombatWaitStart(LocalDateTime.now());
                }
                break;
            }
            case 7: {
                log(clientId, "COMBAT[Hawk] Step 7: press 'adc' (activate modules), click locked_bantam (verify).");
                missionProcessor.keyPress("adc");
                missionProcessor.clickTemplate("locked_bantam");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatPollStart(LocalDateTime.now());
                cs.setFrigateLockRechecked(false);
                cs.setCombatStep(8);
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "COMBAT[Hawk] 60s safe wait — switching to: " + other.getClientId());
                    bringToForeground(other);
                }
                break;
            }
            case 8: {
                // Safe wait 60s (modules firing on the locked support), then poll every 5s:
                // bracket still up → keep shooting; gone → relock ONE more support (step 9
                // confirms whether any remain). Never switch while the bracket is still up.
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                // One-shot ~30s drift re-check: gone this early = drift (not a kill) — re-lock the
                // support + re-fire and restart the countdown.
                if (!cs.isFrigateLockRechecked() && elapsed >= 30) {
                    cs.setFrigateLockRechecked(true);
                    // 'adc' are toggle keys — re-firing when the modules are actually still on
                    // turns them OFF. So a single missed frame must NOT trigger a re-fire: confirm
                    // the lock is really gone across several scans before re-locking + re-firing.
                    if (missionProcessor.confirmTemplateGone("locked_bantam", 4, 300)) {
                        log(clientId, "COMBAT[Hawk] 30s lock re-check: locked_bantam gone (drifted, confirmed) — re-locking, waiting for lock, then re-firing.");
                        missionProcessor.ctrlClickTemplateOnce("target_bantam");
                        // Wait for the lock to take, then SELECT the locked bracket BEFORE firing.
                        // Pressing 'adc' straight after the ctrl-click fires the modules before the
                        // target is locked+selected (modules apply to nothing), like steps 6→7 do.
                        try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        if (missionProcessor.findTemplate("locked_bantam") != null) {
                            missionProcessor.clickTemplate("locked_bantam"); // select first
                            missionProcessor.keyPress("adc");                 // then fire
                        } else {
                            log(clientId, "COMBAT[Hawk] 30s lock re-check: re-lock not confirmed (no locked_bantam) — not firing, re-polling.");
                        }
                        cs.setCombatWaitStart(LocalDateTime.now()); // fire (re)started — reset countdown
                        return;
                    } else {
                        log(clientId, "COMBAT[Hawk] 30s lock re-check: locked_bantam still held (false-negative filtered) — keep firing.");
                    }
                }
                if (elapsed < 60) {
                    log(clientId, "COMBAT[Hawk] safe wait: " + elapsed + "s / 60s.");
                    if (elapsed > 0 && elapsed % 10 == 0) {
                        ClientWindowInfo other = findOtherClient(clientId);
                        if (other != null) bringToForeground(other);
                    }
                    return;
                }
                long sincePoll = Duration.between(cs.getCombatPollStart(), LocalDateTime.now()).getSeconds();
                if (sincePoll < 5) return;
                cs.setCombatPollStart(LocalDateTime.now());
                if (missionProcessor.findTemplate("locked_bantam") != null) {
                    if (elapsed >= 150) {
                        // Failsafe: a stale bracket can keep "still alive" up forever — after
                        // 150s assume dead and move on to the main.
                        log(clientId, "COMBAT[Hawk] Step 8: still alive after " + elapsed + "s — 150s failsafe, assuming dead — engaging the hawk.");
                        cs.setCombatWaitStart(LocalDateTime.now());
                        cs.setCombatStep(9);
                        break;
                    }
                    log(clientId, "COMBAT[Hawk] Step 8: support still alive (" + elapsed + "s) — keep shooting.");
                    break;
                }
                // Double-check across multiple scans before declaring the kill — a single
                // missed frame would prematurely burn the overload on the main.
                if (!missionProcessor.confirmTemplateGone("locked_bantam", 4, 300)) {
                    log(clientId, "COMBAT[Hawk] Step 8: locked_bantam missed frame (false-negative filtered) — keep shooting.");
                    break;
                }
                // Support dead — move to the main (already locked in step 5).
                log(clientId, "COMBAT[Hawk] Step 8: support dead (confirmed) — engaging the hawk.");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(9);
                break;
            }
            case 9: {
                // Kill the main (locked back in step 5). Select + overload; only relock if its
                // bracket is somehow gone (drifted), waiting 3s between relock attempts.
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                String lockedH = missionProcessor.findTemplate("locked_hawk");
                if (lockedH != null) {
                    log(clientId, "COMBAT[Hawk] Step 9: locked_hawk confirmed — click + overload (shift+a, 3s, a, d).");
                    missionProcessor.clickTemplate("locked_hawk");
                    missionProcessor.shiftKeyPress("a");
                    try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    missionProcessor.keyPress("a");
                    missionProcessor.keyPress("d"); // activate 'd' after overloading 'a'
                    cs.setCombatStep(10);
                    ClientWindowInfo other = findOtherClient(clientId);
                    if (other != null) {
                        log(clientId, "COMBAT[Hawk] waiting for mission complete — switching to: " + other.getClientId());
                        bringToForeground(other);
                    }
                } else if (elapsed >= 3) {
                    log(clientId, "COMBAT[Hawk] Step 9: locked_hawk NOT found — relocking the hawk.");
                    missionProcessor.ctrlClickTemplateOnce("target_hawk");
                    cs.setCombatWaitStart(LocalDateTime.now());
                }
                break;
            }
            case 10: {
                pollSetDestinationOrCapsule(clientId, cs, "Hawk", true);
                break;
            }
            default:
                log(clientId, "COMBAT[Hawk] unexpected step " + step + " — resetting.");
                cs.setCombatStep(0);
        }
    }

    /**
     * Anomic Base — Sansha combat sequence.
     *
     * Steps:
     *   0  → Shift+asdx (overload modules)
     *   1  → Ctrl+click 'target_sansha' (lock) + right-click → 'approach' + press 's'
     *   2  → press 'adxc' (activate modules)
     *   3  → WAIT 4s → right-click target_sansha → click range (keep at range)
     *   4  → Confirm keep_at_range (nudge to verify); if missing, repeat step 3
     *   5  → WAIT 15s
     *   6  → Shift+ds (stop overloading afterburner + web; reps 'c' never overloaded)
     *   7  → Alt+F1 (burst cap recharge)
     *   8  → WAIT 1s
     *   9  → Alt+F1 (deactivate cap module)
     *  10  → WAIT for set_destination/dock; if fight > 60s → Alt+F1 again (once); 180s hardcap (pod check) → step 11
     *  11  → press 'xc' (deactivate modules) + handle dock/returning (RETURNING clicks set_destination coords)
     */
    /** Nudge-retries spent hunting the primary combat target before 'alt_target' is accepted. */
    private static final int COMBAT_TARGET_NUDGE_LIMIT = 5;

    /**
     * Resolve — and require — the overview-row template to act on for a combat target.
     * The mission-specific template is strongly preferred: while it isn't on screen this
     * nudges the camera (forcing a fresh frame / rotating the view to reveal an off-screen
     * or occluded target) and returns null so the caller holds the step and retries. Only
     * after {@link #COMBAT_TARGET_NUDGE_LIMIT} failed primary attempts does it fall back to
     * the generic 'alt_target' row — so alt is a genuine last resort, not chosen on a single
     * transient primary miss. Combat never advances until a target row is actually found.
     */
    private String awaitCombatTarget(String clientId, ClientState cs, String primaryKey) {
        if (missionProcessor.findTemplate(primaryKey) != null) {
            cs.setCombatTargetNudges(0);
            return primaryKey;
        }
        int n = cs.getCombatTargetNudges() + 1;
        cs.setCombatTargetNudges(n);
        // Primary still missing but we've exhausted the retries — accept alt_target if present.
        if (n > COMBAT_TARGET_NUDGE_LIMIT && missionProcessor.findTemplate("alt_target") != null) {
            log(clientId, "COMBAT: primary '" + primaryKey + "' not found after " + COMBAT_TARGET_NUDGE_LIMIT
                    + " nudges — falling back to alt_target.");
            cs.setCombatTargetNudges(0);
            return "alt_target";
        }
        log(clientId, "COMBAT: target '" + primaryKey + "' not on screen — nudging view to reveal it ("
                + n + "/" + COMBAT_TARGET_NUDGE_LIMIT + ", holding step).");
        missionProcessor.nudgeView();
        return null;
    }

    /**
     * After clicking "keep at range", confirm the keep_at_range indicator is on screen.
     * If it isn't, nudge the camera once (forcing a fresh frame) and re-scan. Either way
     * combat proceeds — a still-missing indicator is logged and ignored rather than
     * retried, since repeating the click never recovers it.
     */
    private void confirmKeepAtRange(String clientId, String combatLabel) {
        if (missionProcessor.findTemplate("keep_at_range") != null) {
            log(clientId, "COMBAT[" + combatLabel + "] keep_at_range confirmed — proceeding.");
            return;
        }
        log(clientId, "COMBAT[" + combatLabel + "] keep_at_range not found — nudging to confirm.");
        missionProcessor.nudgeView();
        if (missionProcessor.findTemplate("keep_at_range") != null) {
            log(clientId, "COMBAT[" + combatLabel + "] keep_at_range confirmed after nudge — proceeding.");
            return;
        }
        log(clientId, "COMBAT[" + combatLabel + "] keep_at_range still missing after nudge — proceeding anyway.");
    }

    private void handleCombatSansha(String clientId, ClientState cs) {
        int step = cs.getCombatStep();

        switch (step) {
            case 0: {
                log(clientId, "COMBAT[Sansha] Step 0: Shift+asdx (overload modules).");
                missionProcessor.shiftKeyPress("asdx");
                cs.setCombatStartTime(LocalDateTime.now()); // start of the fight, for the 35s extra-cap check
                cs.setExtraCapPulseDone(false);
                cs.setCombatStep(1);
                break;
            }
            case 1: {
                log(clientId, "COMBAT[Sansha] Step 1: press 's' (pre-lock).");
                missionProcessor.keyPress("s");
                cs.setCombatStep(2);
                break;
            }
            case 2: {
                String tk = awaitCombatTarget(clientId, cs, "target_sansha");
                if (tk == null) return; // target not visible — nudged, hold step and retry next tick
                cs.setCombatTargetKey(tk);
                log(clientId, "COMBAT[Sansha] Step 2: right-click " + tk + " → 'approach' + Ctrl+click (lock) + press 'adxc' (activate modules).");
                missionProcessor.rightClickAndSelect(tk, "approach");
                missionProcessor.ctrlClickTemplate(tk);
                missionProcessor.keyPress("adxc");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(3);
                break;
            }
            case 3: {
                // WAIT 4s before keeping at range (like Worm) so modules apply first.
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 4) {
                    log(clientId, "COMBAT[Sansha] wait before range: " + elapsed + "s / 4s.");
                    return;
                }
                String rangeKey = cs.getCombatTargetKey() != null ? cs.getCombatTargetKey() : "target_sansha";
                log(clientId, "COMBAT[Sansha] Step 3: right-click " + rangeKey + " → click range.");
                missionProcessor.rightClickTemplate(rangeKey);
                missionProcessor.clickTemplate("range");
                cs.setCombatStep(4);
                break;
            }
            case 4: {
                confirmKeepAtRange(clientId, "Sansha");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(5);
                break;
            }
            case 5: {
                // WAIT: 10 seconds
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 15) {
                    log(clientId, "COMBAT[Sansha] wait: " + elapsed + "s / 15s.");
                    return;
                }
                log(clientId, "COMBAT[Sansha] 15s wait complete — stopping overloads.");
                cs.setCombatStep(6);
                break;
            }
            case 6: {
                log(clientId, "COMBAT[Sansha] Step 6: Shift+ds (stop overloading afterburner + web; reps 'c' not overloaded).");
                missionProcessor.shiftKeyPress("ds");
                cs.setCombatStep(7);
                break;
            }
            case 7: {
                log(clientId, "COMBAT[Sansha] Step 7: Alt+F1 (burst cap recharge).");
                missionProcessor.altKeyPress("f1");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(8);
                break;
            }
            case 8: {
                // WAIT: 1 second
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 1) return;
                cs.setCombatStep(9);
                break;
            }
            case 9: {
                log(clientId, "COMBAT[Sansha] Step 9: Alt+F1 (deactivate cap module).");
                missionProcessor.altKeyPress("f1");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(10);
                // Modules are running and keep-at-range is set — the fight runs unattended, so
                // switch to the other client once here as we start waiting for mission end (same
                // as Angel). Focus returns via the force-switch watchdog / other client yielding,
                // and step 10 then polls completion (and the >60s extra cap pulse).
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "COMBAT[Sansha] cap set — waiting for mission end, switching to: " + other.getClientId());
                    bringToForeground(other);
                }
                break;
            }
            case 10: {
                // WAIT for set_destination/dock
                String completed = missionProcessor.detectCombatComplete();
                if (completed != null) {
                    log(clientId, "COMBAT[Sansha] " + completed + " detected — deactivating modules.");
                    cs.setCombatStep(11);
                    break;
                }

                // Long fight: if the whole combat has run past 60s and we're still not
                // done, give the cap module another Alt+F1 pulse (once).
                if (!cs.isExtraCapPulseDone()
                        && Duration.between(cs.getCombatStartTime(), LocalDateTime.now()).getSeconds() > 60) {
                    log(clientId, "COMBAT[Sansha] fight > 60s — Alt+F1 again (extra cap pulse).");
                    missionProcessor.altKeyPress("f1");
                    cs.setExtraCapPulseDone(true);
                }

                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();

                // After 60s with no prompt, nudge the camera (throttled) to reveal a hidden set_destination/dock.
                String revealed = nudgeForCompletion(clientId, cs, "Sansha", elapsed, 60);
                if (revealed != null) {
                    log(clientId, "COMBAT[Sansha] " + revealed + " revealed after nudge — deactivating modules.");
                    cs.setCombatStep(11);
                    break;
                }

                // 180s hardcap: no set_destination/dock seen — pod check, then force
                // deactivate + return (step 11 → RETURNING clicks the set_destination
                // coords fallback).
                if (elapsed >= 180) {
                    if (missionProcessor.isFlyingPod()) {
                        handlePodDeath(clientId, true);
                        return;
                    }
                    log(clientId, "COMBAT[Sansha] 180s hardcap — no completion, forcing deactivate + return.");
                    cs.setCombatStep(11);
                    break;
                }

                log(clientId, "COMBAT[Sansha] waiting for mission complete (" + elapsed + "s)...");
                break;
            }
            case 11: {
                log(clientId, "COMBAT[Sansha] Step 11: press 'xc' (deactivate modules).");
                missionProcessor.keyPress("xc");
                // Re-detect: dock = same system, set_destination = different system
                String result = missionProcessor.detectCombatComplete();
                if ("dock".equals(result)) {
                    log(clientId, "COMBAT[Sansha] dock detected — same system, docking.");
                    missionProcessor.clickTemplate("dock");
                    cs.setDockingToStation(true);
                    cs.setTravelPhase(ClientState.TravelPhase.NONE);
                    cs.setWarpStartTime(LocalDateTime.now());
                    stateManager.setState(clientId, ClientState.State.WARPING);
                } else {
                    stateManager.setState(clientId, ClientState.State.RETURNING);
                }
                break;
            }
            default:
                log(clientId, "COMBAT[Sansha] unexpected step " + step + " — resetting.");
                cs.setCombatStep(0);
        }
    }

    /**
     * Infested Ruins — combat sequence.
     *
     *   0  → Shift+as (overload modules)
     *   1  → double-click hive (approach)
     *   2  → press 's' (activate web)
     *   3  → WAIT 15s
     *   4  → press 's' (cycle web)
     *   5  → Ctrl+click target_hive/target_hive2 (lock) + press 'a' + (1s) Ctrl+Space (stop ship) + switch client
     *   6  → SAFE WAIT 50s (switch to other client every 10s during the wait)
     *   7  → await set_destination/dock (→ RETURNING/WARPING); 120s backstop → RETURNING; pod check; if jamed → step 8
     *        NOTE: no client switching after the 50s wait — stay on this client until done or jam-escaped
     *   8  → (jamed) burn away (double-click stargate + 's'), WAIT 5s → back to 7
     */
    private void handleCombatInfested(String clientId, ClientState cs) {
        int step = cs.getCombatStep();

        switch (step) {
            case 0: {
                log(clientId, "COMBAT[Infested] Step 0: Shift+as (overload modules).");
                missionProcessor.shiftKeyPress("as");
                cs.setCombatStep(1);
                break;
            }
            case 1: {
                String hive1 = missionProcessor.findAnyTemplate("target_hive", "target_hive2");
                if (hive1 != null) {
                    log(clientId, "COMBAT[Infested] Step 1: right-click " + hive1 + " → 'approach'.");
                    missionProcessor.rightClickAndSelect(hive1, "approach");
                    cs.setCombatStep(2);
                } else if (Duration.between(cs.getLastViewNudge(), LocalDateTime.now()).getSeconds() >= VIEW_NUDGE_THROTTLE_SECS) {
                    // Neither hive template matched — a stale frame may be hiding it. Nudge
                    // the camera (throttled) to force a fresh frame and retry next tick.
                    cs.setLastViewNudge(LocalDateTime.now());
                    log(clientId, "COMBAT[Infested] Step 1: no hive target (target_hive/target_hive2) — nudging view + retrying.");
                    missionProcessor.nudgeView();
                } else {
                    log(clientId, "COMBAT[Infested] Step 1: no hive target found — retrying.");
                }
                break;
            }
            case 2: {
                log(clientId, "COMBAT[Infested] Step 2: press 's' (activate web).");
                missionProcessor.keyPress("s");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(3);
                break;
            }
            case 3: {
                // WAIT 15 seconds
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 15) {
                    log(clientId, "COMBAT[Infested] wait: " + elapsed + "s / 15s.");
                    return;
                }
                log(clientId, "COMBAT[Infested] 15s wait complete.");
                cs.setCombatStep(4);
                break;
            }
            case 4: {
                log(clientId, "COMBAT[Infested] Step 4: press 's' (cycle web).");
                missionProcessor.keyPress("s");
                cs.setCombatStep(5);
                break;
            }
            case 5: {
                log(clientId, "COMBAT[Infested] Step 5: Ctrl+click hive (lock), press 'a', then Ctrl+Space (stop ship) after 1s.");
                String hive5 = missionProcessor.findAnyTemplate("target_hive", "target_hive2");
                if (hive5 == null) {
                    // Don't give up on the lock — a stale frame may be hiding the hive. Nudge the
                    // camera (throttled) to force a fresh frame and retry next tick until it appears.
                    if (Duration.between(cs.getLastViewNudge(), LocalDateTime.now()).getSeconds() >= VIEW_NUDGE_THROTTLE_SECS) {
                        cs.setLastViewNudge(LocalDateTime.now());
                        log(clientId, "COMBAT[Infested] Step 5: no hive target (target_hive/target_hive2) — nudging view + retrying.");
                        missionProcessor.nudgeView();
                    } else {
                        log(clientId, "COMBAT[Infested] Step 5: no hive target found — retrying.");
                    }
                    return;
                }
                missionProcessor.ctrlClickTemplate(hive5);
                missionProcessor.keyPress("a");
                // Ctrl+Space (stop ship) after a 1s delay — 2s earlier than the old 3s.
                try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                missionProcessor.ctrlKeyPress("space");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(6);
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "COMBAT[Infested] switching to: " + other.getClientId());
                    bringToForeground(other);
                }
                break;
            }
            case 6: {
                // SAFE WAIT: 50 seconds. We already switched away once in step 5; when the
                // force-switch watchdog brings us back, stay on this client until the end
                // (like Worm/Angel) — no more switching during the 50s wait.
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 50) {
                    log(clientId, "COMBAT[Infested] safe wait: " + elapsed + "s / 50s.");
                    return;
                }
                log(clientId, "COMBAT[Infested] 50s wait complete — awaiting set_destination.");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(7);
                break;
            }
            case 7: {
                // Await completion like other combats: set_destination/dock → leave.
                // Infested also jams your warp, so if 'jamed' shows, burn away (step 8).
                String completed = missionProcessor.detectCombatComplete();
                if ("dock".equals(completed)) {
                    log(clientId, "COMBAT[Infested] dock detected — same system, docking.");
                    missionProcessor.clickTemplate("dock");
                    cs.setDockingToStation(true);
                    cs.setJamCheckOnWarp(true); // escape warp may be jammed
                    cs.setJamPropPressed(false); // fresh escape — prop mod not yet pressed
                    cs.setTravelPhase(ClientState.TravelPhase.NONE);
                    cs.setWarpStartTime(LocalDateTime.now());
                    stateManager.setState(clientId, ClientState.State.WARPING);
                    return;
                }
                if (completed != null) { // set_destination
                    log(clientId, "COMBAT[Infested] set_destination detected — mission complete, returning.");
                    cs.setJamCheckOnWarp(true); // escape warp may be jammed
                    cs.setJamPropPressed(false); // fresh escape — prop mod not yet pressed
                    cs.setTravelPhase(ClientState.TravelPhase.RETURNING_HOME);
                    stateManager.setState(clientId, ClientState.State.RETURNING);
                    return;
                }

                long elapsed7 = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();

                // After 60s with no prompt, nudge the camera (throttled) to reveal a hidden set_destination/dock.
                String revealed = nudgeForCompletion(clientId, cs, "Infested", elapsed7, 60);
                if (revealed != null) {
                    cs.setJamCheckOnWarp(true); // escape warp may be jammed
                    cs.setJamPropPressed(false); // fresh escape — prop mod not yet pressed
                    if ("dock".equals(revealed)) {
                        log(clientId, "COMBAT[Infested] dock revealed after nudge — same system, docking.");
                        missionProcessor.clickTemplate("dock");
                        cs.setDockingToStation(true);
                        cs.setTravelPhase(ClientState.TravelPhase.NONE);
                        cs.setWarpStartTime(LocalDateTime.now());
                        stateManager.setState(clientId, ClientState.State.WARPING);
                    } else {
                        log(clientId, "COMBAT[Infested] set_destination revealed after nudge — mission complete, returning.");
                        cs.setTravelPhase(ClientState.TravelPhase.RETURNING_HOME);
                        stateManager.setState(clientId, ClientState.State.RETURNING);
                    }
                    return;
                }

                // Backstop: after 120s with no set_destination prompt it may never
                // appear (or we drifted out without it). Check for a pod first,
                // otherwise go to RETURNING — which clicks set_destination (template,
                // or the hardcoded coords as fallback) so the route home is always set.
                if (elapsed7 >= 120) {
                    if (missionProcessor.isFlyingPod()) {
                        handlePodDeath(clientId, true);
                        return;
                    }
                    log(clientId, "COMBAT[Infested] no set_destination after 120s — returning (set_destination will be clicked).");
                    cs.setJamCheckOnWarp(true); // escape warp may be jammed
                    cs.setJamPropPressed(false); // fresh escape — prop mod not yet pressed
                    cs.setTravelPhase(ClientState.TravelPhase.RETURNING_HOME);
                    stateManager.setState(clientId, ClientState.State.RETURNING);
                    return;
                }

                // Warp jam — burn toward the stargate to break it, then wait (step 8).
                if (missionProcessor.findTemplate("jamed") != null) {
                    log(clientId, "COMBAT[Infested] JAMED — double-click stargate, click away to mid, press 's' to burn away.");
                    missionProcessor.doubleClickTemplate("stargate");
                    missionProcessor.clickEmptySpace(); // click away in space to clear the menu
                    missionProcessor.keyPress("s");
                    cs.setCombatWaitStart(LocalDateTime.now());
                    cs.setCombatStep(8);
                    break;
                }

                // No client switching — stay here and keep watching for set_destination/dock
                // or a jam to escape until the mission completes (or the 120s backstop).
                log(clientId, "COMBAT[Infested] awaiting set_destination, no switch (" + elapsed7 + "s)...");
                break;
            }
            case 8: {
                // After burning away from a jam, wait 5s then resume awaiting completion.
                long elapsed8 = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed8 < 5) {
                    log(clientId, "COMBAT[Infested] jam burn wait: " + elapsed8 + "s / 5s.");
                    return;
                }
                log(clientId, "COMBAT[Infested] jam burn complete — resuming await.");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(7);
                break;
            }
            default:
                log(clientId, "COMBAT[Infested] unexpected step " + step + " — resetting.");
                cs.setCombatStep(0);
        }
    }

    /**
     * Recon 1 of 3 — combat sequence.
     *
     * Entry: IDLE[TO_MISSION] already activated the first gate (right-click activate_gate
     *        → mission_start). The 20s IN_COMBAT grace period covers the gate load.
     *
     *   0  → right-click activate_gate → click mission_start (second gate) → press 's' twice
     *   1  → SAFE WAIT 30s (gate load) + switch to other client
     *   2  → look for dock → click dock → WARPING → COMPLETING
     */
    private void handleCombatRecon(String clientId, ClientState cs) {
        int step = cs.getCombatStep();

        switch (step) {
            case 0: {
                log(clientId, "COMBAT[Recon] Step 0: right-click activate_gate → mission_start (second gate), press 's'.");
                missionProcessor.rightClickAndSelect("activate_gate", "mission_start");
                missionProcessor.keyPress("s");
                missionProcessor.keyPress("s");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(1);
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "COMBAT[Recon] gate loading — safe wait 20s, switching to: " + other.getClientId());
                    bringToForeground(other);
                }
                break;
            }
            case 1: {
                // SAFE WAIT 30s for gate load
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 30) {
                    return;
                }
                log(clientId, "COMBAT[Recon] 30s gate load complete — looking for dock.");
                cs.setCombatStep(2);
                break;
            }
            case 2: {
                // Look for dock button like normal travel — click it and enter WARPING.
                String dockCheck = missionProcessor.detectDockOnly();
                if (dockCheck != null) {
                    log(clientId, "COMBAT[Recon] Step 2: dock visible — clicking dock.");
                    missionProcessor.clickDock();
                    cs.setDockingToStation(true);
                    cs.setWarpStartTime(LocalDateTime.now());
                    cs.setTravelPhase(ClientState.TravelPhase.RETURNING_HOME);
                    stateManager.setState(clientId, ClientState.State.WARPING);
                } else {
                    log(clientId, "COMBAT[Recon] Step 2: dock not visible yet — waiting.");
                }
                break;
            }
            default:
                log(clientId, "COMBAT[Recon] unexpected step " + step + " — resetting.");
                cs.setCombatStep(0);
        }
    }

    /** True if the client's current mission is Recon 1 of 3 (always same-system). */
    private boolean isReconMission(ClientState cs) {
        Mission m = cs.getCurrentMission();
        if (m == null) return false;
        String t = m.getShipTarget() != null ? m.getShipTarget() : m.getTarget();
        return "recon1".equalsIgnoreCase(t);
    }

    /** True if the client's current mission is the Dread Pirate Scarlet run. */
    private boolean isScarletMission(ClientState cs) {
        Mission m = cs.getCurrentMission();
        if (m == null) return false;
        String t = m.getShipTarget() != null ? m.getShipTarget() : m.getTarget();
        return "scarlet".equalsIgnoreCase(t);
    }

    /**
     * Dread Pirate Scarlet (boss "Anire") — combat sequence. Entered when the warp-to-location
     * lands (the mission is already started — there is no first activate_gate). Standings-gated
     * mission (accepted only when standing &lt; 1).
     *
     * Steps (combat starts at step 1 — the warp-to-location landing already settled the grid):
     *   1 → activate gate the usual way (right-click activate_gate → "mission_start" menu entry),
     *       press 's' twice (turn off the warp module).
     *   2 → set destination home via the agent (alt+J → 'active' → right-click portrait → set
     *       destination), so the route is ready before we engage. Safe-wait switch to the other client.
     *   3 → SAFE WAIT 25s (gate load).
     *   4 → activate the next gate (right-click activate_gate → "mission_start"), press 's' twice. Switch.
     *   5 → SAFE WAIT 25s, then confirm warp has ended (warp_active gone) before locking.
     *   6 → ctrl-click 'anire' (lock). NO client switching from here — dedicate this client until she dies.
     *   7 → confirm the lock: poll 'locked_anire'; if not seen within SCARLET_LOCK_RETRY_SECS the
     *       ctrl-click missed → back to step 6 to re-attempt.
     *   8 → right-click 'locked_anire' → 'approach', press 's' twice.
     *   9 → shift+ad (overload modules).
     *  10 → WAIT 2s, then press 'adc' (activate modules).
     *  11 → WAIT 20s (from activating modules), then poll 'locked_anire'; when it's gone (Anire
     *       destroyed) → just jump the stargate (destination already set) → WARPING/RETURNING_HOME,
     *       which flies home and turns the mission in at the agent. No client switching.
     *
     * NOTE: the on-screen set_destination prompt is intentionally ignored for Scarlet — the
     *       alt+J agent method is used instead, in step 1 (location-independent).
     * NOTE: step 7 requires locked_anire.png to be captured — without it findTemplate reads "gone"
     *       and the confirm-lock poll will never pass.
     */
    private void handleCombatScarlet(String clientId, ClientState cs) {
        int step = cs.getCombatStep();

        switch (step) {
            case 1: {
                log(clientId, "COMBAT[Scarlet] Step 1: right-click activate_gate → activate (usual gate flow), press 's' twice (1s gap).");
                missionProcessor.rightClickAndSelect("activate_gate", "mission_start");
                missionProcessor.keyPressTwice("s", 1000);
                cs.setCombatStep(2);
                break;
            }
            case 2: {
                // Set destination home via the alt+J agent method so the route is ready.
                // When Anire dies we just jump — destination already set.
                log(clientId, "COMBAT[Scarlet] Step 2: set destination home via agent (alt+J).");
                missionProcessor.setDestinationViaAgent();
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(3);
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "COMBAT[Scarlet] destination set — gate loading, safe wait 25s, switching to: " + other.getClientId());
                    bringToForeground(other);
                }
                break;
            }
            case 3: {
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 25) {
                    return;
                }
                log(clientId, "COMBAT[Scarlet] 25s gate load complete — activating next gate.");
                cs.setCombatStep(4);
                break;
            }
            case 4: {
                log(clientId, "COMBAT[Scarlet] Step 4: right-click activate_gate → activate next gate (usual gate flow), press 's' twice (1s gap).");
                missionProcessor.rightClickAndSelect("activate_gate", "mission_start");
                missionProcessor.keyPressTwice("s", 1000);
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(5);
                // Stay on this client through the second gate — like angel/worm, no switching
                // after the second gate. We land straight in the Anire pocket and must be here to
                // lock and engage.
                log(clientId, "COMBAT[Scarlet] second gate loading — staying on this client (no switch), waiting to land.");
                break;
            }
            case 5: {
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 25) {
                    return;
                }
                // Don't lock until we've actually exited warp — a lock attempt mid-warp won't take.
                if (missionProcessor.isWarpActive()) {
                    log(clientId, "COMBAT[Scarlet] Step 5: 25s elapsed but still in warp — waiting to land before locking.");
                    return;
                }
                log(clientId, "COMBAT[Scarlet] 25s wait complete + warp ended — engaging Anire.");
                cs.setCombatStep(6);
                break;
            }
            case 6: {
                // Lock first. Dedicating this client until Anire dies — no switching from here.
                // Don't ctrl-click blind: if 'anire' isn't on screen (off-camera / stale frame),
                // nudge the view and hold the step until she appears (no alt_target — a generic
                // overview row would lock the wrong thing in the boss pocket).
                if (missionProcessor.findTemplate("anire") == null) {
                    log(clientId, "COMBAT[Scarlet] Step 6: 'anire' not on screen — nudging view to reveal her (holding step).");
                    missionProcessor.nudgeView();
                    return;
                }
                log(clientId, "COMBAT[Scarlet] Step 6: ctrl-click 'anire' (lock target). No client switching from here.");
                missionProcessor.ctrlClickTemplate("anire");
                cs.setCombatWaitStart(LocalDateTime.now()); // anchors the lock-confirm retry timer
                cs.setCombatStep(7);
                break;
            }
            case 7: {
                // Confirm the lock took before engaging: poll for the locked_anire bracket. If it
                // doesn't show within SCARLET_LOCK_RETRY_SECS the ctrl-click missed — go back to
                // step 6 and re-attempt the lock rather than waiting forever.
                if (missionProcessor.findTemplate("locked_anire") == null) {
                    long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                    if (elapsed >= SCARLET_LOCK_RETRY_SECS) {
                        log(clientId, "COMBAT[Scarlet] Step 7: 'locked_anire' not seen after " + elapsed + "s — re-attempting ctrl-click (back to step 6).");
                        cs.setCombatStep(6);
                        break;
                    }
                    log(clientId, "COMBAT[Scarlet] Step 7: waiting for 'locked_anire' (" + elapsed + "s / " + SCARLET_LOCK_RETRY_SECS + "s, lock not confirmed yet).");
                    return;
                }
                log(clientId, "COMBAT[Scarlet] Step 7: lock confirmed.");
                cs.setCombatStep(8);
                break;
            }
            case 8: {
                log(clientId, "COMBAT[Scarlet] Step 8: right-click 'locked_anire' → 'approach', press 's' twice (1s gap).");
                missionProcessor.rightClickAndSelect("locked_anire", "approach");
                missionProcessor.keyPressTwice("s", 1000);
                cs.setCombatStep(9);
                break;
            }
            case 9: {
                log(clientId, "COMBAT[Scarlet] Step 9: shift+ad (overload modules).");
                missionProcessor.shiftKeyPress("ad");
                cs.setCombatWaitStart(LocalDateTime.now()); // anchors the 2s wait before 'adc'
                cs.setCombatStep(10);
                break;
            }
            case 10: {
                // Wait 2s after the overload, then press 'ad' (activate the first modules).
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 2) {
                    log(clientId, "COMBAT[Scarlet] Step 10: wait before 'ad' (" + elapsed + "s / 2s).");
                    return;
                }
                log(clientId, "COMBAT[Scarlet] Step 10: press 'ad' (activate modules).");
                missionProcessor.keyPress("ad");
                cs.setCombatWaitStart(LocalDateTime.now()); // anchors the wait before 'c'
                cs.setCombatStep(11);
                break;
            }
            case 11: {
                // Wait a bit before pressing 'c' — don't fire every module at once right after the
                // lock; give 'ad' a moment first.
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 10) {
                    log(clientId, "COMBAT[Scarlet] Step 11: wait before 'c' (" + elapsed + "s / 10s).");
                    return;
                }
                log(clientId, "COMBAT[Scarlet] Step 11: press 'c' (activate remaining module).");
                missionProcessor.keyPress("c");
                cs.setCombatWaitStart(LocalDateTime.now()); // anchors the 10s death-watch
                cs.setCombatStep(12);
                break;
            }
            case 12: {
                // No client switching — stay and wait for Anire to die. Hold off polling until
                // 10s after 'c' (the 10s pre-'c' wait already elapsed, so ~20s of module time total),
                // then watch for the locked_anire bracket to disappear.
                // Destination is already set (step 2), so on death we just jump and head home.
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 10) {
                    log(clientId, "COMBAT[Scarlet] waiting for Anire to die (" + elapsed + "s / 10s before checking).");
                    return;
                }
                if (missionProcessor.findTemplate("locked_anire") == null) {
                    log(clientId, "COMBAT[Scarlet] locked_anire gone — Anire destroyed, jumping stargate (destination already set).");
                    missionProcessor.rightClickAndSelect("stargate", "jump");
                    cs.setWarpStartTime(LocalDateTime.now());
                    cs.setTravelPhase(ClientState.TravelPhase.RETURNING_HOME);
                    stateManager.setState(clientId, ClientState.State.WARPING); // wait out the jump-warp → IDLE[RETURNING_HOME]
                    break;
                }
                log(clientId, "COMBAT[Scarlet] Anire still locked (" + elapsed + "s) — waiting (no client switch).");
                break;
            }
            default:
                log(clientId, "COMBAT[Scarlet] unexpected step " + step + " — resetting.");
                cs.setCombatStep(0);
        }
    }

    /**
     * Angel / Dramiel — combat sequence.
     *
     * Steps:
     *   0 → double-click target_angel (select) + Ctrl+click (lock)
     *   1 → Shift+ax (overload modules) + press 'c' (rep, un-overloaded)
     *   2 → press 'x'
     *   3 → WAIT 2s → press 'asd' → click target_angel
     *   4 → Right-click target_angel + click range (keep at range)
     *   5 → WAIT 10s → Shift+c (overload rep) + switch to other client
     *   6 → WAIT for set_destination; 60s hardcap (pod check) → step 7
     *   7 → press 'xc' (deactivate modules) → RETURNING (clicks set_destination coords)
     */
    private void handleCombatAngel(String clientId, ClientState cs) {
        int step = cs.getCombatStep();

        switch (step) {
            case 0: {
                String tk = awaitCombatTarget(clientId, cs, "target_angel");
                if (tk == null) return; // target not visible — nudged, hold step and retry next tick
                cs.setCombatTargetKey(tk);
                log(clientId, "COMBAT[Angel] Step 0: double-click " + tk + " (select) + Ctrl+click (lock).");
                missionProcessor.doubleClickTemplate(tk);
                missionProcessor.ctrlClickTemplate(tk);
                cs.setCombatStartTime(LocalDateTime.now()); // fight start
                cs.setCombatStep(1);
                break;
            }
            case 1: {
                // Overload only a and x here; activate the rep 'c' un-overloaded (plain press)
                // so it isn't overloaded alongside the other modules. It gets overloaded
                // (Shift+c) in step 5, after the 10s wait and before switching clients.
                log(clientId, "COMBAT[Angel] Step 1: Shift+ax (overload modules) + press 'c' (rep, no overload).");
                missionProcessor.shiftKeyPress("ax");
                missionProcessor.keyPress("c");
                cs.setCombatStep(2);
                break;
            }
            case 2: {
                log(clientId, "COMBAT[Angel] Step 2: press 'x'.");
                missionProcessor.keyPress("x");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(3);
                break;
            }
            case 3: {
                // WAIT 2s, then press 'asd' and click the target
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 2) {
                    log(clientId, "COMBAT[Angel] wait before 'asd': " + elapsed + "s / 2s.");
                    return;
                }
                String clickKey = cs.getCombatTargetKey() != null ? cs.getCombatTargetKey() : "target_angel";
                log(clientId, "COMBAT[Angel] Step 3: press 'asd', click " + clickKey + ".");
                missionProcessor.keyPress("asd");
                missionProcessor.clickTemplate(clickKey);
                cs.setCombatStep(4);
                break;
            }
            case 4: {
                String rangeKey = cs.getCombatTargetKey() != null ? cs.getCombatTargetKey() : "target_angel";
                log(clientId, "COMBAT[Angel] Step 4: right-click " + rangeKey + " → click range.");
                missionProcessor.rightClickTemplate(rangeKey);
                missionProcessor.clickTemplate("range");
                confirmKeepAtRange(clientId, "Angel");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(5);
                break;
            }
            case 5: {
                // WAIT 10s after keep-at-range is set, then overload the rep (Shift+c) and
                // switch to the other client. Modules are running and keep-at-range is set, so
                // the fight runs unattended; focus returns via the force-switch watchdog / other
                // client yielding, and step 6 then polls completion.
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 10) {
                    log(clientId, "COMBAT[Angel] wait before Shift+c: " + elapsed + "s / 10s.");
                    return;
                }
                log(clientId, "COMBAT[Angel] Step 5: Shift+c (overload rep) + switch to other client.");
                missionProcessor.shiftKeyPress("c");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(6);
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "COMBAT[Angel] switching to: " + other.getClientId());
                    bringToForeground(other);
                }
                break;
            }
            case 6: {
                // WAIT for set_destination/dock
                String completed = missionProcessor.detectCombatComplete();
                if (completed != null) {
                    log(clientId, "COMBAT[Angel] " + completed + " detected — deactivating modules.");
                    cs.setCombatStep(7);
                    break;
                }

                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();

                // After 60s with no prompt, nudge the camera (throttled) to reveal a hidden
                // set_destination/dock. Runs before the hardcap below, so we get one reveal
                // attempt at 60s before forcing the return.
                String revealed = nudgeForCompletion(clientId, cs, "Angel", elapsed, 60);
                if (revealed != null) {
                    log(clientId, "COMBAT[Angel] " + revealed + " revealed after nudge — deactivating modules.");
                    cs.setCombatStep(7);
                    break;
                }

                // 60s hardcap: no set_destination/dock seen — pod check, then force
                // deactivate + return (step 7 → RETURNING clicks the set_destination
                // coords fallback).
                if (elapsed >= 60) {
                    if (missionProcessor.isFlyingPod()) {
                        handlePodDeath(clientId, true);
                        return;
                    }
                    log(clientId, "COMBAT[Angel] 60s hardcap — no completion, forcing deactivate + return.");
                    cs.setCombatStep(7);
                    break;
                }

                log(clientId, "COMBAT[Angel] waiting for mission complete (" + elapsed + "s)...");
                break;
            }
            case 7: {
                log(clientId, "COMBAT[Angel] Step 7: press 'xc' (deactivate modules).");
                // Completion is usually detected on the first tick after focus switches back to
                // this client (thumbnail click), and a key press can be dropped before the game
                // window has keyboard focus. Click empty space first to ensure focus lands, then press.
                missionProcessor.clickEmptySpace();
                missionProcessor.keyPress("xc");
                // Re-detect: dock = same system, set_destination = different system
                String result = missionProcessor.detectCombatComplete();
                if ("dock".equals(result)) {
                    log(clientId, "COMBAT[Angel] dock detected — same system, docking.");
                    missionProcessor.clickTemplate("dock");
                    cs.setDockingToStation(true);
                    cs.setTravelPhase(ClientState.TravelPhase.NONE);
                    cs.setWarpStartTime(LocalDateTime.now());
                    stateManager.setState(clientId, ClientState.State.WARPING);
                } else {
                    stateManager.setState(clientId, ClientState.State.RETURNING);
                }
                break;
            }
            default:
                log(clientId, "COMBAT[Angel] unexpected step " + step + " — resetting.");
                cs.setCombatStep(0);
        }
    }

    /**
     * Worm / Guristas — combat sequence.
     *
     * Steps:
     *   0 → Shift+asdx (overload modules + web)
     *   1 → right-click target_worm → 'approach'
     *   2 → press 's' (activate web)
     *   3 → WAIT 13s → press 'x'
     *   4 → locked_worm present? skip lock : Ctrl+click target_worm (lock target)
     *   5 → press 'ad' (modules, except 's')
     *   6 → press 's' (separate step, after everything else) + Shift+d (stop overloading web)
     *   7 → WAIT 4s → right-click target_worm + click range (keep at range)
     *   8 → Proceed (keep_at_range template check removed) → wait 7s
     *   9 → WAIT 7s → Alt+F1 (cap module ON) + press 'c' + switch client
     *  10 → poll completion (Sansha-style): set_destination/dock → step 11.
     *        If fight drags > 60s → Alt+F1 again (extra pulse, once).
     *        120s hardcap (pod check) → step 11.
     *  11 → Alt+F1 (cap module OFF; fires on fast and long fights) + press 'xc' (deactivate
     *        modules) + handle dock/returning
     */
    private void handleCombatWorm(String clientId, ClientState cs) {
        int step = cs.getCombatStep();

        switch (step) {
            case 0: {
                log(clientId, "COMBAT[Worm] Step 0: Shift+asdx (overload modules + web).");
                missionProcessor.shiftKeyPress("asdx");
                cs.setCombatStep(1);
                break;
            }
            case 1: {
                String tk = awaitCombatTarget(clientId, cs, "target_worm");
                if (tk == null) return; // target not visible — nudged, hold step and retry next tick
                cs.setCombatTargetKey(tk);
                log(clientId, "COMBAT[Worm] Step 1: right-click " + tk + " → 'approach'.");
                missionProcessor.rightClickAndSelect(tk, "approach");
                cs.setCombatStep(2);
                break;
            }
            case 2: {
                log(clientId, "COMBAT[Worm] Step 2: press 's' (activate web).");
                missionProcessor.keyPress("s");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(3);
                break;
            }
            case 3: {
                // WAIT 13 seconds
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 13) {
                    log(clientId, "COMBAT[Worm] wait: " + elapsed + "s / 13s.");
                    return;
                }
                log(clientId, "COMBAT[Worm] 13s wait complete — press 'x'.");
                missionProcessor.keyPress("x");
                cs.setCombatStep(4);
                break;
            }
            case 4: {
                if (missionProcessor.findTemplate("locked_worm") != null) {
                    log(clientId, "COMBAT[Worm] Step 4: locked_worm already present (auto-locked) — skipping ctrl+click.");
                } else {
                    String lockKey = cs.getCombatTargetKey() != null ? cs.getCombatTargetKey() : "target_worm";
                    log(clientId, "COMBAT[Worm] Step 4: Ctrl+click " + lockKey + " (lock target).");
                    missionProcessor.ctrlClickTemplate(lockKey);
                }
                cs.setCombatStep(5);
                break;
            }
            case 5: {
                log(clientId, "COMBAT[Worm] Step 5: press 'ad' (modules, except 's').");
                missionProcessor.keyPress("ad");
                cs.setCombatStep(6);
                break;
            }
            case 6: {
                // Press 's' on its own, after every other module — separate step so it
                // isn't bundled with 'dxac' (avoids the modules getting confused).
                // Then Shift+d to stop overloading the web now that it's active.
                log(clientId, "COMBAT[Worm] Step 6: press 's' (separate), wait 2s, Shift+d (stop overloading web).");
                missionProcessor.keyPress("s");
                // Give 's' time to take effect before stopping the web overload.
                try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                missionProcessor.shiftKeyPress("d");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(7);
                break;
            }
            case 7: {
                // WAIT 4s before keeping at range
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 4) {
                    log(clientId, "COMBAT[Worm] wait before range: " + elapsed + "s / 4s.");
                    return;
                }
                String rangeKey = cs.getCombatTargetKey() != null ? cs.getCombatTargetKey() : "target_worm";
                log(clientId, "COMBAT[Worm] Step 7: right-click " + rangeKey + " → range.");
                missionProcessor.rightClickTemplate(rangeKey);
                missionProcessor.clickTemplate("range");
                cs.setCombatStep(8);
                break;
            }
            case 8: {
                // Range settle (keep_at_range template check removed) — wait 7s before deactivate.
                log(clientId, "COMBAT[Worm] Step 8: waiting 7s before deactivate.");
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(9);
                break;
            }
            case 9: {
                // WAIT 7s, then Alt+F1 once (cap module ON). The worm dies slowly, so
                // there's nothing to babysit here — hand the foreground to the other
                // client. The force-switch watchdog brings us back periodically to check
                // for completion (and the 80s re-pulse in step 10).
                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();
                if (elapsed < 7) return;
                log(clientId, "COMBAT[Worm] Step 9: Alt+F1 (cap module ON) + press 'c'.");
                missionProcessor.altKeyPress("f1");
                missionProcessor.keyPress("c");
                cs.setCombatStartTime(LocalDateTime.now()); // fight start, for the 60s extra-pulse check
                cs.setExtraCapPulseDone(false);
                cs.setCombatWaitStart(LocalDateTime.now());
                cs.setCombatStep(10);
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "COMBAT[Worm] cap ON — worm won't die fast, switching to: " + other.getClientId());
                    bringToForeground(other);
                }
                break;
            }
            case 10: {
                // Completion poll: wait for set_destination/dock. The locked_worm
                // bracket is NOT used as a completion signal — it can flicker/drop
                // for a scan while the worm is still alive, ending the fight early.
                String completed = missionProcessor.detectCombatComplete();
                if (completed != null) {
                    log(clientId, "COMBAT[Worm] " + completed + " detected — finishing up.");
                    cs.setCombatStep(11);
                    break;
                }

                long elapsed = Duration.between(cs.getCombatWaitStart(), LocalDateTime.now()).getSeconds();

                // After 60s with no prompt, nudge the camera (throttled) to reveal a hidden set_destination/dock.
                String revealed = nudgeForCompletion(clientId, cs, "Worm", elapsed, 60);
                if (revealed != null) {
                    log(clientId, "COMBAT[Worm] " + revealed + " revealed after nudge — finishing up.");
                    cs.setCombatStep(11);
                    break;
                }

                // 120s hardcap: no completion signal seen — pod check, then finish up.
                if (elapsed >= 120) {
                    if (missionProcessor.isFlyingPod()) {
                        handlePodDeath(clientId, true);
                        return;
                    }
                    log(clientId, "COMBAT[Worm] 120s hardcap — no completion, finishing up.");
                    cs.setCombatStep(11);
                    break;
                }

                // Long fight: past 60s the cap module's charges are spent and it has
                // reloaded (sitting idle), so a single Alt+F1 switches it back on for
                // another batch. Once only — if combat were over we'd have left above,
                // so re-pulsing here can't start a stray cycle on a dead grid.
                if (!cs.isExtraCapPulseDone()
                        && Duration.between(cs.getCombatStartTime(), LocalDateTime.now()).getSeconds() > 60) {
                    log(clientId, "COMBAT[Worm] fight > 60s — Alt+F1 again (cap module back ON).");
                    missionProcessor.altKeyPress("f1");
                    cs.setExtraCapPulseDone(true);
                }

                // Switched once already in step 9 — now stay on this client and watch the
                // fight to the end (no more switching away) so we can turn the cap module
                // off at the right moment in step 11.
                log(clientId, "COMBAT[Worm] watching for mission complete (" + elapsed + "s)...");
                break;
            }
            case 11: {
                // Turn the cap module OFF — fires on both fast and long fights. On a fast
                // fight the module is still cycling on the initial activation, so Alt+F1
                // shuts it down. On a long fight the extra pulse at 60s re-activated it, so
                // Alt+F1 here turns that second batch off.
                if (!cs.isExtraCapPulseDone()) {
                    log(clientId, "COMBAT[Worm] Step 11: fast fight — Alt+F1 (cap module OFF).");
                } else {
                    log(clientId, "COMBAT[Worm] Step 11: long fight (extra cap pulse fired) — Alt+F1 (cap module OFF).");
                }
                missionProcessor.altKeyPress("f1");
                // Deactivate modules (same as Angel step 7). Click empty space first so the
                // key press isn't dropped before the game window has keyboard focus.
                log(clientId, "COMBAT[Worm] Step 11: press 'xc' (deactivate modules).");
                missionProcessor.clickEmptySpace();
                missionProcessor.keyPress("xc");
                // Re-detect: dock = same system, set_destination = different system.
                String result = missionProcessor.detectCombatComplete();
                if ("dock".equals(result)) {
                    log(clientId, "COMBAT[Worm] dock detected — same system, docking.");
                    missionProcessor.clickTemplate("dock");
                    cs.setDockingToStation(true);
                    cs.setTravelPhase(ClientState.TravelPhase.NONE);
                    cs.setWarpStartTime(LocalDateTime.now());
                    stateManager.setState(clientId, ClientState.State.WARPING);
                } else {
                    stateManager.setState(clientId, ClientState.State.RETURNING);
                }
                break;
            }
            default:
                log(clientId, "COMBAT[Worm] unexpected step " + step + " — resetting.");
                cs.setCombatStep(0);
        }
    }

    // -----------------------------------------------------------------------
    // RETURNING — set destination home, repeating (click + nudge) until the route panel
    //             actually shows a route. Navigation is handled by IDLE + WARPING cycling.
    // -----------------------------------------------------------------------

    private void handleReturning(String clientId, ClientWindowInfo window) {
        ClientState cs = stateManager.getState(clientId);
        // Same as the outbound leg: template-only click (twice), confirmed by the route panel,
        // nudging the camera to refresh a stale frame if the prompt isn't detected. No route
        // means no jump — stay in RETURNING and repeat on the next tick.
        MissionProcessor.RouteInfo route = clickSetDestinationByTemplate(clientId);
        for (int attempt = 1; route == null && attempt <= 2; attempt++) {
            log(clientId, "RETURNING: no route yet — nudging view (" + attempt + "/2).");
            missionProcessor.nudgeView();
            route = clickSetDestinationByTemplate(clientId);
        }
        if (route == null) {
            // Same-system mission: there's no destination to set — the station is right here and
            // the dock button is up. Hand over to IDLE[RETURNING_HOME], which docks it. Otherwise
            // repeat next tick until the route panel actually shows a route.
            if (missionProcessor.findTemplate("dock") != null) {
                log(clientId, "RETURNING: no route but dock is up — station is in this system, proceeding.");
            } else {
                log(clientId, "RETURNING: still no route and no dock after 2 nudges — NOT jumping, repeating next tick.");
                return;
            }
        }
        cs.setTravelPhase(ClientState.TravelPhase.RETURNING_HOME);
        stateManager.setState(clientId, ClientState.State.IDLE);
    }

    // -----------------------------------------------------------------------
    // COMPLETING — turn in mission, collect rewards, return to IDLE
    // -----------------------------------------------------------------------

    private void handleCompleting(String clientId) {
        if (missionProcessor.detectCompletingUI() == null) {
            // Stuck waiting for the agent icon = we may never have docked. After 60s,
            // if the dock button is visible, click it (twice, like warp-to-location) to
            // dock. Throttled to once per 60s so it doesn't spam while warping to dock.
            ClientState cs = stateManager.getState(clientId);
            long waiting   = Duration.between(cs.getLastStateChange(), LocalDateTime.now()).getSeconds();
            long sinceDock = Duration.between(cs.getLastCompletingDock(), LocalDateTime.now()).getSeconds();
            if (waiting >= 60 && sinceDock >= 60 && missionProcessor.isDockVisible()) {
                log(clientId, "COMPLETING: 60s without agent icon — dock visible, clicking dock (twice) in case we never docked.");
                missionProcessor.clickDockTwice();
                cs.setLastCompletingDock(LocalDateTime.now());
                return;
            }
            log(clientId, "COMPLETING: agent icon not visible yet — waiting.");
            try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return;
        }
        missionProcessor.doubleClickAgentIcon();

        // Read agent standings before completing — determines fleet reward choice
        missionProcessor.clickAgentStandings();
        double standings = missionProcessor.readAgentStandings();

        // Track the standing for mission gating (recon 1 / scarlet / pot-and-kettle are
        // accepted only when standing < 1). recordStanding ignores NaN OCR misses (never
        // clobbers a good value) and persists to disk so it survives restarts.
        if (!Double.isNaN(standings)) {
            stateManager.recordStanding(clientId, standings);
            log(clientId, "COMPLETING: tracked agent standing updated for mission gating (gate: < 1).");
        }

        missionProcessor.closeStandingsDialog();  // close the standings window (region-scoped 'X', fixed-loc fallback)
        missionProcessor.doubleClickAgentIcon(); // refocus agent dialog (standings covers complete_mission)

        // Turn in and confirm it took, the same way ACCEPT is confirmed: no OK dialog, or a
        // COMPLETE MISSION button still on screen, means the turn-in didn't go through (the
        // complete click missed, or the standings window was still covering it). Retry rather
        // than marking the mission complete and requesting the next one on a mission still open.
        boolean turnedIn = false;
        for (int attempt = 1; attempt <= 3 && !turnedIn; attempt++) {
            missionProcessor.clickCompleteMission();
            chooseFleetReward(clientId, standings);   // fleet reward decision based on agent standings
            missionProcessor.waitAndClickOk(); // dismiss fleet reward confirmation dialog if one appeared
            // The OK dialog only shows up for some rewards, so its absence proves nothing — the
            // COMPLETE MISSION button disappearing is what says the turn-in went through. Give
            // the dialog time to actually close before scanning, or we read the stale frame.
            try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            turnedIn = !missionProcessor.isCompleteMissionVisible();
            if (!turnedIn) {
                log(clientId, "COMPLETING: turn-in not confirmed (attempt " + attempt
                        + "/3, COMPLETE MISSION still visible) — retrying.");
                missionProcessor.doubleClickAgentIcon();   // re-open the agent dialog for the retry
            }
        }
        if (!turnedIn) {
            log(clientId, "COMPLETING: turn-in never confirmed after 3 attempts — proceeding anyway.");
            EventLog.log("COMPLETE_UNCONFIRMED", clientId + " — mission turn-in not confirmed after 3 attempts");
        }
        stateManager.getState(clientId).setTravelPhase(ClientState.TravelPhase.NONE);

        // Mission turned in — record reward for the end-of-session summary.
        Mission done = stateManager.getState(clientId).getCurrentMission();
        String missionTitle = (done != null && done.getTitle() != null) ? done.getTitle() : "mission";
        long missionValue = SessionStats.valueFor(done != null ? done.getShipTarget() : null, missionTitle);
        int sessionTotal = SessionStats.recordMission(missionValue);
        EventLog.log("MISSION_COMPLETE", String.format(
                "%s — %s — ~%,d ISK value [session: %d, ~%,d ISK value]",
                clientId, missionTitle, missionValue, sessionTotal, SessionStats.totalValue()));

        // Graceful session end: the mission is turned in — don't request the next one; park
        // this client docked at its agent for the rest of the session.
        if (stateManager.isSessionEnding()) {
            parkForSessionEnd(clientId, "mission turned in");
            return;
        }

        // The other client hit a bubble/gate camp and triggered the global pod cooldown while we
        // were mid-mission. We've now finished and turned this one in — don't undock into the same
        // danger for the next mission. Enter POD_COOLDOWN and wait out whatever remains of the
        // shared window (normal post-completion path is COMPLETING → CHECKING_MISSIONS, which never
        // passes the IDLE cooldown gate, so the check has to happen here).
        LocalDateTime podEnd = stateManager.getPodCooldownEnd();
        if (podEnd != null && LocalDateTime.now().isBefore(podEnd)) {
            long remaining = Duration.between(LocalDateTime.now(), podEnd).getSeconds();
            log(clientId, "COMPLETING: mission turned in but global pod cooldown active ("
                    + (remaining / 60) + "m " + (remaining % 60) + "s) — entering POD_COOLDOWN before next mission.");
            stateManager.getState(clientId).setCurrentMission(null);
            stateManager.setState(clientId, ClientState.State.POD_COOLDOWN);
            ClientWindowInfo other = findOtherClient(clientId);
            if (other != null) {
                bringToForeground(other);
            }
            return;
        }

        // Request next mission immediately and go to CHECKING_MISSIONS
        // instead of IDLE — avoids stale complete_mission being misdetected.
        missionProcessor.clickRequestMission();

        // Switch to other client after requesting — gives the dialog time to load
        ClientWindowInfo other = findOtherClient(clientId);
        if (other != null) {
            log(clientId, "COMPLETING: mission turned in — switching focus to " + other.getClientId());
            bringToForeground(other);
        }
        stateManager.getState(clientId).setCurrentMission(null);
        stateManager.getState(clientId).setMissionCheckStartTime(LocalDateTime.MIN);
        stateManager.setState(clientId, ClientState.State.CHECKING_MISSIONS);
    }

    /** Take-all standings threshold is normally 0.0. It is raised to 1.0 (this client grabs the
     *  standings for itself more eagerly) when the fleet partner grabbed a mission in the last 2
     *  minutes, and forced take-all (regardless of our own standing) when the partner's standing
     *  is more than 1.0 above ours, so the lagging client catches up. */
    private static final double TAKE_ALL_THRESHOLD_HIGH = 1.0;
    private static final long   PARTNER_GRAB_WINDOW_MIN  = 2;
    private static final double PARTNER_STANDING_GAP     = 1.0;

    /**
     * Pick the fleet reward (share vs take-all) from the agent's standing and click it.
     * Below the take-all threshold: take all (fleet_no) to recover standings; healthy: share
     * (fleet_yes). The threshold is normally 0, raised to 1 when the fleet partner just grabbed
     * a mission or is well ahead of us in standing (see {@link #takeAllThreshold}).
     * Exact standings are not logged — OCR misreads the value; only a low-standings warning is kept.
     */
    private void chooseFleetReward(String clientId, double standings) {
        if (Double.isNaN(standings)) {
            log(clientId, "COMPLETING: standings OCR failed — defaulting to fleet YES.");
            missionProcessor.clickFleetYes();
            return;
        }
        double threshold = takeAllThreshold(clientId, standings);
        if (standings < -1.0) {
            log(clientId, "COMPLETING: standings critical — fleet NO.");
            if (standings < -1.5) {
                EventLog.log("STANDINGS_LOW", clientId + " — low standings warning");
            }
            missionProcessor.clickFleetNo();
        } else if (standings < threshold) {
            String bar = Double.isInfinite(threshold) ? "take-all forced" : String.format("< %.1f", threshold);
            log(clientId, "COMPLETING: standings below bar (" + bar + ") — fleet NO.");
            missionProcessor.clickFleetNo();
        } else {
            log(clientId, "COMPLETING: standings healthy — fleet YES.");
            missionProcessor.clickFleetYes();
        }
    }

    /**
     * The take-all (fleet_no) threshold for this client's completion. Normally 0.0, raised to
     * {@link #TAKE_ALL_THRESHOLD_HIGH} when the fleet partner grabbed a mission within the last
     * {@link #PARTNER_GRAB_WINDOW_MIN} minutes, or when the partner's standing is more than
     * {@link #PARTNER_STANDING_GAP} above ours (so the lagging client catches up).
     */
    private double takeAllThreshold(String clientId, double standings) {
        ClientState partner = stateManager.getOtherState(clientId);
        if (partner == null) return 0.0;

        // Partner well ahead of us in standing: take all unconditionally to catch up,
        // even when our own standing already looks healthy (checked before the grab window).
        double partnerStanding = partner.getLastKnownStanding();
        if (!Double.isNaN(standings) && !Double.isNaN(partnerStanding)
                && partnerStanding - standings > PARTNER_STANDING_GAP) {
            log(clientId, "COMPLETING: partner standing >" + PARTNER_STANDING_GAP
                    + " above ours — taking all.");
            return Double.POSITIVE_INFINITY;
        }

        LocalDateTime grab = partner.getLastMissionGrab();
        if (grab.isAfter(LocalDateTime.now().minusMinutes(PARTNER_GRAB_WINDOW_MIN))) {
            log(clientId, "COMPLETING: partner grabbed a mission recently — take-all threshold raised to "
                    + TAKE_ALL_THRESHOLD_HIGH + ".");
            return TAKE_ALL_THRESHOLD_HIGH;
        }
        return 0.0;
    }

    // =======================================================================
    // FACTION AGENT — every-2h check, then run the kernite ("Resources for War")
    // mission: undock → dock at the SOE station → complete → undock → dock home.
    // =======================================================================

    /**
     * FACTION_CHECK — open the faction agent and classify his offer:
     *   - kernite ("Resources for War")  → accept and run it (→ FACTION_TRAVEL).
     *   - "Evolution" (OCR, no template) → leave it for the user, lock the agent for the session.
     *   - anything else                  → decline, but at most once per 4h (else leave the offer).
     * Always returns to IDLE for outcomes that don't start the kernite run.
     */
    private void handleFactionCheck(String clientId, ClientWindowInfo window) {
        ClientState cs = stateManager.getState(clientId);

        // No faction agent icon on screen (e.g. this character has none) — just proceed normally.
        // Re-sweep to confirm: a single match can be a false positive from the OTHER client's
        // window (its live EVE-O Preview thumbnail, or a not-yet-repainted frame after the focus
        // switch). Require the icon to hold across several sweeps before acting on it — otherwise a
        // client with no faction agent gets dragged into the kernite run by the other client's icon.
        if (!missionProcessor.confirmFactionAgentVisible(3, 300)) {
            log(clientId, "FACTION_CHECK: no faction agent icon (confirmed absent on re-sweep) — proceeding normally.");
            stateManager.setState(clientId, ClientState.State.IDLE);
            return;
        }

        // Open the faction agent's conversation.
        missionProcessor.doubleClickFactionAgent();

        // If no live offer is showing, request one.
        if (missionProcessor.detectDialog() != MissionProcessor.DialogState.OFFER) {
            missionProcessor.clickRequestMissionPlain();
            if (!missionProcessor.waitForNewOffer()) {
                log(clientId, "FACTION_CHECK: no offer from faction agent — resuming (leaving dialogs as-is).");
                stateManager.setState(clientId, ClientState.State.IDLE);
                return;
            }
        }

        // 1) Kernite ("Resources for War") — can't accept remotely, so don't click accept here.
        //    Just undock and head to the SOE station; we accept + complete on arrival.
        if (missionProcessor.isKerniteOffer()) {
            log(clientId, "FACTION_CHECK: kernite offer ('Resources for War') — can't accept remotely, undocking to SOE (accept on arrival).");
            EventLog.log("FACTION_KERNITE", clientId + " — kernite (Resources for War) offered, heading to SOE to accept + complete");
            cs.setFactionLeg(ClientState.FactionLeg.TO_SOE);
            stateManager.setState(clientId, ClientState.State.FACTION_TRAVEL);
            return;
        }

        // 2) Evolution (no template — OCR) — leave it, never check the faction agent again this session.
        Mission offer = missionProcessor.readMission(getMissionAreaForClient(clientId));
        String title = (offer != null && offer.getTitle()   != null) ? offer.getTitle().toLowerCase()   : "";
        String raw   = (offer != null && offer.getRawText() != null) ? offer.getRawText().toLowerCase() : "";
        if (title.contains("evolution") || raw.contains("evolution")) {
            parkEvolution(clientId, cs, "FACTION_CHECK");
            return;
        }

        // 3) Anything else — decline, but at most once per 4h.
        boolean neverDeclined = cs.getLastFactionDecline().equals(LocalDateTime.MIN);
        long sinceDecline = Duration.between(cs.getLastFactionDecline(), LocalDateTime.now()).toHours();
        String sinceStr = neverDeclined ? "never" : sinceDecline + "h ago";
        if (neverDeclined || sinceDecline >= FACTION_DECLINE_HOURS) {
            log(clientId, "FACTION_CHECK: unwanted faction mission — declining (last decline " + sinceStr + ").");
            EventLog.log("FACTION_DECLINE", clientId + " — declined faction mission: "
                    + (offer != null && offer.getTitle() != null ? offer.getTitle() : "unknown"));
            missionProcessor.clickDeclineButton();
            // Dismiss both post-decline dialogs with their 'close' BUTTON, not the window X
            // (close_active_window) — the X can tear down the wrong agent window.
            missionProcessor.clickAllCloseButtons();
            stateManager.recordFactionDecline(clientId, LocalDateTime.now());
        } else {
            log(clientId, "FACTION_CHECK: unwanted faction mission but declined < " + FACTION_DECLINE_HOURS
                    + "h ago (" + sinceDecline + "h) — leaving offer, will retry in " + FACTION_CHECK_HOURS + "h.");
        }
        stateManager.setState(clientId, ClientState.State.IDLE);
    }

    /**
     * FACTION_TRAVEL — for the current leg (TO_SOE / TO_HOME): undock (any ship), then
     * right-click the leg's station and Dock. If the station isn't on screen, nudge the
     * camera ('spin') and retry. Docking is driven through WARPING (dockingToStation),
     * which routes back here / to FACTION_COMPLETING once the warp lands.
     */
    private void handleFactionTravel(String clientId, ClientWindowInfo window) {
        ClientState cs = stateManager.getState(clientId);
        ClientState.FactionLeg leg = cs.getFactionLeg();
        if (leg == ClientState.FactionLeg.NONE) {
            log(clientId, "FACTION_TRAVEL: no leg set (restart mid-flow?) — returning to IDLE.");
            stateManager.setState(clientId, ClientState.State.IDLE);
            return;
        }
        String stationKey = (leg == ClientState.FactionLeg.TO_SOE) ? "soe_station" : "home_station";

        // Step 1 — undock (any ship). undockStartTime is reset to MIN on entering FACTION_TRAVEL.
        if (cs.getUndockStartTime().equals(LocalDateTime.MIN)) {
            log(clientId, "FACTION_TRAVEL[" + leg + "]: undocking (any ship).");
            missionProcessor.clickUndock();
            cs.setUndockStartTime(LocalDateTime.now());
            return;
        }

        // Step 2 — wait out the undock animation.
        long undockElapsed = Duration.between(cs.getUndockStartTime(), LocalDateTime.now()).getSeconds();
        if (undockElapsed < UNDOCK_WAIT_SECS) {
            return;
        }

        // Step 3 — right-click the station → Dock. Spin (nudgeView) until it's visible.
        if (!missionProcessor.isStationVisible(stationKey)) {
            long sinceNudge = Duration.between(cs.getLastViewNudge(), LocalDateTime.now()).getSeconds();
            if (sinceNudge >= VIEW_NUDGE_THROTTLE_SECS) {
                log(clientId, "FACTION_TRAVEL[" + leg + "]: " + stationKey + " not visible — nudging view (spin) to find it.");
                missionProcessor.nudgeView();
                cs.setLastViewNudge(LocalDateTime.now());
            }
            return;
        }

        log(clientId, "FACTION_TRAVEL[" + leg + "]: " + stationKey + " visible — right-click → Dock.");
        cs.setDockingToStation(true);
        cs.setWarpStartTime(LocalDateTime.now());
        stateManager.setState(clientId, ClientState.State.WARPING);
        new Thread(() -> missionProcessor.rightClickAndSelect(stationKey, "dock")).start();
    }

    /**
     * FACTION_COMPLETING — docked at the SOE station: turn in the kernite mission with the
     * same standings + fleet-reward flow as the normal agent, then head home (→ FACTION_TRAVEL,
     * TO_HOME leg).
     */
    private void handleFactionCompleting(String clientId, ClientWindowInfo window) {
        ClientState cs = stateManager.getState(clientId);
        if (cs.getFactionLeg() == ClientState.FactionLeg.NONE) {
            log(clientId, "FACTION_COMPLETING: no leg set (restart mid-flow?) — returning to IDLE.");
            stateManager.setState(clientId, ClientState.State.IDLE);
            return;
        }

        // Wait until docked — the faction agent's portrait is visible in the station.
        if (!missionProcessor.isFactionAgentVisible()) {
            long waiting   = Duration.between(cs.getLastStateChange(), LocalDateTime.now()).getSeconds();
            long sinceDock = Duration.between(cs.getLastCompletingDock(), LocalDateTime.now()).getSeconds();
            if (waiting >= 60 && sinceDock >= 60 && missionProcessor.isDockVisible()) {
                log(clientId, "FACTION_COMPLETING: 60s without faction agent — dock visible, clicking dock (twice) in case we never docked.");
                missionProcessor.clickDockTwice();
                cs.setLastCompletingDock(LocalDateTime.now());
                return;
            }
            log(clientId, "FACTION_COMPLETING: faction agent not visible yet — waiting.");
            try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return;
        }

        // Accept on the spot (we couldn't accept remotely), then complete immediately.
        missionProcessor.doubleClickFactionAgent();
        log(clientId, "FACTION_COMPLETING: accepting kernite at the SOE station.");
        missionProcessor.clickAcceptButton();

        // Turn in: read standings, complete, fleet reward, OK.
        missionProcessor.clickAgentStandings();
        double standings = missionProcessor.readAgentStandings();
        if (!Double.isNaN(standings)) {
            stateManager.recordStanding(clientId, standings);   // persist — survives restarts
        }
        missionProcessor.doubleClickFactionAgent(); // refocus agent dialog (standings covers complete_mission)
        missionProcessor.clickCompleteMission();
        chooseFleetReward(clientId, standings);
        missionProcessor.waitAndClickOk();

        EventLog.log("FACTION_COMPLETE", clientId + " — kernite (Resources for War) turned in");
        log(clientId, "FACTION_COMPLETING: kernite turned in — undocking and heading home.");

        // Head back to the home station, then resume normal mission checking.
        cs.setFactionLeg(ClientState.FactionLeg.TO_HOME);
        stateManager.setState(clientId, ClientState.State.FACTION_TRAVEL);
    }

    // -----------------------------------------------------------------------
    // UNDOCKING — wait for undock animation, verify we left the hangar.
    // Runs only when this client is in focus (via processClient).
    // -----------------------------------------------------------------------

    private void handleUndocking(String clientId, ClientWindowInfo window) {
        ClientState cs = stateManager.getState(clientId);
        long elapsed = Duration.between(cs.getUndockStartTime(), LocalDateTime.now()).getSeconds();

        // Always let the undock animation window pass first. agent_icon disappears the
        // instant undock begins — well before the ship is actually out and ready to act —
        // so checking it earlier would send us into TRAVEL mid-animation.
        if (elapsed < UNDOCK_WAIT_SECS) {
            return;
        }

        // Window elapsed — now confirm we actually left the hangar. agent_icon still visible
        // means the undock click didn't take (still docked) → re-click it and re-wait.
        if (missionProcessor.findTemplate("agent_icon") != null) {
            log(clientId, "UNDOCKING: agent_icon still visible after " + elapsed + "s — still docked, re-clicking undock.");
            missionProcessor.clickUndock();
            cs.setUndockStartTime(LocalDateTime.now());
            // Stay on this client — no switching during the re-undock animation.
            return;
        }

        // Window elapsed and agent_icon gone — we're out in space. Before heading to the
        // mission, confirm we actually undocked in a SHIP. If boarding silently failed (e.g.
        // 'Board Ship' never landed because the mission's ship ran out), we'd be flying a
        // capsule — which then wedges forever at the frigate-only mission gate. The pod check
        // is reliable in space (no_ship module slivers + eject probe), so catch it here:
        // redock, quit the mission we have no ship for, and pick another.
        // Guard against a transient false positive: right after undock the ship HUD (module
        // slivers) can still be rendering, so stage 1 misses and the eject probe reads PODDED
        // even though a ship was boarded fine. Confirm with a second read after a short settle
        // before committing to the redock-and-requit flow.
        if (missionProcessor.isFlyingPod()) {
            log(clientId, "UNDOCKING: pod suspected — re-checking after HUD settle before redocking.");
            try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if (!missionProcessor.isFlyingPod()) {
                log(clientId, "UNDOCKING: second pod check clear — HUD was still loading, in a ship. Proceeding to TRAVEL.");
                stateManager.setState(clientId, ClientState.State.TRAVEL);
                return;
            }
            String mTitle = cs.getCurrentMission() != null ? cs.getCurrentMission().getTitle() : "unknown";
            log(clientId, "*** ALERT: undocked in a POD — no ship for this mission. Redocking to quit + re-pick.");
            EventLog.log("SHIP_NOT_FOUND", clientId + " — undocked in pod (mission: " + mTitle + ")");
            soundAlert(NO_SHIP_ALERT);
            if (missionProcessor.emergencyRedock()) {
                log(clientId, "UNDOCKING: redocked — quitting the mission we have no ship for, picking another.");
                missionProcessor.quitMission();
                cs.setCurrentMission(null);
                stateManager.setState(clientId, ClientState.State.CHECKING_MISSIONS);
            } else {
                // Couldn't get back into the hangar — don't fly the pod to the gate. Stop here.
                parkClient(clientId, "undocked in pod, redock failed — no ship for mission");
            }
            return;
        }

        log(clientId, "UNDOCKING: agent_icon gone after " + elapsed + "s — undocked, proceeding to TRAVEL.");
        stateManager.setState(clientId, ClientState.State.TRAVEL);
    }

    // -----------------------------------------------------------------------
    // WARPING — poll warp_active until it disappears, then return to IDLE.
    //
    // Two paths:
    //   • Normal: 4s grace, bubble + warp_active verification, retry, redock.
    //   • Post-miss simplified (jumpCount >= 6 + TO_MISSION): 1s grace, no
    //     verification — just hand focus off and trust the click. Activated
    //     only after the missed-warp_to_location safety net has fired.
    // Both paths share the warp_active landing poll below.
    // -----------------------------------------------------------------------

    /**
     * The navigation element we expect to see in space once a warp lands, given the travel
     * phase: warp_to_location / activate_gate (mission arrival) or a stargate when outbound;
     * dock or a stargate when returning. Returns the matched template key, or null if none
     * has rendered yet. (Mission/dock elements are checked first — stargate is always visible
     * in-system and must not win over the mission-specific UI.)
     */
    private String detectLandingElement(ClientState cs) {
        // One screenshot per branch. RETURNING_HOME excludes the stargate on purpose: a stargate
        // bracket is present in every system (including home) and renders before the DOCK button,
        // so if it ended the settle we'd conclude "no dock" and jump a gate the instant we land
        // home. Wait for dock/agent_icon (the home destination); a stargate-only grid keeps
        // settling so the dock has time to render (and a mid-route gate is fully clickable first).
        if (cs.getTravelPhase() == ClientState.TravelPhase.RETURNING_HOME) {
            return missionProcessor.detectDockOrAgent();   // dock / agent_icon (NOT stargate)
        }
        // Same reasoning outbound: the stargate bracket renders before warp_to_location, so ending
        // the settle on it would jump a gate the instant we land in the mission system. Wait for the
        // mission-specific UI only; a stargate-only grid keeps settling the full LANDING_SETTLE_SECS.
        return missionProcessor.detectMissionArrivalUI();  // activate_gate / warp_to_location (NOT stargate)
    }

    /**
     * Re-issue the action that should have started the current warp, when the warp-start check
     * failed (bubble present or warp_active not showing). We don't track which control kicked off
     * each WARPING entry, so pick the most likely one from current context: docking → DOCK,
     * else a visible stargate → jump, else a visible warp-to-location → warp.
     *
     * Returns false when nothing was on screen to click — usually a session change mid gate jump,
     * where warp_active is missing simply because the grid hasn't loaded yet.
     */
    private boolean reissueWarp(String clientId, ClientState cs) {
        if (cs.isDockingToStation()) {
            // Re-issue the dock with the button-first, station-backup helper. A bare "dock"
            // click silently no-ops when the button isn't on screen — that used to log a
            // "Clicking DOCK" success and then drop us into a bogus bubble escape with no bubble.
            log(clientId, "WARPING: re-issuing DOCK (button, station backup).");
            // Count re-issues that found nothing to click, so a screen with no DOCK button and no
            // station can't spin here forever (redockOrBubbleEscape drops to IDLE at the cap).
            new Thread(() -> {
                if (missionProcessor.dockWithStationBackup()) {
                    cs.setDockReissueMissCount(0);
                } else {
                    cs.setDockReissueMissCount(cs.getDockReissueMissCount() + 1);
                }
            }).start();
        } else if (missionProcessor.findTemplate("stargate") != null) {
            log(clientId, "WARPING: re-issuing gate jump.");
            new Thread(() -> missionProcessor.rightClickAndSelect("stargate", "jump")).start();
        } else if (missionProcessor.findTemplate("warp_to_location") != null) {
            log(clientId, "WARPING: re-issuing warp-to-location.");
            new Thread(() -> missionProcessor.clickWarpToLocation()).start();
        } else {
            return false;
        }
        return true;
    }

    /**
     * Re-issue a safe-spot evade warp that failed to engage. Prefer the jcv/kly bookmark warp
     * (warpToSafeSpot); if neither marker is present, fall back to the generic safe_spot warp.
     */
    private void reissueSafeSpotWarp(String clientId) {
        if (!missionProcessor.warpToSafeSpot()) {
            missionProcessor.warpToGenericSafeSpot();
        }
    }

    /**
     * Handle an insta-dock attempt whose dock warp never engaged. Counts the miss; while under
     * {@link #INSTA_DOCK_MAX_MISSES} it returns false so the caller stays in IDLE and retries the
     * insta-dock next tick. Once the cap is hit it stops retrying, issues a direct dock (DOCK
     * button / right-click station → Dock) and returns true so the caller commits to WARPING.
     */
    private boolean instaDockFallback(String clientId, ClientState cs) {
        cs.setInstaDockMissCount(cs.getInstaDockMissCount() + 1);
        if (cs.getInstaDockMissCount() < INSTA_DOCK_MAX_MISSES) {
            log(clientId, "IDLE[RETURNING_HOME]: insta-dock warp not engaged (miss "
                    + cs.getInstaDockMissCount() + "/" + INSTA_DOCK_MAX_MISSES + ") — staying to retry.");
            return false;
        }
        log(clientId, "IDLE[RETURNING_HOME]: insta-dock warp not engaged " + INSTA_DOCK_MAX_MISSES
                + "x — falling back to direct dock (button / right-click station → Dock).");
        missionProcessor.dockWithStationBackup();
        return true;
    }

    /**
     * Route a finished dock to its next state — shared by the normal warp-gone dock completion
     * and the docked-detected-while-verifying path (agent_icon in station, no pod_station).
     * Faction kernite legs route to their own states; normal mission docks go to COMPLETING.
     */
    private void finishDockArrival(String clientId, ClientState cs, String how) {
        cs.setDockingToStation(false);
        // Faction kernite run docks route differently than normal mission docks.
        if (cs.getFactionLeg() == ClientState.FactionLeg.TO_SOE) {
            log(clientId, "WARPING[FACTION]: docked at SOE station (" + how + ") — transitioning to FACTION_COMPLETING.");
            stateManager.setState(clientId, ClientState.State.FACTION_COMPLETING);
            return;
        }
        if (cs.getFactionLeg() == ClientState.FactionLeg.TO_HOME) {
            log(clientId, "WARPING[FACTION]: warp home complete (" + how + ") — waiting for dock confirmation before resuming.");
            cs.setFactionLeg(ClientState.FactionLeg.NONE);
            cs.setTravelPhase(ClientState.TravelPhase.NONE);
            cs.setFactionResumePending(true); // IDLE waits for agent_icon, skips travel-resume meanwhile
            stateManager.setState(clientId, ClientState.State.IDLE);
            return;
        }
        log(clientId, "WARPING: dock complete (" + how + ") — transitioning to COMPLETING.");
        stateManager.setState(clientId, ClientState.State.COMPLETING);
        // Check once for the agent icon before yielding — we may already be in station with
        // the icon loaded, in which case keep focus and let COMPLETING run next tick.
        if (missionProcessor.detectCompletingUI() != null) {
            log(clientId, "COMPLETING: agent icon already visible — holding focus to complete now.");
            return;
        }
        // The agent icon can take a while to appear after docking (station UI load).
        // Hand the foreground to the other client meanwhile rather than spinning on the
        // wait — the watchdog brings focus back here once it's time to complete.
        ClientWindowInfo docked = findOtherClient(clientId);
        if (docked != null) {
            log(clientId, "COMPLETING: docked — switching focus to " + docked.getClientId() + " while the agent icon loads.");
            bringToForeground(docked);
        }
    }

    /**
     * Terminal warp-start failure handling. If a station is in reach, dock back (direct redock).
     * While deliberately docking we NEVER bubble-escape — that jumps a gate and leaves the system;
     * instead we re-issue the dock and, if a bubble camp pods us, route to pod-death and wait out
     * the cooldown. Only non-docking warps (safe-spot / travel) fall through to the bubble escape.
     */
    private void redockOrBubbleEscape(String clientId, ClientState cs, boolean held, String why) {
        // Whatever gate jump was in flight is not happening — drop it, so its increment can't be
        // banked later by an unrelated warp (a safe-spot evade, say) confirming instead.
        cs.setPendingGateJump(false);
        boolean stationInReach = missionProcessor.findTemplate("home_station") != null;
        boolean returningHome = cs.getTravelPhase() == ClientState.TravelPhase.RETURNING_HOME;

        // Returning home to deliver the mission: the home station is the destination, not an
        // emergency. There is no "emergency redock" here — we just need to dock, which turns in
        // the mission. Route straight into the docking block (agent_icon → finish; else re-issue
        // the dock and wait) so a stuck warp keeps docking instead of parking the client.
        if (stationInReach && returningHome) {
            if (!cs.isDockingToStation()) {
                log(clientId, "WARPING: " + why + " while returning home — station in reach, docking to deliver (no emergency redock).");
                cs.setDockingToStation(true);
            }
            // fall through to the docking block below
        } else if (stationInReach) {
            log(clientId, "WARPING: " + why + " — station in reach, emergency redock!");
            if (missionProcessor.emergencyRedock()) {
                LocalDateTime cooldownEnd = LocalDateTime.now().plusMinutes(POD_COOLDOWN_MINUTES);
                stateManager.setPodCooldownEnd(cooldownEnd);
                log(clientId, (held ? "BUBBLE_CAMP: held at station (jam/bubble)" : "WARPING: stuck warp at station")
                        + " — redocked, " + POD_COOLDOWN_MINUTES + "-minute global cooldown before retry.");
                cs.setPendingPodStationCheck(true);
                stateManager.setState(clientId, ClientState.State.POD_COOLDOWN);
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "WARPING: emergency redock done — switching to " + other.getClientId() + ".");
                    bringToForeground(other);
                }
                return;
            }
            // Redock not confirmed. When docking, don't park — fall through to the wait-it-out below.
            if (!cs.isDockingToStation()) {
                parkClient(clientId, "emergency redock not confirmed — not in station");
                return;
            }
            log(clientId, "WARPING: " + why + " — redock not confirmed while docking; waiting it out (no park).");
        }

        // Docking: never bubble-escape out of the system. If we've been podded, route to pod-death
        // (respawn/self-destruct → cooldown); otherwise re-issue the dock and wait it out.
        if (cs.isDockingToStation()) {
            if (missionProcessor.findTemplate("agent_icon") != null) {
                // agent_icon means we're in station — but a SUCCESSFUL dock looks exactly the
                // same. Only a capsule in the hangar (pod_station / capsule) means we were
                // podded; without it the dock simply landed (e.g. a short-range dock whose warp
                // templates never showed) — proceed as a normal dock arrival, not a pod death.
                // NOTE: pod_station.png is currently missing from templates/ (silently skipped),
                // so capsule is the effective check until it's added.
                if (missionProcessor.findTemplate("pod_station") != null
                        || missionProcessor.findTemplate("capsule") != null) {
                    log(clientId, "WARPING: " + why + " while docking — agent_icon + capsule in hangar, podded/respawned at station.");
                    handlePodDeath(clientId, false);
                    return;
                }
                log(clientId, "WARPING: " + why + " while docking — agent_icon with no pod_station: docked normally, proceeding.");
                finishDockArrival(clientId, cs, "agent_icon in station, no pod");
                return;
            }
            if (missionProcessor.isFlyingPod()) {
                log(clientId, "WARPING: " + why + " while docking — podded in space, self-destructing.");
                handlePodDeath(clientId, true);
                return;
            }
            // Nothing to click for several re-issues running: handleWarping's retry and this branch
            // re-issue each other (each resetting warpRetryCount), so without a cap this loops for
            // good. Hand back to IDLE — the travel phase is kept, so it docks once DOCK renders.
            if (cs.getDockReissueMissCount() >= DOCK_REISSUE_MISS_LIMIT) {
                log(clientId, "WARPING: " + why + " while docking — no DOCK button or station for "
                        + DOCK_REISSUE_MISS_LIMIT + " re-issues; dropping to IDLE to re-evaluate.");
                cs.setDockReissueMissCount(0);
                cs.setDockingToStation(false);
                cs.setWarpRetryCount(0);
                stateManager.setState(clientId, ClientState.State.IDLE);
                return;
            }
            log(clientId, "WARPING: " + why + " while docking — re-issuing dock and waiting it out (no bubble escape).");
            reissueWarp(clientId, cs);               // dockingToStation → dockWithStationBackup
            cs.setWarpRetryCount(0);                  // verify the re-issued dock afresh next cycle
            cs.setWarpStartTime(LocalDateTime.now());
            return;
        }

        log(clientId, "WARPING: " + why + " — no station in reach, starting bubble escape.");
        cs.setBubbleEscapeStep(1);
    }

    /**
     * Bank a gate jump once its warp is confirmed. Called from every WARPING path that has
     * established the jump actually engaged — never from the issue site, so a right-click that
     * missed the stargate leaves our distance-from-home untouched instead of silently drifting.
     *
     * Direction follows the leg: outbound each jump takes us one further from the station,
     * returning retraces that same route so each jump takes us one closer. Only gate jumps get
     * here — safe-spot evades, the warp to the mission site and dock warps never set the flag.
     */
    private void confirmGateJump(String clientId, ClientState cs) {
        if (!cs.isPendingGateJump()) return;
        cs.setPendingGateJump(false);
        if (cs.getTravelPhase() == ClientState.TravelPhase.RETURNING_HOME) {
            cs.decrementJumpCount();
            log(clientId, "JUMP confirmed (homeward) — now " + cs.getJumpCount() + " jump(s) from home.");
        } else {
            cs.incrementJumpCount();
            log(clientId, "JUMP confirmed (outbound) — now " + cs.getJumpCount() + " jump(s) from home.");
        }
    }

    private void handleWarping(String clientId, ClientWindowInfo window) {
        ClientState cs = stateManager.getState(clientId);
        long elapsed = Duration.between(cs.getWarpStartTime(), LocalDateTime.now()).getSeconds();

        boolean firstCheck = cs.getLastWarpCheck().isBefore(LocalDateTime.MIN.plusSeconds(1));

        // --- Infested escape jam check ---
        // For the Infested return warp, the rats can jam you as you initiate the warp.
        // Watch for 'jamed' over the first 3s instead of the normal bubble/switch; if
        // jammed, burn away (double-click stargate + 's') and retry the jump.
        if (cs.isJamCheckOnWarp()) {
            if (missionProcessor.findTemplate("jamed") != null) {
                boolean pressProp = !cs.isJamPropPressed();
                log(clientId, "WARPING[INFESTED]: JAMED — burn away (double-click stargate"
                        + (pressProp ? " + 's'" : ", prop already on") + ") + retry jump.");
                missionProcessor.doubleClickTemplate("stargate");
                if (pressProp) {
                    missionProcessor.keyPress("s"); // prop mod — press once only; every other press toggles it off
                    cs.setJamPropPressed(true);
                }
                try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                new Thread(() -> missionProcessor.rightClickAndSelect("stargate", "jump")).start();
                cs.setWarpStartTime(LocalDateTime.now()); // restart warp + jam window
                return;
            }
            if (elapsed < 5) {
                return; // keep watching for a jam during the 5s window
            }
            cs.setJamCheckOnWarp(false);
            log(clientId, "WARPING[INFESTED]: no jam after 5s — resuming normal warp.");
        }

        // Late-set-destination back-jump: destination was missed on undock and re-clicked one jump
        // late, so this jump goes back 1 jump and lands on top of the gate — no warp tunnel, so the
        // normal warp_active check would misread it as a failed warp and redock. Trust the jump:
        // press the prop mod ('s'), hand off focus, hold a 15s safe wait, then resume normal nav.
        if (cs.isSimplifyNextWarp()) {
            if (elapsed < 1) return; // let the jump issue
            if (firstCheck) {
                cs.setLastWarpCheck(LocalDateTime.now());
                missionProcessor.keyPress("s"); // prop mod — burn off the gate
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    log(clientId, "WARPING[LATE-DEST]: back-jump issued — pressed 's', switching focus to " + other.getClientId() + " for a 15s safe wait.");
                    bringToForeground(other);
                } else {
                    log(clientId, "WARPING[LATE-DEST]: back-jump issued — pressed 's', no other client, holding 15s safe wait.");
                }
                return;
            }
            if (elapsed < UNDOCK_WAIT_SECS) {
                return; // 15s safe wait — no warp_active verification while the jump settles
            }
            cs.setSimplifyNextWarp(false);
            log(clientId, "WARPING[LATE-DEST]: 15s safe wait done — resuming normal navigation.");
            stateManager.setState(clientId, ClientState.State.IDLE);
            cs.setJustExitedWarp(true);
            return;
        }

        // Insta-dock (enemy in local) pre-verified path: instaDock() already slept ~7s and
        // verified warp_active before firing Ctrl+S autopilot, so the dock warp is confirmed on
        // entry. Skip the WARPING grace + re-verification and hand focus off on the first tick;
        // the warp_active landing poll (→ COMPLETING) still runs when focus returns.
        if (cs.isWarpPreVerified()) {
            if (firstCheck) {
                cs.setWarpPreVerified(false);
                cs.setLastWarpCheck(LocalDateTime.now());
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    if (holdWarpFocus(other)) {
                        log(clientId, "WARPING: insta-dock warp pre-verified — other client also warping, holding focus.");
                        return;
                    }
                    log(clientId, "WARPING: insta-dock warp pre-verified — switching focus to: " + other.getClientId());
                    bringToForeground(other);
                } else {
                    log(clientId, "WARPING: insta-dock warp pre-verified — no other clients to switch to.");
                }
                return;
            }
            // Fall through to the warp_active landing poll below on later ticks.
        }

        // Post-miss simplified path: once the IDLE[TO_MISSION] safety net has fired
        // (jumpCount >= 6 + set_destination visible), subsequent warps trust the click
        // and skip bubble/warp_active verification. 1s grace, then hand focus off.
        boolean postMissSimplified =
                cs.getTravelPhase() == ClientState.TravelPhase.TO_MISSION
                && cs.getJumpCount() >= 6;

        if (postMissSimplified) {
            if (elapsed < 1) return;
            if (firstCheck) {
                long sinceActive = Duration.between(cs.getLastCombatOrCheck(), LocalDateTime.now()).toMinutes();
                if (sinceActive >= 10 && !cs.isDockingToStation()) {   // skip while dock-warping — HUD hidden, false pod reads
                    log(clientId, "WARPING[POST-MISS]: " + sinceActive + "m without combat/mission-check — running warp-entry pod check.");
                    if (missionProcessor.isFlyingPod()) {
                        log(clientId, "WARPING[POST-MISS]: no_ship on warp entry — podded, self-destructing.");
                        handlePodDeath(clientId, true);
                        return;
                    }
                }
                // This path deliberately skips warp_active verification (see above), so the jump is
                // banked on the issued click — the best signal available here.
                confirmGateJump(clientId, cs);
                cs.setLastWarpCheck(LocalDateTime.now());
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    if (holdWarpFocus(other)) {
                        log(clientId, "WARPING[POST-MISS]: jump issued — other client also warping, holding focus.");
                        return;
                    }
                    log(clientId, "WARPING[POST-MISS]: jump issued — switching focus to: " + other.getClientId());
                    bringToForeground(other);
                } else {
                    log(clientId, "WARPING[POST-MISS]: jump issued — no other clients to switch to.");
                }
                return;
            }
            // Fall through to the warp_active landing poll below.
        } else {
            // Normal path: full warp-engagement verification.
            // Wait for the warp/jump to engage before checking for bubble/warp. Normally 5s:
            // warp_active (warp entering) can show early, but a warp jam/disruption often lands
            // a beat later, so we hold off the check to avoid reading a false "warp took" before a
            // late jam appears. On the first jump right after undocking — home station still in
            // front of us — the warp from a standstill takes even longer to engage; in that case
            // wait up to 7s, so we don't prematurely read "warp not active" and redock.
            // Safe-spot evade waits a touch shorter than a normal warp (3s vs 5s) but still
            // long enough not to misread a still-spinning-up warp as stuck; the home-station
            // extension below still applies.
            long grace = cs.isSafeSpotWarp() ? 3 : 5;
            if (elapsed < grace) {
                return;
            }
            // The extra home-station wait (grace→7s) only exists to give a standstill warp time to
            // spin up before we'd prematurely read "warp not active". If warp_active is already
            // showing (docking/undocking warp took), don't sit out the rest — proceed to switch now.
            if (elapsed < 7
                    && !missionProcessor.isWarpActive()
                    && missionProcessor.findTemplate("home_station") != null) {
                return;
            }

            // --- Bubble escape loop (step-based, survives across ticks) ---
            int bubbleStep = cs.getBubbleEscapeStep();
            if (bubbleStep > 0) {
                // If the agent icon is showing we're docked at the home station — we got podded
                // (respawned in station), not escaping the bubble. That's why the stargate is
                // never found. Stop the escape loop and run the pod-death handler instead of
                // spinning on a gate that isn't on screen.
                if (missionProcessor.findTemplate("agent_icon") != null) {
                    log(clientId, "WARPING[BUBBLE]: agent_icon visible — podded, respawned at station. Ending escape.");
                    cs.setBubbleEscapeStep(0);
                    handlePodDeath(clientId, false);
                    return;
                }
                switch (bubbleStep) {
                    case 1: {
                        int attempts = cs.getBubbleEscapeAttempts() + 1;
                        if (attempts > 2) {
                            // Don't spin on the escape — after 2 tries, pod-check then resume travel.
                            log(clientId, "WARPING[BUBBLE]: escape failed after 2 attempts — pod check, then resuming travel.");
                            cs.setBubbleEscapeStep(0);
                            if (missionProcessor.isFlyingPod()) {
                                log(clientId, "WARPING[BUBBLE]: no_ship — podded, self-destructing.");
                                handlePodDeath(clientId, true);
                                return;
                            }
                            // Not podded, but we never warped — don't stay in WARPING assuming we did.
                            // Hand back to IDLE so it re-drives the leg we were on (TO_MISSION /
                            // RETURNING_HOME) per the current TravelPhase, re-finding the stargate /
                            // dock and continuing the route instead of polling for a phantom warp.
                            cs.setWarpRetryCount(0);
                            cs.setJustExitedWarp(true);
                            log(clientId, "WARPING[BUBBLE]: not podded — returning to IDLE to resume "
                                    + cs.getTravelPhase() + " navigation.");
                            stateManager.setState(clientId, ClientState.State.IDLE);
                            return;
                        }
                        cs.setBubbleEscapeAttempts(attempts);
                        log(clientId, "WARPING[BUBBLE] step 1 (attempt " + attempts + "/2): shift+s, double-click stargate, press s.");
                        missionProcessor.shiftKeyPress("s");
                        missionProcessor.doubleClickTemplate("stargate");
                        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        missionProcessor.keyPress("s");
                        cs.setBubbleEscapeStart(LocalDateTime.now());
                        cs.setBubbleEscapeStep(2);
                        return;
                    }
                    case 2: {
                        long bubbleElapsed = Duration.between(cs.getBubbleEscapeStart(), LocalDateTime.now()).getSeconds();
                        if (bubbleElapsed < 6) {
                            log(clientId, "WARPING[BUBBLE] step 2: waiting (" + bubbleElapsed + "s / 6s).");
                            return;
                        }
                        log(clientId, "WARPING[BUBBLE] step 2: 6s wait complete — attempting jump.");
                        cs.setBubbleEscapeStep(3);
                        break;
                    }
                    case 3: {
                        missionProcessor.clickEmptySpace(); // dismiss the double-click tooltip
                        boolean jumped = false;
                        for (int attempt = 1; attempt <= 3; attempt++) {
                            missionProcessor.rightClickTemplate("stargate");
                            try { Thread.sleep(400); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                            if (missionProcessor.findTemplate("jump") != null) {
                                log(clientId, "WARPING[BUBBLE] step 3: jump menu visible — clicking jump (attempt " + attempt + ").");
                                missionProcessor.clickTemplate("jump");
                                jumped = true;
                                break;
                            }
                            log(clientId, "WARPING[BUBBLE] step 3: no jump menu after right-click (attempt " + attempt + "/3) — retrying.");
                        }
                        if (!jumped) {
                            log(clientId, "WARPING[BUBBLE] step 3: jump menu never appeared — re-approaching gate.");
                            cs.setBubbleEscapeStep(1);
                            return;
                        }
                        cs.setBubbleEscapeStart(LocalDateTime.now());
                        cs.setBubbleEscapeStep(4);
                        return;
                    }
                    case 4: {
                        long jumpElapsed = Duration.between(cs.getBubbleEscapeStart(), LocalDateTime.now()).getSeconds();
                        if (jumpElapsed < 4) {
                            return;
                        }
                        if (missionProcessor.isWarpActive()) {
                            log(clientId, "WARPING[BUBBLE]: warp_active — escaped, resuming normal warp check.");
                            cs.setBubbleEscapeStep(0);
                            cs.setWarpStartTime(LocalDateTime.now());
                            cs.setLastWarpCheck(LocalDateTime.now());
                        } else {
                            log(clientId, "WARPING[BUBBLE]: warp not active — repeating escape.");
                            cs.setBubbleEscapeStep(1);
                        }
                        return;
                    }
                }
                return;
            }

            if (firstCheck) {
                // Warp-entry pod check — only if 10min+ without combat/mission-check, and NOT while
                // dock-warping: during the docking transition the ship HUD (no_ship sliver) is hidden
                // as the station UI loads, so isFlyingPod() false-reads it as a pod and loops on a
                // bogus self-destruct. Real pod deaths near station are caught by the cooldown/agent_icon paths.
                long sinceActive = Duration.between(cs.getLastCombatOrCheck(), LocalDateTime.now()).toMinutes();
                if (sinceActive >= 10 && !cs.isDockingToStation()) {
                    log(clientId, "WARPING: " + sinceActive + "m without combat/mission-check — running warp-entry pod check.");
                    if (missionProcessor.isFlyingPod()) {
                        log(clientId, "WARPING: no_ship on warp entry — podded, self-destructing.");
                        handlePodDeath(clientId, true);
                        return;
                    }
                }

                // The warp only "took" if there's no disruption bubble AND warp_active is showing.
                boolean bubble  = missionProcessor.findTemplate("bubble_text") != null;
                boolean warping = missionProcessor.isWarpActive();
                if (bubble || !warping) {
                    boolean jammed = missionProcessor.findTemplate("jamed") != null;
                    if (jammed || bubble) {
                        String reason = jammed ? "jammed" : "bubble present";
                        log(clientId, "WARPING: " + reason + " — held, not retrying, redocking immediately.");
                        redockOrBubbleEscape(clientId, cs, true, reason);
                        return;
                    }

                    // Safe-spot evade warp that never engaged (the bookmark click likely missed):
                    // nudge the camera to refresh the frame and re-issue the safe-spot warp, up to
                    // twice. If it still won't engage, treat it like any other stuck warp —
                    // redock if a station is in reach, otherwise bubble-escape.
                    if (cs.isSafeSpotWarp()) {
                        if (cs.getWarpRetryCount() < 2) {
                            cs.setWarpRetryCount(cs.getWarpRetryCount() + 1);
                            log(clientId, "WARPING: safe-spot warp not engaging — nudge + retry " + cs.getWarpRetryCount() + "/2.");
                            missionProcessor.nudgeView();
                            reissueSafeSpotWarp(clientId);
                            cs.setWarpStartTime(LocalDateTime.now()); // restart the wait, stay in first-check
                            return;
                        }
                        // Warp still won't engage after 2 retries — treat it like any other stuck
                        // warp: redock if a station is in reach, otherwise bubble-escape out.
                        log(clientId, "WARPING: safe-spot warp failed after 2 retries — redock / bubble escape.");
                        cs.setSafeSpotWarp(false);
                        cs.setWarpRetryCount(0);
                        redockOrBubbleEscape(clientId, cs, false, "safe-spot warp not engaging after 2 retries");
                        return;
                    }

                    if (cs.getWarpRetryCount() < 1) {
                        if (!reissueWarp(clientId, cs)) {
                            // Nothing on screen to click — almost always a session change mid gate
                            // jump, where warp_active is missing only because the grid is still
                            // loading. Wait it out like the jump wait instead of burning the retry.
                            if (cs.getWarpReissueMissCount() < WARP_REISSUE_MISS_LIMIT) {
                                cs.setWarpReissueMissCount(cs.getWarpReissueMissCount() + 1);
                                log(clientId, "WARPING: no warp control visible (jump still loading?) — waiting ("
                                        + cs.getWarpReissueMissCount() + "/" + WARP_REISSUE_MISS_LIMIT + ").");
                                cs.setWarpStartTime(LocalDateTime.now());
                                return;
                            }
                            log(clientId, "WARPING: no warp control visible after " + WARP_REISSUE_MISS_LIMIT
                                    + " waits — redock / bubble escape.");
                            redockOrBubbleEscape(clientId, cs, false, "no warp control visible to re-issue");
                            return;
                        }
                        cs.setWarpReissueMissCount(0);
                        cs.setWarpRetryCount(cs.getWarpRetryCount() + 1);
                        log(clientId, "WARPING: warp_active not showing — warp re-issued (retry " + cs.getWarpRetryCount() + "/1).");
                        cs.setWarpStartTime(LocalDateTime.now()); // restart the 4s wait, stay in first-check
                        return;
                    }

                    redockOrBubbleEscape(clientId, cs, false, "warp_active not showing after retry");
                    return;
                }

                cs.setSafeSpotWarp(false); // warp engaged — clear the evade-retry flag
                confirmGateJump(clientId, cs); // warp_active with no bubble — the jump took
                cs.setLastWarpCheck(LocalDateTime.now());
                ClientWindowInfo other = findOtherClient(clientId);
                if (other != null) {
                    if (holdWarpFocus(other)) {
                        log(clientId, "WARPING: warp active, no bubble — other client also warping, holding focus.");
                        return;
                    }
                    log(clientId, "WARPING: warp active, no bubble — switching focus to: " + other.getClientId());
                    bringToForeground(other);
                } else {
                    log(clientId, "WARPING: warp active, no bubble — no other clients to switch to.");
                }
                return;
            }
        }

        // Periodic 'general' overview reset (every 5m, throttled by lastPodCheck).
        runPeriodicOverviewReset(clientId, cs);

        // Poll every 2s for warp_active — but if this client just regained the foreground, check
        // immediately rather than waiting out the throttle it left behind when it yielded focus.
        // (Only the first tick after refocus is exempt; setLastWarpCheck below re-arms the throttle,
        // so self-ticks during the handoff don't spam warp checks / re-yields.)
        long sinceLastCheck = Duration.between(cs.getLastWarpCheck(), LocalDateTime.now()).getSeconds();
        if (sinceLastCheck < 2 && !clientRefocused) {
            return;
        }
        cs.setLastWarpCheck(LocalDateTime.now());

        // A single missed warp_active frame (animation flicker / OCR miss) shouldn't end the
        // warp — confirm with a quick second look 500ms later before concluding it landed.
        boolean warpGone = !missionProcessor.isWarpActive();
        if (warpGone) {
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            warpGone = !missionProcessor.isWarpActive();
            if (!warpGone) {
                log(clientId, "WARPING: warp_active missed once but re-confirmed — still warping.");
            }
        }

        if (warpGone) {
            if (cs.isDockingToStation()) {
                finishDockArrival(clientId, cs, "warp complete after " + elapsed + "s");
                return;
            }
            log(clientId, "WARPING: warp_active gone after " + elapsed + "s — transitioning to IDLE.");
            stateManager.setState(clientId, ClientState.State.IDLE);
            cs.setJustExitedWarp(true);
            // A long warp has already settled by the time it ends — skip the landing safe wait and
            // keep focus here rather than handing it away. justExitedWarp stays set so the justLanded
            // route-correction path (close windows, set_destination re-check) still runs.
            if (elapsed > LONG_WARP_NO_SETTLE_SECS) {
                cs.setSkipLandingSettle(true);
                log(clientId, "WARPING: warp landed after " + elapsed + "s (>" + LONG_WARP_NO_SETTLE_SECS
                        + "s) — skipping landing safe wait, holding focus.");
                return;
            }
            cs.setSkipLandingSettle(false);
            // Hold focus through the transition — don't switch here. The IDLE landing safe wait
            // gate also holds focus during its 6s settle so this client can watch its own grid
            // render. justExitedWarp stays set so the settle + justLanded path run.
            log(clientId, "WARPING: warp landed after " + elapsed + "s — transitioning to IDLE; IDLE holds the "
                    + LANDING_SETTLE_SECS + "s landing safe wait.");
        } else {
            // Still warping — hand focus to the other client immediately (was throttled to a
            // ~2s window every 15s, which left this client hogging focus when it shouldn't).
            ClientWindowInfo other = findOtherClient(clientId);
            if (other != null) {
                if (holdWarpFocus(other)) {
                    log(clientId, "still WARPING (" + elapsed + "s) — other client also warping, holding focus.");
                    return;
                }
                log(clientId, "WARPING: " + elapsed + "s elapsed — switching focus to: " + other.getClientId());
                bringToForeground(other);
            }
            log(clientId, "still WARPING (" + elapsed + "s).");
        }
    }


    // -----------------------------------------------------------------------
    // Window focus switching
    // -----------------------------------------------------------------------

    /**
     * True while we should KEEP focus on the current warping client rather than yield to
     * {@code other}: only when {@code other} is also WARPING and we've held the foreground for
     * less than {@link #WARP_HOLD_SECS} since the last switch. If the other client isn't warping
     * (it has real work to do), yield immediately as before.
     */
    private boolean holdWarpFocus(ClientWindowInfo other) {
        if (other == null) return false;
        if (stateManager.getStateEnum(other.getClientId()) != ClientState.State.WARPING) return false;
        long sinceSwitch = Duration.between(lastClientSwitch, LocalDateTime.now()).getSeconds();
        return sinceSwitch < WARP_HOLD_SECS;
    }

    /** True when a client is parked (session end / unrecoverable error) or standings-locked, i.e.
     *  it will do no further work this session. Handing it the foreground is pointless. */
    private boolean isParkedOrLocked(String clientId) {
        ClientState cs = stateManager.getState(clientId);
        return cs.isParked() || cs.isStandingsLocked();
    }

    /** Returns the first registered client that is not {@code excludeId}. */
    private ClientWindowInfo findOtherClient(String excludeId) {
        for (ClientWindowInfo info : clientWindows.values()) {
            if (!info.getClientId().equals(excludeId)) {
                return info;
            }
        }
        return null;
    }

    /**
     * Brings the given window to the foreground by clicking its EVE-O Preview
     * thumbnail. The template key is derived from the client ID:
     * "eve_Meeshayl_Lancer" → "meeshayl_lancer".
     */
    private void bringToForeground(ClientWindowInfo window) {
        String templateKey = window.getClientId()
                .replaceFirst("^eve_", "")
                .toLowerCase();
        //log("[ClientManager] Clicking EVE-O Preview  '" + templateKey + "'");
        missionProcessor.clickTemplateBelow(templateKey, THUMBNAIL_CLICK_OFFSET_Y);
        lastClientSwitch = LocalDateTime.now();
    }

    // -----------------------------------------------------------------------
    // Window focus detection
    // -----------------------------------------------------------------------

    /**
     * Returns the ClientWindowInfo whose title matches the current foreground window,
     * or null if no registered EVE client is in the foreground.
     * Title-based matching is used because HWND pointer comparison across JNA calls
     * is unreliable — the same window can produce different pointer values depending
     * on how the HWND was obtained.
     */
    private ClientWindowInfo getForegroundClient() {
        try {
            com.sun.jna.platform.win32.User32 user32 =
                    com.sun.jna.platform.win32.User32.INSTANCE;
            com.sun.jna.platform.win32.WinDef.HWND fg = user32.GetForegroundWindow();
            if (fg == null) return null;
            char[] titleBuf = new char[512];
            user32.GetWindowText(fg, titleBuf, titleBuf.length);
            String fgTitle = com.sun.jna.Native.toString(titleBuf);
            for (ClientWindowInfo info : clientWindows.values()) {
                if (fgTitle.equals(info.getTitle())) return info;
            }
        } catch (Exception e) {
            // JNA unavailable — no fallback, null means skip this tick
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Window discovery — real EVE client enumeration via JNA
    // -----------------------------------------------------------------------

    private void refreshClientWindows() {
        Set<String> seenIds = new HashSet<>();
        try {
            com.sun.jna.platform.win32.User32 user32 =
                    com.sun.jna.platform.win32.User32.INSTANCE;

            user32.EnumWindows((hWnd, data) -> {
                // Skip minimized-to-taskbar windows; do NOT skip by IsWindowVisible —
                // background EVE clients in a stacked setup may report not-visible.
                int style = user32.GetWindowLong(hWnd, com.sun.jna.platform.win32.WinUser.GWL_STYLE);
                if ((style & com.sun.jna.platform.win32.WinUser.WS_MINIMIZE) != 0) return true;

                char[] titleBuf = new char[512];
                user32.GetWindowText(hWnd, titleBuf, titleBuf.length);
                String windowTitle = com.sun.jna.Native.toString(titleBuf);

                if (windowTitle.startsWith("EVE - ")) {
                    com.sun.jna.platform.win32.WinDef.RECT rect =
                            new com.sun.jna.platform.win32.WinDef.RECT();
                    user32.GetWindowRect(hWnd, rect);

                    int x = rect.left;
                    int y = rect.top;
                    int w = rect.right - rect.left;
                    int h = rect.bottom - rect.top;

                    long hwnd = com.sun.jna.Pointer.nativeValue(hWnd.getPointer());
                    // Build a temporary info to get the title-based ID
                    ClientWindowInfo info = new ClientWindowInfo(windowTitle, "ExeFile", hwnd, x, y, w, h);
                    String id = info.getClientId(); // title-based, e.g. "eve_Gravitar"
                    seenIds.add(id);

                    if (!clientWindows.containsKey(id)) {
                        clientWindows.put(id, info);
                        log("[ClientManager] EVE client discovered: '" + windowTitle
                                + "' id=" + id + " at " + x + "," + y + " " + w + "x" + h);
                    } else {
                        clientWindows.get(id).updateBounds(x, y, w, h);
                    }
                }
                return true; // continue enumeration
            }, null);

            // Remove any clients that were not seen this pass (closed windows, stale stubs)
            clientWindows.keySet().removeIf(id -> !seenIds.contains(id));

            if (clientWindows.isEmpty()) {
                log("[ClientManager] No EVE windows found.");
            }

        } catch (Exception e) {
            // JNA unavailable or enumeration failed — use stub for single-client testing
            if (clientWindows.isEmpty()) {
                Rectangle defaultArea = clientMissionAreas.getOrDefault("default", Layout.missionArea());
                ClientWindowInfo stub = new ClientWindowInfo(
                        "EVE - Character Name", "ExeFile", 0L,
                        defaultArea.x, defaultArea.y,
                        defaultArea.width, defaultArea.height
                );
                clientWindows.put(stub.getClientId(), stub);
                log("[ClientManager] JNA unavailable — registered stub client: "
                        + stub.getClientId());
            }
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    public Rectangle getMissionAreaForClient(String clientId) {
        return clientMissionAreas.getOrDefault(clientId,
                clientMissionAreas.get("default"));
    }

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private void log(String clientId, String msg) {
        System.out.printf("[%s] [%s] %s%n", LocalDateTime.now().format(TS), clientId, msg);
    }

    private void log(String msg) {
        System.out.printf("[%s] %s%n", LocalDateTime.now().format(TS), msg);
    }

    /**
     * Audible alert for events that need my attention. Each event gets a distinct tone pattern
     * (see {@link #POD_ALERT} / {@link #NO_SHIP_ALERT}) so I can tell them apart without looking.
     * Plays on a daemon thread so it never blocks the tick. Synthesised tones — no sound assets.
     */
    private void soundAlert(int[] freqsHz) {
        Thread t = new Thread(() -> {
            for (int i = 0; i < 3; i++) {          // repeat the whole pattern 3 times
                for (int f : freqsHz) {
                    playTone(f, 220);
                }
                try { Thread.sleep(250); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        }, "sound-alert");
        t.setDaemon(true);
        t.start();
    }

    /** Podded outside combat: urgent low two-tone (falling), like an alarm klaxon. */
    private static final int[] POD_ALERT     = { 440, 330 };
    /** No ship for the mission (stuck): high rising three-tone chirp — clearly different. */
    private static final int[] NO_SHIP_ALERT = { 700, 900, 1100 };

    /** Synthesise and play a single sine-wave tone (blocking; called on the alert thread). */
    private void playTone(int hz, int ms) {
        try {
            float sampleRate = 44100f;
            int samples = (int) (sampleRate * ms / 1000f);
            byte[] buf = new byte[samples];
            for (int i = 0; i < samples; i++) {
                double angle = 2.0 * Math.PI * i * hz / sampleRate;
                // Fade in/out ~10ms to avoid clicks at tone edges.
                double env = Math.min(1.0, Math.min(i, samples - i) / (sampleRate * 0.01));
                buf[i] = (byte) (Math.sin(angle) * 90 * env);
            }
            javax.sound.sampled.AudioFormat fmt =
                    new javax.sound.sampled.AudioFormat(sampleRate, 8, 1, true, false);
            try (javax.sound.sampled.SourceDataLine line =
                         javax.sound.sampled.AudioSystem.getSourceDataLine(fmt)) {
                line.open(fmt);
                line.start();
                line.write(buf, 0, buf.length);
                line.drain();
            }
        } catch (Exception e) {
            java.awt.Toolkit.getDefaultToolkit().beep();   // fall back to system beep if audio unavailable
        }
    }
}