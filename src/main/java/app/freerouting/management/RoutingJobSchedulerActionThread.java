package app.freerouting.management;

import app.freerouting.Freerouting;
import app.freerouting.autoroute.BatchAutorouter;
import app.freerouting.autoroute.BatchRoutingAlgorithm;
import app.freerouting.autoroute.RouterFactory;
import app.freerouting.autoroute.BatchOptimizer;
import app.freerouting.autoroute.NamedAlgorithm;
import app.freerouting.autoroute.events.BoardUpdatedEvent;
import app.freerouting.autoroute.events.BoardUpdatedEventListener;
import app.freerouting.core.BoardFileDetails;
import app.freerouting.core.RoutingJob;
import app.freerouting.core.RoutingJobState;
import app.freerouting.core.RoutingStage;
import app.freerouting.core.StoppableThread;
import app.freerouting.io.FileFormat;
import app.freerouting.logger.FRLogger;
import app.freerouting.settings.RouterSettings;
import app.freerouting.util.TextManager;
import com.sun.management.ThreadMXBean;
import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
import java.time.Instant;

/**
 * Used for running an action in a separate thread, that can be stopped by the
 * user. This typically represents an action that is triggered by job scheduler
 */
public class RoutingJobSchedulerActionThread extends StoppableThread {

  private final long MAX_TIMEOUT = 24 * 60 * 60; // 24 hours
  private final int GRACE_PERIOD = 30; // 30 seconds
  /**
   * How often a still-running job says so.
   *
   * <p>Long enough not to crowd the log or churn the 20MB ring, short enough that somebody
   * watching a terminal sees movement before they conclude it has hung. The decision this
   * feeds -- keep waiting, or stop and raise the limit -- is a minutes-scale one.
   */
  private static final int HEARTBEAT_SECONDS = 30;
  RoutingJob job;
  /** Counts monitor ticks so the heartbeat fires on a whole number of seconds. */
  private int heartbeatTicks = 0;
  /** The limit as the user expressed it, kept for display alongside elapsed time. */
  private Long timeoutSecondsForDisplay = null;

  public RoutingJobSchedulerActionThread(RoutingJob job) {
    this.job = job;
  }

