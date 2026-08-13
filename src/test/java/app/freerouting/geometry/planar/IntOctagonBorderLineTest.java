package app.freerouting.geometry.planar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Border lines are a pure function of an immutable octagon, so they may be computed once.
 *
 * <p>US-3, site 2. {@code IntOctagon.border_line} constructs a {@link Line} -- and with it
 * two {@link IntPoint}s -- on every call. It is <b>7.7% of all allocation</b> on bm01, and
 * the {@code Line} constructor is simultaneously the largest single source of IntPoint at
 * 7.8%. Every field of {@code IntOctagon} is {@code final int}, so the answer cannot change
 * between calls; recomputing it is pure waste.
 *
 * <p>Several callers loop over all eight borders ({@code CalcFromSide}, {@code to_Simplex}),
 * and one of them asks for the same index twice inside a single expression. Others fetch one
 * specific border repeatedly. Both patterns pay for caching the set.
 *
 * <p>What caching can break, and therefore what is pinned here: values must be equal to what
 * the computation produced before, two different octagons must never share a cache, and the
 * out-of-range contract must keep throwing what it threw -- an array index would otherwise
 * silently change the exception type callers see.
 */
class IntOctagonBorderLineTest {

  private static IntOctagon octagon() {
    return new IntOctagon(-100, -100, 100, 100, -150, 150, -150, 150);
  }

  @Test
  void repeatedCallsReturnEqualLines() {
    IntOctagon oct = octagon();

    for (int i = 0; i < oct.border_line_count(); i++) {
      Line first = oct.border_line(i);
      Line second = oct.border_line(i);
      assertEquals(first, second, "border line " + i + " must not change between calls");
    }
  }

  @Test
  void allEightBordersAreDistinct() {
    // Guards the obvious cache bug: every index handing back the same entry.
    IntOctagon oct = octagon();

    for (int i = 0; i < 8; i++) {
      for (int j = i + 1; j < 8; j++) {
        assertNotEquals(oct.border_line(i), oct.border_line(j),
            "borders " + i + " and " + j + " are different edges of the octagon");
      }
    }
  }

  @Test
  void differentOctagonsDoNotShareBorderLines() {
    // The dangerous version of a cache: one instance serving another instance's geometry.
    IntOctagon a = octagon();
    IntOctagon b = new IntOctagon(-50, -50, 50, 50, -75, 75, -75, 75);

    assertNotEquals(a.border_line(0), b.border_line(0),
        "a smaller octagon has different borders; a cache must be per instance");
  }

  @Test
  void aTranslatedOctagonHasItsOwnBorders() {
    IntOctagon oct = octagon();
    Line before = oct.border_line(0);
    IntOctagon moved = oct.translate_by(new IntVector(10, 20));

    assertNotEquals(before, moved.border_line(0),
        "translate_by returns a new octagon, which must compute its own borders");
    assertEquals(before, oct.border_line(0), "and the original must be unchanged");
  }

  @Test
  void outOfRangeStillThrowsTheSameContract() {
    IntOctagon oct = octagon();

    assertThrows(IllegalArgumentException.class, () -> oct.border_line(8),
        "indexing a cache array would throw ArrayIndexOutOfBounds instead -- callers see a "
            + "different exception type, which is a silent API change");
    assertThrows(IllegalArgumentException.class, () -> oct.border_line(-1));
  }

  @Test
  void bordersRemainCorrectAfterManyQueries() {
    // Repeated mixed-order access, the pattern the real callers produce.
    IntOctagon oct = octagon();
    Line reference = oct.border_line(3);

    for (int round = 0; round < 50; round++) {
      for (int i = 7; i >= 0; i--) {
        assertTrue(oct.border_line(i) != null);
      }
    }

    assertEquals(reference, oct.border_line(3), "still the same after 400 queries");
  }
}
