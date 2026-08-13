package app.freerouting.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * What a headless run tells a calling program, without it reading the log.
 *
 * <p>Closes FS-X. Exit status was binary — 0 or 1 — and "0" covered a perfectly routed
 * board, a board with 270 unrouted connections and 28 clearance violations, and a run that
 * hit its deadline. Five of the fourteen fault stories were therefore invisible to a
 * caller, which is exactly what a severity taxonomy exists to prevent.
 *
 * <p><b>An incomplete board is not an error.</b> This is where a PCB autorouter differs
 * from a tool whose output must be perfect: connections left unrouted are the normal
 * handover point back to the engineer, who moves parts and routes again. That is the game.
 * So {@code INCOMPLETE} is a distinct signal, not a failure, and it must never be
 * conflated with {@code FAILED} — which means no result was produced at all.
 *
 * <p>Because unrouted is normal, the outcome codes are <b>opt-in</b>. Turning them on by
 * default would make every CI job that checks {@code $? -eq 0} start failing on boards
 * that are behaving exactly as expected — punishing the ordinary case to serve the careful
 * one. Legacy policy stays the default; a caller that wants the detail asks for it.
 */
class CliOutcomeTest {

  @Test
  void aFullyRoutedBoardIsComplete() {
    assertEquals(CliOutcome.COMPLETE, CliOutcome.of(0, 0, false));
  }

  @Test
  void unroutedConnectionsMeanWorkIsLeftForAHuman() {
    // NOT a failure. The engineer moves components and routes again.
    assertEquals(CliOutcome.INCOMPLETE, CliOutcome.of(19, 0, false));
  }

  @Test
  void clearanceViolationsAlsoMeanIncomplete() {
    assertEquals(CliOutcome.INCOMPLETE, CliOutcome.of(0, 192, false));
  }

  @Test
  void stoppedEarlyOutranksIncomplete() {
    // The distinction that matters to a caller: a board that is incomplete because the
    // router finished and could do no more is a different thing from a board that is
    // incomplete because the clock ran out. The first is an answer; the second is a
    // truncated attempt, and rerunning it with a longer budget may change it.
    assertEquals(CliOutcome.STOPPED_EARLY, CliOutcome.of(270, 28, true));
    assertEquals(CliOutcome.STOPPED_EARLY, CliOutcome.of(0, 0, true));
  }

  @Test
  void everyOutcomeExceptFailedProducedAResult() {
    assertTrue(CliOutcome.COMPLETE.producedResult());
    assertTrue(CliOutcome.INCOMPLETE.producedResult());
    assertTrue(CliOutcome.STOPPED_EARLY.producedResult());
    assertFalse(CliOutcome.FAILED.producedResult());
  }

  @Test
  void legacyPolicyIsUnchangedForEveryOutcome() {
    // The compatibility guarantee, asserted rather than hoped for. A board with unrouted
    // connections exited 0 before this existed and must keep exiting 0.
    assertEquals(0, CliOutcome.COMPLETE.exitCode(false));
    assertEquals(0, CliOutcome.INCOMPLETE.exitCode(false));
    assertEquals(0, CliOutcome.STOPPED_EARLY.exitCode(false));
    assertEquals(1, CliOutcome.FAILED.exitCode(false));
  }

  @Test
  void outcomePolicyDistinguishesAllFour() {
    assertEquals(0, CliOutcome.COMPLETE.exitCode(true));
    assertEquals(3, CliOutcome.INCOMPLETE.exitCode(true));
    assertEquals(4, CliOutcome.STOPPED_EARLY.exitCode(true));
    assertEquals(1, CliOutcome.FAILED.exitCode(true));
  }

  @Test
  void failureCodeIsOneUnderBothPolicies() {
    // A script that only knows "nonzero is bad" keeps working under either policy.
    assertEquals(CliOutcome.FAILED.exitCode(false), CliOutcome.FAILED.exitCode(true));
  }

  @Test
  void codesAreDistinctSoACallerCanBranch() {
    int[] codes = {
        CliOutcome.COMPLETE.exitCode(true),
        CliOutcome.INCOMPLETE.exitCode(true),
        CliOutcome.STOPPED_EARLY.exitCode(true),
        CliOutcome.FAILED.exitCode(true)};
    for (int i = 0; i < codes.length; i++) {
      for (int j = i + 1; j < codes.length; j++) {
        assertTrue(codes[i] != codes[j], "outcome exit codes must be distinguishable");
      }
    }
  }
}
