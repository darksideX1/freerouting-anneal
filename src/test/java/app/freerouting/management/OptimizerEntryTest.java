package app.freerouting.management;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Whether the optimizer may run on a board the router abandoned mid-pass.
 *
 * <p>An earlier fix stopped an aborted routing pass from overwriting a good board already
 * on disk with its partially routed one — see {@link FinalBoardPersistenceTest}. That fix
 * held for about a millisecond. The optimizer runs next, unconditionally, and its
 * board-updated listener calls {@code setJobOutput} on the first pass, which writes the
 * very partial board the previous decision had just declined to write.
 *
 * <p>So the preservation was real and then immediately undone by the following stage. The
 * abort has to be carried forward rather than handled locally: a stage that decides not to
 * trust the board cannot leave the next stage to rediscover that on its own.
 *
 * <p>Optimizing an abandoned board is also meaningless on its own terms. The router threw
 * partway through; what is in memory is not a routing result, it is the wreckage of one.
 */
class OptimizerEntryTest {

  @Test
  void normalRun_optimizesWhenAsked() {
    assertTrue(RoutingJobSchedulerActionThread.shouldRunOptimizer(true, false));
  }

  @Test
  void abortedRouting_doesNotOptimize() {
    // THE BUG: this used to be true, and the optimizer's first progress event then
    // overwrote the good board that the abort handling had deliberately preserved.
    assertFalse(RoutingJobSchedulerActionThread.shouldRunOptimizer(true, true));
  }

  @Test
  void optimizerDisabled_staysDisabled() {
    // The user's setting is still the user's setting. An abort may only ever subtract.
    assertFalse(RoutingJobSchedulerActionThread.shouldRunOptimizer(false, false));
    assertFalse(RoutingJobSchedulerActionThread.shouldRunOptimizer(false, true));
  }
}
