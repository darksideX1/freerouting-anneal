package app.freerouting.autoroute;

import app.freerouting.board.Item;
import app.freerouting.board.RoutingBoard;
import app.freerouting.core.RouterCounters;
import app.freerouting.core.RoutingJob;
import app.freerouting.core.scoring.BoardStatistics;
import app.freerouting.logger.FRLogger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.PriorityQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Optimizes routes using multiple threads on a board that has completed auto-routing.
 */
public class BatchOptimizerMultiThreaded extends BatchOptimizer {

  private final BoardUpdateStrategy board_update_strategy;
  private final ItemSelectionStrategy item_selection_strategy;
  private final int thread_pool_size;

  /**
   * The single dispatch predicate for governed MT, shared by the headless scheduler and
   * the GUI thread. Null-safe (a null width means defaults were not merged: run ST) and
   * ceiling-aware BEFORE dispatch: a request that the core ceiling would clamp to 1
   * runs the single-threaded optimizer honestly instead of a pool of one.
   */
  public static boolean shouldUseMultiThreaded(RoutingJob job) {
    Integer requested = job.routerSettings.optimizer.maxThreads;
    if (requested == null) {
      return false;
    }
    int coreCeiling = Runtime.getRuntime().availableProcessors();
    if (requested > 1 && Math.min(requested, coreCeiling) <= 1) {
      // The decline must be as loud as the clamp: a one-core machine asked for width N
      // runs the single-threaded optimiser, and the run says so instead of silently
      // switching modes.
      job.logWarning("Optimizer width " + requested + " not applied: this machine has "
          + coreCeiling + " logical processor(s); running the single-threaded optimizer.");
      return false;
    }
    return Math.min(requested, coreCeiling) > 1;
  }
  private final ArrayList<Integer> item_ids = new ArrayList<>();
  private final HashMap<Integer, ItemRouteResult> result_map = new HashMap<>();
  private final ArrayList<BoardUpdateStrategy> hybrid_list = new ArrayList<>();
  private ThreadPoolExecutor pool;
  private ItemRouteResult best_route_result;
  private OptimizeRouteTask winning_candidate;
  private int num_tasks_finished;
  // volatile: incremented only under the class monitor (accepted master-board
  // replacements), read by the pass thread's poll loop as the rate guard's stall signal.
  private volatile int update_count;
  private CountDownLatch task_completion_signal = new CountDownLatch(1);
  private int hybrid_index = -1;

  /** Measured cost of one board clone, cached per stage; 0 = not yet measured, -1 = measured unusable. */
  private long perCloneBytes = 0L;

  public BatchOptimizerMultiThreaded(RoutingJob job) {
    super(job);

    // Core count is a CEILING, never a target: a request wider than the machine is
    // clamped, loudly. The default (2) is the measured quality point. Null-safe read:
    // a job constructed without merged defaults still gets the measured default.
    Integer requestedSetting = job.routerSettings.optimizer.maxThreads;
    int requestedWidth = (requestedSetting == null) ? 2 : requestedSetting;
    int coreCeiling = Runtime.getRuntime().availableProcessors();
    this.thread_pool_size = Math.min(requestedWidth, coreCeiling);
    if (this.thread_pool_size < requestedWidth) {
      job.logWarning("Optimizer width " + requestedWidth + " clamped to " + this.thread_pool_size
          + ": this machine has " + coreCeiling + " logical processors.");
    }
    this.board_update_strategy = job.routerSettings.optimizer.boardUpdateStrategy;
    this.item_selection_strategy =
        job.routerSettings.optimizer.boardUpdateStrategy == BoardUpdateStrategy.GLOBAL_OPTIMAL ? ItemSelectionStrategy.SEQUENTIAL : job.routerSettings.optimizer.itemSelectionStrategy;

    best_route_result = new ItemRouteResult(-1);
    winning_candidate = null;

    if (this.board_update_strategy == BoardUpdateStrategy.HYBRID) {
      int num_optimal = 1, num_prioritized = 1;

      if (job.routerSettings.optimizer.hybridRatio != null && job.routerSettings.optimizer.hybridRatio.indexOf(":") > 0) {
        String[] ratio = job.routerSettings.optimizer.hybridRatio.split(":");

        try {
          num_optimal = Integer.parseInt(ratio[0], 10);
          num_prioritized = Integer.parseInt(ratio[1], 10);
        } catch (NumberFormatException e) {
          job.logError("Invalid hybrid ratio", e);
          num_optimal = 1;
          num_prioritized = 1;
        }

        for (int i = 0; i < num_optimal; i++) {
          hybrid_list.add(BoardUpdateStrategy.GLOBAL_OPTIMAL);
        }

        for (int i = 0; i < num_prioritized; i++) {
          hybrid_list.add(BoardUpdateStrategy.GREEDY);
        }
      }
    }
  }

