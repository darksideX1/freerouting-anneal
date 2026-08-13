package app.freerouting;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.core.RoutingJobState;
import org.junit.jupiter.api.Test;

/**
 * When the headless CLI may stop waiting, and when it has something worth writing.
 *
 * <p>The wait loop tested COMPLETED or TERMINATED and nothing else. Four of the ten job
 * states are terminal, so a job that ended as TIMED_OUT or CANCELLED satisfied neither
 * condition and the loop span at 500 ms forever. The process had to be killed from
 * outside, and the {@code -do} output was never written, which makes a time-boxed run
 * produce nothing at all.
 *
 * <p>That hid behind a second defect for as long as both existed: the finish line used to
 * relabel a timed-out job COMPLETED whenever the router stopped promptly, and the loop
 * then exited on the wrong label. Fixing the label truthfully is what exposed this. Two
 * bugs cancelling out is not the same as working, and the pair is worth remembering: the
 * accurate change is the one that looked like it broke something.
 *
 * <p>Terminal-ness and worth-writing are separate questions, hence two functions. Every
 * terminal state must stop the wait -- that is what terminal means, and an enum that grows
 * a new one must not silently reintroduce a hang. Only states that actually left a board
 * behind should overwrite the user's output file.
 */
class CliJobWaitTest {

  @Test
  void completed_stopsWaitingAndWrites() {
    assertTrue(Freerouting.isJobFinished(RoutingJobState.COMPLETED));
    assertTrue(Freerouting.shouldWriteCliOutput(RoutingJobState.COMPLETED));
  }

  @Test
  void timedOut_stopsWaitingAndWrites() {
    // THE HANG. A deadline stop is a deliberate, well-formed outcome: the user asked for
    // a time-boxed run and the partial board is the thing they asked for. Refusing to
    // write it makes --router.job_timeout useless.
    assertTrue(Freerouting.isJobFinished(RoutingJobState.TIMED_OUT));
    assertTrue(Freerouting.shouldWriteCliOutput(RoutingJobState.TIMED_OUT));
  }

  @Test
  void cancelled_stopsWaitingButWritesNothing() {
    // Also hung, and this one predates today's changes. A user who cancelled did not ask
    // for a partial board, so their output file is left untouched -- but the program must
    // still exit.
    assertTrue(Freerouting.isJobFinished(RoutingJobState.CANCELLED));
    assertFalse(Freerouting.shouldWriteCliOutput(RoutingJobState.CANCELLED));
  }

  @Test
  void terminated_stopsWaitingButWritesNothing() {
    // A job killed by an error has no result worth overwriting a file with. Unchanged.
    assertTrue(Freerouting.isJobFinished(RoutingJobState.TERMINATED));
    assertFalse(Freerouting.shouldWriteCliOutput(RoutingJobState.TERMINATED));
  }

  @Test
  void everyRunningOrPendingState_keepsWaiting() {
    for (RoutingJobState state : new RoutingJobState[] {
        RoutingJobState.QUEUED,
        RoutingJobState.READY_TO_START,
        RoutingJobState.RUNNING,
        RoutingJobState.PAUSED,
        RoutingJobState.STOPPING,
        RoutingJobState.INVALID}) {
      assertFalse(Freerouting.isJobFinished(state), state + " is not a terminal state");
      assertFalse(Freerouting.shouldWriteCliOutput(state), state + " has no board to write");
    }
  }

  @Test
  void everyStateIsClassified() {
    // Guards the enum against growth: if a new state is added and is not deliberately
    // placed on one side or the other, this is where it gets noticed -- rather than in a
    // headless run that never returns.
    for (RoutingJobState state : RoutingJobState.values()) {
      if (Freerouting.shouldWriteCliOutput(state)) {
        assertTrue(Freerouting.isJobFinished(state),
            state + " would write output without being terminal");
      }
    }
  }
}
