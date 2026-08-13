package app.freerouting.core;

/**
 * What a headless run tells a calling program, without it reading the log.
 *
 * <p>Exit status used to be binary, and "0" covered a perfectly routed board, a board with
 * 270 unrouted connections and 28 clearance violations, and a run that hit its deadline.
 * A caller could not distinguish them without parsing log text, which is precisely what a
 * severity taxonomy exists to make unnecessary.
 *
 * <p><b>An incomplete board is not an error.</b> A PCB autorouter is not a tool whose
 * output must be perfect: connections left unrouted are the normal handover back to the
 * engineer, who moves components and routes again. {@code INCOMPLETE} is therefore a
 * distinct signal rather than a failure, and is never conflated with {@code FAILED}, which
 * means no result was produced at all.
 *
 * <p>Because an incomplete board is normal, these codes are <b>opt-in</b>
 * ({@code --outcome_exit_codes=true}). Enabling them by default would make every existing
 * CI job that tests {@code $? -eq 0} start failing on boards behaving exactly as expected
 * — punishing the ordinary case to serve the careful one.
 */
public enum CliOutcome {

  /** Ran to completion; nothing left to route and no clearance violations. */
  COMPLETE(0),

  /**
   * Ran to completion, and work remains for a human.
   *
   * <p>Not a failure. The router did all it could and is handing the board back.
   */
  INCOMPLETE(3),

  /**
   * Ran, but was cut short — the wall-clock deadline, or an aborted stage.
   *
   * <p>Distinct from {@code INCOMPLETE} because the two mean different things to whoever
   * is deciding what to do next: a board the router finished with is an answer, while a
   * board it was interrupted on is a truncated attempt that a longer budget may improve.
   */
  STOPPED_EARLY(4),

  /** No result was produced: the input could not be read, or the output not written. */
  FAILED(1);

  private final int outcomeExitCode;

  CliOutcome(int outcomeExitCode) {
    this.outcomeExitCode = outcomeExitCode;
  }

  /**
   * Classifies a finished run.
   *
   * @param unroutedCount   connections still incomplete
   * @param violationCount  clearance violations on the final board
   * @param stoppedEarly    whether a deadline or an abort ended the run
   */
  public static CliOutcome of(int unroutedCount, int violationCount, boolean stoppedEarly) {
    if (stoppedEarly) {
      // Outranks the counts deliberately. An interrupted run's counts describe where it
      // happened to be when the clock stopped, not what the router concluded.
      return STOPPED_EARLY;
    }
    if (unroutedCount > 0 || violationCount > 0) {
      return INCOMPLETE;
    }
    return COMPLETE;
  }

  /** Whether a board was produced at all. Only {@code FAILED} did not produce one. */
  public boolean producedResult() {
    return this != FAILED;
  }

  /**
   * The process exit status.
   *
   * @param useOutcomeCodes {@code false} keeps the historical behaviour exactly — 0 for
   *     any run that produced a result, 1 for one that did not. {@code true} distinguishes
   *     all four. {@code FAILED} is 1 under both, so a script that only knows "nonzero is
   *     bad" keeps working either way.
   */
  public int exitCode(boolean useOutcomeCodes) {
    if (!useOutcomeCodes) {
      return producedResult() ? 0 : 1;
    }
    return outcomeExitCode;
  }
}
