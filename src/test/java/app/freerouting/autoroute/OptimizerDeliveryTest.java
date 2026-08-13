package app.freerouting.autoroute;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Defect 31: the multi-threaded optimiser found improvements and threw them away.
 *
 * <p>Measured before the fix: 41 tasks logged {@code improved: true} and were accepted as
 * winners, and the delivered board was byte-identical to running no optimiser at all — at 2
 * and at 4 threads, deterministically, while burning every requested core. Two faults, and
 * this class pins the one with a cheap seam; the board hand-back itself is integration-scale
 * and its acceptance gate is the mechanism probe (MT vias must fall from the no-op 104
 * toward the single-threaded 91 on the reference board).
 *
 * <p>The pinned fault: only a PASS initialises {@code min_cumulative_trace_length}. A
 * task-fresh optimizer — the multi-threaded path constructs one per item — arrived at the
 * comparison with 0.0, so the result read "length exploded from zero" and length-only
 * improvements could never register in a task. That is the majority class: 246 of 311
 * improved items in the single-threaded comparison run were length-only.
 */
class OptimizerDeliveryTest {

  @Test
  @Timeout(10)
  @DisplayName("a pass-established baseline is kept, not overwritten by the board's current value")
  void passBaselineIsKept() {
    assertEquals(1000.0, BatchOptimizer.lengthBaseline(1000.0, 1234.0),
        "a pass set 1000; the item comparison must run against it");
  }

  @Test
  @Timeout(10)
  @DisplayName("a task-fresh optimizer seeds its baseline from the board itself")
  void taskFreshSeedsFromBoard() {
    assertEquals(13433475.0, BatchOptimizer.lengthBaseline(0.0, 13433475.0),
        "the defect: 0.0 was used AS the baseline, so a rerouted item's length always read "
            + "as an explosion and length-only wins never registered in a task");
  }
}
