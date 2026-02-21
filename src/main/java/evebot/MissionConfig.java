package evebot;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import evebot.models.Mission;

import java.nio.file.*;
import java.util.*;

/**
 * MissionConfig loads and evaluates accept/decline rules from
 * config/mission_rules.json.
 *
 * Rule priority (top-to-bottom):
 *   1. Decline rules  — if ANY decline rule matches → decline immediately
 *   2. Accept rules   — if ANY accept rule matches  → accept
 *   3. Default        → decline (unknown = safe to skip)
 *
 * Example config/mission_rules.json:
 * {
 *   "accept": [
 *     { "title_contains": "anomic",           "comment": "Burner missions" },
 *     { "title_contains": "recon",            "comment": "Recon missions"  },
 *     { "title_contains": "annihilation",     "comment": "Anni missions"   }
 *   ],
 *   "decline": [
 *     { "target_contains": "serpentis",       "comment": "No serpentis burners" },
 *     { "target_contains": "jaguar",          "comment": "No jaguar burners"    },
 *     { "location_contains": "j0c-",          "comment": "Too far"              },
 *     { "location_contains": "p33-",          "comment": "Too far"              }
 *   ]
 * }
 */
public class MissionConfig {

    private static final Path RULES_FILE = Paths.get("config", "mission_rules.json");

    private final List<Rule> acceptRules  = new ArrayList<>();
    private final List<Rule> declineRules = new ArrayList<>();
    private final Gson gson = new Gson();

    // -----------------------------------------------------------------------

    public MissionConfig() {
        loadRules();
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    public enum Decision { ACCEPT, DECLINE }

    /**
     * Evaluate a mission and return ACCEPT or DECLINE, printing the reason
     * to the terminal.
     */
    public Decision evaluate(Mission mission) {
        if (mission == null) {
            System.out.println("[MissionConfig] Mission is null → DECLINE");
            return Decision.DECLINE;
        }

        // 1. Check decline rules first
        for (Rule rule : declineRules) {
            if (rule.matches(mission)) {
                System.out.printf("[DECISION] DECLINE — %s  (rule: %s)%n",
                        mission.getTitle(), rule.comment);
                return Decision.DECLINE;
            }
        }

        // 2. Check accept rules
        for (Rule rule : acceptRules) {
            if (rule.matches(mission)) {
                System.out.printf("[DECISION] ACCEPT  — %s  (rule: %s)%n",
                        mission.getTitle(), rule.comment);
                return Decision.ACCEPT;
            }
        }

        // 3. Default: decline unknown missions
        System.out.printf("[DECISION] DECLINE — %s  (no matching accept rule)%n",
                mission.getTitle());
        return Decision.DECLINE;
    }

    // -----------------------------------------------------------------------
    // Rule loading
    // -----------------------------------------------------------------------

    private void loadRules() {
        if (!Files.exists(RULES_FILE)) {
            System.out.println("[MissionConfig] No rules file found at " + RULES_FILE
                    + " — writing default rules.");
            writeDefaultRules();
            loadRules(); // recurse once
            return;
        }

        try {
            String content = new String(Files.readAllBytes(RULES_FILE));
            JsonObject json = gson.fromJson(content, JsonObject.class);

            parseRules(json.getAsJsonArray("accept"),  acceptRules);
            parseRules(json.getAsJsonArray("decline"), declineRules);

            System.out.printf("[MissionConfig] Loaded %d accept / %d decline rules.%n",
                    acceptRules.size(), declineRules.size());
        } catch (Exception e) {
            System.err.println("[MissionConfig] Failed to load rules: " + e.getMessage());
        }
    }

    private void parseRules(JsonArray arr, List<Rule> target) {
        if (arr == null) return;
        arr.forEach(el -> {
            JsonObject obj = el.getAsJsonObject();
            Rule rule = new Rule();
            rule.titleContains    = getString(obj, "title_contains");
            rule.targetContains   = getString(obj, "target_contains");
            rule.locationContains = getString(obj, "location_contains");
            rule.securityContains = getString(obj, "security_contains");
            rule.type             = getString(obj, "type");
            rule.comment          = getString(obj, "comment");
            target.add(rule);
        });
    }

    private String getString(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull()
                ? obj.get(key).getAsString() : null;
    }

    private void writeDefaultRules() {
        String defaultJson = "{\n"
                + "  \"accept\": [\n"
                + "    { \"title_contains\": \"anomic\",       \"comment\": \"Burner missions\" },\n"
                + "    { \"title_contains\": \"recon\",        \"comment\": \"Recon missions\"  },\n"
                + "    { \"title_contains\": \"annihilation\", \"comment\": \"Anni missions\"   }\n"
                + "  ],\n"
                + "  \"decline\": [\n"
                + "    { \"target_contains\": \"serpentis\",   \"comment\": \"No serpentis burners\" },\n"
                + "    { \"target_contains\": \"jaguar\",      \"comment\": \"No jaguar burners\"    },\n"
                + "    { \"location_contains\": \"j0c-\",      \"comment\": \"System too far\"       },\n"
                + "    { \"location_contains\": \"p33-\",      \"comment\": \"System too far\"       }\n"
                + "  ]\n"
                + "}\n";
        try {
            Files.createDirectories(RULES_FILE.getParent());
            Files.write(RULES_FILE, defaultJson.getBytes(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            System.err.println("[MissionConfig] Could not write default rules: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Inner rule class
    // -----------------------------------------------------------------------

    private static class Rule {
        String titleContains;
        String targetContains;
        String locationContains;
        String securityContains;
        String type;
        String comment = "unnamed rule";

        boolean matches(Mission m) {
            if (titleContains != null
                    && !safe(m.getTitle()).contains(titleContains.toLowerCase()))
                return false;
            if (targetContains != null
                    && !safe(m.getTarget()).contains(targetContains.toLowerCase()))
                return false;
            if (locationContains != null
                    && !safe(m.getLocation()).contains(locationContains.toLowerCase()))
                return false;
            if (securityContains != null
                    && !safe(m.getSecurity()).contains(securityContains.toLowerCase()))
                return false;
            if (type != null && m.getType() != null
                    && !m.getType().name().equalsIgnoreCase(type))
                return false;
            return true;
        }

        private String safe(String s) {
            return s == null ? "" : s.toLowerCase();
        }
    }
}