package evebot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import evebot.models.ClientState;
import evebot.models.Mission;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StateManager tracks the current state of all EVE clients in memory.
 */
public class StateManager {

    private static final ClientState.State DEFAULT_START_STATE = ClientState.State.IDLE;

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    private final Map<String, ClientState> states = new ConcurrentHashMap<>();
    private volatile boolean botRunning = false;

    /** Graceful session end (Ctrl+Shift+R): one-way flag. Once armed, clients finish the
     *  mission they're on (turning it in), then park docked at their agent; clients merely
     *  deciding on a new mission park immediately. Every client ends the session docked. */
    private volatile boolean sessionEnding = false;

    /** Global pod-death cooldown — all clients must wait until this time before resuming. */
    private volatile LocalDateTime podCooldownEnd = LocalDateTime.MIN;
    /** Re-pod escalation: a pod death landing within {@link #POD_REPOD_WINDOW_MINUTES} after the
     *  previous cooldown expired escalates every subsequent pod cooldown to
     *  {@link #POD_COOLDOWN_ESCALATED_MINUTES} for the rest of the session (one-way). */
    private static final long POD_REPOD_WINDOW_MINUTES       = 15;
    private static final int  POD_COOLDOWN_ESCALATED_MINUTES = 30;
    private volatile boolean  podCooldownEscalated           = false;
    private static final Path STATE_FILE = Paths.get("config", "bot_state.json");
    /** Faction decline timestamps live in their own file so they survive a bot_state.json wipe
     *  (which is cleared every restart). Maps clientId → last faction-decline time. */
    private static final Path FACTION_FILE = Paths.get("config", "faction_decline.json");
    private final Map<String, LocalDateTime> factionDeclines = new ConcurrentHashMap<>();
    /** Last faction-agent check times, same scheme as the declines: their own file so the 2h
     *  check cadence survives restarts — otherwise every restart checks the agent immediately. */
    private static final Path FACTION_CHECK_FILE = Paths.get("config", "faction_check.json");
    private final Map<String, LocalDateTime> factionChecks = new ConcurrentHashMap<>();
    /** Last known agent standings live in their own file so they survive a bot_state.json wipe
     *  (which is cleared every restart) — otherwise every restart re-starts with unknown standings.
     *  Maps clientId → last read standing. */
    private static final Path STANDINGS_FILE = Paths.get("config", "standings.json");
    private final Map<String, Double> standings = new ConcurrentHashMap<>();
    /** The system we were last podded in, and when. Everything the map says is only reachable
     *  through it is refused until the entry ages out (SystemMap.avoidMinutes). Its own file so a
     *  restart mid-camp doesn't send us straight back in. Shared by both clients — one client
     *  finding a camp is reason enough for the other to keep out. */
    private static final Path POD_AVOID_FILE = Paths.get("config", "pod_avoid.json");
    private volatile String poddedSystem = null;
    private volatile LocalDateTime poddedAt = LocalDateTime.MIN;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final Gson gson;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public StateManager() {
        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .registerTypeAdapter(LocalDateTime.class, new LocalDateTimeAdapter())
                .create();

        loadFactionDeclines();   // before loadStates so loaded clients pick up their decline time
        loadFactionChecks();     // same — so a restart doesn't re-check the faction agent early
        loadStandings();         // before loadStates so loaded clients pick up their standing
        loadPodAvoid();
        loadStates();
        registerHotkey();
    }

    // -----------------------------------------------------------------------
    // Bot on/off toggle
    // -----------------------------------------------------------------------

    public boolean isBotRunning() { return botRunning; }

    public void toggleBot() {
        botRunning = !botRunning;
        log("==============================================");
        log("  BOT " + (botRunning ? "STARTED ▶" : "PAUSED ■"));
        log("==============================================");
        if (botRunning) printStatesSummary();
    }

    public boolean isSessionEnding() { return sessionEnding; }

    /**
     * Arm a graceful session end (Ctrl+Shift+R). One-way: clients already on a mission run
     * it through to turn-in, then park docked at their agent; clients merely deciding on a
     * new mission park immediately. Every client ends the session docked at its agent, so the
     * next launch starts clean from IDLE. The bot keeps running until each client parks.
     */
    public void requestSessionEnd() {
        if (sessionEnding) return;   // one-way — ignore repeats
        sessionEnding = true;
        log("==============================================");
        log("  SESSION END ⏹ — clients finish current missions, then park at their agents.");
        log("==============================================");
    }


