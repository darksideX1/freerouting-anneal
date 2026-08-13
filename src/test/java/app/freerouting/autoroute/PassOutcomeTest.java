package app.freerouting.autoroute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * A routing pass has three outcomes, not two.
 *
 * <p>Previously a pass returned a boolean, and the catch-all around the whole pass body
 * returned {@code false} on any exception — the same value it returns when the pass ran
 * perfectly and simply had nothing left to route. A geometry bug and a finished board
 * were indistinguishable to the caller, so a crash mid-pass presented as normal
 * completion.
 *
 * <p>The two "stop looping" outcomes must stay distinguishable even though they agree on
 * whether to continue: that agreement is exactly what let the bug hide.
 */
class PassOutcomeTest {

  @Test
  void progressAsksTheLoopToContinue() {
    assertTrue(PassOutcome.PROGRESS.shouldContinue());
  }

  @Test
  void noProgressStopsTheLoop() {
    assertFalse(PassOutcome.NO_PROGRESS.shouldContinue());
  }

  @Test
  void anAbortedPassStopsTheLoopButIsNotACleanFinish() {
    assertFalse(PassOutcome.ABORTED.shouldContinue());
    assertTrue(PassOutcome.ABORTED.isAbnormal(),
        "an exception mid-pass is a defect, not a finished board");
    assertFalse(PassOutcome.NO_PROGRESS.isAbnormal(),
        "having nothing left to route is the normal end of routing");
  }

  @Test
  void abortedAndNoProgressAreNotTheSameOutcome() {
    // Both stop the loop. Conflating them is the defect this type exists to prevent.
    assertNotEquals(PassOutcome.ABORTED, PassOutcome.NO_PROGRESS);
    assertEquals(PassOutcome.ABORTED.shouldContinue(),
        PassOutcome.NO_PROGRESS.shouldContinue(),
        "they agree on control flow, which is why a boolean could not tell them apart");
  }
}
