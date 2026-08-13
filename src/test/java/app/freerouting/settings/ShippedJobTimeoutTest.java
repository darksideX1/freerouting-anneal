package app.freerouting.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import app.freerouting.settings.sources.DefaultSettings;
import app.freerouting.util.TextManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The shipped default has to bound a run to a few minutes.
 *
 * <p>The default was 12 hours, which was harmless only for as long as the optimizer did not
 * work: it stopped after one useless pass and the ceiling never mattered. With defect 25 fixed
 * the optimizer runs for real, and on a heavier board it will happily spend 449 seconds and
 * 435 GB improving a board that stopped getting measurably better long before that. An
 * unbounded default is now a promise the product cannot keep.
 *
 * <p>Three minutes is the shipped figure. It is not derived from theory: 240 s and 480 s
 * budgets produced no measurable difference in the routed result, so the wall is well below
 * either. Users who want a better board raise the timeout; users who want speed disable the
 * optimizer. Both are documented, and both are one flag.
 *
 * <p>That the optimizer's own deadline falls strictly inside the job's -- so the stage ends by
 * choice rather than being cut off mid-pass by the outer timeout -- is pinned separately by
 * {@code OptimizerDeadlineTest}, which lives in the package that can see it.
 */
class ShippedJobTimeoutTest {

  @Test
  @DisplayName("the shipped default bounds a run to fifteen minutes")
  void defaultJobTimeoutIsFifteenMinutes() {
    RouterSettings defaults = new DefaultSettings().getSettings();

    assertNotNull(defaults.jobTimeoutString, "a shipped default must bound the run");
    Long seconds = TextManager.parseTimespanString(defaults.jobTimeoutString);
    assertNotNull(seconds, "the default timeout must be parseable: " + defaults.jobTimeoutString);
    assertEquals(
        900L,
        seconds.longValue(),
        "default job timeout should be fifteen minutes, was " + defaults.jobTimeoutString);
  }

}
