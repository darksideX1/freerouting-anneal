package app.freerouting.geometry.planar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.logger.AllowErrorLogs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The invariant that makes this geometry exact must be observable when it breaks.
 *
 * <p>Everything about this router's exactness rests on one domain invariant:
 * {@code |coordinate| <= Limits.CRIT_INT} (2^25). With it, the products inside
 * {@code IntVector.side_of} are at most 2^50 and their difference at most 2^51 — inside a
 * double's 53-bit significand, so the predicates are exact with no filter and no fallback.
 * That is why adaptive-precision predicates would buy nothing here: floating point never
 * destroyed the exactness in the first place.
 *
 * <p><b>The invariant was not enforced.</b> {@code IntPoint} answered a violation with
 * {@code FRLogger.debug(...)} guarded by {@code isDebugEnabled()} — so at the default log
 * level a coordinate that breaks exactness produced <i>complete silence</i>, and the
 * geometry carried on doing arithmetic that was no longer guaranteed exact. In a program
 * whose output is fabricated into copper, the one assumption holding correctness together
 * was unverified and unverifiable at runtime.
 *
 * <p>This does not change what the router computes. It makes a violation impossible to
 * miss, and countable, so that a run which broke the invariant can never be mistaken for
 * one that did not.
 */
@AllowErrorLogs("A range violation logs at ERROR by design -- that IS the behaviour under test.")
class ExactRangeTest {

  @BeforeEach
  void reset() {
    IntPoint.resetExactRangeViolations();
  }

  @Test
  void theExactRangeIsTheDoublePrecisionLimit() {
    // 2^25. Products stay within 2^50, differences within 2^51, and a double carries 53
    // bits exactly. This is the number the whole exactness argument depends on.
    assertEquals(33554432, Limits.CRIT_INT);
    assertEquals(1L << 25, (long) Limits.CRIT_INT);
  }

  @Test
  void coordinatesInsideTheRangeAreAccepted() {
    assertTrue(IntPoint.isWithinExactRange(0));
    assertTrue(IntPoint.isWithinExactRange(Limits.CRIT_INT));
    assertTrue(IntPoint.isWithinExactRange(-Limits.CRIT_INT));
    assertTrue(IntPoint.isWithinExactRange(Limits.CRIT_INT - 1));
  }

  @Test
  void coordinatesOutsideTheRangeAreNot() {
    assertFalse(IntPoint.isWithinExactRange(Limits.CRIT_INT + 1));
    assertFalse(IntPoint.isWithinExactRange(-Limits.CRIT_INT - 1));
    assertFalse(IntPoint.isWithinExactRange(Integer.MAX_VALUE));
    assertFalse(IntPoint.isWithinExactRange(Integer.MIN_VALUE));
  }

  @Test
  void aviolationIsCountedRatherThanSwallowed() {
    assertEquals(0, IntPoint.exactRangeViolationCount());

    new IntPoint(Limits.CRIT_INT + 1, 0);
    assertEquals(1, IntPoint.exactRangeViolationCount());

    new IntPoint(0, -Limits.CRIT_INT - 1);
    assertEquals(2, IntPoint.exactRangeViolationCount());
  }

  @Test
  void bothCoordinatesOutOfRangeCountAsOnePoint() {
    // Counting points, not axes: the question a reader asks is "how many points broke the
    // invariant", and double-counting one bad point would overstate it.
    new IntPoint(Limits.CRIT_INT + 5, Limits.CRIT_INT + 5);
    assertEquals(1, IntPoint.exactRangeViolationCount());
  }

  @Test
  void alegalPointNeverCounts() {
    new IntPoint(1000, -1000);
    new IntPoint(Limits.CRIT_INT, -Limits.CRIT_INT);
    assertEquals(0, IntPoint.exactRangeViolationCount());
  }

  @Test
  void theCountSurvivesManyViolationsWithoutFloodingTheLog() {
    // A board that breaks the invariant breaks it in a loop. The count must stay exact
    // while the log stays readable -- one ERROR, then silence, then the total at the end.
    for (int i = 0; i < 10_000; i++) {
      new IntPoint(Limits.CRIT_INT + 1 + i, 0);
    }
    assertEquals(10_000, IntPoint.exactRangeViolationCount());
  }
}
