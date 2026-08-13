package app.freerouting.autoroute;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Defect 30: the optimisation stage's early-stop existed and could never fire.
 *
 * <p>The loop counts consecutive items that could not be improved and breaks at
 * {@code maxConsecutiveFailures}. That counter was cleared whenever any single item improved
 * by its own local measure — so on a board where 82 of 85 items "improved" while the board
 * itself changed by nothing, the counter never reached its limit and the pass ran to the end
 * of the item list. One board spent 26 minutes that way and handed back an identical board.
 *
 * <p>Independently graded from the outside: via count did not move on any of 41 boards, and
 * 15 of them came back byte-identical. The stage was being told it was succeeding by a
 * signal that never looks at the board.
 *
 * <p>This pins the signal that arms the counter. The board outcome is what counts as
 * progress: connections completed, or violations removed. Local item improvement is not
 * progress — it is the thing that was being mistaken for it.
 */
class OptimizerProgressTest {

  @Test
  @Timeout(10)
  @DisplayName("completing a connection is progress")
  void fewerUnroutedIsProgress() {
    assertTrue(BatchOptimizer.outcomeImproved(10, 5, 9, 5),
        "one connection completed is exactly the work this stage is for");
  }

  @Test
  @Timeout(10)
  @DisplayName("removing a violation is progress")
  void fewerViolationsIsProgress() {
    assertTrue(BatchOptimizer.outcomeImproved(10, 5, 10, 4),
        "a clearance violation repaired is a board a fab can build");
  }

  @Test
  @Timeout(10)
  @DisplayName("an unchanged board is not progress, however many items were re-routed")
  void unchangedOutcomeIsNotProgress() {
    assertFalse(BatchOptimizer.outcomeImproved(10, 5, 10, 5),
        "this is the measured case: 82 of 85 items improved locally, board identical. "
            + "If this counts as progress the early-stop can never fire.");
  }

  @Test
  @Timeout(10)
  @DisplayName("going backwards is not progress either")
  void worseOutcomeIsNotProgress() {
    assertFalse(BatchOptimizer.outcomeImproved(10, 5, 11, 5),
        "more unrouted than we started with is not a reason to keep paying for the pass");
    assertFalse(BatchOptimizer.outcomeImproved(10, 5, 10, 6),
        "more violations is not progress");
  }

  @Test
  @Timeout(10)
  @DisplayName("a trade — one connection gained, one violation introduced — still counts")
  void gainOnEitherAxisCounts() {
    assertTrue(BatchOptimizer.outcomeImproved(10, 5, 9, 6),
        "completing a connection is the stage's job; the violation is reported separately "
            + "and should not silence the fact that it did the work");
  }
}
