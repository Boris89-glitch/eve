package evebot;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import evebot.models.Mission;

import java.nio.file.*;
import java.util.*;
import java.util.List;

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

    private static final List<Path> RULES_CANDIDATES = List.of(
            Paths.get("ocr", "config", "mission_rules.json"),
            Paths.get("config", "mission_rules.json"),
            Paths.get("mission_rules.json")
    );

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

    /** Recon is skipped once agent standing climbs above this, in high sec as well as null sec. */
    private static final double RECON_MAX_STANDING = 2.0;

    /** True if this offer is a Recon mission. The title wraps and OCRs inconsistently
     *  ("Recon 1 of 3" / "Recon1of3"), so the raw dialog text is checked too. */
    private static boolean isRecon(Mission m) {
        String title = m.getTitle()   == null ? "" : m.getTitle().toLowerCase();
        String raw   = m.getRawText() == null ? "" : m.getRawText().toLowerCase();
        return title.contains("recon") || raw.contains("recon");
    }

    /**
     * Evaluate a mission and return ACCEPT or DECLINE, printing the reason
     * to the terminal.
     */
    public Decision evaluate(Mission mission, double standing) {
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

        // 1b. Recon has a hard standings ceiling that applies everywhere, including high sec
        //     (where gatedDecision drops the usual max_standing gate). Above it the mission isn't
        //     worth running, so refuse before any accept rule can match. An unknown standing (NaN)
        //     passes — same "when unsure, accept" direction as the gated rules.
        if (isRecon(mission)) {
            double s = normalizeStanding(standing);
            if (!Double.isNaN(s) && s > RECON_MAX_STANDING) {
                System.out.printf("[DECISION] DECLINE — %s  (recon: standing > %.1f)%n",
                        mission.getTitle(), RECON_MAX_STANDING);
                return Decision.DECLINE;
            }
        }

        // 2a. Standings-gated accept rules first, so a gated mission is always gated even
        //     if a non-gated accept rule would also match it.
        for (Rule rule : acceptRules) {
            if (rule.maxStanding == null) continue;
            if (rule.matches(mission)) return gatedDecision(mission, rule, standing);
        }

        // 2b. Non-gated accept rules.
        for (Rule rule : acceptRules) {
            if (rule.maxStanding != null) continue;
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

    /**
     * Evaluate an offer that matched NO dialog template — OCR is the fallback when template
     * matching fails. The full rule set is consulted (decline → standings-gated → non-gated),
     * identical to {@link #evaluate}, so a wanted mission that OCRs a recognizable title
     * (e.g. Recon 1 of 3) is still accepted when its dialog template didn't match this frame.
     * {@code standing} is the tracked per-client agent standing (NaN if not yet known).
     */
    public Decision evaluateNoTemplate(Mission mission, double standing) {
        return evaluate(mission, standing);
    }

    /**
     * Decide a standings-gated accept rule against the tracked standing. Accept when the
     * standing is below the rule's max OR not yet known (when unsure, accept); decline only
     * when we have a reading that is at/above the max.
     */
    private Decision gatedDecision(Mission mission, Rule rule, double standing) {
        // High sec: we always run the standings-gated missions (Scarlet, Recon 1) regardless of
        // standing — drop the gate entirely. Null sec keeps it (accept only while standing is low).
        if (Environment.isHighSec()) {
            System.out.printf("[DECISION] ACCEPT  — %s  (rule: %s; high sec → standing gate ignored)%n",
                    mission.getTitle(), rule.comment);
            return Decision.ACCEPT;
        }
        if (Double.isNaN(standing)) {
            System.out.printf("[DECISION] ACCEPT  — %s  (rule: %s; standing not yet known → accept)%n",
                    mission.getTitle(), rule.comment);
            return Decision.ACCEPT;
        }
        double s = normalizeStanding(standing);
        if (s < rule.maxStanding) {
            System.out.printf("[DECISION] ACCEPT  — %s  (rule: %s; standing < %.1f)%n",
                    mission.getTitle(), rule.comment, rule.maxStanding);
            return Decision.ACCEPT;
        }
        System.out.printf("[DECISION] DECLINE — %s  (rule: %s; standing ≥ %.1f)%n",
                mission.getTitle(), rule.comment, rule.maxStanding);
        return Decision.DECLINE;
    }

    /**
     * Correct a known standings-OCR misread: the digit 0 is sometimes read as 8, so a
     * read whose integer magnitude is 8 (e.g. 8.4, -8.0) is really a 0.x value. Agent
     * standing is never genuinely ±8 in the gated range, and the minus sign is always
     * rendered, so remapping the leading 8 back to 0 is safe. Other values pass through.
     */
    static double normalizeStanding(double s) {
        if (Double.isNaN(s)) return s;
        double abs = Math.abs(s);
        if (abs >= 8.0 && abs < 9.0) {
            return s - Math.signum(s) * 8.0; // 8.4 → 0.4, -8.0 → 0.0
        }
        return s;
    }

    // -----------------------------------------------------------------------
    // Rule loading
    // -----------------------------------------------------------------------

    private void loadRules() {
        Path rulesFile = RULES_CANDIDATES.stream()
                .filter(Files::exists)
                .findFirst()
                .orElse(null);

        if (rulesFile == null) {
            System.out.println("[MissionConfig] No rules file found — writing default rules.");
            writeDefaultRules();
            loadRules(); // recurse once
            return;
        }

        System.out.println("[MissionConfig] Loading rules from " + rulesFile);
        try {
            String content = new String(Files.readAllBytes(rulesFile));
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
            rule.titleContains     = getString(obj, "title_contains");
            rule.targetContains    = getString(obj, "target_contains");
            rule.objectiveContains = getString(obj, "objective_contains");
            rule.locationContains  = getString(obj, "location_contains");
            rule.securityContains  = getString(obj, "security_contains");
            rule.type              = getString(obj, "type");
            rule.maxStanding       = obj.has("max_standing") && !obj.get("max_standing").isJsonNull()
                    ? obj.get("max_standing").getAsDouble() : null;
            rule.comment           = getString(obj, "comment");
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
        Path defaultTarget = RULES_CANDIDATES.get(0); // write to config/mission_rules.json
        try {
            Files.createDirectories(defaultTarget.getParent());
            Files.write(defaultTarget, defaultJson.getBytes(),
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
        String objectiveContains;
        String locationContains;
        String securityContains;
        String type;
        Double maxStanding;   // if set, accept only when agent standing < this value
        String comment = "unnamed rule";

        boolean matches(Mission m) {
            // The title line does not always survive OCR (it can wrap, or merge with the
            // "Objectives" header), so fall back to the raw dialog text — same as
            // location_contains does. The keyword is specific enough not to false-positive.
            if (titleContains != null
                    && !safe(m.getTitle()).contains(titleContains.toLowerCase())
                    && !safe(m.getRawText()).contains(titleContains.toLowerCase()))
                return false;
            if (targetContains != null
                    && !safe(m.getTarget()).contains(targetContains.toLowerCase()))
                return false;
            if (objectiveContains != null
                    && !safe(m.getObjective()).contains(objectiveContains.toLowerCase()))
                return false;
            if (locationContains != null
                    && !safe(m.getLocation()).contains(locationContains.toLowerCase())
                    && !safe(m.getRawText()).contains(locationContains.toLowerCase()))
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