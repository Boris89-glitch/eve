package evebot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import evebot.models.ClientState;

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
            return;
        }
        Thread fallback = new Thread(() -> {
            log("[Hotkey] JNA unavailable. Type 'toggle' + Enter to start/pause bot.");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if ("toggle".equalsIgnoreCase(line.trim())) toggleBot();
                }
            } catch (IOException e) {
                System.err.println("[Hotkey] stdin reader error: " + e.getMessage());
            }
        }, "hotkey-stdin");
        fallback.setDaemon(true);
        fallback.start();
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
        // saveStates(); // disabled during testing — re-enable for production
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
                    cs.setState(dto.state);
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
            states.forEach((id, cs) -> dtos.put(id, new ClientStateDto(cs.getState())));
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
        states.forEach((id, cs) ->
                System.out.printf("  %-30s %s%n", id, cs.getState()));
    }

    // -----------------------------------------------------------------------
    // Inner classes
    // -----------------------------------------------------------------------

    private static class ClientStateDto {
        ClientState.State state;
        ClientStateDto(ClientState.State state) { this.state = state; }
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