package app.freerouting.autoroute;

import java.util.function.Consumer;

/**
 * The one place that decides when an optimisation pass has earned more clock.
 *
 * <p>This exists because the decision used to live inline in {@link BatchOptimizer} — and
 * {@link BatchOptimizerMultiThreaded} overrides {@code opt_route_pass} wholesale, so on the
 * GUI's default path (multi-threading on, more than two processors) the entire limiter block
 * was silently bypassed: no rate guard, and an explicit {@code rounds} setting ignored.
 * Fourth instance in this fork of code that was correct where it was written and inert where
 * it mattered. Both implementations now consult this class, and the constants, the
 * predicates, and the validation cannot drift apart because there is only one of each.
 *
 * <p>The two loops observe different things — the single-threaded loop can afford a board
 * score per checkpoint, the multi-threaded poll watches the count of accepted master-board
 * replacements — so this class provides one predicate for each observable rather than
 * pretending they are the same measurement.
 */
final class OptimizerPassLimiter {

  /**
   * The window of "is this still worth running", measured in WORK -- items examined by the
   * single-threaded loop, tasks completed by the multi-threaded pool -- never wall clock.
   *
   * <p>The wall-clock predecessor was condemned by measurement three ways: it fired on
   * win-arrival VARIANCE, not stall (it killed a run with 90 seconds of steady wins
   * remaining, because one stochastic 5-second gap occurred); its patience scaled with
   * pool width (more workers = more attempts per second = a longer effective leash --
   * width 6 got near-academic quality from the same guard that killed width 2 early);
   * and its behaviour changes with machine speed, so the same board on a faster box
   * measures differently. Work units have none of those properties.
   *
   * <p>Sizing: on the reference board accepted wins arrived roughly every 4-5 completed
   * tasks while productive; 24 work units of silence is ~5x that interval -- variance
   * tolerated, stall caught. Engineering judgement from measured cadence, checked by the
   * acceptance runs (no premature fire across 3 reps on the productive board; stop within
   * a few windows of the last win on the stall board).
   */
  static final int GUARD_WINDOW_WORK_UNITS = 24;

  /**
   * The board score must climb by at least this fraction across a window for the pass to be
   * worth continuing. Rate, not presence: items report local improvements almost continuously
   * while the board creeps by amounts no fabricator would notice, so a test for "did anything
   * move at all" never fires.
   */
  static final float GUARD_MIN_RELATIVE_GAIN = 0.0005f;

  private OptimizerPassLimiter() {
  }

  /**
   * Did the board improve enough over one window to justify another? Asked of the board's own
   * score, which prices vias and trace length — so removing vias counts, which is the
   * majority of what this stage actually achieves and what the first guard was blind to.
   */
  static boolean windowProgressed(float p_score_at_window_start, float p_score_now) {
    if (p_score_at_window_start <= 0f) {
      return true;
    }
    return (p_score_now - p_score_at_window_start) / p_score_at_window_start
        >= GUARD_MIN_RELATIVE_GAIN;
  }

  /**
   * The multi-threaded pass's stall signal: has the master board been replaced by a better
   * candidate since the window opened? {@code update_count} increments only when a win is
   * accepted under the class monitor, so it is a board-level signal, not the item-local
   * "improvement" that fooled the original defect-30 guard — and it is safe to read from the
   * polling thread, which the board's own statistics are not while workers hold snapshots.
   *
   * <p>Only meaningful under the GREEDY update strategy, where wins land on the master board
   * as they happen. Under GLOBAL_OPTIMAL the board is frozen until pass end and this count
   * stays at zero by design — a guard reading it there would kill every working pass, so the
   * caller must not consult it in that mode.
   */
  static boolean countWindowStalled(long p_updates_at_window_start, long p_updates_now) {
    return p_updates_now == p_updates_at_window_start;
  }

  /**
   * Validates the {@code router.optimizer.rounds} setting. Null — the default — means the
   * automatic rate guard runs. A positive value is honoured as a per-pass item cap.
   *
   * <p>Zero or negative is REJECTED LOUDLY and treated as unset. It used to be treated
   * exactly like unset, silently: the caller asked for a cap, got the rate guard instead,
   * and was never told. That is the same defect as the allocation-census parser silently
   * enabling the mode nobody asked for — a value the user supplied must never be quietly
   * reinterpreted as a different mode.
   */
  static Integer validateRounds(Integer p_raw, Consumer<String> p_error_sink) {
    if (p_raw == null) {
      return null;
    }
    if (p_raw <= 0) {
      p_error_sink.accept("Argument NOT applied: router.optimizer.rounds=" + p_raw
          + " must be a positive number of items to examine per pass. The automatic progress"
          + " guard is used instead. The run is not configured as requested.");
      return null;
    }
    return p_raw;
  }
}
