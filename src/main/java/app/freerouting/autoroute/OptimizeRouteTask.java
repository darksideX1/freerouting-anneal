package app.freerouting.autoroute;

import app.freerouting.board.Item;
import app.freerouting.board.RoutingBoard;
import app.freerouting.core.RoutingJob;
import app.freerouting.logger.FRLogger;

public class OptimizeRouteTask implements Runnable {

  // Assigned in run(), not the constructor. All tasks used to deepCopy the board at
  // CONSTRUCTION -- which happens in the scheduling loop at pass start -- so every task
  // cloned the pre-pass board, GREEDY's intra-pass compounding was structurally impossible,
  // and O(item count) full board clones were alive at once (measured: ~4.4 GB peak RSS,
  // 862 GB of allocation churn in one job). Cloning at RUN time means each task starts
  // from the current master -- later tasks compound on earlier wins -- and live clones
  // drop to O(pool width).
  public RoutingBoard board;
  private final BatchOptimizerMultiThreaded optimizer;
  private final int pass_no;
  private final boolean with_preferred_directions;
  private final RoutingJob job;
  private final int item_id;
  private Item itemToOptimize;
  private ItemRouteResult optimizationResult;

  public OptimizeRouteTask(BatchOptimizerMultiThreaded p_optimizer, RoutingJob job, int item_id, int p_pass_no, boolean p_with_preferred_directions) {
    optimizer = p_optimizer;

    this.job = job;
    this.item_id = item_id;

    pass_no = p_pass_no;
    with_preferred_directions = p_with_preferred_directions;
  }

  @Override
  public void run() {
    long startTime = System.currentTimeMillis();

    // Clone from the CURRENT master, so this task builds on every win accepted before it
    // started. The reference is read under the optimizer's monitor; the copy runs outside
    // it (master boards are immutable after publication -- see currentMasterBoard).
    this.board = optimizer.currentMasterBoard().deepCopy();
    itemToOptimize = this.board.get_item(item_id);

    // The item can legitimately be gone now: a win accepted after this task was scheduled
    // may have rerouted its net and replaced the item. Its region was already improved --
    // skipping is the correct outcome, not an error.
    if (itemToOptimize == null) {
      return;
    }

    optimizationResult = new BatchOptimizer(this.job).opt_route_item(itemToOptimize, with_preferred_directions, true);

    boolean winning_candidate = optimizer.is_winning_candidate(this);

    long duration = System.currentTimeMillis() - startTime;
    long minutes = duration / 60000;
    float sec = (duration % 60000) / 1000.0F;

    if (FRLogger.isDebugEnabled()) {
      FRLogger.debug(
          "Finished   task #" + optimizer.get_num_tasks_finished() + " of " + optimizer.get_num_tasks() + " for item #" + itemToOptimize.get_id_no() + " on pass " + pass_no + " in " + minutes + " m "
              + sec + "s." + " Best so far: " + winning_candidate + ", improved: " + optimizationResult.improved() + ", via reduction: " + optimizationResult.via_count_reduced() + (winning_candidate ? (
              ", length reduction: " + (int) optimizationResult.length_reduced()) : "") + ", incomplete trace reduction: " + (optimizationResult.incomplete_count_before()
              - optimizationResult.incomplete_count()));
    }

    if (!winning_candidate) {
      clean();
    }
  }

  public ItemRouteResult getRouteResult() {
    return this.optimizationResult;
  }

  public Item getItem() {
    return itemToOptimize;
  }

  public void clean() { // try to speed up memory release
    itemToOptimize.board = null;
    itemToOptimize = null;
  }
}