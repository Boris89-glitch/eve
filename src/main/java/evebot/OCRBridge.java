// src/main/java/evebot/OCRBridge.java
package evebot;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import evebot.models.Mission;

import java.io.*;
import java.nio.file.Paths;
import java.util.Map;

public class OCRBridge {
    private String pythonScriptPath;
    private Gson gson;

    public OCRBridge() {
        // Get the absolute path to the Python script
        this.pythonScriptPath = Paths.get("ocr", "mission_reader.py").toAbsolutePath().toString();
        this.gson = new Gson();

        // Check if Python script exists
        File scriptFile = new File(pythonScriptPath);
        if (!scriptFile.exists()) {
            System.err.println("WARNING: Python script not found at: " + pythonScriptPath);
        }
    }

    public String readMissionFromScreen(int x, int y, int width, int height) {
        try {
            // Build process to run Python script with coordinates
            ProcessBuilder pb = new ProcessBuilder("python", pythonScriptPath,
                    String.valueOf(x), String.valueOf(y),
                    String.valueOf(width), String.valueOf(height));

            // Set environment variables if needed
            Map<String, String> env = pb.environment();
            env.put("PYTHONIOENCODING", "utf-8");
            env.put("PYTHONUNBUFFERED", "1");

            // Redirect error stream to output stream
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // Read output
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), "UTF-8"));

            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }

            int exitCode = process.waitFor();

            if (exitCode == 0 && output.length() > 0) {
                return output.toString();
            } else {
                System.err.println("Python process exited with code: " + exitCode);
                return null;
            }

        } catch (Exception e) {
            System.err.println("Error in OCRBridge: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    // NEW: Parse JSON output from Python into Mission object
    public Mission parseMissionJson(String jsonString) {
        try {
            JsonObject json = gson.fromJson(jsonString, JsonObject.class);

            // Check for error message
            if (json.has("error")) {
                System.err.println("OCR Error: " + json.get("error").getAsString());
                return null;
            }

            // Create new Mission object with parsed data
            Mission mission = new Mission();

            // Set basic fields
            mission.setTitle(getJsonString(json, "title"));
            mission.setObjective(getJsonString(json, "objective"));
            mission.setTarget(getJsonString(json, "target"));
            mission.setLocation(getJsonString(json, "location"));
            mission.setSecurity(getJsonString(json, "security"));
            mission.setRawText(getJsonString(json, "raw_text"));

            // Set mission type (handle potential parsing errors)
            try {
                String typeStr = getJsonString(json, "type");
                if (typeStr != null && !typeStr.isEmpty()) {
                    mission.setType(Mission.MissionType.valueOf(typeStr));
                } else {
                    mission.setType(Mission.MissionType.OTHER);
                }
            } catch (IllegalArgumentException e) {
                System.err.println("Unknown mission type: " + getJsonString(json, "type"));
                mission.setType(Mission.MissionType.OTHER);
            }

            return mission;

        } catch (JsonSyntaxException e) {
            System.err.println("Invalid JSON format: " + e.getMessage());
            System.err.println("Raw output: " + jsonString);
            return null;
        } catch (Exception e) {
            System.err.println("Error parsing mission JSON: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    // Helper method to safely get string from JSON
    private String getJsonString(JsonObject json, String key) {
        if (json.has(key) && !json.get(key).isJsonNull()) {
            return json.get(key).getAsString();
        }
        return "Unknown";
    }

    // NEW: Combined method to read and parse mission in one call
    public Mission readMission(int x, int y, int width, int height) {
        String jsonOutput = readMissionFromScreen(x, y, width, height);
        if (jsonOutput != null && !jsonOutput.isEmpty()) {
            return parseMissionJson(jsonOutput);
        }
        return null;
    }

    // Test method
    public static void main(String[] args) {
        OCRBridge bridge = new OCRBridge();

        // Test with some coordinates
        Mission mission = bridge.readMission(100, 100, 400, 300);

        if (mission != null) {
            System.out.println("Mission read successfully:");
            System.out.println("  Title: " + mission.getTitle());
            System.out.println("  Target: " + mission.getTarget());
            System.out.println("  Location: " + mission.getLocation());
            System.out.println("  Security: " + mission.getSecurity());
            System.out.println("  Type: " + mission.getType());
        } else {
            System.out.println("Failed to read mission");
        }
    }
}