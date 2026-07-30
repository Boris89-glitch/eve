package evebot;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import evebot.models.Mission;

import java.io.*;
import java.nio.file.*;

/**
 * OCRBridge — Java ↔ Python bridge.
 *
 * Two Python scripts are used:
 *
 *  mission_reader.py   — OCR only; called with (x y w h), returns JSON Mission
 *  eve_automation.py   — template matching + clicking; called with action flags
 *
 * Public API:
 *
 *   Mission readMission(x, y, w, h)               — OCR read a screen region
 *   void    clickTemplate(String key)              — find & left-click a template
 *   void    doubleClickTemplate(String key)        — find & double-click a template
 *   void    rightClickTemplate(String key)         — find & right-click a template
 *   boolean repairShip(String missionTitle)        — repair + activate ship for mission
 */
public class OCRBridge {

    private final String missionReaderPath;
    private final String automationScriptPath;
    private final Gson gson;
    private final PyDaemon daemon;

    /** Name of the client currently being processed — used as the log tag instead of "OCRBridge"
     *  so each line shows which EVE client it acted on. Set by ClientManager on every tick. */
    private static volatile String activeClient = "OCRBridge";
    public static void setActiveClient(String c) { if (c != null && !c.isEmpty()) activeClient = c; }
    public static String getActiveClient() { return activeClient; }

    /** Verbose logging toggle for the per-call action/detection echoes (findAnyTemplate,
     *  waitForAnyTemplate, clickTemplate, …). Off by default — the meaningful state lines
     *  come from MissionProcessor/ClientManager. Enable with -Docr.verbose=true to debug
     *  detection. The daemon is told to match via the --verbose flag (see ensureStarted). */
    private static final boolean VERBOSE = Boolean.getBoolean("ocr.verbose");

    /** Print a low-level echo only when VERBOSE. Same signature as System.out.printf. */
    private void dbg(String fmt, Object... args) {
        if (VERBOSE) System.out.printf(fmt, args);
    }

    // -----------------------------------------------------------------------

    public OCRBridge() {
        this.missionReaderPath    = resolveScript("mission_reader.py");
        this.automationScriptPath = resolveScript("eve_automation.py");
        this.gson = new Gson();
        this.daemon = new PyDaemon();
    }

    private String resolveScript(String filename) {
        String[] candidates = {
                "ocr/" + filename,
                filename,
                Paths.get("ocr", filename).toAbsolutePath().toString(),
                Paths.get(filename).toAbsolutePath().toString()
        };
        for (String path : candidates) {
            if (new java.io.File(path).exists()) {
                System.out.println("[" + activeClient + "] Found " + filename + " at: " + path);
                return path;
            }
        }
        System.err.printf("[%s] [" + activeClient + "] WARNING: %s not found.%n",
                ts(), filename);
        return "ocr/" + filename;
    }

    // -----------------------------------------------------------------------
    // Core: run Python and capture stdout
    // -----------------------------------------------------------------------

