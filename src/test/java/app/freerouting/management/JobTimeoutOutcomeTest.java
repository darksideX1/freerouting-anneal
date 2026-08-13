package app.freerouting.management;

import static org.junit.jupiter.api.Assertions.assertEquals;

import app.freerouting.core.RoutingJobState;
import org.junit.jupiter.api.Test;

/**
 * What a job is called when the clock, not the router, ended it.
 *
 * <p>The wall-time stop works: a monitor thread watches {@code job.timeoutAt}, asks the
 * router to stop, waits a 30-second grace period, then marks the job {@code TIMED_OUT}.
 * The finish line then reported {@code job.state} and got it wrong, because
 * {@code requestStop()} only sets a flag -- it does NOT move the job to {@code STOPPING}.
 * So during the grace period the state is still {@code RUNNING}, and a router that stops
 * promptly (which is the whole point of asking it to) reaches the finish line BEFORE the
 * monitor writes {@code TIMED_OUT}. The finish line then read {@code RUNNING} and called
 * it {@code COMPLETED}.
 *
 * <p>The result: the better the router behaves at the deadline, the more likely the run is
 * mislabelled a success. A board that ran out of clock with work left is reported exactly
 * like a board that finished with nothing left to do.
 *
 * <p>The routing-stage summary already worked around this race with an explicit deadline
 * check; the final state did not. This is that check, hoisted into a pure function so the
 * precedence between "the user cancelled", "the clock ran out" and "it finished" is stated
 * once and can be argued with.
 */
class JobTimeoutOutcomeTest {

  @Test
  void ranToCompletion_isCompleted() {
    // Nothing asked it to stop and the deadline is in the future.
    assertEquals(
        RoutingJobState.COMPLETED,
        RoutingJobSchedulerActionThread.finalStateFor(
            RoutingJobState.RUNNING, false, false, false));
  }

  @Test
  void monitorWonTheRace_staysTimedOut() {
    // The monitor got there first and already wrote TIMED_OUT. Do not overwrite it.
    assertEquals(
        RoutingJobState.TIMED_OUT,
        RoutingJobSchedulerActionThread.finalStateFor(
            RoutingJobState.TIMED_OUT, true, true, false));
  }

  @Test
  void routerStoppedPromptlyAtDeadline_isTimedOut() {
    // THE BUG. Stop requested, deadline passed, but the router obeyed inside the grace
    // period so the state is still RUNNING. This used to report COMPLETED.
    assertEquals(
        RoutingJobState.TIMED_OUT,
        RoutingJobSchedulerActionThread.finalStateFor(
            RoutingJobState.RUNNING, true, true, false));
  }

  @Test
  void stoppingAtDeadline_isTimedOut() {
    // Same race, reached from STOPPING rather than RUNNING.
    assertEquals(
        RoutingJobState.TIMED_OUT,
        RoutingJobSchedulerActionThread.finalStateFor(
            RoutingJobState.STOPPING, true, true, false));
  }

  @Test
  void userCancelled_isCancelledNotTimedOut() {
    // A cancel is an explicit human act. If the deadline happens to pass in the same
    // moment, what HAPPENED is still that someone pressed cancel -- the more specific
    // and more useful of the two facts. User intent outranks the clock.
    assertEquals(
        RoutingJobState.CANCELLED,
        RoutingJobSchedulerActionThread.finalStateFor(
            RoutingJobState.STOPPING, true, true, true));
  }

  @Test
  void userCancelledBeforeDeadline_isCancelled() {
    // The ordinary cancel, preserved unchanged from the previous behaviour.
    assertEquals(
        RoutingJobState.CANCELLED,
        RoutingJobSchedulerActionThread.finalStateFor(
            RoutingJobState.STOPPING, true, false, true));
  }

  @Test
  void stoppedWithoutDeadline_isCompleted() {
    // Stop requested but the clock had NOT run out and no user cancelled -- e.g. the
    // router hit its pass limit. That is a completed run, not a timeout. Unchanged.
    assertEquals(
        RoutingJobState.COMPLETED,
        RoutingJobSchedulerActionThread.finalStateFor(
            RoutingJobState.STOPPING, true, false, false));
  }

  @Test
  void terminatedByError_isNotRelabelled() {
    // A job that died is not a job that finished, and it is not a job that timed out
    // either, even if the deadline passed while it was failing. Never overwrite a
    // terminal state that already carries a more specific cause.
    assertEquals(
        RoutingJobState.TERMINATED,
        RoutingJobSchedulerActionThread.finalStateFor(
            RoutingJobState.TERMINATED, true, true, false));
  }

  @Test
  void alreadyCancelled_isNotRelabelled() {
    assertEquals(
        RoutingJobState.CANCELLED,
        RoutingJobSchedulerActionThread.finalStateFor(
            RoutingJobState.CANCELLED, true, true, false));
  }
}