  @Override
  protected void thread_action() {
    job.startedAt = Instant.now();
    // Baseline for the exactness invariant. The counter is process-global and the
    // scheduler runs several jobs at once, so the difference across THIS job is the only
    // figure that describes this job.
    final long exactRangeBaseline =
        app.freerouting.geometry.planar.IntPoint.exactRangeViolationCount();
    boolean fanoutTimedOut = false;
    boolean optimizerTimedOut = false;
    // Declared at method scope because the OPTIMIZER needs it. A stage that decides the
    // board cannot be trusted must not leave the following stage to rediscover that.
    boolean routingAborted = false;
    // Use ISO standard time format
    job.logInfo("Job '" + job.shortName + "' started at " + job.startedAt.toString() + ".");

    // check if we need to check for timeout
    Long timeout = TextManager.parseTimespanString(job.routerSettings.jobTimeoutString);
    if (timeout != null) {
      // maximize the timeout to 24 hours
      if (timeout > MAX_TIMEOUT) {
        timeout = MAX_TIMEOUT;
      }

      job.timeoutAt = job.startedAt.plusSeconds(timeout);
      timeoutSecondsForDisplay = timeout;
    }

    // Start a new thread that will monitor the job thread
    Thread monitorThread = new Thread(() -> {
      while ((job != null) && (job.thread != null)) {

        try {
          Thread.sleep(1000);
        } catch (InterruptedException e) {
          e.printStackTrace();
        }

        if (job.state == RoutingJobState.RUNNING || job.state == RoutingJobState.STOPPING) {
          // Get the CPU time and memory usage of the job thread
          this.monitorCpuAndMemoryUsage(job);

          // Say the run is alive. A pass reports only when it FINISHES, and a single pass
          // has been measured consuming an entire 2-minute budget -- so without this the
          // silence lasts exactly as long as the wait that makes someone kill the job.
          if ((job.startedAt != null) && (++heartbeatTicks % HEARTBEAT_SECONDS == 0)) {
            long elapsedSeconds = java.time.Duration.between(job.startedAt, Instant.now()).getSeconds();
            job.logInfo(app.freerouting.core.RoutingProgress.format(
                elapsedSeconds, timeoutSecondsForDisplay) + " (" + job.stage + ").");
          }

          // Check for timeout. timeoutAt is null whenever the timeout string did not
          // parse; without this guard the monitor thread dies on its first tick, taking
          // the CPU and memory sampling above with it and silently zeroing those figures.
          if ((job.timeoutAt != null) && !Instant
              .now()
              .isBefore(job.timeoutAt)) {

            // signal the job thread to stop, and wait gracefully for up to 30 seconds for
            // it
            job.thread.requestStop();
            while ((job.state == RoutingJobState.RUNNING) && Instant
                .now()
                .isBefore(job.timeoutAt.plusSeconds(GRACE_PERIOD))) {
              try {
                Thread.sleep(1000);
              } catch (InterruptedException e) {
                e.printStackTrace();
              }
            }
            job.state = RoutingJobState.TIMED_OUT;
          }
        }
      }
    });
    monitorThread.setDaemon(true);
    monitorThread.start();

    // start the routing task if needed
    if (job.routerSettings.getRunRouter() && (job.routerSettings.maxPasses == null || job.routerSettings.maxPasses >= 0)) {
      job.stage = RoutingStage.ROUTING;

      // Select router implementation based on algorithm setting
      NamedAlgorithm router;
      String algorithm = job.routerSettings.algorithm;

      if (!RouterFactory.isKnownAlgorithm(algorithm)) {
        job.logInfo("Unknown router algorithm '" + algorithm + "', using default ("
            + RouterSettings.ALGORITHM_CURRENT + ")");
      }
      router = RouterFactory.create(job);
      BatchRoutingAlgorithm batchRouter = (BatchRoutingAlgorithm) router;

      // Tracks whether a progress notification already wrote a good board to disk.
      // If one did, an aborted run must not replace it with a partial one.
      final java.util.concurrent.atomic.AtomicBoolean outputWritten =
          new java.util.concurrent.atomic.AtomicBoolean(false);

      router.addBoardUpdatedEventListener(new BoardUpdatedEventListener() {
        @Override
        public void onBoardUpdatedEvent(BoardUpdatedEvent event) {
          // Only a write that actually stored data counts. setJobOutput swallows a writer
          // exception and a false SES result, so recording the ATTEMPT here meant a failed
          // write looked like a good board on disk -- and the final persist was then
          // skipped as redundant, reintroducing the empty output this flag exists to
          // prevent.
          if (setJobOutput(job)) {
            outputWritten.set(true);
          }
        }
      });

      // Call runBatchLoop
      batchRouter.runBatchLoop();
      fanoutTimedOut = batchRouter.isFanoutTimedOut();
      routingAborted = batchRouter.endedAbnormally();

      // Persist the routed board unconditionally. The listener above also writes
      // output, but only as a side effect of a PROGRESS notification -- so a router
      // that completes without emitting progress events produces no output at all
      // while reporting success. BatchAutorouterV19 does exactly that: it never fires
      // a board-updated event, so selecting the v1.9 engine routed the board and then
      // wrote a zero-byte .ses. Saving the result is not a progress concern.
      //
      // The one exception is an aborted pass: the in-memory board is then only
      // partially routed, and overwriting a good board already on disk with it loses
      // the result silently. shouldPersistFinalBoard holds that rule.
      if (shouldPersistFinalBoard(routingAborted, outputWritten.get())) {
        setJobOutput(job);
      } else {
        job.logError("The routing pass was aborted; keeping the last successfully "
            + "written board rather than overwriting it with a partially routed one.",
            null);
      }

      // Log session summary
      Instant sessionStartTime = batchRouter.getSessionStartTime();
      int initialUnroutedCount = batchRouter.getInitialUnroutedCount();

      if (sessionStartTime != null) {
        Instant sessionEndTime = Instant.now();
        long totalSeconds = java.time.Duration.between(sessionStartTime, sessionEndTime).getSeconds();
        double totalTime = totalSeconds
            + (java.time.Duration.between(sessionStartTime, sessionEndTime).getNano() / 1000000000.0);

        var finalStats = job.board.get_statistics();

        String completionStatus = "completed:";
        // Check for timeout explicitly because job.state might not be updated to
        // TIMED_OUT yet due to race conditions
        boolean isTimedOut = (job.state == RoutingJobState.TIMED_OUT) ||
            ((job.timeoutAt != null) && !Instant.now().isBefore(job.timeoutAt) && job.thread.isStopRequested());

        if (isTimedOut) {
          completionStatus = "completed with timeout:";
        } else if (job.thread.isStopRequested()) {
          completionStatus = "interrupted:";
          if (job.isCancelledByUser()) {
            completionStatus = "cancelled:";
          }
        }

        String sessionSummary = String.format(java.util.Locale.US,
            "Auto-routing stage %s started with %d unrouted nets, completed in %.2f seconds, final score: %s, using %.2f total CPU seconds, %.2f GB total allocated, and %.1f MB peak heap usage.",
            completionStatus,
            initialUnroutedCount,
            totalTime,
            FRLogger.formatScore(finalStats.getNormalizedScore(job.routerSettings.scoring),
                finalStats.connections.incompleteCount, finalStats.clearanceViolations.totalCount),
            job.resourceUsage.cpuTimeUsed,
            job.resourceUsage.maxMemoryUsed / 1024.0f,
            job.resourceUsage.peakMemoryUsed);

        job.logInfo(sessionSummary);
      }

      job.stage = RoutingStage.IDLE;
    } else if (job.routerSettings.isFanoutEnabled()) {
      // Headless fanout-only mode: run the fanout pre-pass and skip autorouter passes.
      job.stage = RoutingStage.ROUTING;
      Integer originalMaxPasses = job.routerSettings.maxPasses;
      try {
        job.routerSettings.maxPasses = 0;
        BatchAutorouter batchRouter = new BatchAutorouter(job);
        batchRouter.runBatchLoop();
        fanoutTimedOut = batchRouter.isFanoutTimedOut();
        setJobOutput(job);
      } finally {
        job.routerSettings.maxPasses = originalMaxPasses;
      }
      job.stage = RoutingStage.IDLE;
    }

    if (!shouldRunOptimizer(job.routerSettings.getRunOptimizer(), routingAborted)) {
      if (routingAborted && job.routerSettings.getRunOptimizer()) {
        job.logError("The routing pass was aborted, so optimization is skipped: what is "
            + "in memory is a partially routed board, and the optimizer's first progress "
            + "event would write it over the last good board on disk.", null);
      }
    } else {
      job.stage = RoutingStage.OPTIMIZATION;

      // Stage-scoped scoring: restore the DEFAULT objective for the optimisation stage
      // when asked. The routing stage may have run a variant objective (raised via costs
      // and the like); without this seam that bias also steers every item reroute inside
      // the optimiser and the guard's own score. Loud, never silent.
      if (Boolean.TRUE.equals(job.routerSettings.optimizer.restoreDefaultScoring)) {
        // The fresh default scoring carries null per-layer cost arrays (transient,
        // board-specific). Rebuild them for THIS board -- but applyBoardSpecificOptimizations
        // touches more than scoring (layer routability, bend costs), and this seam is
        // scoring-scoped. So: run it on a CLONE carrying the default scoring, then take
        // ONLY the scoring back.
        app.freerouting.settings.ScoringSettings restored =
            app.freerouting.settings.RouterSettings.defaultScoringForOptimizer();
        if (job.board != null) {
          app.freerouting.settings.RouterSettings scratch =
              app.freerouting.util.gson.GsonProvider.GSON.fromJson(
                  app.freerouting.util.gson.GsonProvider.GSON.toJson(job.routerSettings),
                  app.freerouting.settings.RouterSettings.class);
          scratch.scoring = restored;
          scratch.applyBoardSpecificOptimizations(job.board);
          restored = scratch.scoring;
        }
        job.routerSettings.scoring = restored;
        job.logInfo("Stage-scoped scoring: the optimisation stage runs on the DEFAULT "
            + "objective; the routing stage's scoring variant does not carry over.");
      }

      // start the optimizer task. The multi-threaded optimizer is shipped, governed
      // behaviour: it delivers its wins (defect 31 fixed), compounds within a pass
      // (run-time clones), stops honestly (work-quanta guard) and lives inside the memory
      // budget. Width defaults to 2, the measured quality point; the helper is null-safe
      // and applies the core ceiling BEFORE dispatch, so a narrow machine runs ST.
      // feature_flags.multi_threading is the documented kill switch and is honored on
      // BOTH paths (the GUI already checks it): flag off = single-threaded optimiser,
      // whatever the width setting says.
      boolean multiThreadingAllowed = app.freerouting.Freerouting.globalSettings == null
          || app.freerouting.Freerouting.globalSettings.featureFlags == null
          || app.freerouting.Freerouting.globalSettings.featureFlags.multiThreading;
      BatchOptimizer optimizer = (multiThreadingAllowed
          && app.freerouting.autoroute.BatchOptimizerMultiThreaded.shouldUseMultiThreaded(job))
          ? new app.freerouting.autoroute.BatchOptimizerMultiThreaded(job)
          : new BatchOptimizer(job);
      optimizer.addBoardUpdatedEventListener(new BoardUpdatedEventListener() {
        @Override
        public void onBoardUpdatedEvent(BoardUpdatedEvent event) {
          setJobOutput(job);
        }
      });
      optimizer.runBatchLoop();
      optimizerTimedOut = optimizer.isTimedOut();
      // Kept on the job: the CLI reports the ending long after this local has gone.
      job.stageTimedOut = job.stageTimedOut || optimizerTimedOut;
      job.stage = RoutingStage.IDLE;
    }

    job.finishedAt = Instant.now();
    // Decide the outcome from the deadline as well as the state. Reading job.state alone
    // loses the timeout whenever the router obeys the stop request inside the grace
    // period, which is precisely when it is behaving well.
    final boolean wasRunning = (job.state == RoutingJobState.RUNNING);
    final boolean pastDeadline =
        (job.timeoutAt != null) && !Instant.now().isBefore(job.timeoutAt);
    job.state = finalStateFor(
        job.state, job.thread.isStopRequested(), pastDeadline, job.isCancelledByUser());
    if (wasRunning && (job.state == RoutingJobState.COMPLETED)) {
      // Unchanged: only a job that was running and genuinely finished counts as completed.
      // A job the clock ended is no longer counted, which is the point.
      Freerouting.globalSettings.statistics.incrementJobsCompleted();
    }

    long durationMs = java.time.Duration.between(job.startedAt, job.finishedAt).toMillis();
    double durationSec = durationMs / 1000.0;
    // Built from the board actually being DELIVERED, after every stage. Wired to the end of
    // the routing stage first, it named five tracks the optimiser then removed: accurate for
    // that moment and wrong about the result.
    String underWidthReport = job.board == null ? null
        : app.freerouting.autoroute.BatchAutorouter.buildUnderMinimumWidthReport(job.board);

    StringBuilder details = new StringBuilder();
    if (fanoutTimedOut) {
      details.append(" (fanout stage timed out)");
    }
    if (optimizerTimedOut) {
      details.append(" (optimizer stage timed out)");
    }
    long exactRangeViolations =
        app.freerouting.geometry.planar.IntPoint.exactRangeViolationsSince(exactRangeBaseline);
    if (exactRangeViolations > 0) {
      // A run that broke the exactness invariant must not be able to look clean. Beyond
      // +/-2^25 the orientation predicates are no longer guaranteed exact, so every
      // clearance result on this board is unverified -- and that belongs in the line
      // people actually read, not only in an ERROR near the top of a long log.
      details.append(" (EXACTNESS UNVERIFIED: ").append(exactRangeViolations)
          .append(" coordinate(s) outside the exact range -- treat clearance results")
          .append(" from this board as unverified)");
    }
    if (job.state == RoutingJobState.TIMED_OUT) {
      // The whole-job deadline, distinct from the per-stage timeouts above: this run was
      // stopped with work still to do, and the result below is therefore incomplete BY
      // DESIGN rather than because the board could not be routed.
      details.append(" (stopped at the job time limit of ")
          .append(job.routerSettings.jobTimeoutString)
          .append(" -- the result is partial)");
    }
    // What the router actually produced, not merely that it stopped. COMPLETED alone is
    // true of a perfect board, a board with 19 unrouted connections and 192 violations,
    // and a run that ran out of clock -- three outcomes, one word.
    try {
      if (job.board != null) {
        var finalStats = new app.freerouting.core.scoring.BoardStatistics(job.board);
        int unrouted = finalStats.connections.incompleteCount;
        int violations = finalStats.clearanceViolations.totalCount;
        // Under-width tracks count here too. The summary used to read "fully routed, no
        // clearance violations" one line after warning that the board may not be
        // manufacturable: clearance and minimum width are different rules, and only one
        // of them was being counted.
        boolean underWidth = underWidthReport != null;
        if (unrouted > 0 || violations > 0 || underWidth) {
          details.append(" (").append(unrouted).append(" unrouted, ")
              .append(violations).append(" clearance violations");
          if (underWidth) {
            details.append(", tracks below the minimum width");
          }
          details.append(")");
        } else {
          details.append(" (fully routed, no clearance violations)");
        }
      }
    } catch (Exception statsFailure) {
      // Never let reporting the outcome become the reason a job appears to fail.
      details.append(" (outcome counts unavailable)");
      FRLogger.warn("Could not compute final board statistics for the job summary: "
          + statsFailure.getMessage());
    }

    logUnroutedNets(job);

    if (underWidthReport != null) {
      job.logWarning(underWidthReport);
    }

    job.logInfo("Job '" + job.shortName + "' finished with state: " + job.state.toString() + details.toString() +
        " (elapsed: " + FRLogger.formatDuration(durationSec) + ", finished at UTC: " + job.finishedAt.toString() + ").");
  }

