package evebot;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import evebot.models.Mission;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

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

    // -----------------------------------------------------------------------

    public OCRBridge() {
        this.missionReaderPath    = resolveScript("mission_reader.py");
        this.automationScriptPath = resolveScript("eve_automation.py");
        this.gson = new Gson();
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
                System.out.println("[OCRBridge] Found " + filename + " at: " + path);
                return path;
            }
        }
        System.err.printf("[%s] [OCRBridge] WARNING: %s not found.%n",
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
                System.err.println("[OCRBridge] Python exited with code " + exitCode);
            }

            String result = output.toString().trim();
            return result.isEmpty() ? "" : result;

        } catch (Exception e) {
            System.err.println("[OCRBridge] Error running Python: " + e.getMessage());
            return null;
        }
    }

    /** Run Python and return exit code. -1 on exception. */
    private int runPythonExitCode(String... args) {
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
            process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            return process.waitFor();
        } catch (Exception e) {
            System.err.println("[OCRBridge] Error running Python: " + e.getMessage());
            return -1;
        }
    }

    // -----------------------------------------------------------------------
    // OCR — mission_reader.py
    // -----------------------------------------------------------------------

    public Mission readMission(int x, int y, int width, int height) {
        String json = runPython("python", missionReaderPath,
                String.valueOf(x), String.valueOf(y),
                String.valueOf(width), String.valueOf(height));
        if (json == null || json.isEmpty()) return null;
        return parseMissionJson(json);
    }

    private Mission parseMissionJson(String jsonString) {
        if (jsonString == null || jsonString.isEmpty()) return null;
        System.out.printf("[%s] [OCRBridge] Raw OCR output: %s%n", ts(), jsonString);
        try {
            JsonObject json = gson.fromJson(jsonString, JsonObject.class);
            if (json.has("error")) {
                System.err.println("[OCRBridge] OCR error: " + json.get("error").getAsString());
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
            System.err.println("[OCRBridge] Invalid JSON: " + e.getMessage());
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
        System.out.printf("[%s] [OCRBridge] Standings OCR output: %s%n", ts(), json);
        try {
            JsonObject obj = gson.fromJson(json, JsonObject.class);
            if (obj.has("error")) {
                System.err.println("[OCRBridge] Standings OCR error: " + obj.get("error").getAsString());
                return Double.NaN;
            }
            return obj.get("standings").getAsDouble();
        } catch (Exception e) {
            System.err.println("[OCRBridge] Standings parse error: " + e.getMessage());
            return Double.NaN;
        }
    }

    // -----------------------------------------------------------------------
    // Template clicking — eve_automation.py
    // -----------------------------------------------------------------------

    /**
     * Left-click: find the named template on screen and click its centre.
     * exit 0 = clicked, exit 1 = not found within timeout.
     */
    public void clickTemplate(String key) {
        System.out.printf("[%s] [OCRBridge] clickTemplate: %s%n", ts(), key);
        runPython("python", automationScriptPath, "--click", key);
    }

    /**
     * Left-click a template, offset offsetY pixels below its centre.
     */
    public void clickTemplateBelow(String key, int offsetY) {
        System.out.printf("[%s] [OCRBridge] clickTemplateBelow: %s +%dpx%n", ts(), key, offsetY);
        runPython("python", automationScriptPath, "--click", key, "--offset-y", String.valueOf(offsetY));
    }

    /**
     * Double-click: find the named template on screen and double-click its centre.
     * Used for: activating ships in hangar, opening agent dialog.
     */
    public void doubleClickTemplate(String key) {
        System.out.printf("[%s] [OCRBridge] doubleClickTemplate: %s%n", ts(), key);
        runPython("python", automationScriptPath, "--double-click", key);
    }

    /**
     * Right-click: find the named template on screen and right-click its centre.
     */
    public void rightClickTemplate(String key) {
        System.out.printf("[%s] [OCRBridge] rightClickTemplate: %s%n", ts(), key);
        runPython("python", automationScriptPath, "--right-click", key);
    }

    /**
     * Right-click at fixed screen coordinates (not template-based).
     */
    public void rightClickPos(int x, int y) {
        System.out.printf("[%s] [OCRBridge] rightClickPos: (%d,%d)%n", ts(), x, y);
        runPython("python", automationScriptPath, "--right-click-pos",
                String.valueOf(x), String.valueOf(y));
    }

    /**
     * Hover: find the named template and move mouse to its centre without clicking.
     * Used for opening submenus on hover (e.g. Locations in right-click context menu).
     */
    public void hoverTemplate(String key) {
        System.out.printf("[%s] [OCRBridge] hoverTemplate: %s%n", ts(), key);
        runPython("python", automationScriptPath, "--hover", key);
    }

    /**
     * E+left-click: hold the E key while left-clicking the template centre.
     * Used for stargate jumps and activate-gate interactions.
     */
    public void eClickTemplate(String key) {
        System.out.printf("[%s] [OCRBridge] eClickTemplate: %s%n", ts(), key);
        runPython("python", automationScriptPath, "--e-click", key);
    }

    /**
     * W+left-click: hold the W key while left-clicking the template centre.
     * Used for keep-at-range commands in combat.
     */
    public void wClickTemplate(String key) {
        System.out.printf("[%s] [OCRBridge] wClickTemplate: %s%n", ts(), key);
        runPython("python", automationScriptPath, "--w-click", key);
    }

    /**
     * Q+left-click: hold the Q key while left-clicking the template centre.
     * Used for orbit commands in combat.
     */
    public void qClickTemplate(String key) {
        System.out.printf("[%s] [OCRBridge] qClickTemplate: %s%n", ts(), key);
        runPython("python", automationScriptPath, "--q-click", key);
    }

    /**
     * Ctrl+left-click: hold Ctrl while left-clicking the template centre.
     * Used for locking targets in combat.
     */
    public void ctrlClickTemplate(String key) {
        System.out.printf("[%s] [OCRBridge] ctrlClickTemplate: %s%n", ts(), key);
        runPython("python", automationScriptPath, "--ctrl-click", key);
    }

    /**
     * Ctrl+click ALL matches of a template on screen (lock all targets).
     * Returns the number of targets clicked, or 0 if none found.
     */
    public int ctrlClickAllTemplate(String key) {
        System.out.printf("[%s] [OCRBridge] ctrlClickAllTemplate: %s%n", ts(), key);
        String output = runPython("python", automationScriptPath, "--ctrl-click-all", key);
        if (output == null || output.isEmpty()) {
            System.err.printf("[%s] [OCRBridge] ctrlClickAllTemplate: no output.%n", ts());
            return 0;
        }
        try {
            int count = Integer.parseInt(output.trim());
            System.out.printf("[%s] [OCRBridge] ctrlClickAllTemplate: locked %d targets.%n", ts(), count);
            return count;
        } catch (NumberFormatException e) {
            System.err.printf("[%s] [OCRBridge] ctrlClickAllTemplate: bad output '%s'.%n", ts(), output);
            return 0;
        }
    }

    /**
     * Press each character in the keys string sequentially.
     * Used for activating module groups in combat (e.g., "adc").
     */
    public void keyPress(String keys) {
        System.out.printf("[%s] [OCRBridge] keyPress: %s%n", ts(), keys);
        runPython("python", automationScriptPath, "--key-press", keys);
    }

    public void shiftKeyPress(String keys) {
        System.out.printf("[%s] [OCRBridge] shiftKeyPress: %s%n", ts(), keys);
        runPython("python", automationScriptPath, "--shift-key-press", keys);
    }

    public void ctrlKeyPress(String keys) {
        System.out.printf("[%s] [OCRBridge] ctrlKeyPress: %s%n", ts(), keys);
        runPython("python", automationScriptPath, "--ctrl-key-press", keys);
    }

    public void altKeyPress(String key) {
        System.out.printf("[%s] [OCRBridge] altKeyPress: %s%n", ts(), key);
        runPython("python", automationScriptPath, "--alt-key-press", key);
    }

    /**
     * Hold Ctrl and press R — reloads modules in EVE.
     */
    public void ctrlR() {
        System.out.printf("[%s] [OCRBridge] ctrlR (reload modules)%n", ts());
        runPython("python", automationScriptPath, "--ctrl-r");
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
     *         if repair succeeded but no dialog matched, or null on failure.
     */
    public String repairShip(String missionTitle) {
        System.out.printf("[%s] [OCRBridge] repairShip for mission: %s%n", ts(), missionTitle);
        String output = runPython("python", automationScriptPath,
                "--repair-ship", missionTitle != null ? missionTitle : "");
        if (output == null || output.isEmpty()) {
            System.err.printf("[%s] [OCRBridge] repairShip FAILED (no output).%n", ts());
            return null;
        }
        System.out.printf("[%s] [OCRBridge] repairShip result: %s%n", ts(), output);
        try {
            JsonObject json = gson.fromJson(output, JsonObject.class);
            if (json.has("dialog_matched")) {
                return json.get("dialog_matched").getAsString();
            }
            return "";  // success but no dialog match (OCR fallback was used)
        } catch (JsonSyntaxException e) {
            System.err.printf("[%s] [OCRBridge] repairShip: could not parse JSON: %s%n", ts(), e.getMessage());
            return "";  // repair succeeded (got output) but JSON parse failed — treat as success
        }
    }

    /**
     * Take ONE screenshot and check keys in order; returns the first matched key name,
     * or null if none found. Single Python process — use this instead of chaining
     * multiple waitForTemplate calls.
     */
    public String findAnyTemplate(String... keys) {
        System.out.printf("[%s] [OCRBridge] findAnyTemplate: %s%n", ts(), java.util.Arrays.toString(keys));
        List<String> cmd = new ArrayList<>();
        cmd.add("python");
        cmd.add(automationScriptPath);
        cmd.add("--find-any");
        for (String key : keys) cmd.add(key);
        String result = runPython(cmd.toArray(new String[0]));
        return (result != null && !result.isBlank()) ? result.trim() : null;
    }

    /**
     * Wait up to timeoutSeconds for a template to appear. Returns true if found.
     */
    public boolean waitForTemplate(String key, int timeoutSeconds) {
        System.out.printf("[%s] [OCRBridge] waitForTemplate: %s (%ds)%n", ts(), key, timeoutSeconds);
        int code = runPythonExitCode("python", automationScriptPath,
                "--wait", key, "--timeout", String.valueOf(timeoutSeconds));
        return code == 0;
    }

    public boolean waitForTemplateGone(String key, int timeoutSeconds) {
        System.out.printf("[%s] [OCRBridge] waitForTemplateGone: %s (%ds)%n", ts(), key, timeoutSeconds);
        int code = runPythonExitCode("python", automationScriptPath,
                "--wait-gone", key, "--timeout", String.valueOf(timeoutSeconds));
        return code == 0;
    }

    /**
     * Poll for any of the given templates in one subprocess; returns the first
     * matched key name, or null on timeout. Single Python process — one screenshot
     * per poll cycle checks all keys.
     */
    public String waitForAnyTemplate(int timeoutSeconds, String... keys) {
        System.out.printf("[%s] [OCRBridge] waitForAnyTemplate: %s (%ds)%n",
                ts(), java.util.Arrays.toString(keys), timeoutSeconds);
        List<String> cmd = new ArrayList<>();
        cmd.add("python");
        cmd.add(automationScriptPath);
        cmd.add("--wait-any");
        for (String key : keys) cmd.add(key);
        cmd.add("--timeout");
        cmd.add(String.valueOf(timeoutSeconds));
        String result = runPython(cmd.toArray(new String[0]));
        return (result != null && !result.isBlank()) ? result.trim() : null;
    }

    /**
     * Wait for a template to appear then click it, all in one subprocess.
     * Returns true on success, false on timeout.
     */
    public boolean waitAndClickTemplate(String key, int timeoutSeconds) {
        System.out.printf("[%s] [OCRBridge] waitAndClickTemplate: %s (%ds)%n", ts(), key, timeoutSeconds);
        int code = runPythonExitCode("python", automationScriptPath,
                "--wait-click", key, "--timeout", String.valueOf(timeoutSeconds));
        return code == 0;
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