package evebot;

/**
 * Manual LIVE test for the kill-report → bad-contact flow. NOT used by the bot runtime.
 *
 * Set-up: dock the EVE client with the kill report OPEN on the pilot (feed icon visible bottom-right),
 * then run this with that EVE client FOCUSED. A 6s countdown gives you time to click the EVE window into
 * the foreground. Do NOT run the bot itself (Main) at the same time — two processes would fight over the
 * mouse.
 *
 * Runs the whole flow: open report → right-click the Final Blow name → Add Corporate Contact →
 * (edit-contact check) → orange Bad standing → confirm → close the kill report.
 *
 * Example:
 *   java -cp <jar> evebot.ContactTest
 */
public class ContactTest {

    public static void main(String[] args) throws Exception {
        countdown();
        MissionProcessor mp = new MissionProcessor();
        mp.addKillerFromKillReport();
        System.out.println("[ContactTest] Done — check the pilot is now a Bad (orange) corporate contact.");
        System.exit(0); // the OCR daemon threads would otherwise keep the JVM alive
    }

    private static void countdown() throws InterruptedException {
        System.out.println("[ContactTest] Focus the EVE client now (docked, kill report open, feed icon visible). Starting in 6s...");
        for (int i = 6; i > 0; i--) { System.out.println("  " + i); Thread.sleep(1000); }
    }
}