  private void monitorCpuAndMemoryUsage(RoutingJob job) {
    try {
      // Get the ThreadMXBean instance and cast it to com.sun.management.ThreadMXBean
      ThreadMXBean threadMXBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();

      // Get all live thread IDs
      long[] threadIds = threadMXBean.getAllThreadIds();

      // Iterate through the thread IDs and get memory usage
      for (long threadId : threadIds) {
        if (threadId == job.thread.threadId()) {
          // CPU time and memory usage
          float cpuTime = threadMXBean.getThreadCpuTime(threadId) / 1000.0f / 1000.0f / 1000.0f;

          // Enable thread memory allocation measurement
          threadMXBean.setThreadAllocatedMemoryEnabled(true);

          // Get the thread's allocated memory in bytes
          long allocatedMemory = threadMXBean.getThreadAllocatedBytes(threadId);
          float allocatedMB = allocatedMemory / (1024.0f * 1024.0f);

          // Update the job's resource usage
          // Fix: Use assignment instead of accumulation for total time, as
          // getThreadCpuTime returns cumulative time
          // Note: This only tracks the main thread. Worker threads add their stats
          // separately.
          job.resourceUsage.cpuTimeUsed = cpuTime;
          // Fix: maxMemoryUsed represents total allocated bytes here, so we accumulate if
          // we track partials,
          // but here it tracks the monotonically increasing allocation of the main
          // thread.
          job.resourceUsage.maxMemoryUsed = allocatedMB;
        }
      }

      // Track peak heap memory usage across all threads
      java.lang.management.MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
      long heapUsed = memoryMXBean.getHeapMemoryUsage().getUsed();
      float heapUsedMB = heapUsed / (1024.0f * 1024.0f);

      // Update peak memory if current usage is higher
      if (heapUsedMB > job.resourceUsage.peakMemoryUsed) {
        job.resourceUsage.peakMemoryUsed = heapUsedMB;
      }
    } catch (Throwable t) {
      // java.management or jdk.management module may not be available in minimal JRE builds;
      // resource usage stats will remain at their current values.
    }
  }