    private String runPython(String... args) {
        try {
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            pb.environment().put("PYTHONUNBUFFERED", "1");
            pb.redirectErrorStream(false);

            Process process = pb.start();

            Thread stderrThread = new Thread(() -> {
                try (BufferedReader err = new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), "UTF-8"))) {
                    String line;
                    while ((line = err.readLine()) != null) {
                        System.err.println("[OCR stderr] " + line);
                    }
                } catch (IOException ignored) {}
            }, "ocr-stderr");
            stderrThread.setDaemon(true);
            stderrThread.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                System.err.println("[" + activeClient + "] Python exited with code " + exitCode);
            }

            String result = output.toString().trim();
            return result.isEmpty() ? "" : result;

        } catch (Exception e) {
            System.err.println("[" + activeClient + "] Error running Python: " + e.getMessage());
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Persistent Python daemon — one resident process for all hot-path actions.
    //
    // Each call is a single JSON request/response round-trip (~50-150ms) instead
    // of spawning a new `python` process (~1s of imports) per action. The daemon
    // auto-restarts if it dies; only standings OCR still uses the spawn path
    // (runPython) — it is infrequent and not latency-sensitive.
    // -----------------------------------------------------------------------

    private final class PyDaemon {
        private Process proc;
        private BufferedWriter in;
        private BufferedReader out;

        synchronized JsonObject send(JsonObject cmd) {
            JsonObject r = trySend(cmd);
            if (r != null) return r;
            // One restart + retry — covers a daemon that died between calls.
            System.err.printf("[%s] [" + activeClient + "] daemon call failed — restarting daemon.%n", ts());
            stop();
            return trySend(cmd);
        }

        private JsonObject trySend(JsonObject cmd) {
            try {
                ensureStarted();
                in.write(gson.toJson(cmd));
                in.write("\n");
                in.flush();
                String line = out.readLine();
                if (line == null) throw new IOException("daemon closed stdout");
                return gson.fromJson(line, JsonObject.class);
            } catch (Exception e) {
                System.err.printf("[%s] [" + activeClient + "] daemon send error: %s%n", ts(), e.getMessage());
                stop();
                return null;
            }
        }

        private void ensureStarted() throws IOException {
            if (proc != null && proc.isAlive()) return;
            java.util.List<String> args = new java.util.ArrayList<>(java.util.List.of(
                    "python", automationScriptPath, "--daemon",
                    "--security", Environment.security()));
            if (VERBOSE) args.add("--verbose");
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            pb.environment().put("PYTHONUNBUFFERED", "1");
            pb.redirectErrorStream(false);
            proc = pb.start();
            in  = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), "UTF-8"));
            out = new BufferedReader(new InputStreamReader(proc.getInputStream(), "UTF-8"));

            Thread stderrThread = new Thread(() -> {
                try (BufferedReader err = new BufferedReader(
                        new InputStreamReader(proc.getErrorStream(), "UTF-8"))) {
                    String l;
                    while ((l = err.readLine()) != null) {
                        System.err.println("[OCR daemon] " + l);
                    }
                } catch (IOException ignored) {}
            }, "ocr-daemon-stderr");
            stderrThread.setDaemon(true);
            stderrThread.start();

            String ready = out.readLine();  // daemon prints {"ok":true,"value":"ready"}
            System.out.printf("[%s] [" + activeClient + "] daemon started: %s%n", ts(), ready);
        }

        synchronized void stop() {
            try { if (proc != null) proc.destroyForcibly(); } catch (Exception ignored) {}
            proc = null; in = null; out = null;
        }
    }

    /** Build a command object with just a cmd name. */
    private JsonObject cmd(String name) {
        JsonObject o = new JsonObject();
        o.addProperty("cmd", name);
        return o;
    }

    /** Send a command; return the "value" as boolean (false if missing/null/failed). */
    private boolean sendBool(JsonObject c) {
        JsonObject r = daemon.send(c);
        return r != null && r.has("value")
                && !r.get("value").isJsonNull() && r.get("value").getAsBoolean();
    }

    /** Send a command; return the "value" as String, or null. */
    private String sendString(JsonObject c) {
        JsonObject r = daemon.send(c);
        if (r == null || !r.has("value") || r.get("value").isJsonNull()) return null;
        return r.get("value").getAsString();
    }

    /** Send a fire-and-forget action command (return value ignored). */
    private void sendAction(JsonObject c) {
        daemon.send(c);
    }

    /** Stop the resident daemon (called on shutdown). */
    public void shutdown() {
        daemon.stop();
    }

    // -----------------------------------------------------------------------
    // OCR — mission_reader.py (via daemon read_mission; spawn fallback unused)
    // -----------------------------------------------------------------------

    public Mission readMission(int x, int y, int width, int height) {
        JsonObject c = cmd("read_mission");
        c.addProperty("x", x);
        c.addProperty("y", y);
        c.addProperty("w", width);
        c.addProperty("h", height);
        JsonObject r = daemon.send(c);
        if (r == null || !r.has("value") || r.get("value").isJsonNull()) return null;
        return parseMissionJson(gson.toJson(r.get("value")));
    }

    /**
     * OCR the info-panel Route section. Returns {route_set, jumps, destination, raw},
     * or null if the daemon call failed. Parsing lives in Python (it needs the per-word
     * OCR boxes to dig the jump count out of its smaller font).
     */
    public JsonObject readRoute(int x, int y, int width, int height) {
        JsonObject c = cmd("read_route");
        c.addProperty("x", x);
        c.addProperty("y", y);
        c.addProperty("w", width);
        c.addProperty("h", height);
        JsonObject r = daemon.send(c);
        if (r == null || !r.has("value") || !r.get("value").isJsonObject()) return null;
        return r.getAsJsonObject("value");
    }

    /**
     * OCR the kill report's location header — "X47L-Q -0.3 &lt; MDM8-J &lt; Pure Blind", the
     * system we were killed in. Returns {system, raw}, or null if the daemon call failed.
     * Same row format as a route waypoint, but here the system is the FIRST name on the line.
     */
    public JsonObject readKillSystem(int x, int y, int width, int height) {
        JsonObject c = cmd("read_kill_system");
        c.addProperty("x", x);
        c.addProperty("y", y);
        c.addProperty("w", width);
        c.addProperty("h", height);
        JsonObject r = daemon.send(c);
        if (r == null || !r.has("value") || !r.get("value").isJsonObject()) return null;
        return r.getAsJsonObject("value");
    }

    /**
     * Template-first mission classification (no OCR). Returns the matched mission
     * dialog key (e.g. "mission_hawk") or null if no known mission template is
     * visible. One screenshot inside the daemon.
     */
    public String classifyMissionOffer(int x, int y, int width, int height) {
        dbg("[%s] [" + activeClient + "] classifyMissionOffer (template-first, region %d,%d %dx%d)%n",
                ts(), x, y, width, height);
        JsonObject c = cmd("classify_offer");
        JsonObject region = new JsonObject();
        region.addProperty("x", x);
        region.addProperty("y", y);
        region.addProperty("w", width);
        region.addProperty("h", height);
        c.add("region", region);
        return sendString(c);
    }

    private Mission parseMissionJson(String jsonString) {
        if (jsonString == null || jsonString.isEmpty()) return null;
        dbg("[%s] [" + activeClient + "] Raw OCR output: %s%n", ts(), jsonString);
        try {
            JsonObject json = gson.fromJson(jsonString, JsonObject.class);
            if (json.has("error")) {
                System.err.println("[" + activeClient + "] OCR error: " + json.get("error").getAsString());
                return null;
            }
            Mission mission = new Mission();
            mission.setTitle(str(json, "title"));
            mission.setObjective(str(json, "objective"));
            mission.setTarget(str(json, "target"));
            mission.setLocation(str(json, "location"));
            mission.setSecurity(str(json, "security"));
            mission.setRawText(str(json, "raw_text"));
            mission.setShipTarget(extractShipTarget(mission));
            try {
                String typeStr = str(json, "type");
                mission.setType(typeStr != null
                        ? Mission.MissionType.valueOf(typeStr.toUpperCase())
                        : Mission.MissionType.OTHER);
            } catch (IllegalArgumentException e) {
                mission.setType(Mission.MissionType.OTHER);
            }
            return mission;
        } catch (JsonSyntaxException e) {
            System.err.println("[" + activeClient + "] Invalid JSON: " + e.getMessage());
            return null;
        }
    }

    /**
     * OCR-read agent standings from a screen region.
     * Returns the standings value as a double, or Double.NaN on failure.
     */
    public double readStandings(int x, int y, int width, int height) {
        String json = runPython("python", missionReaderPath,
                "--standings",
                String.valueOf(x), String.valueOf(y),
                String.valueOf(width), String.valueOf(height));
        if (json == null || json.isEmpty()) return Double.NaN;
        dbg("[%s] [" + activeClient + "] Standings OCR output: %s%n", ts(), json);
        try {
            JsonObject obj = gson.fromJson(json, JsonObject.class);
            if (obj.has("error")) {
                System.err.println("[" + activeClient + "] Standings OCR error: " + obj.get("error").getAsString());
                return Double.NaN;
            }
            return correctStanding(obj.get("standings").getAsDouble());
        } catch (Exception e) {
            System.err.println("[" + activeClient + "] Standings parse error: " + e.getMessage());
            return Double.NaN;
        }
    }

    /**
     * Sanity-correct an OCR'd standing. EVE standings are hard-capped at ±10 and always shown
     * to two decimals, so two Tesseract misreads are recoverable:
     *   - Magnitude &gt;= 10: the decimal point was dropped (0.44 read as "44") — divide by 100
     *     to restore the two-decimal value.
     *   - Magnitude in [8, 9): a leading 8 is a misread leading 0 (8.45 is really 0.45) —
     *     strip the 8, keeping the sign.
     */
    private double correctStanding(double v) {
        if (Double.isNaN(v)) return v;
        if (Math.abs(v) >= 10.0) {
            double fixed = v / 100.0;
            System.out.println("[" + activeClient + "] Standings OCR read " + v
                    + " — impossible, dropped decimal point → " + fixed);
            v = fixed;
        }
        if (Math.abs(v) >= 8.0 && Math.abs(v) < 9.0) {
            double fixed = Math.signum(v) * (Math.abs(v) - 8.0);
            System.out.println("[" + activeClient + "] Standings OCR read " + v
                    + " — impossible, leading 8 is a misread 0 → " + fixed);
            v = fixed;
        }
        return v;
    }

    // -----------------------------------------------------------------------
    // Template clicking — eve_automation.py
    // -----------------------------------------------------------------------

    /**
     * Left-click: find the named template on screen and click its centre.
     * exit 0 = clicked, exit 1 = not found within timeout.
     */
    public void clickTemplate(String key) {
        dbg("[%s] [" + activeClient + "] clickTemplate: %s%n", ts(), key);
        JsonObject c = cmd("click");
        c.addProperty("key", key);
        sendAction(c);
    }

    /** As {@link #clickTemplate}, but returns whether the template was found and clicked. */
    public boolean clickTemplateChecked(String key) {
        dbg("[%s] [" + activeClient + "] clickTemplateChecked: %s%n", ts(), key);
        JsonObject c = cmd("click");
        c.addProperty("key", key);
        return sendBool(c);
    }

    /**
     * Color-disambiguated click: among all same-shape candidates for {@code key}, click the one
     * whose on-screen color best matches the template. Use for buttons that differ only by hue
     * (the orange "Bad" vs red "Terrible" standing squares), where mean-normalized template
     * matching can otherwise pick the wrong color. Returns true if a candidate was clicked.
     */
    public boolean clickColor(String key) {
        dbg("[%s] [" + activeClient + "] clickColor: %s%n", ts(), key);
        JsonObject c = cmd("click_color");
        c.addProperty("key", key);
        return sendBool(c);
    }

    /**
     * Color-disambiguated click confined to a screen region. The low-threshold candidate hunt
     * of {@link #clickColor} matches all over a full screen (it once clicked the orange UNDOCK
     * button); scoping it to the box where the target must live prevents that.
     */
    public boolean clickColorInRegion(String key, int x, int y, int width, int height) {
        dbg("[%s] [" + activeClient + "] clickColorInRegion: %s in %d,%d %dx%d%n",
                ts(), key, x, y, width, height);
        JsonObject c = cmd("click_color");
        c.addProperty("key", key);
        JsonObject region = new JsonObject();
        region.addProperty("x", x);
        region.addProperty("y", y);
        region.addProperty("w", width);
        region.addProperty("h", height);
        c.add("region", region);
        return sendBool(c);
    }

    /**
     * Left-click a template, but only match it inside the given screen region
     * (x, y, width, height). Use when the same template (e.g. a close "X") can appear
     * in more than one window and only the one inside the box should be clicked.
     * Returns true if a match was found and clicked within the default timeout.
     */
    public boolean clickTemplateInRegion(String key, int x, int y, int width, int height) {
        dbg("[%s] [" + activeClient + "] clickTemplateInRegion: %s in %d,%d %dx%d%n",
                ts(), key, x, y, width, height);
        JsonObject c = cmd("click");
        c.addProperty("key", key);
        JsonObject region = new JsonObject();
        region.addProperty("x", x);
        region.addProperty("y", y);
        region.addProperty("w", width);
        region.addProperty("h", height);
        c.add("region", region);
        return sendBool(c);
    }

    /**
     * Left-click a template, offset offsetY pixels below its centre.
     */
    public void clickTemplateBelow(String key, int offsetY) {
        dbg("[%s] [" + activeClient + "] clickTemplateBelow: %s +%dpx%n", ts(), key, offsetY);
        JsonObject c = cmd("click");
        c.addProperty("key", key);
        c.addProperty("offset_y", offsetY);
        sendAction(c);
    }

    /**
     * Double-click: find the named template on screen and double-click its centre.
     * Used for: activating ships in hangar, opening agent dialog.
     */
    public void doubleClickTemplate(String key) {
        dbg("[%s] [" + activeClient + "] doubleClickTemplate: %s%n", ts(), key);
        JsonObject c = cmd("double_click");
        c.addProperty("key", key);
        sendAction(c);
    }

    /**
     * Right-click: find the named template on screen and right-click its centre.
     */
    public void rightClickTemplate(String key) {
        dbg("[%s] [" + activeClient + "] rightClickTemplate: %s%n", ts(), key);
        JsonObject c = cmd("right_click");
        c.addProperty("key", key);
        sendAction(c);
    }

    /**
     * Right-click at fixed screen coordinates (not template-based).
     */
    public void rightClickPos(int x, int y) {
        dbg("[%s] [" + activeClient + "] rightClickPos: (%d,%d)%n", ts(), x, y);
        JsonObject c = cmd("right_click_pos");
        c.addProperty("x", x);
        c.addProperty("y", y);
        sendAction(c);
    }

    /**
     * Left-click at fixed screen coordinates (not template-based).
     */
    public void clickPos(int x, int y) {
        dbg("[%s] [" + activeClient + "] clickPos: (%d,%d)%n", ts(), x, y);
        JsonObject c = cmd("click_pos");
        c.addProperty("x", x);
        c.addProperty("y", y);
        sendAction(c);
    }

    /**
     * Press-hold-drag at fixed screen coordinates by (dx,dy). In empty space this
     * rotates the EVE camera slightly, forcing a fresh frame render so a stale screen
     * re-paints — used to help template detection. Does not move the ship.
     */
    public void dragPos(int x, int y, int dx, int dy) {
        dbg("[%s] [" + activeClient + "] dragPos: (%d,%d) by (%d,%d)%n", ts(), x, y, dx, dy);
        JsonObject c = cmd("drag_pos");
        c.addProperty("x", x);
        c.addProperty("y", y);
        c.addProperty("dx", dx);
        c.addProperty("dy", dy);
        sendAction(c);
    }

    /**
     * Hover: find the named template and move mouse to its centre without clicking.
     * Used for opening submenus on hover (e.g. Locations in right-click context menu).
     */
    public void hoverTemplate(String key) {
        dbg("[%s] [" + activeClient + "] hoverTemplate: %s%n", ts(), key);
        JsonObject c = cmd("hover");
        c.addProperty("key", key);
        sendAction(c);
    }

    /**
     * E+left-click: hold the E key while left-clicking the template centre.
     * Used for stargate jumps and activate-gate interactions.
     */
    public void eClickTemplate(String key) {
        dbg("[%s] [" + activeClient + "] eClickTemplate: %s%n", ts(), key);
        JsonObject c = cmd("e_click");
        c.addProperty("key", key);
        sendAction(c);
    }

    /**
     * W+left-click: hold the W key while left-clicking the template centre.
     * Used for keep-at-range commands in combat.
     */
    public void wClickTemplate(String key) {
        dbg("[%s] [" + activeClient + "] wClickTemplate: %s%n", ts(), key);
        JsonObject c = cmd("w_click");
        c.addProperty("key", key);
        sendAction(c);
    }

    /**
     * Q+left-click: hold the Q key while left-clicking the template centre.
     * Used for orbit commands in combat.
     */
    public void qClickTemplate(String key) {
        dbg("[%s] [" + activeClient + "] qClickTemplate: %s%n", ts(), key);
        JsonObject c = cmd("q_click");
        c.addProperty("key", key);
        sendAction(c);
    }

    /**
     * Ctrl+left-click: hold Ctrl while left-clicking the template centre.
     * Used for locking targets in combat.
     */
    public void ctrlClickTemplate(String key) {
        dbg("[%s] [" + activeClient + "] ctrlClickTemplate: %s%n", ts(), key);
        JsonObject c = cmd("ctrl_click");
        c.addProperty("key", key);
        sendAction(c);
    }

    /**
     * Ctrl+click ALL matches of a template on screen (lock all targets).
     * Returns the number of targets clicked, or 0 if none found.
     */
    public int ctrlClickAllTemplate(String key) {
        dbg("[%s] [" + activeClient + "] ctrlClickAllTemplate: %s%n", ts(), key);
        JsonObject c = cmd("ctrl_click_all");
        c.addProperty("key", key);
        JsonObject r = daemon.send(c);
        if (r == null || !r.has("value") || r.get("value").isJsonNull()) {
            System.err.printf("[%s] [" + activeClient + "] ctrlClickAllTemplate: no result.%n", ts());
            return 0;
        }
        int count = r.get("value").getAsInt();
        dbg("[%s] [" + activeClient + "] ctrlClickAllTemplate: locked %d targets.%n", ts(), count);
        return count;
    }

    /** Count distinct on-screen matches of a template. Returns 0 if none / no result. */
    public int countTemplate(String key) {
        JsonObject c = cmd("count");
        c.addProperty("key", key);
        JsonObject r = daemon.send(c);
        if (r == null || !r.has("value") || r.get("value").isJsonNull()) {
            System.err.printf("[%s] [" + activeClient + "] countTemplate: no result.%n", ts());
            return 0;
        }
        int count = r.get("value").getAsInt();
        dbg("[%s] [" + activeClient + "] countTemplate(%s): %d%n", ts(), key, count);
        return count;
    }

    /**
     * Press each character in the keys string sequentially.
     * Used for activating module groups in combat (e.g., "adc").
     */
    public void keyPress(String keys) {
        dbg("[%s] [" + activeClient + "] keyPress: %s%n", ts(), keys);
        JsonObject c = cmd("key_press");
        c.addProperty("keys", keys);
        sendAction(c);
    }

    public void shiftKeyPress(String keys) {
        dbg("[%s] [" + activeClient + "] shiftKeyPress: %s%n", ts(), keys);
        JsonObject c = cmd("shift_key_press");
        c.addProperty("keys", keys);
        sendAction(c);
    }

    public void ctrlKeyPress(String keys) {
        dbg("[%s] [" + activeClient + "] ctrlKeyPress: %s%n", ts(), keys);
        JsonObject c = cmd("ctrl_key_press");
        c.addProperty("keys", keys);
        sendAction(c);
    }

    public void altKeyPress(String key) {
        dbg("[%s] [" + activeClient + "] altKeyPress: %s%n", ts(), key);
        JsonObject c = cmd("alt_key_press");
        c.addProperty("key", key);
        sendAction(c);
    }

    /**
     * Hold Ctrl and press R — reloads modules in EVE.
     */
    public void ctrlR() {
        dbg("[%s] [" + activeClient + "] ctrlR (reload modules)%n", ts());
        sendAction(cmd("ctrl_r"));
    }

    /**
     * Pod check (two-stage). The daemon first does a cheap passive match on the
     * "no_ship" module sliver (visible only in a ship); if it's missing it confirms
     * by right-clicking our ship and looking for the "Eject" menu item, dismissing
     * the menu with a left-click in space. Returns true if podded, false otherwise.
     */
    public boolean isFlyingPod() {
        dbg("[%s] [" + activeClient + "] podCheck (right-click centre → look for eject)%n", ts());
        return sendBool(cmd("pod_check"));
    }

    /**
     * Repair + activate the correct ship for the given mission title.
     *
     * Calls: eve_automation.py --repair-ship "<missionTitle>"
     *
     * The Python script:
     *   1. Resolves the ship template from ship_mission_map.json based on the title
     *   2. Right-clicks the ship icon → Get Repair Quote → Repair All → Yes
     *   3. Right-clicks the ship icon → Board Ship to make it the active ship
     *
     * @param missionTitle The accepted mission's title (used for ship lookup)
     * @return true if repair + activation succeeded, false on failure
     */
    /**
     * @return dialog_matched key (e.g. "mission_hawk") if identified, empty string
     *         if repair succeeded but no dialog matched, "SHIP_NOT_FOUND" if the
     *         ship was not in hangar (mission quit + new mission requested), or null on failure.
     */
    public static final String SHIP_NOT_FOUND = "SHIP_NOT_FOUND";

    public String repairShip(String missionTitle) {
        System.out.printf("[%s] [" + activeClient + "] repairShip for mission: %s%n", ts(), missionTitle);
        JsonObject c = cmd("repair_ship");
        c.addProperty("title", missionTitle != null ? missionTitle : "");
        JsonObject r = daemon.send(c);
        if (r == null || !r.has("value") || r.get("value").isJsonNull() || !r.get("value").isJsonObject()) {
            System.err.printf("[%s] [" + activeClient + "] repairShip FAILED (no daemon result).%n", ts());
            return null;
        }
        JsonObject value = r.getAsJsonObject("value");
        System.out.printf("[%s] [" + activeClient + "] repairShip result: %s%n", ts(), value);
        if (value.has("ship_not_found") && value.get("ship_not_found").getAsBoolean()) {
            return SHIP_NOT_FOUND;
        }
        if (value.has("failed") && value.get("failed").getAsBoolean()) {
            return null;  // a required UI element was missing mid-flow — caller aborts
        }
        if (value.has("dialog_matched")) {
            return value.get("dialog_matched").getAsString();
        }
        return "";  // success but no dialog match (OCR fallback was used)
    }

    /**
     * Abandon the active mission and request a new one (quit_mission → Yes → request_mission,
     * done in the daemon). Returns true if the quit button was found and clicked.
     */
    public boolean quitMissionAndRequestNew() {
        dbg("[%s] [" + activeClient + "] quitMissionAndRequestNew%n", ts());
        return sendBool(cmd("quit_mission"));
    }

    /**
     * Take ONE screenshot and check keys in order; returns the first matched key name,
     * or null if none found. Single Python process — use this instead of chaining
     * multiple waitForTemplate calls.
     */
    public String findAnyTemplate(String... keys) {
        dbg("[%s] [" + activeClient + "] findAnyTemplate: %s%n", ts(), java.util.Arrays.toString(keys));
        JsonObject c = cmd("find_any");
        JsonArray arr = new JsonArray();
        for (String key : keys) arr.add(key);
        c.add("keys", arr);
        return sendString(c);
    }

    /**
     * Find a template and return its centre coordinates as {x, y}, or null if not found.
     */
    public int[] findTemplatePos(String key) {
        return findTemplatePos(key, "find_pos");
    }

    /**
     * Like findTemplatePos but, when the template matches several times, returns the
     * highest one on screen (smallest y) instead of the best-scoring one. Used for the
     * feed, where the newest entry is the top one.
     */
    public int[] findTemplatePosTopmost(String key) {
        return findTemplatePos(key, "find_pos_top");
    }

    private int[] findTemplatePos(String key, String command) {
        dbg("[%s] [" + activeClient + "] " + command + ": %s%n", ts(), key);
        JsonObject c = cmd(command);
        c.addProperty("key", key);
        String result = sendString(c);
        if (result != null && result.contains(",")) {
            String[] parts = result.trim().split(",");
            try {
                return new int[]{ Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) };
            } catch (NumberFormatException e) {
                System.err.printf("[%s] [" + activeClient + "] " + command + ": bad output '%s'%n", ts(), result);
            }
        }
        return null;
    }

    /**
     * Wait up to timeoutSeconds for a template to appear. Returns true if found.
     */
    public boolean waitForTemplate(String key, int timeoutSeconds) {
        dbg("[%s] [" + activeClient + "] waitForTemplate: %s (%ds)%n", ts(), key, timeoutSeconds);
        JsonObject c = cmd("wait");
        c.addProperty("key", key);
        c.addProperty("timeout", timeoutSeconds);
        return sendBool(c);
    }

    public boolean waitForTemplateGone(String key, int timeoutSeconds) {
        dbg("[%s] [" + activeClient + "] waitForTemplateGone: %s (%ds)%n", ts(), key, timeoutSeconds);
        JsonObject c = cmd("wait_gone");
        c.addProperty("key", key);
        c.addProperty("timeout", timeoutSeconds);
        return sendBool(c);
    }

    /**
     * Poll for any of the given templates; returns the first matched key name,
     * or null on timeout. One screenshot per poll cycle checks all keys.
     */
    public String waitForAnyTemplate(int timeoutSeconds, String... keys) {
        dbg("[%s] [" + activeClient + "] waitForAnyTemplate: %s (%ds)%n",
                ts(), java.util.Arrays.toString(keys), timeoutSeconds);
        JsonObject c = cmd("wait_any");
        JsonArray arr = new JsonArray();
        for (String key : keys) arr.add(key);
        c.add("keys", arr);
        c.addProperty("timeout", timeoutSeconds);
        return sendString(c);
    }

    /**
     * Wait for a template to appear then click it.
     * Returns true on success, false on timeout.
     */
    public boolean waitAndClickTemplate(String key, int timeoutSeconds) {
        dbg("[%s] [" + activeClient + "] waitAndClickTemplate: %s (%ds)%n", ts(), key, timeoutSeconds);
        JsonObject c = cmd("wait_click");
        c.addProperty("key", key);
        c.addProperty("timeout", timeoutSeconds);
        return sendBool(c);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String extractShipTarget(Mission mission) {
        String[] fields = { mission.getObjective(), mission.getRawText(), mission.getTitle() };
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "(?i)des[a-z]{0,4}y\\s+(?:the\\s+)?([A-Z][a-zA-Z]+)");
        for (String field : fields) {
            if (field == null) continue;
            java.util.regex.Matcher m = p.matcher(field);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    private String str(JsonObject json, String key) {
        return (json.has(key) && !json.get(key).isJsonNull())
                ? json.get(key).getAsString() : null;
    }

    private String ts() {
        return java.time.LocalDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
    }
}