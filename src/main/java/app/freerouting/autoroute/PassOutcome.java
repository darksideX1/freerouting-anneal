package app.freerouting.autoroute;

/**
 * How a routing pass ended.
 *
 * <p>A pass used to report a boolean, which forced two unrelated situations to share a
 * value: "nothing left to route" and "an exception aborted this pass" both returned
 * false. A crash therefore presented to the caller as a finished board, and the only
 * trace was a log line among thousands.
 *
 * <p>{@link #ABORTED} and {@link #NO_PROGRESS} deliberately agree on
 * {@link #shouldContinue()} -- the loop stops either way. They differ on
 * {@link #isAbnormal()}, which is the distinction the boolean could not carry.
 */
public enum PassOutcome {

  /** The pass routed or ripped something; another pass is worth running. */
  PROGRESS(true, false),

  /** The pass completed normally with nothing left to do. Routing is finished. */
  NO_PROGRESS(false, false),

  /** An exception ended the pass early. The board may be partially routed. */
  ABORTED(false, true);

  private final boolean shouldContinue;
  private final boolean abnormal;

  PassOutcome(boolean shouldContinue, boolean abnormal) {
    this.shouldContinue = shouldContinue;
    this.abnormal = abnormal;
  }

  /** Whether the batch loop should run another pass. */
  public boolean shouldContinue() {
    return shouldContinue;
  }

  /** Whether the pass ended because of a defect rather than because it was done. */
  public boolean isAbnormal() {
    return abnormal;
  }
}
