package app.freerouting.autoroute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fanout has to run inside the job's clock, not beside it.
 *
 * <p>It read only its own {@code router.fanout.timeout}, which ships unset, so with no explicit
 * value it had no deadline at all and never asked the job how much time was left. Setting a
 * three minute job timeout and then watching a stage run past it is the exact failure the
 * global envelope exists to prevent — and the optimizer already derives its deadline this way,
 * so fanout was the one stage outside the rule.
 */
class FanoutDeadlineTest {

  private static final long NOW = 1_000_000L;

  @Test
  @DisplayName("with no explicit timeout, fanout still stops before the job does")
  void derivesFromTheJobDeadline() {
    long jobDeadline = NOW + 180_000L;

    Long deadline = BatchFanout.computeFanoutDeadlineMs(null, jobDeadline, NOW);

    assertNotNull(deadline, "fanout must inherit a deadline from the job");
    assertTrue(
        deadline < jobDeadline,
        "fanout must finish before the job deadline, not be cut off by it: " + deadline);
    assertTrue(deadline > NOW, "the derived deadline must leave fanout some time to work");
  }

  @Test
  @DisplayName("an explicit fanout timeout still cannot outlive the job")
  void explicitTimeoutIsClampedToTheJob() {
    long jobDeadline = NOW + 60_000L;

    // The user asked for ten minutes of fanout inside a one minute job. The job wins.
    Long deadline = BatchFanout.computeFanoutDeadlineMs(600L, jobDeadline, NOW);

    assertNotNull(deadline);
    assertTrue(
        deadline < jobDeadline,
        "an explicit stage timeout must never extend past the job envelope: " + deadline);
  }

  @Test
  @DisplayName("a shorter explicit timeout is honoured")
  void shorterExplicitTimeoutWins() {
    long jobDeadline = NOW + 180_000L;

    Long deadline = BatchFanout.computeFanoutDeadlineMs(10L, jobDeadline, NOW);

    assertEquals(NOW + 10_000L, deadline.longValue(),
        "an explicit timeout inside the envelope should be used as given");
  }

  @Test
  @DisplayName("with no job deadline and no explicit timeout there is nothing to derive")
  void noEnvelopeNoDeadline() {
    assertNull(BatchFanout.computeFanoutDeadlineMs(null, null, NOW));
  }
}
