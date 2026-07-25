package evebot;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * The null-sec route graph around the home station, read once from config/system_map.json.
 *
 * Its one job is pod avoidance. After a pod death the system we died in is cut out of the
 * graph and a BFS runs from the station: everything that is no longer reachable is refused,
 * because the only way there is back through whatever killed us. Systems that keep an
 * alternate path stay allowed — dying in KLY-C0 does not block ROIR-Y, since
 * J-CIJV => G95-VZ => ROIR-Y still reaches it.
 *
 * Every lookup goes through {@link #normalize}: system names arrive from OCR (mission
 * "Location" line, the info-panel route waypoints, the kill report header), so "O" vs "0",
 * a dropped dash and the usual tesseract letter/digit swaps all have to compare equal.
 */
public final class SystemMap {

    private static final Path FILE = Paths.get("config", "system_map.json");

    /** OCR letter → digit folding. The map's 40 systems stay collision-free under this
     *  (asserted at load), so folding aggressively costs nothing and catches more misreads. */
    private static final char[][] OCR_FOLD = {
            {'O', '0'}, {'I', '1'}, {'S', '5'}, {'B', '8'}, {'Z', '2'}, {'G', '6'}
    };

    /** normalized name → display name, for every system in the map. */
    private static final Map<String, String> CANONICAL = new HashMap<>();
    /** normalized name → normalized neighbours. */
    private static final Map<String, Set<String>> ADJACENCY = new HashMap<>();
    /** normalized name → templates/<name>.png used to right-click-and-jump that system. */
    private static final Map<String, String> REROUTE = new HashMap<>();

    private static String station = null;
    private static int avoidMinutes = 60;

    static { load(); }

    private SystemMap() {}

    // -----------------------------------------------------------------------
    // Loading
    // -----------------------------------------------------------------------

    private static void load() {
        if (!Files.exists(FILE)) {
            System.err.println("[SystemMap] No " + FILE + " — pod avoidance disabled.");
            return;
        }
        try {
            JsonObject o = new Gson().fromJson(new String(Files.readAllBytes(FILE)), JsonObject.class);

            if (o.has("station")) station = normalize(o.get("station").getAsString());
            if (o.has("avoid_minutes")) avoidMinutes = o.get("avoid_minutes").getAsInt();

            JsonArray routes = o.getAsJsonArray("routes");
            if (routes != null) {
                for (JsonElement chainEl : routes) {
                    JsonArray chain = chainEl.getAsJsonArray();
                    for (int i = 0; i < chain.size(); i++) {
                        String display = chain.get(i).getAsString();
                        register(display);
                        if (i > 0) link(chain.get(i - 1).getAsString(), display);
                    }
                }
            }

            JsonObject reroute = o.getAsJsonObject("reroute_templates");
            if (reroute != null) {
                for (Map.Entry<String, JsonElement> e : reroute.entrySet()) {
                    REROUTE.put(normalize(e.getKey()), e.getValue().getAsString());
                }
            }

            System.out.println("[SystemMap] Loaded " + CANONICAL.size() + " system(s) from " + FILE
                    + " (station " + display(station) + ", avoid "
                    + (avoidMinutes <= 0 ? "rest of session" : avoidMinutes + "m") + ").");
            if (station != null && !CANONICAL.containsKey(station)) {
                System.err.println("[SystemMap] Station " + display(station)
                        + " is not in any route — pod avoidance will refuse nothing.");
            }
        } catch (Exception e) {
            System.err.println("[SystemMap] Error reading " + FILE + ": " + e.getMessage());
        }
    }

    /**
     * Add a system, warning if two differently-named systems fold to the same normalized form.
     * A collision would make one of them silently answer for the other in every lookup, so it
     * has to be visible at startup rather than discovered as a bad decline mid-session.
     */
    private static void register(String display) {
        String key = normalize(display);
        String prev = CANONICAL.putIfAbsent(key, display);
        if (prev != null && !prev.equals(display)) {
            System.err.println("[SystemMap] OCR-normalization collision: '" + display + "' and '"
                    + prev + "' both fold to '" + key + "' — they cannot be told apart.");
        }
        ADJACENCY.computeIfAbsent(key, k -> new HashSet<>());
    }

    private static void link(String aDisplay, String bDisplay) {
        String a = normalize(aDisplay), b = normalize(bDisplay);
        if (a.equals(b)) return;
        ADJACENCY.computeIfAbsent(a, k -> new HashSet<>()).add(b);
        ADJACENCY.computeIfAbsent(b, k -> new HashSet<>()).add(a);
    }

    // -----------------------------------------------------------------------
    // Name normalization
    // -----------------------------------------------------------------------

