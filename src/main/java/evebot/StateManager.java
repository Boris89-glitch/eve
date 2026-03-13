package evebot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import evebot.models.ClientState;
import evebot.models.Mission;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.*;
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

    /**
     * Track button only appears on the very first mission accept per agent session.
     * Once we've attempted it once, skip it on all future accepts.
     */
    private volatile boolean trackAttempted = false;
    private static final Path STATE_FILE = Paths.get("config", "bot_state.json");
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

    public boolean isTrackAttempted()        { return trackAttempted; }
    public void    setTrackAttempted(boolean v) { this.trackAttempted = v; }

    private void registerHotkey() {
        if (tryRegisterJnaHotkey()) {
            log("[Hotkey] Ctrl+Shift+O registered (JNA).");
        } else {
            log("[Hotkey] JNA unavailable — use 'toggle' command to start/pause.");
        }
        // Always start the stdin command listener (override commands work regardless of JNA)
        Thread cmdThread = new Thread(() -> {
            log("[Cmd] Commands: toggle | status | state <STATE> | phase <PHASE> | mission <TITLE> | target <TARGET> | ship <SHIP> | step <N>");
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
            final int VK_CONTROL = 0x11, VK_SHIFT = 0x10, VK_O = 0x4F;
            Thread hotkeyThread = new Thread(() -> {
                boolean wasPressed = false;
                while (true) {
                    try {
                        short ctrl  = (short) getAsyncKeyState.invoke(user32, VK_CONTROL);
                        short shift = (short) getAsyncKeyState.invoke(user32, VK_SHIFT);
                        short o     = (short) getAsyncKeyState.invoke(user32, VK_O);
                        boolean pressed = (ctrl & 0x8000) != 0
                                && (shift & 0x8000) != 0
                                && (o    & 0x8000) != 0;
                        if (pressed && !wasPressed) toggleBot();
                        wasPressed = pressed;
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
            log("[StateManager] New client '" + id + "' starting in " + DEFAULT_START_STATE);
            return cs;
        });
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

    public void saveStates() {
        try {
            Files.createDirectories(STATE_FILE.getParent());
            Map<String, ClientStateDto> dtos = new LinkedHashMap<>();
            states.forEach((id, cs) -> dtos.put(id, new ClientStateDto(cs)));
            String json = gson.toJson(dtos);
            Files.write(STATE_FILE, json.getBytes(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            System.err.println("[StateManager] Failed to save state: " + e.getMessage());
        }
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
                if (parts.length < 2) { log("[Cmd] Usage: target <TARGET>  (e.g. target jaguar)"); break; }
                String clientId = firstClientId();
                if (clientId == null) { log("[Cmd] No clients registered yet."); break; }
                ClientState cs = getState(clientId);
                Mission m = cs.getCurrentMission();
                if (m == null) { m = new Mission(); cs.setCurrentMission(m); }
                String targetVal = parts.length == 2 ? parts[1] : parts[1] + " " + parts[2];
                m.setTarget(targetVal);
                saveStates();
                log("[Cmd] Override: " + clientId + " target → '" + targetVal + "'");
                break;
            }

            case "ship": {
                if (parts.length < 2) { log("[Cmd] Usage: ship <SHIP_TARGET>"); break; }
                String clientId = firstClientId();
                if (clientId == null) { log("[Cmd] No clients registered yet."); break; }
                ClientState cs = getState(clientId);
                Mission m = cs.getCurrentMission();
                if (m == null) { m = new Mission(); cs.setCurrentMission(m); }
                m.setShipTarget(parts[1]);
                saveStates();
                log("[Cmd] Override: " + clientId + " shipTarget → '" + parts[1] + "'");
                break;
            }

            case "step": {
                if (parts.length < 2) { log("[Cmd] Usage: step <N>"); break; }
                String clientId = firstClientId();
                if (clientId == null) { log("[Cmd] No clients registered yet."); break; }
                try {
                    int n = Integer.parseInt(parts[1]);
                    getState(clientId).setCombatStep(n);
                    saveStates();
                    log("[Cmd] Override: " + clientId + " combatStep → " + n);
                } catch (NumberFormatException e) {
                    log("[Cmd] step requires an integer.");
                }
                break;
            }

            default:
                log("[Cmd] Unknown command '" + cmd + "'. Commands: toggle | status | state | phase | mission | target | ship | step");
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