package evebot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Last-hour player ship-kill counts per solar system, from ESI's public
 * {@code /universe/system_kills/} endpoint — the same "ships destroyed" numbers
 * EVE's in-game map shows. Server-side data refreshes hourly and needs no auth.
 *
 * <p>The whole array (~1k systems) is fetched fresh on every call — no caching — so
 * the check always reflects the latest hourly ESI update. On failure,
 * {@link #shipKillsLastHour(int)} returns -1 so the caller can fall back to the safe
 * default (decline).
 */
public final class EsiKills {

    private static final String URL =
            "https://esi.evetech.net/latest/universe/system_kills/?datasource=tranquility";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private EsiKills() {}

    /**
     * Player ship kills in {@code systemId} over the last hour, fetched fresh from ESI.
     * A system with no kills returns 0 (ESI omits quiet systems). Returns -1 if the
     * lookup failed (network/parse/non-200).
     */
    public static int shipKillsLastHour(int systemId) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(URL))
                    .timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return -1;
            JsonArray arr = JsonParser.parseString(res.body()).getAsJsonArray();
            for (JsonElement el : arr) {
                var o = el.getAsJsonObject();
                if (o.get("system_id").getAsInt() == systemId) {
                    return o.get("ship_kills").getAsInt();
                }
            }
            return 0; // system not listed -> no kills last hour
        } catch (Exception e) {
            return -1;
        }
    }
}