    /**
     * Fold an OCR'd system name to its comparison key: upper-case, drop everything that is not
     * a letter or digit (dashes, spaces, the stray '<' from a waypoint row), then apply the
     * letter→digit swaps tesseract makes in this font. "0-N8XZ", "O N8XZ" and "O-N8X2" all
     * become the same key. Returns "" for null/blank.
     */
    public static String normalize(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        for (char c : raw.toUpperCase().toCharArray()) {
            if (!Character.isLetterOrDigit(c)) continue;
            for (char[] fold : OCR_FOLD) {
                if (c == fold[0]) { c = fold[1]; break; }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** The map's spelling of a normalized key, or the key itself if it isn't a known system. */
    public static String display(String normalized) {
        if (normalized == null || normalized.isEmpty()) return "unknown";
        return CANONICAL.getOrDefault(normalized, normalized);
    }

    /** True if this OCR'd name matches a system in the map at all. */
    public static boolean isKnown(String raw) {
        return CANONICAL.containsKey(normalize(raw));
    }

    /** How long a podded system stays cut out, in minutes; 0 or less means rest of session. */
    public static int avoidMinutes() { return avoidMinutes; }

    /** True if the map failed to load / has no routes — every avoidance check then passes. */
    public static boolean isEmpty() { return ADJACENCY.isEmpty() || station == null; }

    // -----------------------------------------------------------------------
    // Avoidance
    // -----------------------------------------------------------------------

    /**
     * Every system that can no longer be reached from the station once {@code podded} is cut
     * out — the podded system itself plus anything behind it. Returns an empty set when the
     * map is unusable or the podded system isn't on it, so an unrecognised name never blocks
     * the whole map.
     */
    public static Set<String> blockedBy(String podded) {
        String cut = normalize(podded);
        if (isEmpty() || cut.isEmpty() || !ADJACENCY.containsKey(cut)) return Collections.emptySet();
        // Podded at the home station itself: there is nothing to route around, and cutting the
        // station out would leave the whole map unreachable and refuse every mission there is.
        if (station.equals(cut)) return Collections.emptySet();

        Set<String> reachable = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        if (!station.equals(cut)) {
            reachable.add(station);
            queue.add(station);
        }
        while (!queue.isEmpty()) {
            for (String next : ADJACENCY.getOrDefault(queue.poll(), Collections.emptySet())) {
                if (next.equals(cut) || !reachable.add(next)) continue;
                queue.add(next);
            }
        }

        Set<String> blocked = new HashSet<>(CANONICAL.keySet());
        blocked.removeAll(reachable);
        return blocked;
    }

    /**
     * True if {@code location} sits at, or behind, the system we were podded in. Unknown
     * locations return false: we only refuse what the map positively says is cut off, so an
     * OCR miss lets the mission through rather than declining everything.
     */
    public static boolean isBlocked(String location, String podded) {
        String key = normalize(location);
        return !key.isEmpty() && blockedBy(podded).contains(key);
    }

    /**
     * Shortest station → destination path with the podded system cut out, as display names
     * starting at the station. Empty if there is no way round (the caller should refuse the
     * mission instead of flying it). This is the detour the reroute templates fly.
     */
    public static List<String> detour(String destination, String podded) {
        return shortestPath(destination, normalize(podded));
    }

    /**
     * Shortest station → destination path as display names, station first, with nothing cut out —
     * the route we would normally fly. Indexing it by how many jumps we are from the station gives
     * the system we are currently in, which is how a pod death is located when the kill report
     * can't be read. Empty if the destination isn't on the map.
     */
    public static List<String> pathFromStation(String destination) {
        return shortestPath(destination, "");
    }

    /** BFS from the station to {@code destination}, optionally with {@code cut} removed. */
    private static List<String> shortestPath(String destination, String cut) {
        String goal = normalize(destination);
        if (isEmpty() || goal.isEmpty() || !CANONICAL.containsKey(goal)) return Collections.emptyList();
        if (goal.equals(cut) || station.equals(cut)) return Collections.emptyList();

        Map<String, String> cameFrom = new HashMap<>();
        cameFrom.put(station, null);
        Deque<String> queue = new ArrayDeque<>();
        queue.add(station);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (current.equals(goal)) break;
            for (String next : ADJACENCY.getOrDefault(current, Collections.emptySet())) {
                if (next.equals(cut) || cameFrom.containsKey(next)) continue;
                cameFrom.put(next, current);
                queue.add(next);
            }
        }
        if (!cameFrom.containsKey(goal)) return Collections.emptyList();

        LinkedList<String> path = new LinkedList<>();
        for (String at = goal; at != null; at = cameFrom.get(at)) path.addFirst(display(at));
        return path;
    }

    /** templates/&lt;name&gt;.png to right-click-and-jump for this system, or null if it has none. */
    public static String rerouteTemplate(String system) {
        return REROUTE.get(normalize(system));
    }
}