    public LocalDateTime getPodCooldownEnd()                { return podCooldownEnd; }
    public void          setPodCooldownEnd(LocalDateTime t) { this.podCooldownEnd = t; }

    /**
     * Register a fresh pod death for re-pod escalation. When it lands within
     * {@link #POD_REPOD_WINDOW_MINUTES} after the previous cooldown expired, escalate — this and
     * every subsequent pod cooldown then run {@link #POD_COOLDOWN_ESCALATED_MINUTES} minutes.
     * One-way for the session. Call once per pod death, BEFORE {@link #setPodCooldownEnd} overwrites
     * the reference end time.
     */
    public void notePodDeathForEscalation() {
        if (podCooldownEscalated) return;
        LocalDateTime prev = podCooldownEnd;
        LocalDateTime now  = LocalDateTime.now();
        if (!prev.equals(LocalDateTime.MIN)
                && !now.isBefore(prev)
                && now.isBefore(prev.plusMinutes(POD_REPOD_WINDOW_MINUTES))) {
            podCooldownEscalated = true;
            log("[StateManager] Re-podded within " + POD_REPOD_WINDOW_MINUTES
                    + " min of the last cooldown expiry — escalating all pod cooldowns to "
                    + POD_COOLDOWN_ESCALATED_MINUTES + " min for the session.");
        }
    }

    /** Pod-cooldown length to apply: the escalated length once a re-pod inside the window has been
     *  seen this session, otherwise {@code base}. */
    public int podCooldownMinutes(int base) {
        return podCooldownEscalated ? POD_COOLDOWN_ESCALATED_MINUTES : base;
    }

