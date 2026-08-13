package app.freerouting.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * What a job state means, asked in one place instead of five.
 *
 * <p>"Which states are terminal" and "which states left a board worth serving" were both
 * answered by inline enum comparisons scattered across the CLI wait loop, two REST download
 * endpoints and two SSE streams. Each copy listed the states its author happened to be
 * thinking about, so introducing {@code TIMED_OUT} as a routine outcome broke every copy in
 * a different way — the CLI hung, the REST endpoints returned 400 "has no valid output" for
 * a board that exists, and the SSE streams never closed.
 *
 * <p>The lesson is not "I missed some call sites". It is that a predicate about an enum
 * belongs on the enum, where adding a state forces one decision instead of silently
 * defaulting five.
 *
 * <p>The two questions are separate and deliberately do not coincide. {@code CANCELLED} is
 * terminal and has nothing worth serving; {@code TIMED_OUT} is terminal and has exactly
 * what the user asked for. Conflating them is what produced both bugs.
 */
class RoutingJobStateTest {

  @Test
  void terminalStatesAreTheFourThatStop() {
    assertTrue(RoutingJobState.COMPLETED.isTerminal());
    assertTrue(RoutingJobState.TERMINATED.isTerminal());
    assertTrue(RoutingJobState.TIMED_OUT.isTerminal());
    assertTrue(RoutingJobState.CANCELLED.isTerminal());
  }

  @Test
  void everythingElseIsStillInFlight() {
    for (RoutingJobState state : new RoutingJobState[] {
        RoutingJobState.INVALID,
        RoutingJobState.QUEUED,
        RoutingJobState.READY_TO_START,
        RoutingJobState.RUNNING,
        RoutingJobState.PAUSED,
        RoutingJobState.STOPPING}) {
      assertFalse(state.isTerminal(), state + " must not be treated as terminal");
    }
  }

  @Test
  void aTimedOutJobHasTheBoardTheUserAskedFor() {
    // The whole point of a time-boxed run. Serving 400 "has no valid output" for this is
    // wrong in the CLI and equally wrong over REST -- the same job must not yield a board
    // through one interface and an error through the other.
    assertTrue(RoutingJobState.TIMED_OUT.hasUsableOutput());
    assertTrue(RoutingJobState.COMPLETED.hasUsableOutput());
  }

  @Test
  void aFailedOrAbandonedJobHasNothingWorthServing() {
    assertFalse(RoutingJobState.TERMINATED.hasUsableOutput());
    assertFalse(RoutingJobState.CANCELLED.hasUsableOutput());
    assertFalse(RoutingJobState.INVALID.hasUsableOutput());
  }

  @Test
  void usableOutputImpliesTerminal() {
    // In-progress partial output is a different question, answered separately by the API.
    // This one is only about states that have stopped.
    for (RoutingJobState state : RoutingJobState.values()) {
      if (state.hasUsableOutput()) {
        assertTrue(state.isTerminal(), state + " claims output without having stopped");
      }
    }
  }

  @Test
  void everyStateIsClassifiedExactlyOnce() {
    // Growth guard. A new state added to this enum lands here rather than in a headless
    // run that never returns or an endpoint that 400s on a real board.
    int terminal = 0;
    for (RoutingJobState state : RoutingJobState.values()) {
      if (state.isTerminal()) {
        terminal++;
      }
    }
    assertEquals(4, terminal,
        "a state was added or reclassified -- decide deliberately what it means for the "
            + "CLI wait loop, the REST download endpoints and the SSE streams");
  }
}
