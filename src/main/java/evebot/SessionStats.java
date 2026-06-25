package evebot;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks completed missions for an end-of-session reward summary.
 * The total ISK value (direct ISK reward + LP value) varies by mission type, so
 * the running total is accumulated from each mission's value rather than from a
 * single count × flat rate.
 */
public final class SessionStats {

    // Approximate total ISK value per mission type (direct ISK reward + LP value).
    public static final long BURNER_VALUE   = 58_000_000L; // anomic burners: 13m ISK + 22k LP (~45m)
    public static final long RECON_VALUE    = 12_000_000L;
    public static final long INFESTED_VALUE = 35_000_000L;
    public static final long SCARLET_VALUE  = 35_000_000L;

    private static final AtomicInteger missions   = new AtomicInteger(0);
    private static final AtomicLong    totalValue = new AtomicLong(0);

    private SessionStats() {}

    /**
     * Approximate total ISK value for a completed mission, classified by ship
     * target (falling back to the title). Burner missions are the default.
     */
    public static long valueFor(String shipTarget, String title) {
        String s = (shipTarget != null ? shipTarget : "").toLowerCase();
        String t = (title != null ? title : "").toLowerCase();
        if (s.contains("recon")    || t.contains("recon"))                            return RECON_VALUE;
        if (s.contains("infested") || t.contains("infested") || t.contains("ruins"))  return INFESTED_VALUE;
        if (s.contains("scarlet")  || t.contains("scarlet"))                          return SCARLET_VALUE;
        return BURNER_VALUE;
    }

    /** Record one completed mission worth {@code value} ISK; returns the new session mission count. */
    public static int recordMission(long value) {
        totalValue.addAndGet(value);
        return missions.incrementAndGet();
    }

    /** Running session total ISK value across all completed missions. */
    public static long totalValue() {
        return totalValue.get();
    }

    /** Append the session totals to the event log — call on shutdown. */
    public static void logSummary() {
        int n = missions.get();
        if (n == 0) {
            EventLog.log("SESSION_SUMMARY", "no missions completed this session");
            return;
        }
        EventLog.log("SESSION_SUMMARY", String.format(
                "%d missions — ~%,d ISK total value", n, totalValue.get()));
    }
}
