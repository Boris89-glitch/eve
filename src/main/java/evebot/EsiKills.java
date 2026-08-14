package evebot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Last-hour player ship-kill counts per solar system, from ESI's public
 * {@code /universe/system_kills/} endpoint — the same "ships destroyed" numbers
 * EVE's in-game map shows. Server-side data refreshes hourly and needs no auth.
 *
 * <p>The whole array (~1k systems) is fetched once and cached for a few minutes so
 * a burst of mission offers doesn't hammer ESI. On a failed refresh the last good
 * cache is reused; if there is none, {@link #shipKillsLastHour(int)} returns -1 so
 * the caller can fall back to the safe default (decline).
 */
public final class EsiKills {

    private static final String URL =
            "https://esi.evetech.net/latest/universe/system_kills/?datasource=tranquility";
    private static final long CACHE_MS = 30 * 60 * 1000L;

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static Map<Integer, Integer> cache = null;
    private static long fetchedAt = 0L;

    private EsiKills() {}

    /**
     * Player ship kills in {@code systemId} over the last hour. A system with no
     * kills returns 0 (ESI omits quiet systems). Returns -1 if the lookup failed
     * and no cached data is available.
     */
    public static synchronized int shipKillsLastHour(int systemId) {
        Map<Integer, Integer> map = current();
        if (map == null) return -1;
        return map.getOrDefault(systemId, 0);
    }

    private static Map<Integer, Integer> current() {
        long now = System.currentTimeMillis();
        if (cache != null && now - fetchedAt < CACHE_MS) return cache;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(URL))
                    .timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return cache; // stale (or null) on error
            JsonArray arr = JsonParser.parseString(res.body()).getAsJsonArray();
            Map<Integer, Integer> parsed = new HashMap<>(arr.size() * 2);
            for (JsonElement el : arr) {
                var o = el.getAsJsonObject();
                parsed.put(o.get("system_id").getAsInt(), o.get("ship_kills").getAsInt());
            }
            cache = parsed;
            fetchedAt = now;
            return cache;
        } catch (Exception e) {
            return cache; // prefer stale data over nothing; null if never fetched
        }
    }
}
