package evebot;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Entry point for the EVE Online Multi-Client Bot.
 *
 * Start-up sequence:
 *   1. StateManager  — loads persisted client states + registers Ctrl+Shift+O
 *   2. ClientManager — loads window / coordinate config, builds rule engine
 *   3. Scheduler     — polls clients every 1 s, auto-saves state every 20 s
 *
 * The bot does NOT act until Ctrl+Shift+O is pressed (or "toggle" typed).
 * It then resumes each client from its last known state.  Clients with no
 * saved state begin in IDLE, waiting for a mission dialog to appear.
 */
public class Main {

    private StateManager stateManager;
    private ClientManager clientManager;
    private ScheduledExecutorService scheduler;

    // -----------------------------------------------------------------------

    public static void main(String[] args) {
        new Main().start();
    }

    public void start() {
        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║   EVE Online Multi-Client Bot  v0.2      ║");
        System.out.println("║   Ctrl+Shift+O : start / pause           ║");
        System.out.println("║   Ctrl+Shift+R : end session (park)      ║");
        System.out.println("╚══════════════════════════════════════════╝");

        // Anchor the session clock so the end-of-session summary reports true runtime.
        SessionStats.markStart();

        // 1. State manager (also registers hotkey)
        this.stateManager = new StateManager();

        // 2. Client manager (depends on stateManager)
        this.clientManager = new ClientManager(stateManager);

        // 3. Scheduler
        this.scheduler = Executors.newScheduledThreadPool(3);

        // Poll clients every 500ms for snappier response times
        scheduler.scheduleAtFixedRate(
                clientManager::monitorClients,
                0, 500, TimeUnit.MILLISECONDS);

        // Auto-save state every 20 s
        scheduler.scheduleAtFixedRate(stateManager::saveStates, 20, 20, TimeUnit.SECONDS);

        // Pre-downtime auto session-end: servers reset at ~13:00, so at 12:50 (machine local
        // time — assumed CET/CEST) arm the graceful session end, the same action as Ctrl+Shift+R.
        // Every client finishes its current mission and parks docked at its agent before the
        // servers drop, so none are caught in space at disconnect. Fires daily.
        scheduleDailySessionEnd(LocalTime.of(12, 50));

        // Keep the JVM alive
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[Main] Shutting down.");
            SessionStats.logSummary();
            stateManager.resetAllToIdle();   // persist all clients IDLE — next launch starts clean
            stateManager.saveStates();
            clientManager.shutdown();
            scheduler.shutdownNow();
        }));
    }

    /**
     * Schedule a graceful session end (park all clients docked) at {@code when} every day —
     * used to get clients docked before the daily server downtime. The armed session end is
     * one-way per process (see {@link StateManager#requestSessionEnd()}), so within a single
     * bot run this effectively fires once; the daily re-fire is a harmless no-op if the bot is
     * left running across a downtime. Restart the bot after downtime to re-arm.
     */
    private void scheduleDailySessionEnd(LocalTime when) {
        long initialDelay = secondsUntilNext(when);
        scheduler.scheduleAtFixedRate(() -> {
            System.out.println("[Main] Scheduled pre-downtime session end (" + when +
                    ") — clients finish current missions, then park docked.");
            stateManager.requestSessionEnd();
        }, initialDelay, TimeUnit.DAYS.toSeconds(1), TimeUnit.SECONDS);
        System.out.printf("[Main] Daily pre-downtime session end scheduled for %s local (first in %d min).%n",
                when, initialDelay / 60);
    }

    /** Seconds from now until the next occurrence of {@code when} in the machine's local time. */
    private static long secondsUntilNext(LocalTime when) {
        LocalDateTime now  = LocalDateTime.now();
        LocalDateTime next = now.toLocalDate().atTime(when);
        if (!next.isAfter(now)) next = next.plusDays(1);   // already past today → tomorrow
        return Duration.between(now, next).getSeconds();
    }
}