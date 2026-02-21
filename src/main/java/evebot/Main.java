package evebot;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Main {
    private ClientManager clientManager;
    private StateManager stateManager;
    private ScheduledExecutorService scheduler;

    public static void main(String[] args) {
        new Main().start();
    }

    public void start() {
        System.out.println("Starting EVE Online Multi-Client Bot...");

        // Initialize components
        this.stateManager = new StateManager();
        this.clientManager = new ClientManager(stateManager);
        this.scheduler = Executors.newScheduledThreadPool(5);

        // Load configuration
        loadConfiguration();

        // Start monitoring clients
        startMonitoring();
    }

    private void loadConfiguration() {
        // TODO: Load mission configs and client positions
        System.out.println("Loading configuration...");
    }

    private void startMonitoring() {
        // Schedule client monitoring every 2 seconds
        scheduler.scheduleAtFixedRate(() -> {
            clientManager.monitorClients();
        }, 0, 2, TimeUnit.SECONDS);

        // Schedule state persistence every 30 seconds
        scheduler.scheduleAtFixedRate(() -> {
            stateManager.saveStates();
        }, 0, 30, TimeUnit.SECONDS);
    }
}