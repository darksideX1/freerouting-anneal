package app.freerouting.autoroute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The optimiser must stop itself before the job clock stops it.
 *
 * <p>Today the optimiser stage has its own timeout that defaults to <b>unset</b>, which
 * means unbounded — so what actually ends it is the job-level timeout firing around it.
 * That is the ugly version: the stage is CUT OFF mid-pass and the run is reported as
 * {@code TIMED_OUT}, rather than the stage finishing cleanly and reporting the best board
 * it found in the time available. A user who allows two minutes should get the best board
 * achievable in two minutes, not a failure.
 *
 * <p>So the stage deadline is derived rather than left implicit: it is the job's deadline
 * minus a small grace, computed when the optimiser STARTS, so it naturally accounts for
 * however long routing already consumed. Give the job three minutes and the optimiser gets
 * three minutes minus what routing used minus the grace. One knob for the ordinary case;
 * the explicit optimiser timeout stays available for the rare one.
 *
 * <p>An explicit setting may only ever SHORTEN the window. A stage timeout longer than the
 * job it runs inside is not a longer stage, it is the cut-off again.
 *
 * <p>Determinism note, because this interacts with defect 20: a deadline stop is
 * wall-clock-dependent and therefore machine-dependent. This makes the stop GRACEFUL, not
 * reproducible. Reproducibility wants a work-unit bound as the primary limit with the clock
 * as backstop, which is a separate change.
 */
class OptimizerDeadlineTest {

  private static final long NOW = 1_000_000L;
  private static final long GRACE = BatchOptimizer.OPTIMIZER_DEADLINE_GRACE_MS;

  @Test
  void graceIsSmallButRealSoOneFinishesBeforeTheOther() {
    assertTrue(GRACE > 0, "the optimiser must finish strictly before the job deadline");
    assertTrue(GRACE <= 5_000, "the grace is a safety margin, not a meaningful slice of the budget");
  }

  @Test
  void withNoExplicitTimeoutTheStageInheritsTheJobDeadlineMinusGrace() {
    long jobDeadline = NOW + 120_000;

    Long got = BatchOptimizer.computeOptimizerDeadlineMs(null, jobDeadline, NOW);

    assertEquals(jobDeadline - GRACE, got,
        "the stage should stop just before the job clock would cut it off");
  }

  @Test
  void theStageDeadlineAccountsForTimeRoutingAlreadySpent() {
    // The job started 100 s ago with a 120 s budget; routing used that time, so the
    // optimiser inherits what is LEFT, not the whole budget.
    long jobDeadline = NOW + 20_000;

    Long got = BatchOptimizer.computeOptimizerDeadlineMs(null, jobDeadline, NOW);

    assertEquals(jobDeadline - GRACE, got);
    assertTrue(got - NOW < 20_000, "only the remaining window is available");
  }

  @Test
  void anExplicitTimeoutMayShortenTheWindow() {
    long jobDeadline = NOW + 120_000;

    Long got = BatchOptimizer.computeOptimizerDeadlineMs(30L, jobDeadline, NOW);

    assertEquals(NOW + 30_000, got, "an explicit, shorter stage timeout is honoured");
  }

  @Test
  void anExplicitTimeoutMayNotOutliveTheJob() {
    // The rare-case knob must not be able to reintroduce the cut-off it exists to avoid.
    long jobDeadline = NOW + 60_000;

    Long got = BatchOptimizer.computeOptimizerDeadlineMs(300L, jobDeadline, NOW);

    assertEquals(jobDeadline - GRACE, got,
        "a stage timeout longer than its job is the cut-off again, so it is capped");
  }

  @Test
  void withNoJobDeadlineTheExplicitTimeoutStillApplies() {
    Long got = BatchOptimizer.computeOptimizerDeadlineMs(45L, null, NOW);

    assertEquals(NOW + 45_000, got);
  }

  @Test
  void withNeitherSetTheStageStaysUnbounded() {
    assertNull(BatchOptimizer.computeOptimizerDeadlineMs(null, null, NOW),
        "no job deadline and no stage timeout is the only case that stays open-ended");
  }

  @Test
  void aDeadlineAlreadyInsideTheGraceStopsImmediatelyRatherThanGoingBackwards() {
    // Routing consumed all but a moment of the budget. The answer is "stop now", never a
    // deadline in the past, which would make elapsed-time arithmetic negative downstream.
    long jobDeadline = NOW + 200;

    Long got = BatchOptimizer.computeOptimizerDeadlineMs(null, jobDeadline, NOW);

    assertEquals(NOW, got, "stop immediately, but do not hand back a past deadline");
  }
}
