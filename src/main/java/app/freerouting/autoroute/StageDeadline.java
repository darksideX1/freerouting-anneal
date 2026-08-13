package app.freerouting.autoroute;

/**
 * When a routing stage should stop, given its own optional timeout and the job's.
 *
 * <p>Fanout, auto-routing and optimisation all run inside one job clock. Each stage reads the
 * job's deadline when it starts, so whatever earlier stages spent is already gone and the
 * stage simply takes what is left — no budget is handed back explicitly, because a second
 * bookkeeping path is a second thing that can disagree with the first.
 *
 * <p>This rule lived in two places and was missing from a third. BatchOptimizer and
 * BatchFanout each had their own copy, and auto-routing had none at all — which is why it was
 * the one stage cut off mid-pass by the outer watchdog while the other two finished by
 * choice. Three copies of a rule is three chances to drift, so this is the only copy.
 */
final class StageDeadline {

  /**
   * How far inside the job deadline a stage aims to finish.
   *
   * <p>The watchdog enforcing the job deadline ticks once a second. A stage aiming to land
   * exactly on the deadline races that tick and loses about half the time, which turns a
   * graceful finish into an amputation. Five seconds rather than one because one was tried
   * and still raced.
   */
  static final long STAGE_DEADLINE_GRACE_MS = 5_000L;

  private StageDeadline() {
  }

  /**
   * Returns the instant this stage should stop, or null if nothing bounds it.
   *
   * @param p_explicitTimeoutSeconds the stage's own timeout, or null if unset. It can shorten
   *     the stage; it can never extend it past the job.
   * @param p_jobDeadlineMs when the whole job must be finished, or null if the job is unbounded.
   * @param p_nowMs the moment the stage starts.
   */
  static Long compute(Long p_explicitTimeoutSeconds, Long p_jobDeadlineMs, long p_nowMs) {
    Long fromExplicit =
        p_explicitTimeoutSeconds != null ? p_nowMs + p_explicitTimeoutSeconds * 1000 : null;
    if (p_jobDeadlineMs == null) {
      return fromExplicit;
    }
    // max(now, ...) so a stage that starts after the job deadline stops immediately rather
    // than being handed a deadline in the past.
    long latestAllowed = Math.max(p_nowMs, p_jobDeadlineMs - STAGE_DEADLINE_GRACE_MS);
    if (fromExplicit == null) {
      return latestAllowed;
    }
    return Math.min(fromExplicit, latestAllowed);
  }
}
