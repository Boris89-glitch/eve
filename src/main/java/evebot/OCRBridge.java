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
 *  eve_automation.py   — template matching + clicking; called with action flags
 *
 * Public API:
 *
 *   Mission readMission(x, y, w, h)        — OCR read a screen region
 *   void    clickTemplate(String key)       — find & left-click a template on screen
 *   void    rightClickTemplate(String key)  — find & right-click a template on screen
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
        System.err.printf("[%s] [OCRBridge] WARNING: " + filename + " not found.%n", java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")));
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

            // Stream stderr to console in background
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
            // Click commands exit cleanly with no stdout — that is normal.
            // Only treat empty output as an error for OCR reads (caller checks null).
            return result.isEmpty() ? "" : result;

        } catch (Exception e) {
            System.err.println("[OCRBridge] Error running Python: " + e.getMessage());
            return null;
        }
    }

    /** Like runPython but returns the process exit code instead of stdout. -1 on exception. */
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
            // drain stdout
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
        System.out.printf("[%s] [OCRBridge] Raw OCR output: %s%n", java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")), jsonString);
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
     * Left-click: find the named template on screen and click its centre.
     *
     * eve_automation.py --click <key>
     *   exit 0 = clicked successfully
     *   exit 1 = template not found within timeout
     *
     * Errors are logged but not propagated — the state machine continues.
     */
    public void clickTemplate(String key) {
        System.out.println("[OCRBridge] clickTemplate: " + key);
        runPython("python", automationScriptPath, "--click", key);
    }

    /**
     * Right-click: find the named template on screen and right-click its centre.
     *
     * eve_automation.py --right-click <key>
     *
     * This requires the --right-click flag to be handled by eve_automation.py
     * (see cmd_right_click in that script).
     */
    public void rightClickTemplate(String key) {
        System.out.println("[OCRBridge] rightClickTemplate: " + key);
        runPython("python", automationScriptPath, "--right-click", key);
    }

    /**
     * Wait up to timeoutSeconds for a template to appear. Returns true if found.
     */
    public boolean waitForTemplate(String key, int timeoutSeconds) {
        System.out.printf("[%s] [OCRBridge] waitForTemplate: " + key + " (" + timeoutSeconds + "s)%n", java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")));
        int code = runPythonExitCode("python", automationScriptPath,
                "--wait", key, "--timeout", String.valueOf(timeoutSeconds));
        return code == 0;
    }

    public boolean waitForTemplateGone(String key, int timeoutSeconds) {
        System.out.printf("[%s] [OCRBridge] waitForTemplateGone: " + key + " (" + timeoutSeconds + "s)%n", java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")));
        int code = runPythonExitCode("python", automationScriptPath,
                "--wait-gone", key, "--timeout", String.valueOf(timeoutSeconds));
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
}