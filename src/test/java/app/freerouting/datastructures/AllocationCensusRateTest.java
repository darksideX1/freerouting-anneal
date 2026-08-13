package app.freerouting.datastructures;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A diagnostic that costs a {@code StackWalker} traversal on every allocation must never be
 * switched on by accident.
 *
 * <p>The old parser did exactly that, two ways. A malformed value fell back to rate 1 —
 * exact census, the most expensive mode there is — so a typo in the property made routing
 * drastically slower while appearing to have been accepted. And {@code Math.max(1, ...)}
 * clamped a parsed 0 up to 1, so {@code -Dfreerouting.alloc.census=0}, the obvious way to
 * ask for it to be off, turned it fully on.
 *
 * <p>An instrument you did not ask for, running at maximum cost, is worse than no instrument.
 * Anything that is not a positive number now means disabled.
 */
class AllocationCensusRateTest {

  @Test
  @Timeout(10)
  @DisplayName("absent property means disabled")
  void absentIsDisabled() {
    assertEquals(0, AllocationCensus.parseSampleRate(null));
  }

  @Test
  @Timeout(10)
  @DisplayName("blank means disabled")
  void blankIsDisabled() {
    assertEquals(0, AllocationCensus.parseSampleRate(""));
    assertEquals(0, AllocationCensus.parseSampleRate("   "));
  }

  @Test
  @Timeout(10)
  @DisplayName("a typo disables, it does not enable the most expensive mode")
  void malformedIsDisabled() {
    assertEquals(0, AllocationCensus.parseSampleRate("abc"),
        "the old parser returned 1 here: a mistyped flag put a StackWalker on every "
            + "allocation and the run just got mysteriously slow");
    assertEquals(0, AllocationCensus.parseSampleRate("10x"));
    assertEquals(0, AllocationCensus.parseSampleRate("1.5"));
  }

  @Test
  @Timeout(10)
  @DisplayName("zero means off, which is what anyone typing zero intends")
  void zeroIsDisabled() {
    assertEquals(0, AllocationCensus.parseSampleRate("0"),
        "the old parser clamped this to 1 with Math.max, so asking for it to be off "
            + "turned it fully on");
  }

  @Test
  @Timeout(10)
  @DisplayName("negatives mean off")
  void negativeIsDisabled() {
    assertEquals(0, AllocationCensus.parseSampleRate("-1"));
    assertEquals(0, AllocationCensus.parseSampleRate("-100"));
  }

  @Test
  @Timeout(10)
  @DisplayName("a positive number is honoured, surrounding whitespace included")
  void positiveIsHonoured() {
    assertEquals(1, AllocationCensus.parseSampleRate("1"));
    assertEquals(100, AllocationCensus.parseSampleRate("100"));
    assertEquals(100, AllocationCensus.parseSampleRate("  100  "));
  }
}
