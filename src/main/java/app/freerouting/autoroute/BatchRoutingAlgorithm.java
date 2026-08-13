package app.freerouting.autoroute;

import java.time.Instant;

/**
 * The contract a batch router must satisfy to be driven by the job scheduler.
 *
 * <p>Extracted so the scheduler can select an implementation instead of hardcoding one.
 * Both the current router and the v1.9 router satisfy it; the scheduler previously
 * declared its local variable as the concrete {@code BatchAutorouter}, which is why the
 * algorithm setting could not be honoured even though the constant for it existed.
 */
public interface BatchRoutingAlgorithm {

  /** Runs ripup passes until the board is complete or the router is stopped. */
  boolean runBatchLoop();

  /** True when the fanout stage gave up on its time budget. */
  boolean isFanoutTimedOut();

  /** When this routing session started, or null if it never ran. */
  Instant getSessionStartTime();

  /** Unrouted connections counted at the start of the session. */
  int getInitialUnroutedCount();

  /**
   * True when the batch loop stopped because a pass was cut short by an exception,
   * leaving the board only partially routed.
   *
   * <p>The caller needs this to decide whether the in-memory board is safe to persist
   * over one that was already written: a partial board and a finished board are
   * indistinguishable once they are bytes in a file.
   */
  boolean endedAbnormally();
}
