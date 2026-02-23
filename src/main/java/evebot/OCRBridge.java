package evebot;

import com.google.gson.Gson;
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
 *  eve_automation.py   — template matching + clicking; called with an action name
 *
 * Public API:
 *
 *   Mission readMission(x, y, w, h)   — OCR read
 *   void    clickTemplate(String key) — find & click a named UI element
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
            if (new File(path).exists()) {
                System.out.println("[OCRBridge] Found " + filename + " at: " + path);
                return path;
            }
        }
        System.err.println("[OCRBridge] WARNING: " + filename + " not found.");
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
            if (result.isEmpty()) {
                System.err.println("[OCRBridge] Python produced no output.");
                return null;
            }
            return result;

        } catch (Exception e) {
            System.err.println("[OCRBridge] Error running Python: " + e.getMessage());
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // OCR — mission_reader.py
    // -----------------------------------------------------------------------

    public Mission readMission(int x, int y, int width, int height) {
        String json = runPython("python", missionReaderPath,
                String.valueOf(x), String.valueOf(y),
                String.valueOf(width), String.valueOf(height));
        return parseMissionJson(json);
    }

    private Mission parseMissionJson(String jsonString) {
        if (jsonString == null || jsonString.isEmpty()) return null;
        System.out.println("[OCRBridge] Raw OCR output: " + jsonString);
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

    // -----------------------------------------------------------------------
    // Template clicking — eve_automation.py --click <key>
    // -----------------------------------------------------------------------

    /**
     * Asks eve_automation.py to find the named template on screen and click it.
     * Known keys: request_mission, accept, decline, track, close
     *
     * Fire-and-forget — the Python script handles wait/timeout internally.
     * Errors are logged but not propagated so the state machine keeps running.
     */
    public void clickTemplate(String key) {
        System.out.println("[OCRBridge] clickTemplate: " + key);
        runPython("python", automationScriptPath, "--click", key);
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
}