    private void registerHotkey() {
        if (tryRegisterJnaHotkey()) {
            log("[Hotkey] Ctrl+Shift+O (start/pause) and Ctrl+Shift+R (session end) registered (JNA).");
        } else {
            log("[Hotkey] JNA unavailable — use the 'toggle' command instead.");
        }
        // Always start the stdin command listener (override commands work regardless of JNA)
        Thread cmdThread = new Thread(() -> {
            log("[Cmd] Commands: toggle | end | status | state <STATE> | phase <PHASE> | mission <TITLE> | target <TARGET> | ship <SHIP> | step <N>");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    handleCommand(line.trim());
                }
            } catch (IOException e) {
                System.err.println("[Cmd] stdin reader error: " + e.getMessage());
            }
        }, "hotkey-stdin");
        cmdThread.setDaemon(true);
        cmdThread.start();
    }

    private boolean tryRegisterJnaHotkey() {
        try {
            Class<?> user32Class = Class.forName("com.sun.jna.platform.win32.User32");
            Object user32 = user32Class.getField("INSTANCE").get(null);
            java.lang.reflect.Method getAsyncKeyState =
                    user32Class.getMethod("GetAsyncKeyState", int.class);
            final int VK_CONTROL = 0x11, VK_SHIFT = 0x10, VK_O = 0x4F, VK_R = 0x52;
            Thread hotkeyThread = new Thread(() -> {
                boolean wasPressedO = false, wasPressedR = false;
                while (true) {
                    try {
                        short ctrl  = (short) getAsyncKeyState.invoke(user32, VK_CONTROL);
                        short shift = (short) getAsyncKeyState.invoke(user32, VK_SHIFT);
                        short o     = (short) getAsyncKeyState.invoke(user32, VK_O);
                        short r     = (short) getAsyncKeyState.invoke(user32, VK_R);
                        boolean ctrlShift = (ctrl & 0x8000) != 0 && (shift & 0x8000) != 0;
                        boolean pressedO = ctrlShift && (o & 0x8000) != 0;
                        boolean pressedR = ctrlShift && (r & 0x8000) != 0;
                        if (pressedO && !wasPressedO) toggleBot();
                        if (pressedR && !wasPressedR) requestSessionEnd();
                        wasPressedO = pressedO;
                        wasPressedR = pressedR;
                        Thread.sleep(100);
                    } catch (Exception e) { break; }
                }
            }, "hotkey-jna");
            hotkeyThread.setDaemon(true);
            hotkeyThread.start();
            return true;
        } catch (ClassNotFoundException | NoSuchFieldException |
                 NoSuchMethodException | IllegalAccessException e) {
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // State access
    // -----------------------------------------------------------------------

    /** Returns the state for a client, creating one at IDLE if none exists. */
    public ClientState getState(String clientId) {
        return states.computeIfAbsent(clientId, id -> {
            ClientState cs = new ClientState(id);
            cs.setState(DEFAULT_START_STATE);
            LocalDateTime fd = factionDeclines.get(id);
            if (fd != null) cs.setLastFactionDecline(fd);   // survives bot_state.json wipe
            LocalDateTime fc = factionChecks.get(id);
            if (fc != null) cs.setLastFactionCheck(fc);      // survives bot_state.json wipe
            Double st = standings.get(id);
            if (st != null) cs.setLastKnownStanding(st);     // survives bot_state.json wipe
            applyStartupStandingsPause(cs);
            log("[StateManager] New client '" + id + "' starting in " + DEFAULT_START_STATE);
            return cs;
        });
    }

    /** Startup gate: the persisted standing survives restarts, but the pause flag doesn't — without
     *  this a client restarted below the pause floor happily checks/declines until the next re-read.
     *  NaN (unknown) standing never pauses. If both clients start below the floor, the pause gate's
     *  no-partner-can-carry fallback un-pauses one of them, so the pair can't deadlock. */
    private void applyStartupStandingsPause(ClientState cs) {
        if (cs.getLastKnownStanding() < ClientManager.STANDINGS_PAUSE_FLOOR) {
            cs.setStandingsPaused(true);
            cs.setStandingsPauseFromPersisted(true);   // stale value — pause gate verifies with a live read
            log("[StateManager] '" + cs.getClientId() + "' persisted standing below pause floor — starting standings-paused until the partner completes a mission.");
        }
    }

    /** Returns another client's state (any client whose id differs from {@code clientId}),
     *  or null if this is the only client. Practically the fleet partner — used to share
     *  standings and grab timing across the two clients. */
    public ClientState getOtherState(String clientId) {
        for (Map.Entry<String, ClientState> e : states.entrySet()) {
            if (!e.getKey().equals(clientId)) return e.getValue();
        }
        return null;
    }

    public void setState(String clientId, ClientState.State newState) {
        ClientState cs = getState(clientId);
        ClientState.State old = cs.getState();
        cs.setState(newState);
        System.out.printf("[%s] [State] %s: %s → %s%n",
                LocalDateTime.now().format(TS), clientId, old, newState);
        saveStates();
    }

    public ClientState.State getStateEnum(String clientId) {
        return getState(clientId).getState();
    }

    // -----------------------------------------------------------------------
    // Persistence (disabled during testing — called from Main.java when enabled)
    // -----------------------------------------------------------------------

    private void loadStates() {
        if (!Files.exists(STATE_FILE)) {
            log("[StateManager] No saved state found — starting fresh.");
            return;
        }
        try {
            String json = new String(Files.readAllBytes(STATE_FILE));
            Type mapType = new TypeToken<Map<String, ClientStateDto>>(){}.getType();
            Map<String, ClientStateDto> dtos = gson.fromJson(json, mapType);
            if (dtos != null) {
                dtos.forEach((id, dto) -> {
                    // Discard old position-based IDs (eve_client_X_Y) — title-based IDs
                    // (eve_CharacterName) are now used. Old entries would never match a
                    // discovered window and would pollute the state summary indefinitely.
                    if (id.matches("eve_client_-?\\d+_-?\\d+")) {
                        log("[StateManager] Discarding legacy state ID '" + id + "'");
                        return;
                    }
                    ClientState cs = new ClientState(id);
                    // setState() resets some fields — apply saved overrides after it
                    cs.setState(dto.state != null ? dto.state : ClientState.State.IDLE);
                    if (dto.travelPhase     != null) cs.setTravelPhase(dto.travelPhase);
                    cs.setDestinationSet(dto.destinationSet);
                    cs.setWarpedToLocation(dto.warpedToLocation);
                    cs.setMissionTracked(dto.missionTracked);
                    cs.setCombatStep(dto.combatStep);
                    cs.setLastKnownStanding(dto.lastKnownStanding != null ? dto.lastKnownStanding : Double.NaN);
                    Double st = standings.get(id);
                    if (st != null) cs.setLastKnownStanding(st);   // dedicated file wins — survives bot_state.json wipe
                    applyStartupStandingsPause(cs);
                    LocalDateTime fd = factionDeclines.get(id);
                    if (fd != null) cs.setLastFactionDecline(fd);
                    LocalDateTime fc = factionChecks.get(id);
                    if (fc != null) cs.setLastFactionCheck(fc);
                    if (dto.missionTitle != null || dto.missionTarget != null || dto.missionShipTarget != null) {
                        Mission m = new Mission();
                        m.setTitle(dto.missionTitle);
                        m.setTarget(dto.missionTarget);
                        m.setShipTarget(dto.missionShipTarget);
                        m.setType(dto.missionType);
                        cs.setCurrentMission(m);
                    }
                    states.put(id, cs);
                });
                log("[StateManager] Loaded " + states.size() + " client state(s) from disk.");
                printStatesSummary();
            }
        } catch (Exception e) {
            System.err.println("[StateManager] Failed to load state: " + e.getMessage());
        }
    }

    /** Force every client to IDLE — called on shutdown so a persisted mid-mission state
     *  can't be reloaded next launch (clients always start clean from IDLE). */
    public void resetAllToIdle() {
        states.values().forEach(cs -> cs.setState(ClientState.State.IDLE));
    }

    public void saveStates() {
        try {
            Files.createDirectories(STATE_FILE.getParent());
            Map<String, ClientStateDto> dtos = new LinkedHashMap<>();
            states.forEach((id, cs) -> dtos.put(id, new ClientStateDto(cs)));
            String json = gson.toJson(dtos);
            Files.write(STATE_FILE, json.getBytes(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            // Catch everything (not just IOException): saveStates() runs inside the
            // scheduled monitor tick, and an uncaught throw here cancels the whole
            // ScheduledExecutorService task — silently killing the bot.
            System.err.println("[StateManager] Failed to save state: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Faction decline persistence (separate file — outlives a bot_state.json wipe)
    // -----------------------------------------------------------------------

    private void loadFactionDeclines() {
        if (!Files.exists(FACTION_FILE)) return;
        try {
            String json = new String(Files.readAllBytes(FACTION_FILE));
            Type t = new TypeToken<Map<String, LocalDateTime>>(){}.getType();
            Map<String, LocalDateTime> m = gson.fromJson(json, t);
            if (m != null) {
                factionDeclines.putAll(m);
                log("[StateManager] Loaded " + factionDeclines.size() + " faction decline time(s) from disk.");
            }
        } catch (Exception e) {
            System.err.println("[StateManager] Failed to load faction declines: " + e.getMessage());
        }
    }

    /** Record a faction decline for a client — updates the in-memory state and persists to the
     *  dedicated faction file so the 4h cooldown survives restarts (and bot_state.json wipes). */
    public void recordFactionDecline(String clientId, LocalDateTime when) {
        factionDeclines.put(clientId, when);
        getState(clientId).setLastFactionDecline(when);
        try {
            Files.createDirectories(FACTION_FILE.getParent());
            Files.write(FACTION_FILE, gson.toJson(factionDeclines).getBytes(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            System.err.println("[StateManager] Failed to save faction declines: " + e.getMessage());
        }
    }

    private void loadFactionChecks() {
        if (!Files.exists(FACTION_CHECK_FILE)) return;
        try {
            String json = new String(Files.readAllBytes(FACTION_CHECK_FILE));
            Type t = new TypeToken<Map<String, LocalDateTime>>(){}.getType();
            Map<String, LocalDateTime> m = gson.fromJson(json, t);
            if (m != null) {
                factionChecks.putAll(m);
                log("[StateManager] Loaded " + factionChecks.size() + " faction check time(s) from disk.");
            }
        } catch (Exception e) {
            System.err.println("[StateManager] Failed to load faction checks: " + e.getMessage());
        }
    }

    /** Record a faction-agent check for a client — updates the in-memory state and persists so
     *  the 2h check cadence survives restarts (no more checking the agent on every restart). */
    public void recordFactionCheck(String clientId, LocalDateTime when) {
        factionChecks.put(clientId, when);
        getState(clientId).setLastFactionCheck(when);
        try {
            Files.createDirectories(FACTION_CHECK_FILE.getParent());
            Files.write(FACTION_CHECK_FILE, gson.toJson(factionChecks).getBytes(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            System.err.println("[StateManager] Failed to save faction checks: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Standings persistence (separate file — outlives a bot_state.json wipe)
    // -----------------------------------------------------------------------

    private void loadStandings() {
        if (!Files.exists(STANDINGS_FILE)) return;
        try {
            String json = new String(Files.readAllBytes(STANDINGS_FILE));
            Type t = new TypeToken<Map<String, Double>>(){}.getType();
            Map<String, Double> m = gson.fromJson(json, t);
            if (m != null) {
                standings.putAll(m);
                log("[StateManager] Loaded " + standings.size() + " agent standing(s) from disk.");
            }
        } catch (Exception e) {
            System.err.println("[StateManager] Failed to load standings: " + e.getMessage());
        }
    }

    /** Record a client's last known agent standing — updates the in-memory state and persists to
     *  the dedicated standings file so it survives restarts (and bot_state.json wipes), meaning we
     *  no longer start with unknown standings. NaN reads are ignored (never clobber a good value). */
    public void recordStanding(String clientId, double standing) {
        if (Double.isNaN(standing)) return;
        standings.put(clientId, standing);
        getState(clientId).setLastKnownStanding(standing);
        try {
            Files.createDirectories(STANDINGS_FILE.getParent());
            Files.write(STANDINGS_FILE, gson.toJson(standings).getBytes(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            System.err.println("[StateManager] Failed to save standings: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Pod avoidance (separate file — outlives a bot_state.json wipe)
    // -----------------------------------------------------------------------

    /** Just enough to remember a camp across a restart. */
    private static class PodAvoid {
        String system;
        LocalDateTime when;
    }

    private void loadPodAvoid() {
        if (!Files.exists(POD_AVOID_FILE)) return;
        try {
            PodAvoid a = gson.fromJson(new String(Files.readAllBytes(POD_AVOID_FILE)), PodAvoid.class);
            if (a == null || a.system == null || a.when == null) return;
            poddedSystem = a.system;
            poddedAt = a.when;
            String active = getAvoidedSystem();
            log("[StateManager] Loaded pod avoidance: " + SystemMap.display(SystemMap.resolve(a.system))
                    + (active == null ? " (already expired — not avoiding)" : " — avoiding it and everything behind it."));
        } catch (Exception e) {
            System.err.println("[StateManager] Failed to load pod avoidance: " + e.getMessage());
        }
    }

    /**
     * Record the system we were just podded in. Everything the map says is only reachable through
     * it is refused from now on — missions there get declined at the agent, and one that slips
     * through is quit after undock when the route panel reveals the destination.
     */
    public void recordPoddedSystem(String system) {
        if (system == null || system.trim().isEmpty()) return;
        poddedSystem = system.trim();
        poddedAt = LocalDateTime.now();
        try {
            PodAvoid a = new PodAvoid();
            a.system = poddedSystem;
            a.when = poddedAt;
            Files.createDirectories(POD_AVOID_FILE.getParent());
            Files.write(POD_AVOID_FILE, gson.toJson(a).getBytes(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            System.err.println("[StateManager] Failed to save pod avoidance: " + e.getMessage());
        }
    }

    /**
     * The system currently being avoided, or null if there is none (or the entry has aged out of
     * SystemMap.avoidMinutes — 0 there means it lasts the rest of the session).
     */
    public String getAvoidedSystem() {
        // High sec: the null-sec route map and its pod avoidance no longer apply.
        if (Environment.isHighSec()) return null;
        String system = poddedSystem;
        if (system == null) return null;
        int minutes = SystemMap.avoidMinutes();
        if (minutes > 0 && Duration.between(poddedAt, LocalDateTime.now()).toMinutes() >= minutes) {
            return null;
        }
        return system;
    }

    /** Minutes left on the current avoidance, or -1 when it has no expiry / none is active. */
    public long getAvoidMinutesRemaining() {
        if (getAvoidedSystem() == null || SystemMap.avoidMinutes() <= 0) return -1;
        return SystemMap.avoidMinutes() - Duration.between(poddedAt, LocalDateTime.now()).toMinutes();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void log(String msg) {
        System.out.printf("[%s] %s%n", LocalDateTime.now().format(TS), msg);
    }

    private void printStatesSummary() {
        if (states.isEmpty()) {
            log("[StateManager] No client states in memory.");
            return;
        }
        log("[StateManager] Current client states:");
        states.forEach((id, cs) -> {
            Mission m = cs.getCurrentMission();
            String mStr = m != null && m.getTitle() != null ? m.getTitle() : "none";
            System.out.printf("  %-30s %-20s phase=%-16s mission=%s  ship=%s  combatStep=%d%n",
                    id, cs.getState(), cs.getTravelPhase(), mStr,
                    m != null && m.getShipTarget() != null ? m.getShipTarget() : "-",
                    cs.getCombatStep());
        });
    }

    // -----------------------------------------------------------------------
    // Manual override (stdin commands for testing)
    //
    //   toggle                  — start/pause bot
    //   status                  — print all client states
    //   state <STATE>           — force state on first/only client
    //   state <id> <STATE>      — force state on named client
    //   phase <PHASE>           — force TravelPhase on first client
    //   phase <id> <PHASE>      — force TravelPhase on named client
    //   mission <TITLE>         — set mission title on first client (clears target/ship)
    //   mission <id> <TITLE>    — set mission title on named client
    //   ship <SHIP>             — set missionShipTarget on first client
    //   step <N>                — set combatStep on first client
    // -----------------------------------------------------------------------

    private void handleCommand(String raw) {
        if (raw.isEmpty()) return;
        String[] parts = raw.split("\\s+", 3);
        String cmd = parts[0].toLowerCase();

        switch (cmd) {
            case "toggle":
                toggleBot();
                break;

            case "end":
                requestSessionEnd();
                break;

            case "status":
                printStatesSummary();
                break;

            case "state": {
                if (parts.length < 2) { log("[Cmd] Usage: state <STATE>  or  state <clientId> <STATE>"); break; }
                String clientId, stateName;
                if (parts.length == 2) { clientId = firstClientId(); stateName = parts[1]; }
                else                   { clientId = parts[1];        stateName = parts[2]; }
                if (clientId == null) { log("[Cmd] No clients registered yet."); break; }
                try {
                    ClientState.State s = ClientState.State.valueOf(stateName.toUpperCase());
                    setState(clientId, s);
                    saveStates();
                    log("[Cmd] Override: " + clientId + " → " + s);
                } catch (IllegalArgumentException e) {
                    log("[Cmd] Unknown state '" + stateName + "'. Valid: " +
                            java.util.Arrays.toString(ClientState.State.values()));
                }
                break;
            }

            case "phase": {
                if (parts.length < 2) { log("[Cmd] Usage: phase <PHASE>  or  phase <clientId> <PHASE>"); break; }
                String clientId, phaseName;
                if (parts.length == 2) { clientId = firstClientId(); phaseName = parts[1]; }
                else                   { clientId = parts[1];        phaseName = parts[2]; }
                if (clientId == null) { log("[Cmd] No clients registered yet."); break; }
                try {
                    ClientState.TravelPhase p = ClientState.TravelPhase.valueOf(phaseName.toUpperCase());
                    getState(clientId).setTravelPhase(p);
                    saveStates();
                    log("[Cmd] Override: " + clientId + " travelPhase → " + p);
                } catch (IllegalArgumentException e) {
                    log("[Cmd] Unknown phase '" + phaseName + "'. Valid: " +
                            java.util.Arrays.toString(ClientState.TravelPhase.values()));
                }
                break;
            }

            case "mission": {
                if (parts.length < 2) { log("[Cmd] Usage: mission <TITLE>  or  mission <clientId> <TITLE>"); break; }
                String clientId, title;
                // heuristic: if parts[1] looks like a registered client id, use it
                if (parts.length == 3 && states.containsKey(parts[1])) {
                    clientId = parts[1]; title = parts[2];
                } else {
                    clientId = firstClientId();
                    title = parts.length == 2 ? parts[1] : parts[1] + " " + parts[2];
                }
                if (clientId == null) { log("[Cmd] No clients registered yet."); break; }
                Mission m = new Mission();
                m.setTitle(title);
                getState(clientId).setCurrentMission(m);
                saveStates();
                log("[Cmd] Override: " + clientId + " mission → '" + title + "'");
                break;
            }

            case "target": {
                if (parts.length < 2) { log("[Cmd] Usage: target <TARGET>  or  target <clientId> <TARGET>"); break; }
                String clientId, targetVal;
                if (parts.length == 3 && states.containsKey(parts[1])) {
                    clientId = parts[1]; targetVal = parts[2];
                } else {
                    clientId = firstClientId();
                    targetVal = parts.length == 2 ? parts[1] : parts[1] + " " + parts[2];
                }
                if (clientId == null) { log("[Cmd] No clients registered yet. Use: state <clientId> IN_COMBAT first."); break; }
                ClientState cs = getState(clientId);
                Mission m = cs.getCurrentMission();
                if (m == null) { m = new Mission(); cs.setCurrentMission(m); }
                m.setTarget(targetVal);
                saveStates();
                log("[Cmd] Override: " + clientId + " target → '" + targetVal + "'");
                break;
            }

            case "ship": {
                if (parts.length < 2) { log("[Cmd] Usage: ship <SHIP_TARGET>  or  ship <clientId> <SHIP_TARGET>"); break; }
                String clientId, shipVal;
                if (parts.length == 3 && states.containsKey(parts[1])) {
                    clientId = parts[1]; shipVal = parts[2];
                } else {
                    clientId = firstClientId();
                    shipVal = parts[1];
                }
                if (clientId == null) { log("[Cmd] No clients registered yet. Use: state <clientId> IN_COMBAT first."); break; }
                ClientState cs = getState(clientId);
                Mission m = cs.getCurrentMission();
                if (m == null) { m = new Mission(); cs.setCurrentMission(m); }
                m.setShipTarget(shipVal);
                saveStates();
                log("[Cmd] Override: " + clientId + " shipTarget → '" + shipVal + "'");
                break;
            }

            case "step": {
                if (parts.length < 2) { log("[Cmd] Usage: step <N>  or  step <clientId> <N>"); break; }
                String clientId;
                String stepStr;
                if (parts.length == 3 && states.containsKey(parts[1])) {
                    clientId = parts[1]; stepStr = parts[2];
                } else {
                    clientId = firstClientId(); stepStr = parts[1];
                }
                if (clientId == null) { log("[Cmd] No clients registered yet. Use: state <clientId> IN_COMBAT first."); break; }
                try {
                    int n = Integer.parseInt(stepStr);
                    getState(clientId).setCombatStep(n);
                    saveStates();
                    log("[Cmd] Override: " + clientId + " combatStep → " + n);
                } catch (NumberFormatException e) {
                    log("[Cmd] step requires an integer.");
                }
                break;
            }

            default:
                log("[Cmd] Unknown command '" + cmd + "'. Commands: toggle | end | status | state | phase | mission | target | ship | step");
        }
    }

    /** Returns the first registered client ID, or null if none. */
    private String firstClientId() {
        return states.isEmpty() ? null : states.keySet().iterator().next();
    }

    // -----------------------------------------------------------------------
    // Inner classes
    // -----------------------------------------------------------------------

    private static class ClientStateDto {
        ClientState.State       state;
        ClientState.TravelPhase travelPhase;
        boolean                 destinationSet;
        boolean                 warpedToLocation;
        boolean                 missionTracked;
        int                     combatStep;
        // Boxed (nullable): NaN is not valid JSON, so "unknown standing" persists as null.
        Double                  lastKnownStanding;
        // Mission fields (flattened — no rawText, no objective)
        String                  missionTitle;
        String                  missionTarget;
        String                  missionShipTarget;
        Mission.MissionType     missionType;

        ClientStateDto() {}   // Gson

        ClientStateDto(ClientState cs) {
            this.state           = cs.getState();
            this.travelPhase     = cs.getTravelPhase();
            this.destinationSet  = cs.isDestinationSet();
            this.warpedToLocation= cs.isWarpedToLocation();
            this.missionTracked  = cs.isMissionTracked();
            this.combatStep      = cs.getCombatStep();
            double standing      = cs.getLastKnownStanding();
            this.lastKnownStanding = Double.isNaN(standing) ? null : standing; // NaN → null (JSON-safe)
            Mission m = cs.getCurrentMission();
            if (m != null) {
                this.missionTitle     = m.getTitle();
                this.missionTarget    = m.getTarget();
                this.missionShipTarget= m.getShipTarget();
                this.missionType      = m.getType();
            }
        }
    }

    private static class LocalDateTimeAdapter
            implements com.google.gson.JsonSerializer<LocalDateTime>,
            com.google.gson.JsonDeserializer<LocalDateTime> {

        @Override
        public com.google.gson.JsonElement serialize(LocalDateTime src,
                                                     java.lang.reflect.Type typeOfSrc,
                                                     com.google.gson.JsonSerializationContext context) {
            return new com.google.gson.JsonPrimitive(src.toString());
        }

        @Override
        public LocalDateTime deserialize(com.google.gson.JsonElement json,
                                         java.lang.reflect.Type typeOfT,
                                         com.google.gson.JsonDeserializationContext context) {
            return LocalDateTime.parse(json.getAsString());
        }
    }
}