  /**
   * Whether the final in-memory board should be written at the end of a session.
   *
   * <p>Pure and package-private so the rule can be tested directly. Two defects meet
   * here: a router that fires no progress events must still produce output, and an
   * aborted pass must not replace a good board with a partial one.
   *
   * @param endedAbnormally a pass was cut short by an exception
   * @param outputAlreadyWritten a progress notification already persisted a board
   */
  /**
   * The outcome of a job, decided from the deadline as well as the observed state.
   *
   * <p>Pure and package-visible because the precedence between three competing causes --
   * the user cancelled, the clock ran out, it finished -- is a judgement that should be
   * stated once and argued with, not an {@code if} buried mid-method.
   *
   * <p>The race this exists for: {@code requestStop()} sets a flag and does NOT move the
   * job to {@code STOPPING}, so during the 30-second grace period the state is still
   * {@code RUNNING}. A router that stops promptly -- the desired behaviour -- reaches the
   * finish line before the monitor thread writes {@code TIMED_OUT}, and used to be
   * reported {@code COMPLETED}.
   *
   * @param observedState  the job state as it stands, which may be mid-race
   * @param stopRequested  whether anything has asked the router to stop
   * @param pastDeadline   whether the wall-clock deadline has passed
   * @param cancelledByUser whether a human cancelled this job
   */
  static RoutingJobState finalStateFor(
      RoutingJobState observedState,
      boolean stopRequested,
      boolean pastDeadline,
      boolean cancelledByUser) {

    // A human act outranks the clock. If someone pressed cancel, that is what happened,
    // and it stays true even if the deadline passed in the same moment -- "cancelled" is
    // the more specific and more useful of the two facts.
    if (cancelledByUser) {
      return RoutingJobState.CANCELLED;
    }

    // Never relabel a state that already carries a more specific cause. A job that was
    // TERMINATED by an error did not finish and did not time out.
    if ((observedState != RoutingJobState.RUNNING)
        && (observedState != RoutingJobState.STOPPING)) {
      return observedState;
    }

    // The clock ended it: the deadline passed AND something asked the router to stop.
    // Both are required -- a job merely running past its deadline with no stop request
    // has not been ended by the deadline, and a stop request with time left on the clock
    // is an ordinary finish (a pass limit, say), not a timeout.
    if (stopRequested && pastDeadline) {
      return RoutingJobState.TIMED_OUT;
    }

    return RoutingJobState.COMPLETED;
  }

