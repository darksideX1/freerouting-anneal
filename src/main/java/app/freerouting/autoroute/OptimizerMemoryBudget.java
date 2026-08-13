package app.freerouting.autoroute;

import java.util.function.Consumer;

/**
 * The optimiser's memory budget: a numeric bound the stage must live inside, sized like a
 * good citizen and enforced by capping concurrency — with deferred clones, live board
 * copies are O(pool width), so capping the width IS capping the memory.
 *
 * <p>Hard rules (adopted from independent review, binding):
 * <ul>
 *   <li>The default is derived (60% of the JVM's max heap) but always LOGGED as an
 *       absolute number — never a silent percentage.</li>
 *   <li>A degradation is a first-class banked outcome, not a debug line: a run that
 *       silently degraded concurrency is a different experiment from one that did not.</li>
 *   <li>A budget below the single-clone floor REFUSES with the numbers named — never
 *       degrades to zero workers and hangs. Refusal beats mystery. The refusing caller
 *       falls back to the single-threaded in-place pass, announced.</li>
 *   <li>The outer limit rules: the budget derives from {@code Runtime.maxMemory()}, so a
 *       container or -Xmx cap below the configured budget shrinks it automatically — in
 *       containers the outer limit is the one that kills you.</li>
 * </ul>
 */
final class OptimizerMemoryBudget {

  /** Share of the JVM max heap the optimiser may claim when no explicit budget is set. */
  static final double DEFAULT_HEAP_SHARE = 0.60;

  private OptimizerMemoryBudget() {
  }

  /** The derived default, in bytes: 60% of what the JVM may ever use. */
  static long defaultBudgetBytes(long p_max_heap_bytes) {
    return (long) (p_max_heap_bytes * DEFAULT_HEAP_SHARE);
  }

  /**
   * Validates {@code router.optimizer.memory_budget_mb}. Null — the default — means the
   * derived budget. Zero or negative is rejected loudly and treated as unset, same
   * doctrine as every other knob here: a value the user supplied is never silently
   * reinterpreted as a different mode.
   */
  static Integer validateBudgetMb(Integer p_raw, Consumer<String> p_error_sink) {
    if (p_raw == null) {
      return null;
    }
    if (p_raw <= 0) {
      p_error_sink.accept("Argument NOT applied: router.optimizer.memory_budget_mb=" + p_raw
          + " must be a positive number of megabytes. The derived default (60% of max heap)"
          + " is used instead. The run is not configured as requested.");
      return null;
    }
    return p_raw;
  }

  /**
   * How wide the pool may actually be under the budget.
   *
   * @return the affordable width; {@code 0} means REFUSE — the budget cannot hold even one
   *     clone, and the caller must fall back to the in-place single-threaded pass with the
   *     numbers named. A width of 1 is still a working (if degenerate) pool; zero workers
   *     is never returned as something to run with.
   */
  static int effectiveWidth(int p_requested, long p_budget_bytes, long p_per_clone_bytes) {
    if (p_requested <= 0) {
      return 0;
    }
    if (p_per_clone_bytes <= 0) {
      // Measurement failed (a GC during the probe can reclaim more than the copy costs).
      // An unusable safety measurement must not be read as unlimited capacity; one clone
      // is the conservative answer, same doctrine as the racing width.
      return 1;
    }
    long affordable = p_budget_bytes / p_per_clone_bytes;
    if (affordable < 1) {
      return 0;
    }
    return (int) Math.min(p_requested, affordable);
  }
}
