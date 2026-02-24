package evebot;

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
        System.out.println("║   Press Ctrl+Shift+O to start / pause    ║");
        System.out.println("╚══════════════════════════════════════════╝");

        // 1. State manager (also registers hotkey)
        this.stateManager = new StateManager();

        // 2. Client manager (depends on stateManager)
        this.clientManager = new ClientManager(stateManager);

        // 3. Scheduler
        this.scheduler = Executors.newScheduledThreadPool(3);

        // Poll clients every 1 second — fast enough to catch declines and re-request quickly
        scheduler.scheduleAtFixedRate(
                clientManager::monitorClients,
                0, 1, TimeUnit.SECONDS);

        // State persistence disabled during testing — re-enable by uncommenting:
        // scheduler.scheduleAtFixedRate(stateManager::saveStates, 20, 20, TimeUnit.SECONDS);

        // Keep the JVM alive
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[Main] Shutting down.");
            // State save on exit disabled during testing — re-enable by uncommenting:
            // stateManager.saveStates();
            scheduler.shutdownNow();
        }));
    }
}