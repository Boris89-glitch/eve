package evebot;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.awt.Point;
import java.awt.Rectangle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Per-machine screen layout, read once from config/layout.json (gitignored, one per
 * machine). config/layout.example.json is the checked-in desktop layout and the
 * fallback when no layout.json exists — so every resolution-specific coordinate lives
 * in config, never in the code, and branches don't diverge over screen size.
 *
 * The Python side reads the same file (see load_layout in ocr/eve_automation.py).
 */
public final class Layout {

    private static final Path FILE    = Paths.get("config", "layout.json");
    private static final Path EXAMPLE = Paths.get("config", "layout.example.json");

    private static final JsonObject json = load();

    private Layout() {}

    private static JsonObject load() {
        for (Path path : List.of(FILE, EXAMPLE)) {
            if (!Files.exists(path)) continue;
            try {
                JsonObject o = new Gson().fromJson(
                        new String(Files.readAllBytes(path)), JsonObject.class);
                System.out.println("[Layout] Loaded screen layout from " + path
                        + (o.has("screen") ? " (" + o.get("screen").getAsString() + ")" : ""));
                return o;
            } catch (Exception e) {
                System.err.println("[Layout] Error reading " + path + ": " + e.getMessage());
            }
        }
        System.err.println("[Layout] No config/layout.json — copy config/layout.example.json "
                + "and tune it for this machine. Using built-in defaults.");
        return new JsonObject();
    }

    private static Point point(String key, int dx, int dy) {
        JsonObject o = json.getAsJsonObject(key);
        if (o == null) return new Point(dx, dy);
        return new Point(o.get("x").getAsInt(), o.get("y").getAsInt());
    }

    private static Rectangle rect(String key, int dx, int dy, int dw, int dh) {
        JsonObject o = json.getAsJsonObject(key);
        if (o == null) return new Rectangle(dx, dy, dw, dh);
        return new Rectangle(o.get("x").getAsInt(), o.get("y").getAsInt(),
                o.get("width").getAsInt(), o.get("height").getAsInt());
    }

    /** Mission dialog area (OCR read region). */
    public static Rectangle missionArea()     { return rect("mission_area", 440, 92, 988, 336); }

    /** Empty space in the middle of the screen — left-click here to dismiss menus (never Escape). */
    public static Point emptySpace()          { return point("empty_space", 960, 540); }

    /** Empty space above centre — right-click target that avoids hitting our own ship. */
    public static Point spaceAboveShip()      { return point("space_above_ship", 960, 300); }

    /** Ammo/module launcher slot on the HUD. */
    public static Point launcher()            { return point("launcher", 1093, 1028); }

    /** Close "X" of the agent-standings ("Character: Information") window. */
    public static Point standingsClose()      { return point("standings_close", 632, 208); }

    /** Box around that close "X" — scopes the template match so it can't hit another window's X. */
    public static Rectangle standingsWindow() { return rect("standings_window", 500, 140, 210, 130); }

    /** OCR region holding the standings value. */
    public static Rectangle standingsOcr()    { return rect("standings_ocr", 300, 490, 100, 55); }

    /** Right-click target on the kill report's "Final Blow" pilot name. */
    public static Point killerClick()         { return point("killer_click", 777, 559); }
}
