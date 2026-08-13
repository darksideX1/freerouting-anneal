package app.freerouting.autoroute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One rule for when a stage stops, shared by all three stages.
 *
 * <p>Fanout, auto-routing and optimisation each run inside the job's clock. The rule was
 * written twice already — once in BatchOptimizer, once in BatchFanout — and auto-routing had
 * no rule at all, so it was the stage that got amputated by the outer watchdog while the
 * other two finished by choice. Three copies of a rule is three chances for it to drift, so
 * this is the single copy and the stages delegate to it.
 *
 * <p>The grace exists because the watchdog enforcing the job deadline ticks once a second: a
 * stage aiming to land exactly on the deadline races that tick and loses, which turns a
 * graceful finish into a cut-off mid-pass.
 */
class StageDeadlineTest {

  private static final long NOW = 1_000_000L;

  @Test
  @DisplayName("with no explicit stage timeout, a stage inherits the job's clock")
  void inheritsTheJobDeadline() {
    Long deadline = StageDeadline.compute(null, NOW + 180_000L, NOW);

    assertNotNull(deadline, "a stage must inherit a deadline from the job");
    assertTrue(deadline < NOW + 180_000L, "a stage must finish before the job deadline");
    assertTrue(deadline > NOW, "the derived deadline must leave the stage time to work");
  }

  @Test
  @DisplayName("an explicit stage timeout can shorten a stage but never outlive the job")
  void explicitTimeoutIsClampedToTheJob() {
    long jobDeadline = NOW + 60_000L;

    Long deadline = StageDeadline.compute(600L, jobDeadline, NOW);

    assertTrue(deadline < jobDeadline,
        "ten minutes of stage inside a one minute job must still end inside the job");
  }

  @Test
  @DisplayName("a shorter explicit timeout is honoured as given")
  void shorterExplicitTimeoutWins() {
    assertEquals(NOW + 10_000L,
        StageDeadline.compute(10L, NOW + 180_000L, NOW).longValue(),
        "an explicit timeout inside the envelope should be used unchanged");
  }

  @Test
  @DisplayName("no envelope and no explicit timeout means no deadline")
  void noEnvelopeNoDeadline() {
    assertNull(StageDeadline.compute(null, null, NOW));
  }

  @Test
  @DisplayName("a job deadline already passed does not produce a deadline in the past")
  void neverDerivesAPastDeadline() {
    Long deadline = StageDeadline.compute(null, NOW - 60_000L, NOW);

    assertNotNull(deadline);
    assertTrue(deadline >= NOW,
        "a stage starting after the job deadline should stop immediately, not time-travel");
  }
}
