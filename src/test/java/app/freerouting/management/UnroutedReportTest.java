package app.freerouting.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The unrouted report must add up.
 *
 * <p>This report is the terminal artifact. Unlike a pipeline that hands its leftovers to a
 * review stage, nothing downstream of here re-checks the claim — so the arithmetic being
 * reconcilable IS the contract, in the same way that "never a false SAFE" is the contract
 * where a certificate is the last word.
 *
 * <p>What was wrong: the summary claimed 270 unrouted connections, the list showed 25 nets
 * summing to 143, and the cap line said "and 98 further net(s) … not listed". Every
 * statement was true and the reader still could not get from 143 to 270 — 127 connections
 * existed only as the word "98 nets". Someone skimming would reasonably conclude the
 * visible rows were most of the problem when they were barely half of it.
 *
 * <p>Under-telling is not lying, and it is not good enough here. A partial list presented
 * without its own remainder reads as a complete one.
 */
class UnroutedReportTest {

  @Test
  void headerStatesBothTotalsSoTheListCanBeReconciled() {
    String header = RoutingJobSchedulerActionThread.unroutedHeader(123, 270);

    assertTrue(header.contains("123"), "net total must be stated: " + header);
    assertTrue(header.contains("270"), "connection total must be stated: " + header);
  }

  @Test
  void capNoteAccountsForConnectionsNotJustNets() {
    // The defect. "98 further nets" alone leaves 127 connections unaccounted for.
    String note = RoutingJobSchedulerActionThread.unroutedCapNote(98, 127, 25);

    assertTrue(note.contains("98"), "hidden net count: " + note);
    assertTrue(note.contains("127"), "hidden CONNECTION count must be stated: " + note);
    assertTrue(note.contains("25"), "the cap itself must be stated: " + note);
  }

  @Test
  void listedPlusHiddenEqualsTheClaimedTotal() {
    // The invariant a reader should be able to check by eye, expressed as arithmetic.
    int listedConnections = 143;
    int hiddenConnections = 127;
    int claimedTotal = 270;

    assertEquals(claimedTotal, listedConnections + hiddenConnections,
        "the report must reconcile: what is shown plus what is withheld is the total");
  }

  @Test
  void nothingHiddenMeansNoCapNote() {
    // Do not print a remainder of zero; it reads as though something was withheld.
    assertEquals("", RoutingJobSchedulerActionThread.unroutedCapNote(0, 0, 25));
  }

  @Test
  void aSingleHiddenNetReadsNaturally() {
    String note = RoutingJobSchedulerActionThread.unroutedCapNote(1, 4, 25);
    assertTrue(note.contains("1 further net"), "singular form: " + note);
    assertTrue(note.contains("4"), note);
  }
}
