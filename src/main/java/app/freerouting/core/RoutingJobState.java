package app.freerouting.core;

/* The different states a routing job can be in. */
public enum RoutingJobState {
  INVALID, // The job is in an invalid state
  QUEUED, // The job is waiting to be processed
  READY_TO_START, // The job is ready to start
  RUNNING, // The job is currently being processed
  PAUSED, // The job is paused and can be resumed
  COMPLETED, // The job has been completed successfully
  TIMED_OUT, // The job has been timed out
  STOPPING, // The job is in the process of being stopped
  CANCELLED, // The job has been cancelled by the user
  TERMINATED; // The job has been terminated due to an error

  /**
   * Whether the job has stopped, for any reason.
   *
   * <p>On the enum rather than at each call site because five separate places used to
   * carry their own inline list -- the CLI wait loop, two REST download endpoints and two
   * SSE streams -- and each listed the states its author was thinking about at the time.
   * Adding {@code TIMED_OUT} as a routine outcome broke all five differently. A predicate
   * about an enum belongs on the enum, where a new state forces one decision instead of
   * silently defaulting several.
   */
  public boolean isTerminal() {
    return this == COMPLETED || this == TERMINATED || this == TIMED_OUT || this == CANCELLED;
  }

  /**
   * Whether the job left a board worth serving to whoever asks for its output.
   *
   * <p>Deliberately NOT the same question as {@link #isTerminal()}. {@code CANCELLED} has
   * stopped and has nothing worth handing over; {@code TIMED_OUT} has stopped and holds
   * exactly what the caller asked for, since a time-boxed run is a request for the best
   * board at the deadline. Conflating the two is what made the API reject a real board.
   *
   * <p>Partial output from a job still RUNNING is a separate matter, decided by the caller.
   */
  public boolean hasUsableOutput() {
    return this == COMPLETED || this == TIMED_OUT;
  }
}