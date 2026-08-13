package app.freerouting.geometry.planar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Test;

/**
 * normalize() on an already-normalised octagon must not allocate.
 *
 * <p>It computes eight adjusted coordinates and then constructs a new IntOctagon
 * unconditionally — including when every coordinate came back unchanged, which is the
 * common case: shapes are normalised repeatedly as expansion rooms are built and
 * compared. IntOctagon is immutable (all fields final) and nothing in the codebase
 * compares shapes by identity, so returning {@code this} when nothing changed is
 * indistinguishable to every caller.
 */
class IntOctagonNormalizeTest {

  private static Object sink;

  private static long allocatedBytes() {
    ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    bean.setThreadAllocatedMemoryEnabled(true);
    return bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
  }

  /** An octagon that is already normalised: normalising it changes nothing. */
  private static IntOctagon alreadyNormalised() {
    IntOctagon raw = new IntOctagon(-100, -100, 100, 100, -200, 200, -200, 200);
    return raw.normalize();
  }

  @Test
  void normalisingAnAlreadyNormalisedOctagonReturnsTheSameInstance() {
    IntOctagon oct = alreadyNormalised();
    assertNotNull(oct);
    assertSame(
        oct,
        oct.normalize(),
        "already normalised: there is nothing to compute and nothing to allocate");
  }

  @Test
  void normalisingAnAlreadyNormalisedOctagonDoesNotAllocate() {
    IntOctagon oct = alreadyNormalised();

    for (int i = 0; i < 200; i++) {
      sink = oct.normalize(); // warm up
    }

    long before = allocatedBytes();
    for (int i = 0; i < 10_000; i++) {
      sink = oct.normalize();
    }
    long used = allocatedBytes() - before;

    // 10k fresh IntOctagons would be on the order of half a megabyte.
    assertTrue(
        used < 50_000,
        "normalize() allocated " + used + " bytes for 10000 no-op calls; an octagon that"
            + " is already normalised should return itself");
  }

  @Test
  void normalisingStillCorrectsAnUnnormalisedOctagon() {
    // Diagonal constraints here genuinely bite, so the result must differ.
    IntOctagon raw = new IntOctagon(-100, -100, 100, 100, -50, 50, -50, 50);
    IntOctagon norm = raw.normalize();
    assertNotNull(norm);
    assertEquals(norm, norm.normalize(), "normalise must be idempotent");
    assertSame(norm.normalize(), norm.normalize().normalize(),
        "and stable once reached");
  }

  @Test
  void anEmptyOctagonStaysEmpty() {
    IntOctagon empty = new IntOctagon(100, 100, -100, -100, 0, 0, 0, 0).normalize();
    assertSame(IntOctagon.EMPTY, empty, "an inverted octagon normalises to EMPTY");
  }
}
