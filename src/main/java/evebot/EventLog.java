package evebot;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Persistent event log — appends important events to {@code events.log}.
 */
public final class EventLog {

    private static final String LOG_FILE = "events.log";
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private EventLog() {}

    public static void log(String event, String detail) {
        String line = String.format("[%s] [%s] %s",
                LocalDateTime.now().format(FMT), event, detail);
        try (PrintWriter pw = new PrintWriter(new FileWriter(LOG_FILE, true))) {
            pw.println(line);
        } catch (IOException ignored) {
            // logging must never crash the bot
        }
    }
}
