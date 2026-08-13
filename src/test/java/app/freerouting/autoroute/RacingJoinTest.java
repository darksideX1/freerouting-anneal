package app.freerouting.autoroute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * A racing pass must never read a board from a thread that is still writing it.
 *
 * <p>The racing pass waited with {@code join(TIME_LIMIT_TO_PREVENT_ENDLESS_LOOP)} — and that
 * constant is <b>1000 milliseconds</b> while a pass takes seconds. The wait therefore always
 * expired, and the code below it read each worker's board regardless: {@code bh.add(...)},
 * then {@code get_statistics()} to score it, then adoption of the winner into
 * {@code this.board}. Scoring walks the search tree, which is exactly where the crashes
 * landed —
 *
 * <pre>
 *   ShapeSearchTree.overlapping_tree_entries_with_clearance
 *   Item.clearance_violations
 *   ShapeTree$Leaf.compareTo
 * </pre>
 *
 * — all on a null leaf object, at a rate that did not vary with worker count because this
 * read happens once per pass however many workers there are.
 *
 * <p>Found twice independently: by measurement here (crash rate flat at 2, 4 and 8 workers)
 * and by the reviewer on the pull request, who added the part measurement could not see —
 * the winning board is adopted into {@code this.board} while the losing daemon workers are
 * still running, so the NEXT pass can mutate a board those threads still hold.
 *
 * <p>This pins the selection half, which is the part that can be tested without threads:
 * a thread that did not finish is not a candidate, however good its half-written board
 * looks. The waiting half is enforced at the call site, which sets the mask.
 */
class RacingJoinTest {

  @Test
  void anUnfinishedThreadIsNeverSelected() {
    // Thread 1 has the best score and is still running. Picking it would mean serialising
    // a board mid-write, which is the crash.
    float[] scores = {10f, 99f, 20f};
    boolean[] finished = {true, false, true};

    assertEquals(2, BatchAutorouter.bestThreadIndexByScore(scores, finished),
        "the highest score belongs to a thread that never finished; it is not a candidate");
  }

  @Test
  void noFinishedThreadMeansNoWinner() {
    // Every worker overran. There is no board that can be safely read, so the pass has no
    // result -- and -1 is what the caller turns into an ABORTED outcome rather than
    // silently adopting whatever happens to be in memory.
    float[] scores = {50f, 60f};
    boolean[] finished = {false, false};

    assertEquals(-1, BatchAutorouter.bestThreadIndexByScore(scores, finished),
        "with nothing finished the pass must report no winner, not pick one anyway");
  }

  @Test
  void amongFinishedThreadsTheBestScoreStillWins() {
    float[] scores = {10f, 30f, 20f};
    boolean[] finished = {true, true, true};

    assertEquals(1, BatchAutorouter.bestThreadIndexByScore(scores, finished));
  }

  @Test
  void tiesGoToTheLowestIndexAmongFinishedThreads() {
    // Deterministic tie-breaking: two boards of equal score must not select differently
    // from one run to the next, or the race becomes a second source of nondeterminism.
    float[] scores = {30f, 30f, 30f};
    boolean[] finished = {false, true, true};

    assertEquals(1, BatchAutorouter.bestThreadIndexByScore(scores, finished),
        "lowest index among the finished threads, so the choice is reproducible");
  }

  @Test
  void negativeScoresAreStillSelectable() {
    // A board can score below zero. The winner must be the best AVAILABLE board, not
    // "nothing", or a pass with only poor results silently reports no progress.
    float[] scores = {-50f, -10f, -70f};
    boolean[] finished = {true, true, true};

    assertEquals(1, BatchAutorouter.bestThreadIndexByScore(scores, finished));
  }

  @Test
  void aShorterMaskDoesNotExcludeTrailingThreads() {
    // Defensive: mask and scores should be the same length, but a shorter mask must not
    // silently drop candidates -- that would fail closed in the wrong direction.
    float[] scores = {10f, 40f};
    boolean[] finished = {true};

    assertTrue(BatchAutorouter.bestThreadIndexByScore(scores, finished) >= 0,
        "a mismatched mask must not make every thread ineligible");
  }
}
