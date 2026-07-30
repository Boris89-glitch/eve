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
    public static final long BURNER_VALUE   = 40_000_000L; // anomic burners
    public static final long RECON_VALUE    =  8_400_000L; // 12m − 30%
    public static final long INFESTED_VALUE = 24_500_000L; // 35m − 30%
    public static final long SCARLET_VALUE  = 24_500_000L; // 35m − 30%

    private static final AtomicInteger missions   = new AtomicInteger(0);
    private static final AtomicLong    totalValue = new AtomicLong(0);
    /** Wall-clock start of the session, for the end-of-session runtime. Defaults to class-load
     *  time; call {@link #markStart()} at startup to anchor it to the actual bot launch. */
    private static final AtomicLong    startMillis = new AtomicLong(System.currentTimeMillis());

    private SessionStats() {}

    /** Anchor the session clock to now — call once at startup. */
    public static void markStart() {
        startMillis.set(System.currentTimeMillis());
    }

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

    /** Human-readable session runtime, e.g. "3h 07m". */
    private static String ranFor() {
        long totalMin = Math.max(0, System.currentTimeMillis() - startMillis.get()) / 60_000;
        return String.format("%dh %02dm", totalMin / 60, totalMin % 60);
    }

    /** Append the session totals to the event log — call on shutdown. */
    public static void logSummary() {
        int n = missions.get();
        if (n == 0) {
            EventLog.log("SESSION_SUMMARY", "no missions completed this session — ran " + ranFor());
            return;
        }
        EventLog.log("SESSION_SUMMARY", String.format(
                "%d missions — ~%,d ISK total value — ran %s", n, totalValue.get(), ranFor()));
    }
}
