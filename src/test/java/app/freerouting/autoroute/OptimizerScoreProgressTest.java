package app.freerouting.autoroute;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Defect 30, third attempt. Each previous guard failed for a different reason, and all three
 * failures are pinned here because each one looked correct when written.
 *
 * <p><b>Attempt 1</b> counted items examined since the board became more complete or more
 * legal. That consults unrouted and violation counts only — neither is what this stage does.
 * Measured across 26 boards from the routed outputs, the stage leaves both untouched while
 * removing 10.3% of all vias (864 to 771). The guard was blind to the entire useful output of
 * the stage it guarded.
 *
 * <p><b>Attempt 2</b> asked the score, correctly, but asked it through {@code
 * progressThrottler} — shared with two per-item callers that fire display events. {@code
 * shouldUpdate()} stamps its timestamp when it returns true, so those two consumed every tick
 * and the guard ran <b>zero times in 93 seconds</b> of optimisation. Measured cost: the stage
 * got 85% slower than the guard it replaced, because nothing stopped it any more.
 *
 * <p><b>Attempt 3</b>, pinned below: the guard owns its clock, and asks for a <i>rate</i> of
 * improvement rather than the mere existence of one. Items report local improvements almost
 * continuously while the board creeps by amounts no fabricator would notice, so a binary test
 * would never have fired even with its own clock.
 */
class OptimizerScoreProgressTest {

  @Test
  @Timeout(10)
  @DisplayName("a real gain across the window is progress")
  void realGainIsProgress() {
    assertTrue(OptimizerPassLimiter.windowProgressed(900.0f, 901.0f),
        "0.11% in five seconds is the stage earning its clock");
  }

  @Test
  @Timeout(10)
  @DisplayName("an unchanged score is not progress")
  void unchangedScoreIsNotProgress() {
    assertFalse(OptimizerPassLimiter.windowProgressed(945.45f, 945.45f),
        "the measured stagnation case: one board sat at 945.45 for 805 seconds");
  }

  @Test
  @Timeout(10)
  @DisplayName("a worse score is not progress")
  void worseScoreIsNotProgress() {
    assertFalse(OptimizerPassLimiter.windowProgressed(900.0f, 899.0f),
        "going backwards is not a reason to keep paying for the pass");
  }

  /**
   * Attempt 2's defect, stated as a test. The score does move — continuously, in amounts far
   * too small to matter. Any guard asking "did it move at all" is satisfied forever.
   */
  @Test
  @Timeout(10)
  @DisplayName("microscopic creep is not progress, however continuous")
  void creepIsNotProgress() {
    assertFalse(OptimizerPassLimiter.windowProgressed(900.0f, 900.05f),
        "0.006% in five seconds: real movement, worthless. A binary 'did it improve' test "
            + "passes here and the pass never ends.");
  }

  @Test
  @Timeout(10)
  @DisplayName("a degenerate starting score cannot divide by zero")
  void zeroStartIsSafe() {
    assertTrue(OptimizerPassLimiter.windowProgressed(0.0f, 0.0f),
        "an unscored board must not stop the pass on a division that never happened");
    assertTrue(OptimizerPassLimiter.windowProgressed(-1.0f, 5.0f),
        "same for a negative score");
  }

  /**
   * Attempt 1's defect. Same board outcome — nothing completed, nothing repaired — but vias
   * removed, which raises the score. The old predicate says stop; the stage was working.
   */
  @Test
  @Timeout(10)
  @DisplayName("removing vias is progress, even though unrouted and violations are unchanged")
  void viaOnlyImprovementIsProgress() {
    assertFalse(BatchOptimizer.outcomeImproved(10, 5, 10, 5),
        "old predicate: unrouted and violations unchanged, so it reports no progress");
    assertTrue(OptimizerPassLimiter.windowProgressed(900.0f, 902.0f),
        "new predicate: the score rose because vias came out. This is the 10.3% the first "
            + "guard was throwing away.");
  }
}
