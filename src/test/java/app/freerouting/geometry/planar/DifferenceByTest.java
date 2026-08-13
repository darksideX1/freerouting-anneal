package app.freerouting.geometry.planar;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The difference of two points needs one vector, not two.
 *
 * <p>US-3, site 3. {@code IntPoint.difference_by(Point)} was written as double dispatch:
 *
 * <pre>
 *   Vector tmp = p_other.difference_by(this);   // allocates an IntVector
 *   return tmp.negate();                        // allocates another, discards the first
 * </pre>
 *
 * <p>On the dominant IntPoint-to-IntPoint path both allocations are IntVectors and the
 * first is immediately garbage. {@code IntVector.negate()} is {@code new IntVector(-x, -y)},
 * so {@code -(other.x - x)} is just {@code x - other.x}: the same value, reachable directly.
 *
 * <p>Safe against overflow because coordinates are bounded by the exactness invariant at
 * +/-2^25, so a difference cannot exceed 2^26 and cannot reach the negation edge case at
 * {@code Integer.MIN_VALUE}. That bound is the reason this is a rewrite rather than a risk.
 *
 * <p>These assertions pin the VALUE across sign combinations and the extremes of the legal
 * range, because "the arithmetic is obviously equivalent" is exactly the kind of claim that
 * should be executed rather than believed.
 */
class DifferenceByTest {

  private static void assertDifference(int ax, int ay, int bx, int by) {
    IntPoint a = new IntPoint(ax, ay);
    IntPoint b = new IntPoint(bx, by);

    Vector viaGenericEntry = a.difference_by((Point) b);
    IntVector expected = new IntVector(ax - bx, ay - by);

    assertEquals(expected, viaGenericEntry,
        "difference of (" + ax + "," + ay + ") and (" + bx + "," + by + ")");
  }

  @Test
  void differenceIsCorrectForEverySignCombination() {
    assertDifference(10, 20, 3, 4);
    assertDifference(-10, -20, 3, 4);
    assertDifference(10, -20, -3, 4);
    assertDifference(-10, 20, 3, -4);
    assertDifference(3, 4, 10, 20);
  }

  @Test
  void differenceOfAPointWithItselfIsZero() {
    assertDifference(1234, -5678, 1234, -5678);
  }

  @Test
  void differenceHoldsAtTheEdgesOfTheExactRange() {
    // The exactness invariant caps coordinates at +/-2^25; the largest legal difference is
    // therefore 2^26, which still fits an int with room to spare.
    int limit = 33_554_432; // 2^25
    assertDifference(limit, limit, -limit, -limit);
    assertDifference(-limit, limit, limit, -limit);
  }

  @Test
  void theGenericEntryAgreesWithTheTypedOverload() {
    // Double dispatch and the direct path must not diverge -- that divergence would be
    // invisible at every call site that happens to use the other one.
    IntPoint a = new IntPoint(77, -31);
    IntPoint b = new IntPoint(-12, 44);

    assertEquals(a.difference_by(b), a.difference_by((Point) b),
        "the typed and generic entry points must produce the same vector");
  }
}
