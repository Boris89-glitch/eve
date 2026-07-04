package evebot;

/**
 * Manual LIVE test for the kill-report → bad-contact flow. NOT used by the bot runtime.
 *
 * Set-up: dock the EVE client with the kill report CLOSED (the feed icon must be visible bottom-right),
 * then run this with that EVE client FOCUSED. A 6s countdown gives you time to click the EVE window into
 * the foreground. Do NOT run the bot itself (Main) at the same time — two processes would fight over the
 * mouse.
 *
 * Every mode (except `add`) opens the kill report itself first (feed_icon → kill_report), then runs its
 * step — you do NOT pre-open the report.
 *
 * Modes (first arg):
 *   full            (default) whole flow: open report → right-click name → add, with automatic
 *                             Copy-Kill-Information backup if the right-click offers no Add-Contact.
 *   copyname                  open report → three-dot → Copy Kill Information → parse & print the name.
 *                             No contact is added — verifies feed_icon / kill_report / kill_report_menu
 *                             / copy_kill_info templates and the clipboard parsing.
 *   backup                    open report → force the backup: Copy Kill Information → parse name →
 *                             add via the contacts search.
 *   add <PilotName>           skip the report and add <PilotName> via the contacts search only.
 *
 * Examples:
 *   java -cp <jar> evebot.ContactTest
 *   java -cp <jar> evebot.ContactTest copyname
 *   java -cp <jar> evebot.ContactTest backup
 *   java -cp <jar> evebot.ContactTest add "Skye Tan"
 */
public class ContactTest {

    public static void main(String[] args) throws Exception {
        String mode = args.length >= 1 ? args[0].toLowerCase() : "full";
        countdown();
        MissionProcessor mp = new MissionProcessor();

        switch (mode) {
            case "copyname": {
                mp.openKillReport();
                String name = mp.copyKillInfoParseName();
                System.out.println("[ContactTest] Copy Kill Information parsed name: '" + name + "'");
                break;
            }
            case "backup": {
                mp.openKillReport();
                String name = mp.copyKillInfoParseName();
                System.out.println("[ContactTest] Parsed name: '" + name + "'");
                if (name != null && !name.isEmpty()) {
                    boolean ok = mp.addViaContactsSearch(name);
                    System.out.println("[ContactTest] addViaContactsSearch returned: " + ok);
                } else {
                    System.out.println("[ContactTest] No name parsed — nothing to add.");
                }
                break;
            }
            case "add": {
                if (args.length < 2) {
                    System.out.println("[ContactTest] Usage: ContactTest add <PilotName>");
                    break;
                }
                boolean ok = mp.addViaContactsSearch(args[1]);
                System.out.println("[ContactTest] addViaContactsSearch('" + args[1] + "') returned: " + ok);
                break;
            }
            case "full":
            default: {
                String killer = mp.addKillerFromKillReport();
                System.out.println("[ContactTest] Done. Killer name: '" + killer
                        + "' — check they're now a Bad (orange) contact.");
                break;
            }
        }
        System.exit(0); // the OCR daemon threads would otherwise keep the JVM alive
    }

    private static void countdown() throws InterruptedException {
        System.out.println("[ContactTest] Focus the EVE client now (docked, kill report CLOSED, feed icon visible). Starting in 6s...");
        for (int i = 6; i > 0; i--) { System.out.println("  " + i); Thread.sleep(1000); }
    }
}