  /**
   * Whether the optimizer may run.
   *
   * <p>An abort may only ever subtract: the user's setting stays the user's setting, and
   * a routing pass that ended abnormally removes the option. Optimizing an abandoned board
   * is meaningless on its own terms -- what is in memory is not a routing result, it is
   * the wreckage of one -- and, more concretely, the optimizer's first board-updated event
   * writes that board over the good one the abort handling just preserved.
   */
  static boolean shouldRunOptimizer(boolean optimizerEnabled, boolean routingAborted) {
    return optimizerEnabled && !routingAborted;
  }

  static boolean shouldPersistFinalBoard(boolean endedAbnormally, boolean outputAlreadyWritten) {
    return !endedAbnormally || !outputAlreadyWritten;
  }

  /**
   * Header for the unrouted list, stating both totals up front.
   *
   * <p>Both numbers are given so the reader can reconcile the rows they can see against
   * the figure in the summary line. Without the totals, a capped list reads as a complete
   * one — and this report is the last word, with nothing downstream to re-check it.
   */
  static String unroutedHeader(int netCount, int connectionCount) {
    return "Unrouted connections by net (" + netCount + " net"
        + (netCount == 1 ? "" : "s") + ", " + connectionCount + " connection"
        + (connectionCount == 1 ? "" : "s") + " total):";
  }

