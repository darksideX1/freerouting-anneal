package app.freerouting.autoroute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The limiter is the shared home of the pass-guard logic precisely because it used to live
 * inside {@link BatchOptimizer} — and {@link BatchOptimizerMultiThreaded} overrides
 * {@code opt_route_pass} wholesale, so on the GUI's default path (multi-threading enabled,
 * more than two processors) the entire guard was bypassed: no rate guard, and an explicit
 * {@code rounds} value silently ignored. Everything measured for the 1.0.3 release ran the
 * single-threaded CLI path; the default GUI path ran unguarded. These tests pin the shared
 * pieces so the two implementations cannot drift apart again.
 */
class OptimizerPassLimiterTest {

  @Test
  @Timeout(10)
  @DisplayName("unset rounds means the automatic guard, and no complaint")
  void unsetRoundsIsQuietlyNull() {
    List<String> errors = new ArrayList<>();
    assertNull(OptimizerPassLimiter.validateRounds(null, errors::add));
    assertTrue(errors.isEmpty(), "the default must not warn about itself");
  }

  @Test
  @Timeout(10)
  @DisplayName("a positive rounds value is honoured as given")
  void positiveRoundsIsHonoured() {
    List<String> errors = new ArrayList<>();
    assertEquals(150, OptimizerPassLimiter.validateRounds(150, errors::add));
    assertEquals(1, OptimizerPassLimiter.validateRounds(1, errors::add));
    assertTrue(errors.isEmpty());
  }

  /**
   * The codex finding, as a test. {@code rounds=0} parsed successfully, was non-null, failed
   * the {@code > 0} check — and was treated exactly like unset, so the caller who asked for a
   * cap got the rate guard instead and was never told. A value the user supplied must never
   * be silently reinterpreted as a different mode; this fork has now made that mistake twice
   * in one day (the allocation census's Math.max was the other).
   */
  @Test
  @Timeout(10)
  @DisplayName("zero or negative rounds is rejected LOUDLY, not silently mode-switched")
  void nonPositiveRoundsIsLoud() {
    List<String> errors = new ArrayList<>();
    assertNull(OptimizerPassLimiter.validateRounds(0, errors::add));
    assertNull(OptimizerPassLimiter.validateRounds(-5, errors::add));
    assertEquals(2, errors.size(), "each rejected value must produce its own error");
    assertTrue(errors.get(0).contains("NOT applied"),
        "the wording must match the dropped-flag doctrine: the run is not configured as requested");
    assertTrue(errors.get(0).contains("rounds=0"), "the rejected value is named");
  }

  @Test
  @Timeout(10)
  @DisplayName("an unchanged update count across a window is a stalled pass")
  void unchangedCountIsStalled() {
    assertTrue(OptimizerPassLimiter.countWindowStalled(7, 7),
        "no accepted master-board replacement in a whole window: the pass is buying nothing");
  }

  @Test
  @Timeout(10)
  @DisplayName("any accepted board update within the window keeps the pass alive")
  void acceptedUpdateIsProgress() {
    assertFalse(OptimizerPassLimiter.countWindowStalled(7, 8));
    assertFalse(OptimizerPassLimiter.countWindowStalled(0, 3));
  }
}
