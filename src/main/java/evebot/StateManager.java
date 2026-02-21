package evebot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import evebot.models.ClientState;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StateManager tracks the current state of all EVE clients in memory and
 * persists that state to disk so the bot can resume after a restart.
 *
 * Hotkey: Ctrl+Shift+O toggles the bot on/off (registered via a background
 * thread that polls the keyboard — no native hook library required).
 */
public class StateManager {

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    /** Live state map: clientId → ClientState */
    private final Map<String, ClientState> states = new ConcurrentHashMap<>();

    /** Whether the bot loop is currently active */
    private volatile boolean botRunning = false;

    /** Path where state is persisted between runs */
    private static final Path STATE_FILE = Paths.get("config", "bot_state.json");

    private final Gson gson;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public StateManager() {
        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .registerTypeAdapter(LocalDateTime.class,
                        new LocalDateTimeAdapter())
                .create();

        loadStates();
        registerHotkey();
    }

    // -----------------------------------------------------------------------
    // Bot on/off toggle
    // -----------------------------------------------------------------------

    /** Returns true when the bot should be actively working. */
    public boolean isBotRunning() {
        return botRunning;
    }

    /** Toggle the running flag and print a clear status message. */
    public void toggleBot() {
        botRunning = !botRunning;
        System.out.println("==============================================");
        System.out.println("  BOT " + (botRunning ? "STARTED ▶" : "PAUSED ■"));
        System.out.println("==============================================");

        if (botRunning) {
            // Print a summary of current states so the operator knows
            // where each client will resume.
            printStatesSummary();
        }
    }

    /**
     * Registers a background thread that listens for Ctrl+Shift+O using
     * Java's Robot/AWT polling approach (no JNI required).
     *
     * We use com.sun.jna (already in pom.xml) to check key state on Windows,
     * but fall back to a console-based "press ENTER to toggle" if JNA is
     * unavailable so the code always compiles cross-platform.
     */
    private void registerHotkey() {
        // Primary: JNA-based global hotkey on Windows
        if (tryRegisterJnaHotkey()) {
            System.out.println("[Hotkey] Ctrl+Shift+O registered (JNA).");
            return;
        }

        // Fallback: read lines from stdin — type 'toggle' and press Enter
        Thread fallback = new Thread(() -> {
            System.out.println("[Hotkey] JNA unavailable. Type 'toggle' + Enter to start/pause bot.");
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(System.in))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if ("toggle".equalsIgnoreCase(line.trim())) {
                        toggleBot();
                    }
                }
            } catch (IOException e) {
                System.err.println("[Hotkey] stdin reader error: " + e.getMessage());
            }
        }, "hotkey-stdin");
        fallback.setDaemon(true);
        fallback.start();
    }

    /**
     * Attempts to set up a polling thread using JNA User32 to detect
     * Ctrl+Shift+O globally.  Returns false if JNA is not available.
     */
    private boolean tryRegisterJnaHotkey() {
        try {
            // Dynamic class lookup so this compiles even without JNA at compile-time
            Class<?> user32Class = Class.forName("com.sun.jna.platform.win32.User32");
            Object user32 = user32Class.getField("INSTANCE").get(null);
            java.lang.reflect.Method getAsyncKeyState =
                    user32Class.getMethod("GetAsyncKeyState", int.class);

            // VK codes
            final int VK_CONTROL = 0x11;
            final int VK_SHIFT   = 0x10;
            final int VK_O       = 0x4F;

            Thread hotkeyThread = new Thread(() -> {
                boolean wasPressed = false;
                while (true) {
                    try {
                        short ctrl  = (short) getAsyncKeyState.invoke(user32, VK_CONTROL);
                        short shift = (short) getAsyncKeyState.invoke(user32, VK_SHIFT);
                        short o     = (short) getAsyncKeyState.invoke(user32, VK_O);

                        boolean pressed = (ctrl & 0x8000) != 0
                                && (shift & 0x8000) != 0
                                && (o & 0x8000) != 0;

                        if (pressed && !wasPressed) {
                            toggleBot();
                        }
                        wasPressed = pressed;
                        Thread.sleep(100);
                    } catch (Exception e) {
                        // If anything goes wrong, exit the thread silently
                        break;
                    }
                }
            }, "hotkey-jna");
            hotkeyThread.setDaemon(true);
            hotkeyThread.start();
            return true;

        } catch (ClassNotFoundException | NoSuchFieldException |
                 NoSuchMethodException | IllegalAccessException e) {
            return false; // JNA not available
        }
    }

    // -----------------------------------------------------------------------
    // State access
    // -----------------------------------------------------------------------

    /**
     * Returns the state for a given client, creating a fresh IDLE state if
     * none exists (e.g. first run, no persisted data).
     */
    public ClientState getState(String clientId) {
        return states.computeIfAbsent(clientId, ClientState::new);
    }

    /** Overwrite the full state for a client and save immediately. */
    public void setState(String clientId, ClientState.State newState) {
        ClientState cs = getState(clientId);
        ClientState.State old = cs.getState();
        cs.setState(newState);
        System.out.printf("[State] %s: %s → %s%n", clientId, old, newState);
        saveStates(); // persist every transition
    }

    /** Convenience: get just the enum value for a client. */
    public ClientState.State getStateEnum(String clientId) {
        return getState(clientId).getState();
    }

    // -----------------------------------------------------------------------
    // Persistence
    // -----------------------------------------------------------------------

    /** Load persisted states from disk (called at startup). */
    private void loadStates() {
        if (!Files.exists(STATE_FILE)) {
            System.out.println("[StateManager] No saved state found — starting fresh.");
            return;
        }
        try {
            String json = new String(Files.readAllBytes(STATE_FILE));
            Type mapType = new TypeToken<Map<String, ClientStateDto>>(){}.getType();
            Map<String, ClientStateDto> dtos = gson.fromJson(json, mapType);
            if (dtos != null) {
                dtos.forEach((id, dto) -> {
                    ClientState cs = new ClientState(id);
                    cs.setState(dto.state);
                    states.put(id, cs);
                });
                System.out.printf("[StateManager] Loaded %d client state(s) from disk.%n",
                        states.size());
                printStatesSummary();
            }
        } catch (Exception e) {
            System.err.println("[StateManager] Failed to load state: " + e.getMessage());
        }
    }

    /** Write all current states to disk. */
    public void saveStates() {
        try {
            Files.createDirectories(STATE_FILE.getParent());
            Map<String, ClientStateDto> dtos = new LinkedHashMap<>();
            states.forEach((id, cs) ->
                    dtos.put(id, new ClientStateDto(cs.getState())));
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

    private void printStatesSummary() {
        if (states.isEmpty()) {
            System.out.println("[StateManager] No client states in memory — waiting for mission screen.");
            return;
        }
        System.out.println("[StateManager] Current client states:");
        states.forEach((id, cs) ->
                System.out.printf("  %-30s %s%n", id, cs.getState()));
    }

    // -----------------------------------------------------------------------
    // Inner classes
    // -----------------------------------------------------------------------

    /** Lightweight DTO used only for JSON serialisation. */
    private static class ClientStateDto {
        ClientState.State state;
        ClientStateDto(ClientState.State state) { this.state = state; }
    }

    /**
     * Gson type adapter for LocalDateTime because Gson doesn't handle it
     * out of the box.
     */
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