  /**
   * The remainder line, accounting for connections and not merely nets.
   *
   * <p>Saying "98 further nets" left 127 unrouted connections described only as a count of
   * nets. Stating both means listed + withheld = total, and the reader can check it by eye.
   *
   * @return an empty string when nothing was withheld — a remainder of zero reads as
   *     though something was held back
   */
  static String unroutedCapNote(int hiddenNets, int hiddenConnections, int limit) {
    if (hiddenNets <= 0) {
      return "";
    }
    return "... and " + hiddenNets + " further net" + (hiddenNets == 1 ? "" : "s")
        + " holding " + hiddenConnections + " unrouted connection"
        + (hiddenConnections == 1 ? "" : "s") + ", not listed (limit " + limit + ").";
  }

  /** How many nets to name before summarising the rest. */
  private static final int MAX_LISTED_UNROUTED_NETS = 25;

  /**
   * Names the nets that still have incomplete connections.
   *
   * <p>"19 unrouted" tells a user they have a problem and leaves them to find it. The
   * per-net breakdown already exists inside the design-rules checker; it was simply never
   * surfaced. Failure to produce it must never become the reason a job looks failed, so
   * everything here is contained.
   */
  private void logUnroutedNets(RoutingJob job) {
    if (job == null || job.board == null) {
      return;
    }
    try {
      var drc = new app.freerouting.drc.DesignRulesChecker(job.board, null);
      drc.calculateAllIncompletes();

      int maxNetNo = job.board.rules.nets.max_net_no();
      StringBuilder listed = new StringBuilder();
      int namedNets = 0;
      int unnamedNets = 0;
      int totalNets = 0;
      int totalConnections = 0;
      int hiddenConnections = 0;

      for (int netNo = 1; netNo <= maxNetNo; netNo++) {
        int incomplete = drc.getIncompleteCount(netNo);
        if (incomplete <= 0) {
          continue;
        }
        totalNets++;
        totalConnections += incomplete;
        if (namedNets < MAX_LISTED_UNROUTED_NETS) {
          var net = job.board.rules.nets.get(netNo);
          String netName = (net != null && net.name != null) ? net.name : ("net " + netNo);
          listed.append(System.lineSeparator())
              .append("    ").append(netName).append(": ").append(incomplete);
          namedNets++;
        } else {
          unnamedNets++;
          hiddenConnections += incomplete;
        }
      }

      if (namedNets == 0) {
        return;
      }

      StringBuilder message =
          new StringBuilder(unroutedHeader(totalNets, totalConnections)).append(listed);
      String capNote = unroutedCapNote(unnamedNets, hiddenConnections, MAX_LISTED_UNROUTED_NETS);
      if (!capNote.isEmpty()) {
        // Say what was withheld, in the same unit as the total. A truncated list whose
        // remainder is given only as a count of nets still reads as a complete one.
        message.append(System.lineSeparator()).append("    ").append(capNote);
      }
      job.logInfo(message.toString());

    } catch (Exception e) {
      FRLogger.warn("Could not list the unrouted nets for the job summary: " + e.getMessage());
    }
  }

