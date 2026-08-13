package app.freerouting.settings;

import app.freerouting.autoroute.BoardUpdateStrategy;
import app.freerouting.autoroute.ItemSelectionStrategy;
import com.google.gson.annotations.SerializedName;
import java.io.Serializable;

/**
 * Settings for the route optimizer which runs after auto-routing to reduce vias and trace length.
 */
public class OptimizerSettings implements Serializable, Cloneable {

  /**
   * Whether the route optimizer is enabled.
   */
  /**
   * How many items the optimisation stage may examine per pass. Null -- the default -- means
   * the stage stops itself when the board stops improving measurably, and this knob does not
   * apply.
   *
   * <p>Set it and the automatic guard is switched OFF and this limit is used instead. The
   * stage is probabilistic rather than converging: passes find improvements or they do not,
   * mostly vias and occasionally a connection, so more items examined means more chances
   * rather than steadier progress. Measured against routing alone on a 26-board set, 50
   * costs roughly 3.5x the routing time and 150 roughly 5.7x. Below 150 is rarely worth the
   * clock.
   */
  @SerializedName("rounds")
  public Integer rounds;

  /**
   * The optimiser's memory budget in megabytes. Null -- the default -- derives 60% of the
   * JVM max heap, logged as an absolute number at use. Enforced by capping pool width
   * (with run-time clones, live copies are O(width), so capping width IS capping memory).
   * A budget that cannot hold one clone REFUSES loudly and the stage runs single-threaded
   * in place. Not assigned in DefaultSettings on purpose: a default there would make the
   * null branch unreachable, the dead-default defect this fork has now hit three times.
   */
  @SerializedName("memory_budget_mb")
  public Integer memoryBudgetMb;

  /**
   * When true, the optimisation stage runs on the DEFAULT scoring objective even if the
   * routing stage ran a variant one (e.g. raised via costs). Exists because scoring leaks
   * into the optimiser twice — the guard and pass threshold read the weighted score, and
   * every item reroute is a mini routing job on the same cost tables — so "route with a
   * biased objective, then polish with the honest one" is impossible from flags alone.
   * Null (the default) means the objective stays whatever the run was configured with.
   * Deliberately not assigned in DefaultSettings (the dead-default rule).
   */
  @SerializedName("restore_default_scoring")
  public Boolean restoreDefaultScoring;

  @SerializedName("enabled")
  public Boolean enabled;

  /**
   * The identifier of the optimization algorithm to use (e.g., "freerouting-optimizer").
   */
  @SerializedName("algorithm")
  public String algorithm;

  /**
   * The maximum number of full optimization passes (sweeps over the board's items) to run.
   */
  @SerializedName("max_passes")
  public Integer maxPasses;

  /**
   * The maximum number of item optimization attempts allowed.
   * If this limit is reached, the optimization stage will stop early.
   */
  @SerializedName("max_items")
  public Integer maxItems;

  /**
   * The maximum number of threads to use for parallel route optimization.
   */
  @SerializedName("max_threads")
  public Integer maxThreads;

  /**
   * The improvement threshold (as a fraction, e.g., 0.01 for 1%) below which the optimizer terminates.
   * If a pass improves the board by less than this fraction, the optimization process stops.
   */
  @SerializedName("improvement_threshold")
  public Float optimizationImprovementThreshold;

  /**
   * The maximum number of consecutive item optimization failures allowed before aborting the current pass.
   */

  /**
   * A multiplier applied to the base ripup cost at the start of optimization.
   * Higher values make ripping up existing traces more expensive, prioritizing routing speed.
   */
  @SerializedName("additional_ripup_cost_factor_at_start")
  public Integer additionalRipupCostFactorAtStart;

  /**
   * A cost discount factor applied when ripping up trace items (as opposed to vias).
   * Typically less than 1.0 to make traces easier to rip up and reroute than vias.
   */
  @SerializedName("trace_ripup_cost_factor")
  public Float traceRipupCostFactor;

  /**
   * The maximum number of autoroute passes allowed when ripping up and rerouting a single item (and its connected items) during optimization.
   */
  @SerializedName("max_autoroute_passes")
  public Integer maxAutoroutePasses;

  // -------------------------------

  /**
   * The strategy to update the board: GREEDY (update immediately on any improvement),
   * GLOBAL_OPTIMAL (calculate updates in parallel and apply the single best improvement),
   * or HYBRID (combine GREEDY and GLOBAL_OPTIMAL).
   */
  // Was transient: invisible to JSON and CLI, so the strategy matrix was unreachable
  // outside GUI defaults -- a knob that existed and could not be turned.
  @SerializedName("board_update_strategy")
  public BoardUpdateStrategy boardUpdateStrategy;

  /**
   * The ratio of GLOBAL_OPTIMAL to GREEDY updates when using the HYBRID strategy (e.g., "1:1").
   */
  public transient String hybridRatio;

  /**
   * The strategy for selecting and ordering the items to be optimized (e.g., SEQUENTIAL, RANDOM, or PRIORITIZED).
   */
  // Was transient, same story as board_update_strategy above.
  @SerializedName("item_selection_strategy")
  public ItemSelectionStrategy itemSelectionStrategy;

  /**
   * Timeout for the optimizer stage (e.g., "5m", "300s").
   * Default is null (no timeout).
   */
  @SerializedName("timeout")
  public String timeoutString;

  public OptimizerSettings() {
  }

  /**
   * Creates a deep copy of this RouterOptimizerSettings object.
   * All fields including transient ones are cloned.
   *
   * @return A new RouterOptimizerSettings instance with the same values
   */
  @Override
  public OptimizerSettings clone() {
    try {
      OptimizerSettings result = (OptimizerSettings) super.clone();
      // Primitive wrappers and Strings are immutable, so no need to clone them
      // But we need to ensure transient fields are copied
      result.boardUpdateStrategy = this.boardUpdateStrategy;
      result.hybridRatio = this.hybridRatio;
      result.itemSelectionStrategy = this.itemSelectionStrategy;
      return result;
    } catch (CloneNotSupportedException e) {
      // This should never happen since we implement Cloneable
      throw new AssertionError("Clone not supported", e);
    }
  }
}
