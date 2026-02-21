package evebot;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import evebot.models.Mission;

import java.io.*;
import java.nio.file.*;

/**
 * OCRBridge calls the Python mission_reader.py script and parses its JSON
 * output into a Mission object.
 *
 * The script is located by searching in order:
 *   1. ocr/mission_reader.py   (standard layout)
 *   2. mission_reader.py       (flat layout / running from project root)
 */
public class OCRBridge {

    private final String pythonScriptPath;
    private final Gson gson;

    // -----------------------------------------------------------------------

    public OCRBridge() {
        this.pythonScriptPath = resolvePythonScript();
        this.gson = new Gson();
    }

    private String resolvePythonScript() {
        System.out.println("[OCRBridge] Working dir: " + java.nio.file.Paths.get("").toAbsolutePath());
        String[] candidates = {
                "ocr/mission_reader.py",
                "mission_reader.py",
                Paths.get("ocr", "mission_reader.py").toAbsolutePath().toString(),
                Paths.get("mission_reader.py").toAbsolutePath().toString()
        };
        for (String path : candidates) {
            if (new File(path).exists()) {
                System.out.println("[OCRBridge] Found Python script at: " + path);
                return path;
            }
        }
        System.err.println("[OCRBridge] WARNING: mission_reader.py not found in any expected location.");
        return "mission_reader.py"; // best-effort fallback
    }

    // -----------------------------------------------------------------------
    // Core: run the Python script and return raw JSON string
    // -----------------------------------------------------------------------

    public String readMissionFromScreen(int x, int y, int width, int height) {

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "python", pythonScriptPath,
                    String.valueOf(x),
                    String.valueOf(y),
                    String.valueOf(width),
                    String.valueOf(height));

            pb.environment().put("PYTHONIOENCODING", "utf-8");
            pb.environment().put("PYTHONUNBUFFERED", "1");
            pb.redirectErrorStream(false); // keep stderr separate so we can log it

            Process process = pb.start();

            // Log stderr without blocking
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

            // Read stdout (the JSON result)
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
    // Parse JSON → Mission
    // -----------------------------------------------------------------------

    public Mission parseMissionJson(String jsonString) {
        if (jsonString == null || jsonString.isEmpty()) return null;

        System.out.println("[OCRBridge] Raw Python output: " + jsonString);

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

            // Parse ship target from objective/raw text (e.g. "Destroy the Enyo")
            mission.setShipTarget(extractShipTarget(mission));

            System.out.println("[OCRBridge] Raw Python output: " + jsonString);

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
            System.err.println("[OCRBridge] Raw output was: " + jsonString);
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Ship target extraction  ("Destroy the Enyo" → "Enyo")
    // -----------------------------------------------------------------------

    /**
     * Looks for "Destroy the <Ship>" or "Destray the <Ship>" (OCR typo)
     * in objective and raw_text fields.
     */
    private String extractShipTarget(Mission mission) {
        String[] fieldsToSearch = {
                mission.getObjective(),
                mission.getRawText(),
                mission.getTitle()
        };

        // Regex: "destroy" (with common OCR variants) followed by optional "the"
        // then the ship name (capitalised word)
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "(?i)des[a-z]{0,4}y\\s+(?:the\\s+)?([A-Z][a-zA-Z]+)"
        );

        for (String field : fieldsToSearch) {
            if (field == null) continue;
            java.util.regex.Matcher m = pattern.matcher(field);
            if (m.find()) {
                return m.group(1); // e.g. "Enyo"
            }
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Combined convenience method
    // -----------------------------------------------------------------------

    public Mission readMission(int x, int y, int width, int height) {
        String json = readMissionFromScreen(x, y, width, height);
        return parseMissionJson(json);
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private String str(JsonObject json, String key) {
        return (json.has(key) && !json.get(key).isJsonNull())
                ? json.get(key).getAsString()
                : null;
    }
}