  /**
   * Serialises the current board into the job's output field.
   *
   * @return {@code true} only if data was actually stored. Both writers here can fail
   *     quietly -- an exception is caught and logged, and the SES writer can simply return
   *     false -- and a caller that treats a failed attempt as a good board on disk will
   *     skip the write that would have saved the result.
   */
  private boolean setJobOutput(RoutingJob job) {
    if (job.output == null) {
      job.output = new BoardFileDetails(job.board);
      job.output.addUpdatedEventListener(_ -> job.fireOutputUpdatedEvent());
      String outputBaseName = (job.input != null) ? job.input.getFilenameWithoutExtension() : job.name;
      if (job.input != null && job.input.format == FileFormat.KICAD_DESIGN_JSON) {
        job.output.format = FileFormat.KICAD_SESSION_JSON;
        job.output.setFilename(outputBaseName + ".json");
      } else {
        job.output.format = FileFormat.SES;
        job.output.setFilename(outputBaseName + ".ses");
      }
    }

    // save the result to the output field
    if (job.output.format == FileFormat.KICAD_SESSION_JSON) {
      try {
        String jsonStr = app.freerouting.io.kicad.KiCadJsonWriter.write(job.board, job.name);
        job.output.setData(jsonStr.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return true;
      } catch (Exception e) {
        FRLogger.error("Couldn't save the JSON output into the job object.", e);
      }
    } else if (job.output.format == FileFormat.SES) {
      HeadlessBoardManager boardManager = new HeadlessBoardManager(job);
      boardManager.replaceRoutingBoard(job.board);

      // Save the SES file after the auto-router has finished
      try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
        if (boardManager.saveAsSpecctraSessionSes(baos, job.name)) {
          job.output.setData(baos.toByteArray());
          return true;
        }
        FRLogger.error("The SES writer reported failure; no output was stored for job '"
            + job.shortName + "'.", null);
      } catch (Exception e) {
        FRLogger.error("Couldn't save the SES output into the job object.", e);
      }
    }
    return false;
  }

}