  public int get_num_tasks() {
    return item_ids.size();
  }

  public int get_num_tasks_finished() {
    return num_tasks_finished;
  }

  private BoardUpdateStrategy current_board_update_strategy() {
    if (this.board_update_strategy == BoardUpdateStrategy.HYBRID) {
      return hybrid_list.get(hybrid_index);
    }

    return this.board_update_strategy;
  }

  private ItemSelectionStrategy current_item_selection_strategy() {
    return current_board_update_strategy() == BoardUpdateStrategy.GLOBAL_OPTIMAL ? ItemSelectionStrategy.SEQUENTIAL : this.item_selection_strategy;
  }

  synchronized void prepare_task_completion_signal() {
    if (task_completion_signal.getCount() <= 0) {
      task_completion_signal = new CountDownLatch(1);
      // no other way to increase the count for repeated use
      // It's still simpler than general wait/notify
    }
  }

  public synchronized boolean is_winning_candidate(OptimizeRouteTask task) {
    ++num_tasks_finished;

    ItemRouteResult r = task.getRouteResult();

    result_map.put(r.item_id(), r);

    boolean won = false;

    if (r.improved()) {
      if (winning_candidate == null) {
        won = true;
        winning_candidate = task;
        best_route_result = r;

      } else {
        if (r.improved_over(best_route_result)) {
          won = true;

          winning_candidate.clean();

          winning_candidate = task;
          best_route_result = r;
        }
      }
    }

    if (won && current_board_update_strategy() == BoardUpdateStrategy.GREEDY) {
      replaceMasterRoutingBoardWithTheWinningCandidate(); // new tasks will copy the updated board
    }

    task_completion_signal.countDown();
    return won;
  }

  /**
   * The board a task should start from, at the moment the task actually RUNS.
   *
   * <p>Only the reference is read under the monitor. The copy itself happens outside the
   * lock, which is safe because master boards are never mutated after publication in
   * multi-threaded mode: tasks mutate only their own clones, and a win replaces the master
   * by reference swap. Cloning under the monitor would serialise every worker behind copy
   * time and reintroduce the bottleneck this design removes.
   */
  synchronized RoutingBoard currentMasterBoard() {
    return this.board;
  }

  private void replaceMasterRoutingBoardWithTheWinningCandidate() {
    this.board = winning_candidate.board;

    BoardStatistics boardStatistics = this.board.get_statistics();
    this.fireBoardUpdatedEvent(boardStatistics, null, this.board);

    this.min_cumulative_trace_length = boardStatistics.traces.totalWeightedLength;

    ++update_count;
  }

