package evebot;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Global game-state flags shared by both machines — read once from config/environment.json.
 * Unlike config/layout.json (per-machine screen coordinates), this is the same on every
 * machine because it tracks where the character actually is in-game (all clients move
 * together). Committed to git so a push carries it to the laptop.
 *
 * Currently just the security band. When "highsec" the agent no longer opens the mission
 * dialog on a bare double-click — you double-click to open the agent window, then click
 * View/Request Mission. The Python side reads the same flag (passed as --security) to prefer
 * '<name>.highsec.png' template variants so the null-sec captures stay put for a move back.
 */
public final class Environment {

    private static final Path FILE = Paths.get("config", "environment.json");

    private static final JsonObject json = load();

    private Environment() {}

    private static JsonObject load() {
        if (Files.exists(FILE)) {
            try {
                JsonObject o = new Gson().fromJson(
                        new String(Files.readAllBytes(FILE)), JsonObject.class);
                System.out.println("[Environment] Loaded from " + FILE
                        + " (security=" + (o.has("security") ? o.get("security").getAsString() : "nullsec") + ")");
                return o;
            } catch (Exception e) {
                System.err.println("[Environment] Error reading " + FILE + ": " + e.getMessage());
            }
        }
        System.err.println("[Environment] No config/environment.json — defaulting to nullsec.");
        return new JsonObject();
    }

    /** "highsec" or "nullsec" (default). */
    public static String security() {
        return json.has("security") ? json.get("security").getAsString() : "nullsec";
    }

    public static boolean isHighSec() {
        return "highsec".equalsIgnoreCase(security());
    }
}
