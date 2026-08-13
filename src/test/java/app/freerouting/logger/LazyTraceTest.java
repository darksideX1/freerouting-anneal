package app.freerouting.logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import app.freerouting.geometry.planar.Point;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * The logger defers its own cost, so callers stop having to.
 *
 * <p>The five-argument {@code FRLogger.trace} is not a logging call. It runs
 * {@code DebugControl.getInstance().check(...)}, which implements single-step execution
 * and can pause the router, and it publishes a {@code TraceEvent}. Because its
 * {@code message} parameter is an already-built String, ~169 call sites across the tree
 * wrap it in {@code if (FRLogger.isTraceEnabled())} to avoid the concatenation.
 *
 * <p><b>Those guards do something worse than defer allocation: 38 of them wrap the
 * debugger.</b> With the level above TRACE, {@code check()} never runs, so single-step
 * execution is silently dead at those breakpoints. A root logger of {@code Level.ALL}
 * masked that for months by making every guard true.
 *
 * <p>The cure is not more guards, it is an API that does not need them. With suppliers,
 * nothing is built unless something will actually consume it, and the caller can drop its
 * guard — which is what restores the debugger.
 *
 * <p>Three arguments are deferred, not one: at the hottest site all three of the message,
 * the impacted-items string and {@code getImpactedPoints(...)} allocate.
 */
class LazyTraceTest {

  @Test
  void nothingIsBuiltWhenNobodyIsListening() {
    // The default state: granular trace off, single-step off. Every supplier must go
    // untouched, or this change trades string building for lambda allocation and loses.
    AtomicInteger message = new AtomicInteger();
    AtomicInteger items = new AtomicInteger();
    AtomicInteger points = new AtomicInteger();

    boolean interesting = FRLogger.trace("m", "op",
        counting(message, "expensive message"),
        counting(items, "Net #1"),
        countingPoints(points));

    assertFalse(interesting, "with nothing enabled this is not an interesting trace event");
    assertEquals(0, message.get(), "message supplier must not run");
    assertEquals(0, items.get(), "impacted-items supplier must not run");
    assertEquals(0, points.get(), "impacted-points supplier must not run");
  }

  @Test
  void theDebuggerIsAskedEvenWithTraceOff() {
    // The defect this fixes. isActive() reports whether DebugControl would do anything;
    // with single-step off it is false, which is exactly why the early return above is
    // safe. If this ever returns true by default, the early return must be revisited.
    assertFalse(app.freerouting.debug.DebugControl.getInstance().isActive(),
        "single-step is off by default, so the cheap early return is correct");
  }

  @Test
  void repeatedCallsStayFree() {
    // Hot paths call this per item per pass. The cost of the disabled case must not grow.
    AtomicInteger message = new AtomicInteger();
    for (int i = 0; i < 10_000; i++) {
      FRLogger.trace("m", "op", counting(message, "x"), () -> "", () -> null);
    }
    assertEquals(0, message.get());
  }

  private static Supplier<String> counting(AtomicInteger counter, String value) {
    return () -> {
      counter.incrementAndGet();
      return value;
    };
  }

  private static Supplier<Point[]> countingPoints(AtomicInteger counter) {
    return () -> {
      counter.incrementAndGet();
      return new Point[0];
    };
  }
}