  private void prepare_next_round_of_route_items() {
    if (this.board_update_strategy == BoardUpdateStrategy.HYBRID) {
      hybrid_index = (hybrid_index + 1) % hybrid_list.size();
    }

    item_ids.clear();

    this.sorted_route_items = new ReadSortedRouteItems();

    ItemSelectionStrategy selection = current_item_selection_strategy();
    boolean ordered = (selection == ItemSelectionStrategy.PRIORITIZED
        || selection == ItemSelectionStrategy.MOST_TO_GAIN);
    if (ordered && !result_map.isEmpty()) {
      ArrayList<Integer> new_item_ids = new ArrayList<>();
      // PRIORITIZED polishes the already-best first (natural order: best after-state
      // first); MOST_TO_GAIN is the mirror bet -- rescue the worst first. Same data,
      // reversed comparator, measured as separate strategies.
      PriorityQueue<ItemRouteResult> pq = (selection == ItemSelectionStrategy.MOST_TO_GAIN)
          ? new PriorityQueue<>(java.util.Comparator.reverseOrder())
          : new PriorityQueue<>();

      for (Item item = sorted_route_items.next(); item != null; item = sorted_route_items.next()) {
        ItemRouteResult r = result_map.get(item.get_id_no());
        if (r != null) { // use PriorityQueue to sort item according to route result
          pq.add(r);
        } else {
          new_item_ids.add(item.get_id_no());
        }
      }

      for (ItemRouteResult r = pq.poll(); r != null; r = pq.poll()) {
        item_ids.add(r.item_id());
      }

      item_ids.addAll(new_item_ids);
    } else {
      for (Item item = sorted_route_items.next(); item != null; item = sorted_route_items.next()) {
        item_ids.add(item.get_id_no());
      }

      if (selection == ItemSelectionStrategy.RANDOM) {
        Collections.shuffle(item_ids);
      }
    }

    this.sorted_route_items = null;
    result_map.clear();
  }

