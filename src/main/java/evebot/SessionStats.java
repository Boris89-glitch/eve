package evebot;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks completed missions for an end-of-session reward summary.
 * Burner rewards are roughly fixed, so totals are derived from a mission count
 * rather than OCR'd per mission.
 */
public final class SessionStats {

    // Approximate reward per burner mission.
    public static final long ISK_PER_MISSION       = 13_000_000L; // direct ISK reward
    public static final int  LP_PER_MISSION        = 22_000;      // loyalty points
    public static final long ISK_VALUE_PER_MISSION = 45_000_000L; // ISK reward + LP value

    private static final AtomicInteger missions = new AtomicInteger(0);

    private SessionStats() {}

    /** Record one completed mission; returns the new session total. */
    public static int recordMission() {
        return missions.incrementAndGet();
    }

    /** Append the session totals to the event log — call on shutdown. */
    public static void logSummary() {
        int n = missions.get();
        if (n == 0) {
            EventLog.log("SESSION_SUMMARY", "no missions completed this session");
            return;
        }
        EventLog.log("SESSION_SUMMARY", String.format(
                "%d missions — %,d ISK, %,d LP, ~%,d ISK total value",
                n, n * ISK_PER_MISSION, (long) n * LP_PER_MISSION, n * ISK_VALUE_PER_MISSION));
    }
}
