package app.freerouting.autoroute;

import app.freerouting.autoroute.events.TaskStateChangedEvent;
import app.freerouting.board.Item;
import app.freerouting.board.RoutingBoard;
import app.freerouting.board.Trace;
import app.freerouting.board.Via;
import app.freerouting.core.ProgressThrottler;
import app.freerouting.core.RouterCounters;
import app.freerouting.core.RoutingJob;
import app.freerouting.core.scoring.BoardStatistics;
import app.freerouting.datastructures.UndoableObjects;
import app.freerouting.drc.DesignRulesChecker;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.logger.FRLogger;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.Collection;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeSet;

/**
 * Optimizes routes using a single thread on a board that has completed
 * auto-routing.
 */
public class BatchOptimizer extends NamedAlgorithm {

  protected final ProgressThrottler progressThrottler = new ProgressThrottler(1000);
  protected ReadSortedRouteItems sorted_route_items;
  // in the first passes the ripup costs are increased for better performance.
  protected boolean use_increased_ripup_costs;
  // the minimum cumulative trace length that was reached during the optimization
  protected double min_cumulative_trace_length = 0.0;
  protected RoutingJob job;
  protected int totalItemsOptimized = 0;
  protected Long deadlineMs = null;

  /**
   * How far before the job's own deadline the optimiser stage must stop.
   *
   * <p>Sized against the WATCHDOG, not against the work. Finalisation after the stage --
   * final statistics and writing the session file -- was measured at 16 to 73 ms across
   * three boards, so it is not what this must cover.
   *
   * <p>What it must cover is the monitor thread's polling interval. The watchdog wakes every
   * 1000 ms, and marks the job TIMED_OUT only if the deadline has passed AND something has
   * requested a stop -- the stop request being the watchdog's own. So a grace EQUAL to the
   * tick is a coin flip: the job either finishes first, or the watchdog ticks first and
   * brands a cleanly-finished run as timed out. That is exactly what was observed -- the
   * same board reported COMPLETED at a 120 s budget and TIMED_OUT at 68 s, with the stage
   * ending gracefully both times.
   *
   * <p>Five seconds is five ticks of headroom, still negligible against any real budget.
   */
  static final long OPTIMIZER_DEADLINE_GRACE_MS = 5_000L;

  /**
   * The deadline for the optimiser stage.
   *
   * <p>The stage timeout used to default to unset, i.e. unbounded, so what actually ended
   * the optimiser was the JOB timeout firing around it. That is the ugly ending: the stage
   * is cut off mid-pass and the run is reported {@code TIMED_OUT}, instead of the stage
   * stopping cleanly and presenting the best board it found. Someone who allows two minutes
   * should get the best board achievable in two minutes, not a failure.
   *
   * <p>So it is derived instead of left implicit, and derived AT STAGE START, which makes it
   * self-adjusting: whatever routing already consumed is simply not in the remaining window.
   * Allow the job three minutes and the optimiser gets three minutes, minus routing, minus
   * the grace.
   *
   * <p>An explicit stage timeout may only ever SHORTEN this. A stage timeout longer than the
   * job it runs inside is not a longer stage -- it is the cut-off again, arriving by another
   * route.
   *
   * <p>DETERMINISM, because this touches defect 20: a deadline stop is wall-clock dependent
   * and therefore machine dependent. This makes stopping GRACEFUL, not REPRODUCIBLE. If
   * reproducibility is wanted the primary bound must be work units with the clock as a
   * backstop, which is a separate change and deliberately not smuggled in here.
   *
   * @param explicitTimeoutSeconds the configured stage timeout, or null if unset
   * @param jobDeadlineMs          the job's own deadline, or null if the job is unbounded
   * @param nowMs                  the current time, passed in so this stays pure and testable
   * @return the stage deadline, or null when nothing bounds the stage at all
   */
  static Long computeOptimizerDeadlineMs(Long explicitTimeoutSeconds, Long jobDeadlineMs, long nowMs) {
    // Delegates: every stage stops by the same rule, and one copy cannot drift from another.
    return StageDeadline.compute(explicitTimeoutSeconds, jobDeadlineMs, nowMs);
  }