  @Override
  protected float opt_route_pass(int p_pass_no, boolean p_with_preferred_directions) {
    long startTime = System.currentTimeMillis();
    update_count = 0;
    num_tasks_finished = 0;

    if (winning_candidate != null) {
      winning_candidate.clean();
      winning_candidate = null;
    }

    BoardStatistics boardStatisticsBefore = board.get_statistics();
    RouterCounters routerCounters = new RouterCounters();
    routerCounters.passCount = p_pass_no;
    this.fireBoardUpdatedEvent(boardStatisticsBefore, routerCounters, this.board);

    this.min_cumulative_trace_length = boardStatisticsBefore.traces.totalWeightedLength;

    String optimizationPassId = "BatchOptRouteMT.opt_route_pass #" + p_pass_no + " with " + item_ids.size() + " items, " + boardStatisticsBefore.items.viaCount + " vias and " + "%(,.2f".formatted(
        boardStatisticsBefore.traces.totalLength) + " trace length running on " + thread_pool_size + " threads.";
    FRLogger.traceEntry(optimizationPassId);

    prepare_next_round_of_route_items();

    // Same limiter, same validation, same announcements as the single-threaded pass. This
    // override used to bypass the limiter entirely, so on the GUI's default path (multi-
    // threading on) neither the rate guard nor an explicit rounds cap ever applied.
    // MEMORY BUDGET (phase C). Per-clone cost is measured from one real copy on the
    // first pass (a GC-settled heap delta, same instrument racing uses) and cached for
    // the stage. The budget caps pool width; a budget that cannot hold one clone REFUSES
    // below, with the numbers named, and the stage runs single-threaded in place.
    if (this.perCloneBytes == 0L) {
      Runtime rt = Runtime.getRuntime();
      System.gc();
      long before = rt.totalMemory() - rt.freeMemory();
      RoutingBoard probe = currentMasterBoard().deepCopy();
      this.perCloneBytes = (rt.totalMemory() - rt.freeMemory()) - before;
      if (probe != null && this.perCloneBytes <= 0) {
        this.perCloneBytes = -1L; // measured, unusable; never re-probe every pass
      }
    }
    Integer budgetMbSetting = OptimizerMemoryBudget.validateBudgetMb(
        this.settings.optimizer.memoryBudgetMb, m -> job.logError(m, null));
    long budgetBytes = budgetMbSetting != null
        ? budgetMbSetting * 1048576L
        : OptimizerMemoryBudget.defaultBudgetBytes(Runtime.getRuntime().maxMemory());
    int budgetWidth = OptimizerMemoryBudget.effectiveWidth(
        this.thread_pool_size, budgetBytes, this.perCloneBytes);
    if (budgetWidth == 0) {
      job.logError("MEMORY BUDGET REFUSED: budget " + (budgetBytes / 1048576)
          + " MB cannot hold one board clone (measured " + (this.perCloneBytes / 1048576)
          + " MB on this board). Running the optimisation pass single-threaded in place"
          + " instead -- no clones, no pool.", null);
      return super.opt_route_pass(p_pass_no, p_with_preferred_directions);
    }
    int effectiveWidth = budgetWidth;
    if (effectiveWidth < this.thread_pool_size) {
      // A first-class banked outcome, not a debug line: a run that silently degraded
      // concurrency is a different experiment from one that did not.
      job.logWarning("MEMORY BUDGET DEGRADED: optimizer width " + this.thread_pool_size
          + " -> " + effectiveWidth + " (budget " + (budgetBytes / 1048576)
          + " MB, one clone measured " + (this.perCloneBytes / 1048576) + " MB).");
    }

    Integer roundsSetting =
        OptimizerPassLimiter.validateRounds(this.settings.optimizer.rounds, m -> job.logError(m, null));
    boolean useRounds = roundsSetting != null;
    int itemsToSchedule = useRounds ? Math.min(roundsSetting, item_ids.size()) : item_ids.size();
    if (useRounds) {
      job.logInfo(String.format(java.util.Locale.US,
          "Optimization pass #%d will examine at most %d items (of %d); the automatic progress "
          + "guard is off because router.optimizer.rounds was set.",
          p_pass_no, itemsToSchedule, item_ids.size()));
    }
    // The rate guard watches accepted master-board updates, which only land mid-pass under
    // GREEDY. Under GLOBAL_OPTIMAL the board is frozen until pass end, so the count would
    // read as stalled on every working pass -- there the cap, the deadline and the
    // between-pass threshold are the bounds.
    boolean useRateGuard = !useRounds
        && current_board_update_strategy() == BoardUpdateStrategy.GREEDY;
    long tasksAtWindowStart = 0L;
    int updatesAtWindowStart = update_count;

    best_route_result = new ItemRouteResult(-1);
    winning_candidate = null;

    pool = (ThreadPoolExecutor) Executors.newFixedThreadPool(effectiveWidth, r ->
    {
      Thread t = new Thread(r);
      t.setUncaughtExceptionHandler((t1, e) -> job.logError("Exception in thread pool worker thread: " + t1, e));
      return t;
    });

    // One new optimizer task is initialized for each item to be re-rerouted, and we keep the best result in the end
    for (int t = 0; t < itemsToSchedule; t++) {
      int item_id = item_ids.get(t);
      final int taskNo = t + 1;
      job.logDebug(() -> "Scheduling task #" + taskNo + " of " + item_ids.size() + " for item #" + item_id + ".");

      // We schedule just enough tasks to keep workers busy in order not to exhaust JVM memory so that it can run on systems without huge amount of RAM using the pool
      OptimizeRouteTask newTask = new OptimizeRouteTask(this, this.job, item_id, p_pass_no, p_with_preferred_directions);
      pool.execute(newTask);
    }

    job.logDebug(() -> "All items are queued for execution, waiting for the tasks to finish.");
    pool.shutdown();

    boolean interrupted = false;

    try {
      int i = 0;
      while (!pool.awaitTermination(1, TimeUnit.SECONDS)) {
        job.logDebug(() -> "Running route optimizer on " + pool.getActiveCount() + " thread(s). Completed " + pool.getCompletedTaskCount() + " of " + pool.getTaskCount() + " tasks.");

        if (this.thread.isStopRequested()) {
          // Deliver-then-stop: fall through to the hand-back below. A deadline or a
          // cancel must not discard masters the workers already accepted -- returning
          // here skipped the defect-31 hand-back and lost every win of the pass. A stop
          // request is NOT an interrupt: the GLOBAL_OPTIMAL candidate replacement below
          // must still run, or that strategy loses its wins on every deadline.
          pool.shutdownNow();
          break;
        }

        if (useRateGuard) {
          long tasksDone = pool.getCompletedTaskCount();
          if (tasksDone - tasksAtWindowStart >= OptimizerPassLimiter.GUARD_WINDOW_WORK_UNITS) {
            if (OptimizerPassLimiter.countWindowStalled(updatesAtWindowStart, update_count)) {
              job.logInfo(String.format(java.util.Locale.US,
                  "Stopping optimization pass #%d: no accepted board improvement across the "
                  + "last %d completed tasks (%d of %d done). Continuing this pass will not "
                  + "pay for itself.",
                  p_pass_no, OptimizerPassLimiter.GUARD_WINDOW_WORK_UNITS,
                  tasksDone, pool.getTaskCount()));
              pool.shutdownNow();
              break;
            }
            updatesAtWindowStart = update_count;
            tasksAtWindowStart = tasksDone;
          }
        }
      }
    } catch (InterruptedException ie) {
      job.logError("Exception with pool.awaitTermination", ie);

      interrupted = true;
      pool.shutdownNow();

      Thread.currentThread().interrupt(); // Preserve interrupt status
    }

    // Whatever ended the loop, the workers must actually be gone before this thread
    // reads best_route_result / the winning candidate / the board: shutdownNow() only
    // ASKS. Bounded -- a worker that ignores interruption for 10s is a defect we would
    // rather see as a warning than as a torn read.
    try {
      if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
        job.logWarning("Optimizer worker threads did not terminate within 10s of shutdown;"
            + " results are read anyway and may miss the last accepted update.");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    pool = null;

    if (!interrupted && best_route_result.improved() && current_board_update_strategy() == BoardUpdateStrategy.GLOBAL_OPTIMAL) {
      replaceMasterRoutingBoardWithTheWinningCandidate();
    }

    // Defect 31: the winning board only ever reached THIS object's field. job.board --
    // which is what the save, the report and the DRC read -- kept pointing at the original,
    // so every accepted improvement was silently discarded at delivery. Measured before the
    // fix: 41 accepted wins, delivered board byte-identical to running no optimiser at all.
    if (this.board != job.board && this.board != null) {
      job.board = this.board;
    }

    float route_improved = best_route_result.improvement_percentage();

    if (this.use_increased_ripup_costs && !best_route_result.improved()) {
      this.use_increased_ripup_costs = false;
      route_improved = -1; // to keep the optimizer going with lower ripup costs
    }

    long duration = System.currentTimeMillis() - startTime;
    long minutes = duration / 60000;
    float sec = (duration % 60000) / 1000.0F;

    String us = current_board_update_strategy() == BoardUpdateStrategy.GLOBAL_OPTIMAL ? "Global Optimal" : "Greedy";
    String is = current_item_selection_strategy() == ItemSelectionStrategy.SEQUENTIAL ? "Sequential" : (current_item_selection_strategy() == ItemSelectionStrategy.RANDOM ? "Random" : "Prioritized");

    BoardStatistics boardStatisticsAfter = board.get_statistics();
    this.fireBoardUpdatedEvent(boardStatisticsAfter, routerCounters, this.board);

    job.logDebug(() -> "Finished optimizer pass #" + p_pass_no + " in " + minutes + " minutes " + sec + " seconds with " + update_count + " board updates using " + thread_pool_size + " thread(s) with '" + us
            + "' strategy and '" + is + "' item selection strategy.");
    final boolean wasInterrupted = interrupted;
    job.logDebug(() -> "Route optimizer pass summary - Improved: " + best_route_result.improved() + ", interrupted: " + wasInterrupted + ", via count: " + best_route_result.via_count() + ", trace length: "
        + boardStatisticsAfter.traces.totalLength + ", via count delta: " + (boardStatisticsBefore.items.viaCount - best_route_result.via_count()) + ", trace length delta: " + (
        boardStatisticsBefore.traces.totalLength - boardStatisticsAfter.traces.totalLength) + ".");

    FRLogger.traceExit(optimizationPassId);

    return route_improved;
  }

  public double getWinningCandidateScore() {
    return this.board.get_statistics().traces.totalLength;
  }
}