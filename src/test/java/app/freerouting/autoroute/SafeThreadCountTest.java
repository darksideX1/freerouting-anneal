package app.freerouting.autoroute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Racing must not be able to exhaust the heap.
 *
 * <p>Every racing thread works on its own {@code board.deepCopy()}. The dead implementation
 * allocated {@code maxThreads} of them with no reference to available memory at all — on a
 * large board that is an {@code OutOfMemoryError} waiting for a user with more cores than
 * headroom, and it works directly against the user story "it must not eat my RAM".
 *
 * <p>The count is derived from a MEASURED board copy rather than an estimate: the first
 * copy is made, the heap delta observed, and the remaining thread count decided from what
 * actually fits. Guessing the size of a routing board from item counts would be a second
 * thing to be wrong about.
 *
 * <p>Half the free heap is reserved. The router still has to do its work inside whatever
 * racing leaves behind, and a thread count that fits the copies but starves the search has
 * simply moved the failure.
 */
class SafeThreadCountTest {

  @Test
  void aSingleThreadIsAlwaysAllowed() {
    // Racing off. Must never be reduced to zero, whatever the memory picture says.
    assertEquals(1, BatchAutorouter.safeThreadCount(1, 0, Long.MAX_VALUE));
    assertEquals(1, BatchAutorouter.safeThreadCount(0, 0, 1));
    assertEquals(1, BatchAutorouter.safeThreadCount(-4, 1000, 1));
  }

  @Test
  void plentyOfHeadroomGrantsWhatWasAsked() {
    // 8 threads, 10 MB per board, 4 GB free: never the constraint.
    assertEquals(8, BatchAutorouter.safeThreadCount(8, 4L * 1024 * 1024 * 1024, 10L * 1024 * 1024));
  }

  @Test
  void tightHeadroomReducesRatherThanFails() {
    // 8 asked, 100 MB per board, 500 MB free -> half reserved = 250 MB -> 2 fit.
    assertEquals(2, BatchAutorouter.safeThreadCount(8, 500L * 1024 * 1024, 100L * 1024 * 1024));
  }

  @Test
  void aBoardTooBigToRaceFallsBackToOne() {
    // One copy already exceeds the reserve. Racing is simply not available here, and the
    // right answer is to route single-threaded rather than to die.
    assertEquals(1, BatchAutorouter.safeThreadCount(8, 100L * 1024 * 1024, 900L * 1024 * 1024));
  }

  @Test
  void anUnmeasurableBoardFallsBackToOneThread() {
    // REVERSED, and the old expectation is worth recording because I wrote it, defended it
    // in a comment, and pinned it with an assertion. It read:
    //
    //   "a failed measurement must not block racing, or racing depends on GC timing"
    //
    // and returned the full requested count. A reviewer pointed out what that actually
    // does: it grants EVERY requested board copy without ever consulting free heap, so a
    // large board under memory pressure can throw OutOfMemoryError from the copy loop --
    // and OutOfMemoryError is an Error, so the surrounding catch (Exception) does not catch
    // it. The failure mode of the safety check was worse than the thing it guarded.
    //
    // A zero or negative delta means we do not know what one copy costs. The safe answer to
    // "how many can I afford" when the cost is unknown is one.
    assertEquals(1, BatchAutorouter.safeThreadCount(4, 1024L * 1024 * 1024, 0));
    assertEquals(1, BatchAutorouter.safeThreadCount(4, 1024L * 1024 * 1024, -1));
  }

  @Test
  void theResultNeverExceedsWhatWasAsked() {
    // Memory can only ever subtract. A user asking for 2 does not get 9 because the box is big.
    assertEquals(2, BatchAutorouter.safeThreadCount(2, Long.MAX_VALUE / 4, 1));
  }

  @Test
  void theResultIsAlwaysAtLeastOne() {
    for (long free : new long[] {0, 1, 1024, Long.MAX_VALUE / 2}) {
      for (long per : new long[] {1, 1024, Long.MAX_VALUE / 4}) {
        assertTrue(BatchAutorouter.safeThreadCount(8, free, per) >= 1,
            "free=" + free + " per=" + per);
      }
    }
  }
}
