package app.freerouting.autoroute;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.core.StoppableThread;
import org.junit.jupiter.api.Test;

/**
 * "Stop the auto-router" must not mean "stop the optimiser's re-router". Defect 25.
 *
 * <p>{@code AUTO_ROUTER_ONLY} is documented in {@code StoppableThread} as <i>"stop the
 * auto-router, but continue with the optimizer and other tasks"</i>. But
 * {@code is_stop_auto_router_requested()} returns true for that state as well as for a
 * full stop, and {@code autoroute_pass} consults it in its per-item loop — a loop the
 * OPTIMISER also runs, through {@code autoroute_passes_for_optimizing_item}.
 *
 * <p>The result was measured: the optimiser's re-route queued 29–31 items and routed
 * <b>zero</b>, breaking out on the first net of the first item, then reported
 * {@code NO_PROGRESS}. Every optimisation attempt therefore ripped a connection up, failed
 * to put it back, measured the board as worse, and undid it — 45 items examined and 0
 * improved, forever, with byte-identical output because of the undo.
 *
 * <p>Fixing the outer pass loop alone was not enough, and this test exists because I made
 * exactly that mistake: the same predicate is consulted at several depths, and the one that
 * mattered was two levels in.
 *
 * <p>The distinction is context-dependent, which is why it needs a named helper rather than
 * a raw predicate: the SAME pass code serves the main routing stage, where
 * {@code AUTO_ROUTER_ONLY} genuinely means stop, and the optimiser, where it must not.
 */
class StopSemanticsTest {

  private static final class TestThread extends StoppableThread {
    @Override
    protected void thread_action() {
      // never started; only its stop state is read
    }
  }

  @Test
  void mainRoutingStops_onAnAutoRouterStop() {
    TestThread thread = new TestThread();
    thread.request_stop_auto_router();

    assertTrue(BatchAutorouter.routingShouldStop(thread, false),
        "the main auto-router must honour an auto-router stop -- that is what it is for");
  }

  @Test
  void theOptimizerReroute_ignoresAnAutoRouterStop() {
    TestThread thread = new TestThread();
    thread.request_stop_auto_router();

    assertFalse(BatchAutorouter.routingShouldStop(thread, true),
        "the optimiser's re-route must keep going: AUTO_ROUTER_ONLY means routing is "
            + "finished and the optimiser now has the board");
  }

  @Test
  void bothStop_onAFullStop() {
    TestThread thread = new TestThread();
    thread.requestStop();

    assertTrue(BatchAutorouter.routingShouldStop(thread, false), "main routing halts");
    assertTrue(BatchAutorouter.routingShouldStop(thread, true),
        "so does the optimiser -- a full stop is the user ending everything");
  }

  @Test
  void neitherStops_whenNothingWasRequested() {
    TestThread thread = new TestThread();

    assertFalse(BatchAutorouter.routingShouldStop(thread, false));
    assertFalse(BatchAutorouter.routingShouldStop(thread, true));
  }
}
