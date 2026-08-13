package app.freerouting;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.core.RoutingJob;
import app.freerouting.core.RoutingJobState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * What the run says about how it ended.
 *
 * <p>This sentence is the only thing telling a user whether to re-run with a longer budget,
 * and it has been wrong repeatedly: first because {@code job.state} reads COMPLETED for a
 * run the clock cut (stages stop INSIDE their deadline), then because pressing {@code s}
 * still claimed the run finished by itself. Each time it was confident and wrong, which is
 * the worst combination for a line somebody acts on.
 *
 * <p>Cancellation is the case here. Pressing {@code c} sets CANCELLED, and
 * {@code hasUsableOutput()} is COMPLETED || TIMED_OUT -- so cancelling writes no file at
 * all, while the message promised a board.
 */
class RunEndingMessageTest {

  @Test
  @Timeout(10)
  @DisplayName("cancelling does not claim a board was written, because none is")
  void cancellationDoesNotPromiseAFile() {
    RoutingJob job = new RoutingJob();
    job.state = RoutingJobState.CANCELLED;

    String message = Freerouting.endingMessage(job);

    assertNotNull(message, "a cancelled run must still say how it ended");
    // Assert the contract, not a word inside it. The first version of this test banned the
    // substring "written", which the CORRECT message trips on -- "No output file was
    // written" is precisely what we want it to say. A proxy for a claim is not the claim,
    // and this one would have blocked the fix it was written to force.
    assertFalse(message.contains("Board written"),
        "cancelling writes no output file, so the ending must not claim a board: " + message);
    assertTrue(message.toLowerCase().contains("no output file"),
        "a cancelled run must say plainly that nothing was written: " + message);
  }

  @Test
  @Timeout(10)
  @DisplayName("a run the clock cut says so, whatever job.state reads")
  void timedOutSaysRanOutOfTime() {
    RoutingJob job = new RoutingJob();
    job.state = RoutingJobState.COMPLETED;
    job.stageTimedOut = true;

    assertTrue(Freerouting.endingMessage(job).startsWith("Ran out of time."),
        "stages stop inside their deadline, so state alone cannot tell this ending");
  }

  @Test
  @Timeout(10)
  @DisplayName("stopping on request keeps the board and says so")
  void stoppedByUserKeepsTheBoard() {
    RoutingJob job = new RoutingJob();
    job.state = RoutingJobState.COMPLETED;
    job.stoppedByUser = true;

    String message = Freerouting.endingMessage(job);
    assertTrue(message.startsWith("Stopped on request."), message);
    assertTrue(message.toLowerCase().contains("written"),
        "stop keeps the board, so this ending must say the board was written: " + message);
  }

  @Test
  @Timeout(10)
  @DisplayName("finishing by itself tells the user a longer budget buys nothing")
  void completedSaysMoreTimeWillNotHelp() {
    RoutingJob job = new RoutingJob();
    job.state = RoutingJobState.COMPLETED;

    assertTrue(Freerouting.endingMessage(job).startsWith("Pass finished."),
        "a run that stopped finding improvements must not invite a re-run");
  }
}
