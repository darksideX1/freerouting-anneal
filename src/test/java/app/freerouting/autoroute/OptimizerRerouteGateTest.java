package app.freerouting.autoroute;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.core.StoppableThread;
import org.junit.jupiter.api.Test;

/**
 * The optimiser's re-router must survive an auto-router stop. Defect 25.
 *
 * <p>{@code StoppableThread} has three states and documents them in the source:
 *
 * <pre>
 *   requestStop()              -> ALL               stop fanout, auto-router AND optimizer
 *   request_stop_auto_router() -> AUTO_ROUTER_ONLY  "stop the auto-router, but CONTINUE
 *                                                    with the optimizer and other tasks"
 * </pre>
 *
 * <p>But {@code is_stop_auto_router_requested()} returns true for BOTH states, and the
 * optimiser's own re-routing helper gated its pass loop on exactly that predicate. So the
 * flag whose entire purpose is "routing is finished, hand over to the optimiser" was the
 * flag that switched the optimiser off.
 *
 * <p>Measured before this fix: the re-route loop executed <b>zero</b> passes
 * ({@code stopRequested=true} on every entry, {@code passes used: 0}), so every item was
 * ripped up, not re-routed, found worse, and undone. 45 items examined and 0 improved, on
 * every pass, for 100 passes — and the board came out byte-identical because the undo
 * restored it each time. That is the direct cause of this fork shipping 17 vias and 9% more
 * trace length where the 2023 original produces 14.
 *
 * <p>The distinction below is the whole defect, so it is pinned rather than left to a
 * comment: a full stop must halt the optimiser, an auto-router stop must not.
 */
class OptimizerRerouteGateTest {

  /** Minimal concrete thread; the class under test only reads its stop state. */
  private static final class TestThread extends StoppableThread {
    @Override
    protected void thread_action() {
      // nothing: this test never starts the thread, it only asks about its stop state
    }
  }

  @Test
  void anAutoRouterStopMustNotDisableTheOptimizerReroute() {
    TestThread thread = new TestThread();
    thread.request_stop_auto_router();

    assertTrue(thread.is_stop_auto_router_requested(),
        "precondition: the auto-router stop is set");
    assertTrue(BatchAutorouter.optimizerRerouteMayRun(thread),
        "AUTO_ROUTER_ONLY means 'routing is done, now optimise' -- it must NOT stop the "
            + "optimiser's own re-router, which is what defect 25 was");
  }

  @Test
  void aFullStopMustDisableTheOptimizerReroute() {
    TestThread thread = new TestThread();
    thread.requestStop();

    assertFalse(BatchAutorouter.optimizerRerouteMayRun(thread),
        "a full stop is the user asking for everything to end, optimiser included");
  }

  @Test
  void withNothingRequestedTheRerouteRuns() {
    assertTrue(BatchAutorouter.optimizerRerouteMayRun(new TestThread()),
        "the ordinary case: nothing has asked anything to stop");
  }

  @Test
  void anAutoRouterStopFollowedByAFullStopStillStops() {
    // Order matters: request_stop_auto_router() only promotes from NONE, so a full stop
    // afterwards must still win. If it did not, a user pressing stop during optimisation
    // would be ignored.
    TestThread thread = new TestThread();
    thread.request_stop_auto_router();
    thread.requestStop();

    assertFalse(BatchAutorouter.optimizerRerouteMayRun(thread),
        "a full stop after an auto-router stop must still halt the optimiser");
  }
}