  protected boolean isTimedOut = false;

  /**
   * Creates a new instance of BatchOptRoute, which is used to optimize the board.
   *
   * @param job
   */
  public BatchOptimizer(RoutingJob job) {
    super(job.thread, job.board, job.routerSettings);
    this.job = job;
  }

  public boolean isTimedOut() {
    return this.isTimedOut;
  }

  static boolean contains_only_unfixed_traces(Collection<Item> p_item_list) {
    for (Item curr_item : p_item_list) {
      if (curr_item.is_user_fixed() || !(curr_item instanceof Trace)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Optimize the route on the board.
   */
  public void runBatchLoop() {
    job.logDebug(() -> "Before optimization: Via count: " + board
        .get_vias()
        .size() + ", trace length: " + Math.round(board.cumulative_trace_length()));

    double scoreImprovement = -1;
    int currentPass = 0;
    use_increased_ripup_costs = true;

    // Capture initial board state for session summary
    BoardStatistics initialStats = board.get_statistics();
    float initialScore = initialStats.getNormalizedScore(job.routerSettings.scoring);
    int initialIncomplete = initialStats.connections.incompleteCount;
    int initialViolations = initialStats.clearanceViolations.totalCount;

    job.logInfo("Optimization stage started on board '" + this.board.get_hash() + "' with score "
        + FRLogger.formatScore(initialScore, initialIncomplete, initialViolations) + ".");

    // Capture start-of-session resource usage baselines
    long sessionStartMs = System.currentTimeMillis();
    float cpuSecondsStart = sampleCurrentThreadCpuSeconds();
    float allocMbStart = sampleCurrentThreadAllocatedMb();
    float peakHeapMb = sampleHeapUsageMb();

    Long explicitOptimizerTimeoutSeconds = null;
    if (this.settings.optimizer != null && this.settings.optimizer.timeoutString != null) {
      explicitOptimizerTimeoutSeconds =
          app.freerouting.util.TextManager.parseTimespanString(this.settings.optimizer.timeoutString);
    }
    // The job's deadline, if it has one, is what makes the stage self-limiting: the
    // optimiser stops just before the job clock would cut it off, so the run ends as
    // COMPLETED with the best board found rather than TIMED_OUT mid-pass.
    Long jobDeadlineMs = (job != null && job.timeoutAt != null) ? job.timeoutAt.toEpochMilli() : null;
    this.deadlineMs =
        computeOptimizerDeadlineMs(explicitOptimizerTimeoutSeconds, jobDeadlineMs, sessionStartMs);
    if (this.deadlineMs != null) {
      job.logInfo("Optimization stage will stop by "
          + Math.max(0, (this.deadlineMs - sessionStartMs) / 1000) + "s from now"
          + (jobDeadlineMs != null && explicitOptimizerTimeoutSeconds == null
              ? " (derived from the job's remaining time)" : "")
          + ", so it finishes before the job deadline rather than being cut off by it.");
    }

    this.fireTaskStateChangedEvent(new TaskStateChangedEvent(this, TaskState.STARTED, 0, this.board.get_hash()));

    while ((this.settings.optimizer.maxPasses == null || currentPass < this.settings.optimizer.maxPasses)
        && (this.settings.optimizer.maxItems == null || this.totalItemsOptimized < this.settings.optimizer.maxItems)
        && (!this.thread.isStopRequested())) {
      if (this.deadlineMs != null && System.currentTimeMillis() >= this.deadlineMs) {
        this.isTimedOut = true;
        job.logInfo("Optimizer stage timed out before starting pass #" + (currentPass + 1));
        break;
      }
      ++currentPass;

      float scoreBeforePass = board.get_statistics().getNormalizedScore(job.routerSettings.scoring);

      // Stop if potential improvement is less than threshold
      if (scoreBeforePass * (1 + this.settings.optimizer.optimizationImprovementThreshold) >= 1000.0f) {
        job.logInfo(String.format(java.util.Locale.US,
            "Stopping optimizer because the current board score (%.2f) is already close to the maximum score (1000). Remaining potential improvement is less than the threshold (%.2f%%).",
            scoreBeforePass, this.settings.optimizer.optimizationImprovementThreshold * 100));
        break;
      }

      String currentBoardHash = this.board.get_hash();
      job.setCurrentPass(currentPass);
      this.fireTaskStateChangedEvent(new TaskStateChangedEvent(this, TaskState.RUNNING, currentPass, currentBoardHash));

      boolean with_preferred_directions = currentPass % 2 != 0; // to create more variations
      opt_route_pass(currentPass, with_preferred_directions);
      peakHeapMb = Math.max(peakHeapMb, sampleHeapUsageMb());

      if (this.isTimedOut) {
        break;
      }

      float scoreAfterPass = board.get_statistics().getNormalizedScore(job.routerSettings.scoring);
      double passImprovement = scoreBeforePass > 0 ? (double) (scoreAfterPass - scoreBeforePass) / scoreBeforePass : 0;

      if (this.use_increased_ripup_costs && scoreAfterPass <= scoreBeforePass) {
        this.use_increased_ripup_costs = false;
        // Keep the optimizer going to try with normal ripup costs
        scoreImprovement = -1;
      } else {
        scoreImprovement = passImprovement;
      }

      if (scoreImprovement != -1 && scoreImprovement < this.settings.optimizer.optimizationImprovementThreshold) {
        job.logInfo(String.format(java.util.Locale.US,
            "Stopping optimizer because the improvement in this pass (%.4f%%) is below the threshold (%.2f%%).",
            scoreImprovement * 100, this.settings.optimizer.optimizationImprovementThreshold * 100));
        break;
      }
    }

    this.fireTaskStateChangedEvent(
        new TaskStateChangedEvent(this, TaskState.FINISHED, currentPass, this.board.get_hash()));

    // Session summary
    double sessionDurationSeconds = (System.currentTimeMillis() - sessionStartMs) / 1000.0;
    float cpuSecondsEnd = sampleCurrentThreadCpuSeconds();
    float allocMbEnd = sampleCurrentThreadAllocatedMb();
    float cpuSecondsUsed = (cpuSecondsStart >= 0f && cpuSecondsEnd >= cpuSecondsStart)
        ? cpuSecondsEnd - cpuSecondsStart : Math.max(0f, cpuSecondsEnd);
    float allocMbUsed = (allocMbStart >= 0f && allocMbEnd >= allocMbStart)
        ? allocMbEnd - allocMbStart : Math.max(0f, allocMbEnd);
    peakHeapMb = Math.max(peakHeapMb, sampleHeapUsageMb());

    BoardStatistics finalStats = new BoardStatistics(this.board);
    float finalScore = finalStats.getNormalizedScore(job.routerSettings.scoring);
    String completionStatus = this.isTimedOut ? "completed with timeout:"
        : (this.thread.isStopRequested() ? "interrupted:" : "completed:");
    job.logInfo(String.format(java.util.Locale.US,
        "Optimization stage %s started with score %s, completed in %.2f seconds, final score: %s, using %.2f total CPU seconds, %.2f GB total allocated, and %.1f MB peak heap usage.",
        completionStatus,
        FRLogger.formatScore(initialScore, initialIncomplete, initialViolations),
        sessionDurationSeconds,
        FRLogger.formatScore(finalScore, finalStats.connections.incompleteCount,
            finalStats.clearanceViolations.totalCount),
        cpuSecondsUsed,
        allocMbUsed / 1024.0f,
        peakHeapMb));
  }

  // Guard constants and predicates live in OptimizerPassLimiter, the single home both this
  // class and BatchOptimizerMultiThreaded consult. They lived here first -- and the MT
  // subclass overrides opt_route_pass wholesale, so everything defined here was bypassed on
  // the GUI's default path.

  /**
   * The length baseline an item comparison runs against: the value a pass established, or
   * -- for a task-fresh optimizer that never ran a pass -- the board's own current length.
   * Zero is never a real baseline; it is the unset marker, and comparing against it made
   * every length change read as an explosion.
   */
  static double lengthBaseline(double p_current, double p_from_board) {
    return p_current == 0.0 ? p_from_board : p_current;
  }

  /**
   * Tries to reduce the number of vias and the trace length of a completely
   * routed board. Returns the amount of improvements is made in percentage
   * (expressed between 0.0 and 1.0). -1 if the routing
   * must go on no matter how much it improved.
   */
  protected float opt_route_pass(int p_pass_no, boolean p_with_preferred_directions) {
    float route_improved = 0.0f;

    BoardStatistics boardStatisticsBefore = board.get_statistics();
    RouterCounters routerCounters = new RouterCounters();
    routerCounters.passCount = p_pass_no;
    progressThrottler.reset();
    this.fireBoardUpdatedEvent(boardStatisticsBefore, routerCounters, this.board);

    this.sorted_route_items = new ReadSortedRouteItems();
    this.min_cumulative_trace_length = boardStatisticsBefore.traces.totalWeightedLength;
    String optimizationPassId = "BatchOptRoute.opt_route_pass #" + p_pass_no + " with "
        + boardStatisticsBefore.items.viaCount + " vias and "
        + "%(,.2f".formatted(boardStatisticsBefore.traces.totalLength) + " trace length.";

    FRLogger.traceEntry(optimizationPassId);

    // Progress guard. The pass stops when the BOARD stops getting better by its own score,
    // which prices vias and trace length -- the two quantities this stage exists to reduce.
    //
    // The previous guard counted items examined since the board became more complete or more
    // legal. Measured across 26 boards from the routed outputs, this stage moves neither of
    // those and removes 10.3% of all vias. That guard was therefore blind to the entire useful
    // output of the stage it was guarding, and would cut a pass off while it was working.
    //
    // There is deliberately no setting for this. A stage that cannot tell it has stopped
    // improving is not something a user should have to tune, and a knob here invites the
    // advice "raise it and you might complete a board", which is not advice worth giving.
    // The guard keeps its OWN clock. It used to ask progressThrottler, which is shared with
    // two per-item callers inside opt_route_item that fire the display events. shouldUpdate()
    // stamps its timestamp when it returns true, so those two consumed every tick and the
    // guard was starved: it ran 0 times in 93 seconds of optimisation. Attaching a decision
    // to a throttle built for display is the defect, not the interval.
    float scoreAtWindowStart = boardStatisticsBefore.getNormalizedScore(job.routerSettings.scoring);
    int itemsAtWindowStart = 0;

    // The alternative, when the user has asked for it: a flat cap on items examined per
    // pass, and the automatic guard switched off. One mechanism or the other, never both,
    // so the log always explains the run from a single line.
    Integer roundsSetting =
        OptimizerPassLimiter.validateRounds(this.settings.optimizer.rounds, m -> job.logError(m, null));
    boolean useRounds = roundsSetting != null;
    if (useRounds) {
      job.logInfo(String.format(java.util.Locale.US,
          "Optimization pass #%d will examine at most %d items; the automatic progress guard "
          + "is off because router.optimizer.rounds was set.", p_pass_no, roundsSetting));
    }

    // What this pass actually looked at. Until defect 25 the pass reported only its
    // duration, so a pass that visited nothing and a pass that visited everything and
    // improved nothing produced the same line -- and told apart only by re-running the
    // whole job under a debugger. A stage whose job is to improve the board should say how
    // much of it it examined.
    int itemsVisited = 0;
    int itemsImproved = 0;

    while (true) {
      if (this.deadlineMs != null && System.currentTimeMillis() >= this.deadlineMs) {
        job.logInfo("Optimizer stage timed out.");
        this.isTimedOut = true;
        FRLogger.traceExit(optimizationPassId);
        return route_improved;
      }
      if (this.thread.isStopRequested()) {
        FRLogger.traceExit(optimizationPassId);
        return route_improved;
      }
      if (this.settings.optimizer.maxItems != null && this.settings.optimizer.maxItems > 0 && this.totalItemsOptimized >= this.settings.optimizer.maxItems) {
        job.logInfo("Max items limit reached (" + this.settings.optimizer.maxItems + "). Stopping optimizer.");
        break;
      }
      Item curr_item = sorted_route_items.next();
      if (curr_item == null) {
        break;
      }
      ItemRouteResult result = opt_route_item(curr_item, p_with_preferred_directions, false);
      this.totalItemsOptimized++;
      itemsVisited++;
      if (result.improved()) {
        itemsImproved++;
        // Highest improvement seen in this pass, not the last one seen. This was assigned
        // outright, so a trivially-improving item at the end of the list decided whether
        // another pass ran.
        float item_improvement = (float) (boardStatisticsBefore.items.viaCount != 0
            && boardStatisticsBefore.traces.totalLength != 0
                ? 1.0 - ((((float) result.via_count() / boardStatisticsBefore.items.viaCount)
                    + (result.trace_length() / boardStatisticsBefore.traces.totalLength)) / 2)
                : 0);
        route_improved = Math.max(route_improved, item_improvement);
      }

      // Runs whether or not this item improved: a long run of items that cannot be improved
      // is exactly the state the guard exists to catch.
      //
      // The question is not "did anything move" but "is it moving fast enough to be worth the
      // clock". Items report local improvements almost continuously while the board creeps by
      // amounts too small to matter, so a binary test never fires. One statistics computation
      // per window, which is cheap next to routing an item.
      if (useRounds) {
        if (itemsVisited >= roundsSetting) {
          job.logInfo(String.format(java.util.Locale.US,
              "Stopping optimization pass #%d: examined the requested %d items (%d improved).",
              p_pass_no, itemsVisited, itemsImproved));
          break;
        }
        continue;
      }

      if (itemsVisited - itemsAtWindowStart >= OptimizerPassLimiter.GUARD_WINDOW_WORK_UNITS) {
        float scoreNow = board.get_statistics().getNormalizedScore(job.routerSettings.scoring);
        if (!OptimizerPassLimiter.windowProgressed(scoreAtWindowStart, scoreNow)) {
          job.logInfo(String.format(java.util.Locale.US,
              "Stopping optimization pass #%d: the board score improved by less than %.2f%% over "
              + "the last %d items examined (%d examined in total, %d improved). Continuing "
              + "this pass will not pay for itself.",
              p_pass_no, OptimizerPassLimiter.GUARD_MIN_RELATIVE_GAIN * 100,
              OptimizerPassLimiter.GUARD_WINDOW_WORK_UNITS,
              itemsVisited, itemsImproved));
          break;
        }
        scoreAtWindowStart = scoreNow;
        itemsAtWindowStart = itemsVisited;
      }
    }

    this.sorted_route_items = null;
    if (this.use_increased_ripup_costs && (route_improved == 0)) {
      this.use_increased_ripup_costs = false;
      route_improved = -1; // to keep the optimizer going with lower ripup costs
    }

    double routeoptimizer_pass_duration = FRLogger.traceExit(optimizationPassId);
    BoardStatistics boardStatisticsAfter = new BoardStatistics(this.board);
    this.fireBoardUpdatedEvent(boardStatisticsAfter, routerCounters, this.board);
    job.logInfo(String.format(java.util.Locale.US,
        "Optimizer pass #%d on board '%s' examined %d item(s), improved %d, completed in %.2f seconds with the score of %s.",
        p_pass_no, this.board.get_hash(), itemsVisited, itemsImproved, routeoptimizer_pass_duration,
        FRLogger.formatScore(
            boardStatisticsAfter.getNormalizedScore(job.routerSettings.scoring),
            boardStatisticsAfter.connections.incompleteCount, boardStatisticsAfter.clearanceViolations.totalCount)));
    return route_improved;
  }

  /**
   * Try to improve the route by re-routing the connections containing p_item.
   *
   * @param p_item                      the item to be re-routed
   * @param p_with_preferred_directions if true, the preferred directions are used
   *                                    for the traces
   * @param disableSnapshots            if true, the snapshots are not used which
   *                                    means that the routing cannot be undone,
   *                                    but it's much more efficient
   */

  /**
   * Whether the board is better than when this pass started.
   *
   * <p>Progress is the board being more complete or more legal. It is deliberately NOT
   * "an item was re-routed to something its own measure prefers": a pass on one board
   * reported 82 of 85 items improved and returned a board identical in every respect, and
   * that signal was what kept the early-stop from ever firing.
   *
   * <p>A trade -- a connection completed while a violation appears -- counts as progress.
   * Completion is this stage's job and the violation is reported separately; treating the
   * trade as failure would silence the fact that work was done.
   */
  static boolean outcomeImproved(int p_unrouted_before, int p_violations_before,
      int p_unrouted_after, int p_violations_after) {
    return p_unrouted_after < p_unrouted_before || p_violations_after < p_violations_before;
  }

  protected ItemRouteResult opt_route_item(Item p_item, boolean p_with_preferred_directions, boolean disableSnapshots) {
    // check if item.board is a RoutingBoard
    if (!(p_item.board instanceof RoutingBoard routingBoard)) {
      job.logWarning("The item to be optimized is not on a RoutingBoard.");
      return new ItemRouteResult(p_item.get_id_no());
    }

    // calculate the statistics for the board before the routing
    BoardStatistics boardStatisticsBefore = new BoardStatistics(routingBoard, null, false);
    RouterCounters routerCountersBefore = new RouterCounters();
    routerCountersBefore.incompleteCount = calculateIncompleteCount(routingBoard);
    if (progressThrottler.shouldUpdate()) {
      this.fireBoardUpdatedEvent(boardStatisticsBefore, routerCountersBefore, routingBoard);
    }

    // Defect 31, second fault. Only a PASS initialises the length baseline; a task-fresh
    // optimizer (the multi-threaded path constructs one per item) arrived here with 0.0,
    // so the result read "length exploded from zero" and length-only improvements -- the
    // majority class, 246 of 311 items in the single-threaded comparison -- could never
    // register in a task.
    //
    // Seeded with the UNWEIGHTED length, because that is the metric ItemRouteResult
    // compares against. The first version of this fix seeded the weighted length, which
    // runs far larger -- so every task read "length improved" trivially, replacing
    // wins-can-never-register with wins-always-register. Same wrong-metric disease,
    // opposite sign. (The PASS path's own weighted-vs-unweighted mismatch is pre-existing
    // upstream behaviour and deliberately untouched: one defect per change.)
    this.min_cumulative_trace_length = lengthBaseline(
        this.min_cumulative_trace_length, boardStatisticsBefore.traces.totalLength);

    // collect the items to be re-routed
    Set<Item> ripped_items = new TreeSet<>();
    ripped_items.add(p_item);

    // add the contacts of the traces to the ripped items if it's a trace
    if (p_item instanceof Trace curr_trace) {
      // add also the fork items, especially because not all fork items may be
      // returned by ReadSortedRouteItems because of matching end points.
      Set<Item> curr_contact_list = curr_trace.get_start_contacts();
      for (int i = 0; i < 2; i++) {
        if (contains_only_unfixed_traces(curr_contact_list)) {
          ripped_items.addAll(curr_contact_list);
        }
        curr_contact_list = curr_trace.get_end_contacts();
      }
    }

    Set<Item> ripped_connections = new TreeSet<>();
    // add all the connections of the items to be re-routed
    for (Item curr_item : ripped_items) {
      ripped_connections.addAll(curr_item.get_connection_items(Item.StopConnectionOption.NONE));
    }

    // check if the connections contain user fixed items, which should not be
    // re-routed
    for (Item curr_item : ripped_connections) {
      if (curr_item.is_user_fixed()) {
        return new ItemRouteResult(p_item.get_id_no());
      }
    }

    if (!disableSnapshots) {
      // make the current situation restorable by undo with the snapshot
      routingBoard.generate_snapshot();
    }

    // remove the items to be re-routed
    routingBoard.remove_items(ripped_connections);
    for (int i = 0; i < p_item.net_count(); i++) {
      routingBoard.combine_traces(p_item.get_net_no(i));
    }

    // calculate the ripup costs
    int ripup_costs = this.settings.get_start_ripup_costs();
    if (this.use_increased_ripup_costs) {
      ripup_costs *= this.settings.optimizer.additionalRipupCostFactorAtStart;
    }

    // reduce the ripup costs for traces
    if (p_item instanceof Trace) {
      ripup_costs = (int) Math.round(this.settings.optimizer.traceRipupCostFactor * (double) ripup_costs);
    }

    // route the connections
    BatchAutorouter.autoroute_passes_for_optimizing_item(job, this.settings.optimizer.maxAutoroutePasses, ripup_costs,
        settings.trace_pull_tight_accuracy, p_with_preferred_directions, routingBoard, settings);

    // check the result by generating the statistics for the board again after the
    // routing
    BoardStatistics boardStatisticsAfter = new BoardStatistics(routingBoard, null, false);
    RouterCounters routerCountersAfter = new RouterCounters();
    routerCountersAfter.incompleteCount = calculateIncompleteCount(routingBoard);
    if (progressThrottler.shouldUpdate()) {
      this.fireBoardUpdatedEvent(boardStatisticsAfter, routerCountersAfter, routingBoard);
    }

    // check if the board was improved
    ItemRouteResult result = new ItemRouteResult(p_item.get_id_no(), boardStatisticsBefore.items.viaCount,
        boardStatisticsAfter.items.viaCount, this.min_cumulative_trace_length,
        boardStatisticsAfter.traces.totalLength, routerCountersBefore.incompleteCount,
        routerCountersAfter.incompleteCount);
    boolean route_improved = !this.thread.isStopRequested() && result.improved();
    result.update_improved(route_improved);

    if (route_improved) {
      this.min_cumulative_trace_length = Math.min(this.min_cumulative_trace_length,
          boardStatisticsAfter.traces.totalWeightedLength);

      if (!disableSnapshots) {
        // this was a successful routing, so the snapshot can be removed
        routingBoard.pop_snapshot();
      }
    } else {
      if (!disableSnapshots) {
        // this was not a successful routing, so we can undo the routing using the
        // snapshot
        routingBoard.undo(null);
      }
    }

    return result;
  }

  /**
   * Returns the current position of the item, which will be rerouted or null, if
   * the optimizer is not active.
   */
  public FloatPoint get_current_position() {
    if (sorted_route_items == null) {
      return null;
    }
    return sorted_route_items.get_current_position();
  }

  @Override
  public String getId() {
    return "freerouting-optimizer";
  }

  @Override
  protected String getName() {
    return "Freerouting Optimizer";
  }

  @Override
  protected String getVersion() {
    return "1.0";
  }

  @Override
  protected String getDescription() {
    return "Freerouting Optimizer v1.0";
  }

  @Override
  protected NamedAlgorithmType getType() {
    return NamedAlgorithmType.OPTIMIZER;
  }

  /**
   * Reads the vias and traces on the board in ascending x order. Because the vias
   * and traces on the board change while optimizing the item list of the board is
   * read from scratch each time the next
   * route item is returned.
   */
  protected class ReadSortedRouteItems {

    protected FloatPoint min_item_coor;
    protected int min_item_layer;

    ReadSortedRouteItems() {
      min_item_coor = new FloatPoint(Integer.MIN_VALUE, Integer.MIN_VALUE);
      min_item_layer = -1;
    }

    Item next() {
      Item result = null;
      FloatPoint curr_min_coor = new FloatPoint(Integer.MAX_VALUE, Integer.MAX_VALUE);
      int curr_min_layer = Integer.MAX_VALUE;
      Iterator<UndoableObjects.UndoableObjectNode> it = board.item_list.start_read_object();
      for (;;) {
        UndoableObjects.Storable curr_item = board.item_list.read_object(it);
        if (curr_item == null) {
          break;
        }
        if (curr_item instanceof Via curr_via) {
          if (!curr_via.is_user_fixed()) {
            FloatPoint curr_via_center = curr_via
                .get_center()
                .to_float();
            int curr_via_min_layer = curr_via.first_layer();
            if (curr_via_center.x > min_item_coor.x
                || curr_via_center.x == min_item_coor.x && (curr_via_center.y > min_item_coor.y
                    || curr_via_center.y == min_item_coor.y && curr_via_min_layer > min_item_layer)) {
              if (curr_via_center.x < curr_min_coor.x
                  || curr_via_center.x == curr_min_coor.x && (curr_via_center.y < curr_min_coor.y
                      || curr_via_center.y == curr_min_coor.y && curr_via_min_layer < curr_min_layer)) {
                curr_min_coor = curr_via_center;
                curr_min_layer = curr_via_min_layer;
                result = curr_via;
              }
            }
          }
        }
      }
      // Read traces last to prefer vias to traces at the same location
      it = board.item_list.start_read_object();
      for (;;) {
        UndoableObjects.Storable curr_item = board.item_list.read_object(it);
        if (curr_item == null) {
          break;
        }
        if (curr_item instanceof Trace curr_trace) {
          if (!curr_trace.is_shove_fixed()) {
            FloatPoint first_corner = curr_trace
                .first_corner()
                .to_float();
            FloatPoint last_corner = curr_trace
                .last_corner()
                .to_float();
            FloatPoint compare_corner;
            if (first_corner.x < last_corner.x || first_corner.x == last_corner.x && first_corner.y < last_corner.y) {
              compare_corner = last_corner;
            } else {
              compare_corner = first_corner;
            }
            int curr_trace_layer = curr_trace.get_layer();
            if (compare_corner.x > min_item_coor.x
                || compare_corner.x == min_item_coor.x && (compare_corner.y > min_item_coor.y
                    || compare_corner.y == min_item_coor.y && curr_trace_layer > min_item_layer)) {
              if (compare_corner.x < curr_min_coor.x
                  || compare_corner.x == curr_min_coor.x && (compare_corner.y < curr_min_coor.y
                      || compare_corner.y == curr_min_coor.y && curr_trace_layer < curr_min_layer)) {
                boolean is_connected_to_via = false;
                Set<Item> trace_contacts = curr_trace.get_normal_contacts();
                for (Item curr_contact : trace_contacts) {
                  if (curr_contact instanceof Via && !curr_contact.is_user_fixed()) {
                    is_connected_to_via = true;
                    break;
                  }
                }
                if (!is_connected_to_via) {
                  curr_min_coor = compare_corner;
                  curr_min_layer = curr_trace_layer;
                  result = curr_trace;
                }
              }
            }
          }
        }
      }
      min_item_coor = curr_min_coor;
      min_item_layer = curr_min_layer;
      return result;
    }

    FloatPoint get_current_position() {
      return min_item_coor;
    }
  }
}