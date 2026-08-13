package app.freerouting.autoroute;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Racing is reachable, and off unless asked for.
 *
 * <p>Until now {@code autoroute_pass_multi_thread} had ZERO callers — it was not
 * work-in-progress behind a flag, it was orphaned. Wiring it is the point of this stream.
 *
 * <p>But it must not be wired to {@code maxThreads}, which defaults to
 * {@code availableProcessors - 1} — fifteen on the development box. Dispatching on that
 * would silently move every existing user onto a path whose correctness defects were being
 * fixed this same afternoon, and would change the memory profile of every run. The plan's
 * exit criterion is explicit: on by default only if it wins, and it has not been measured
 * yet.
 */
class RacingDispatchTest {

  @Test
  void racingIsOffUnlessAskedFor() {
    // The default. maxThreads is high on any modern box and must not imply consent.
    assertFalse(BatchAutorouter.shouldRace(false, 15));
    assertFalse(BatchAutorouter.shouldRace(false, 2));
  }

  @Test
  void askingForItWithThreadsAvailableRaces() {
    assertTrue(BatchAutorouter.shouldRace(true, 2));
    assertTrue(BatchAutorouter.shouldRace(true, 15));
  }

  @Test
  void askingForItWithOneThreadDoesNotRace() {
    // A one-thread race is the single-threaded path plus a board copy and a join: strictly
    // worse. Honour the intent by declining rather than by pretending.
    assertFalse(BatchAutorouter.shouldRace(true, 1));
    assertFalse(BatchAutorouter.shouldRace(true, 0));
  }
}
