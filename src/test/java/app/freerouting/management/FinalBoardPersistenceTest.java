package app.freerouting.management;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The rule for persisting the final routed board.
 *
 * <p>Two defects meet at this decision and pull in opposite directions, which is why it is
 * a pure function with a truth table rather than an {@code if} buried in the scheduler.
 *
 * <p><b>Defect 1 (fixed earlier):</b> the board used to be saved ONLY from inside a
 * board-updated listener, so a router that emits no progress events reported success and
 * wrote a zero-byte {@code .ses}. {@code BatchAutorouterV19} does exactly that.
 *
 * <p><b>Defect 2 (this test):</b> the fix for defect 1 saved unconditionally, so a pass
 * that was cut short by an exception now OVERWRITES the last good board the listener had
 * written with a partially-routed one. The caller cannot tell, because a partial board and
 * a completed board are the same bytes as far as the file is concerned.
 *
 * <p>The resolution keeps both properties: never lose a result that was produced, and never
 * replace a good result with a broken one.
 */
class FinalBoardPersistenceTest {

  @Test
  void normalRunWithProgressEvents_persists() {
    // The ordinary case: the listener wrote output and the loop finished cleanly.
    // Writing again is harmless and keeps the final state authoritative.
    assertTrue(RoutingJobSchedulerActionThread.shouldPersistFinalBoard(false, true));
  }

  @Test
  void normalRunWithoutProgressEvents_persists() {
    // This is defect 1 -- the v1.9 engine routes correctly and fires no events.
    // Without this, the job reports success and produces a zero-byte file.
    assertTrue(RoutingJobSchedulerActionThread.shouldPersistFinalBoard(false, false));
  }

  @Test
  void abortedRunAfterGoodOutput_doesNotOverwriteIt() {
    // This is defect 2, and the reason this test exists. A pass threw partway through;
    // the board in memory is partially routed. The listener already persisted the last
    // good board, so the correct action is to leave that file alone.
    assertFalse(RoutingJobSchedulerActionThread.shouldPersistFinalBoard(true, true));
  }

  @Test
  void abortedRunWithNoOutputAtAll_stillPersists() {
    // Aborted AND nothing was ever written. A partial board is worth more than an empty
    // file here: there is no good result to protect, and an empty output is the failure
    // mode we fixed first. The abort is reported through the log either way.
    assertTrue(RoutingJobSchedulerActionThread.shouldPersistFinalBoard(true, false));
  }
}
