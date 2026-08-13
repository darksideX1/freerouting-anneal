package app.freerouting.autoroute;

import static java.util.Collections.shuffle;

import app.freerouting.autoroute.events.BoardUpdatedEvent;
import app.freerouting.autoroute.events.BoardUpdatedEventListener;
import app.freerouting.autoroute.events.TaskStateChangedEvent;
import app.freerouting.board.BasicBoard;
import app.freerouting.board.ConductionArea;
import app.freerouting.board.Connectable;
import app.freerouting.board.DrillItem;
import app.freerouting.board.Item;
import app.freerouting.board.Pin;
import app.freerouting.board.PolylineTrace;
import app.freerouting.board.RoutingBoard;
import app.freerouting.board.Trace;
import app.freerouting.board.Via;
import app.freerouting.core.RouterCounters;
import app.freerouting.core.RoutingJob;
import app.freerouting.core.RoutingJobState;
import app.freerouting.core.StoppableThread;
import app.freerouting.core.scoring.BoardStatistics;
import app.freerouting.datastructures.TimeLimit;
import app.freerouting.datastructures.UndoableObjects;
import app.freerouting.drc.AirLine;
import app.freerouting.drc.DesignRulesChecker;
import app.freerouting.geometry.planar.FloatLine;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.geometry.planar.Point;
import app.freerouting.logger.FRLogger;
import app.freerouting.rules.Net;
import app.freerouting.settings.RouterSettings;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Handles the sequencing of the auto-router passes.
 */
public class BatchAutorouter extends NamedAlgorithm implements BatchRoutingAlgorithm {

  // The lowest rank of the board to be selected to go back to.
  // Must not exceed BoardHistory.MAX_HISTORY_SIZE so the check can actually fire.
  private static final int BOARD_RANK_LIMIT = BoardHistory.MAX_HISTORY_SIZE;
  // Maximum number of tries on the same board
  private static final int MAXIMUM_TRIES_ON_THE_SAME_BOARD = 3;
  private static final int TIME_LIMIT_TO_PREVENT_ENDLESS_LOOP = 1000;
  /**
   * How many {@link #TIME_LIMIT_TO_PREVENT_ENDLESS_LOOP} slices a racing join may wait.
   *
   * <p>The join loop needs a hard cap so a worker that never terminates degrades the pass
   * rather than the process. Generous on purpose: the job deadline is the real bound, and
   * this only has to stop an unbounded wait.
   */
  private static final int MAX_RACING_JOIN_ATTEMPTS = 600;
  // The minimum number of passes to complete the board, unless all items are
  // routed
  private static final int STOP_AT_PASS_MINIMUM = 8;
  // The modulo of the pass number to check if the improvements were so small that
  // process should stop despite not all items are routed
  private static final int STOP_AT_PASS_MODULO = 4;
  // Number of consecutive passes with no meaningful score improvement before
  // aborting (prevents endless looping when items cannot be routed)
  private static final int STAGNATION_PASS_LIMIT = 10;
  // Number of no-improvement passes before attempting a one-time fanout-tail cleanup.
  private static final int FANOUT_RECOVERY_STAGNATION_PASSES = 3;
  // Minimum score gain (on the 0–1000 normalized scale) that counts as a
  // meaningful improvement; gains smaller than this are treated as stagnation.
  private static final float STAGNATION_SCORE_THRESHOLD = 0.5f;

  private final boolean remove_unconnected_vias;
  private final AutorouteControl.ExpansionCostFactor[] trace_cost_arr;
  private final boolean retain_autoroute_database;
  private final int start_ripup_costs;

  /** The racing/width conflict is warned once per job, not once per pass. */
  private boolean racingMismatchWarned;
  private final int trace_pull_tight_accuracy;
  // Reusable collections to reduce memory churn (thread-safe as each thread has
  // its own BatchAutorouter instance)
  private final List<Item> reusable_autoroute_item_list = new ArrayList<>();
  private final Set<Item> reusable_handled_items = new TreeSet<>();
  protected RoutingJob job;
  private int totalItemsRouted = 0;
  private boolean fanoutTimedOut = false;

  /** Set when a pass was cut short by an exception; see {@link PassOutcome#ABORTED}. */
  private boolean endedAbnormally = false;

  @Override
  public boolean endedAbnormally() {
    return this.endedAbnormally;
  }

  public boolean isFanoutTimedOut() {
    return this.fanoutTimedOut;
  }
  /**
   * Time when the routing session started.
   */
  private Random random;
  /**
   * Used to draw the airline of the current routed incomplete.
   */
  private FloatLine air_line;
  /**
   * Initial number of unrouted nets at the start of the routing session.
   */
  private int initialUnroutedCount;
  /**
   * Time when the routing session started.
   */
  private Instant sessionStartTime;
  private long lastBoardUpdateTimestamp = 0;

  private boolean isOptimizerAutorouter = false;

  public BatchAutorouter(RoutingJob job) {
    this(job.thread, job.board, job.routerSettings, !job.routerSettings.isFanoutEnabled(), true,
        job.routerSettings.get_start_ripup_costs(), job.routerSettings.trace_pull_tight_accuracy);
    this.job = job;
  }

  public BatchAutorouter(StoppableThread p_thread, RoutingBoard board, RouterSettings settings,
      boolean p_remove_unconnected_vias, boolean p_with_preferred_directions, int p_start_ripup_costs,
      int p_pull_tight_accuracy) {
    super(p_thread, board, settings);

    this.random = new Random(0);

    this.remove_unconnected_vias = p_remove_unconnected_vias;
    if (p_with_preferred_directions) {
      this.trace_cost_arr = this.settings.get_trace_cost_arr();
    } else {
      // remove preferred direction
      this.trace_cost_arr = new AutorouteControl.ExpansionCostFactor[this.board.get_layer_count()];
      for (int i = 0; i < this.trace_cost_arr.length; i++) {
        double curr_min_cost = this.settings.get_preferred_direction_trace_costs(i);
        this.trace_cost_arr[i] = new AutorouteControl.ExpansionCostFactor(curr_min_cost, curr_min_cost);
      }
    }

    this.start_ripup_costs = p_start_ripup_costs;
    this.trace_pull_tight_accuracy = p_pull_tight_accuracy;
    this.retain_autoroute_database = false;
  }

  /**
   * Auto-routes ripup passes until the board is completed or the auto-router is
   * stopped by the user, or if p_max_pass_count is exceeded. Is currently used in
   * the optimize via batch pass. Returns the
   * number of passes to complete the board or p_max_pass_count + 1, if the board
   * is not completed.
   */
  /**
   * Whether the optimiser's own re-router may run.
   *
   * <p>DEFECT 25. This used to ask {@code is_stop_auto_router_requested()}, which returns
   * true for BOTH stop states -- and {@code AUTO_ROUTER_ONLY} exists precisely to mean
   * "the auto-router is finished, now run the optimiser". So the flag whose purpose was to
   * hand control TO the optimiser was the flag that switched its re-router off.
   *
   * <p>The consequence was silent and total: the re-route loop executed ZERO passes, so
   * every item was ripped up, never re-routed, measured as worse, and undone. 45 items
   * examined and 0 improved, on every pass, for 100 passes -- with byte-identical output,
   * because the undo restored the board each time. It is the direct cause of this fork
   * shipping 17 vias and 9% more trace length where the 2023 original produces 14.
   *
   * <p>A FULL stop still halts it: that is the user asking for everything to end.
   */
  static boolean optimizerRerouteMayRun(app.freerouting.core.StoppableThread thread) {
    return !thread.isStopRequested();
  }

  public static int autoroute_passes_for_optimizing_item(RoutingJob job, int p_max_pass_count, int p_ripup_costs,
      int trace_pull_tight_accuracy, boolean p_with_preferred_directions,
      RoutingBoard updated_routing_board, RouterSettings routerSettings) {
    BatchAutorouter router_instance = new BatchAutorouter(job.thread, updated_routing_board, routerSettings, true,
        p_with_preferred_directions, p_ripup_costs, trace_pull_tight_accuracy);
    router_instance.job = job;
    router_instance.isOptimizerAutorouter = true;

    boolean still_unrouted_items = true;
    int curr_pass_no = 1;
    // `true` = this IS the optimiser's re-route, so only a FULL stop halts it. Passing
    // false here (or calling is_stop_auto_router_requested directly) reintroduces defect
    // 25: the loop runs zero passes and every optimisation is ripped up and undone.
    while (still_unrouted_items && !routingShouldStop(job.thread, true) && curr_pass_no <= p_max_pass_count) {
      still_unrouted_items = router_instance.autoroute_pass(curr_pass_no).shouldContinue();
      if (still_unrouted_items && !routingShouldStop(job.thread, true) && updated_routing_board == null) {
      }
      ++curr_pass_no;
    }
    router_instance.remove_tails(Item.StopConnectionOption.NONE);
    if (!still_unrouted_items) {
      --curr_pass_no;
    }
    return curr_pass_no;
  }

  private static Point[] getImpactedPoints(Item item) {
    if (item instanceof Trace trace) {
      return new Point[] { trace.first_corner(), trace.last_corner() };
    }
    if (item instanceof Via via) {
      return new Point[] { via.get_center() };
    }
    if (item instanceof Pin pin) {
      return new Point[] { pin.get_center() };
    }
    if (item instanceof DrillItem drillItem) {
      return new Point[] { drillItem.get_center() };
    }
    return Point.EMPTY;
  }

  private static float getCpuSecondsSnapshot(RoutingJob job) {
    if (job == null || job.resourceUsage == null) {
      return 0f;
    }
    return job.resourceUsage.cpuTimeUsed;
  }

  /**
   * Auto-routes one ripup pass of all items of the board. Returns false, if the
   * board is already completely routed.
   */

  private static float getAllocatedMemoryMbSnapshot(RoutingJob job) {
    if (job == null || job.resourceUsage == null) {
      return 0f;
    }
    return job.resourceUsage.maxMemoryUsed;
  }

  private static float getPeakHeapMbSnapshot(RoutingJob job) {
    if (job == null || job.resourceUsage == null) {
      return 0f;
    }
    return job.resourceUsage.peakMemoryUsed;
  }

  /**
   * Whether routing should stop, given who is doing the routing.
   *
   * <p>DEFECT 25, and the reason it needed a named helper rather than a raw predicate: the
   * SAME pass code serves two callers with opposite requirements.
   *
   * <p>{@code StoppableThread} documents {@code AUTO_ROUTER_ONLY} as "stop the auto-router,
   * but continue with the optimizer and other tasks". For the main routing stage that
   * means stop. For the OPTIMISER'S re-route -- which runs the very same
   * {@code autoroute_pass} -- it must mean carry on, because that state is precisely the
   * signal that routing has finished and the optimiser now has the board.
   *
   * <p>Consulting {@code is_stop_auto_router_requested()} everywhere made the second caller
   * impossible: the per-item loop broke on the first net of the first item, so the
   * optimiser's re-route queued 29-31 items and routed ZERO, reported NO_PROGRESS, and
   * every optimisation attempt ripped a connection up, failed to restore it, measured the
   * board as worse and undid it. 45 items examined, 0 improved, on every pass.
   *
   * <p>A FULL stop halts both: that is the user ending the job.
   */
  static boolean routingShouldStop(app.freerouting.core.StoppableThread thread,
      boolean isOptimizerReroute) {
    return isOptimizerReroute ? thread.isStopRequested() : thread.is_stop_auto_router_requested();
  }

  /** Instance form of {@link #routingShouldStop}, using this router's role. */
  private boolean routingShouldStop() {
    return routingShouldStop(this.thread, this.isOptimizerAutorouter);
  }

  private boolean shouldFireBoardUpdate() {
    long currentTime = System.currentTimeMillis();
    if (currentTime - lastBoardUpdateTimestamp > 250) { // Limit updates to 4 times per second (250ms)
      lastBoardUpdateTimestamp = currentTime;
      return true;
    }
    return false;
  }

  private List<Item> getAutorouteItems(RoutingBoard board) {
    // Reuse instance collections to reduce memory allocation
    reusable_autoroute_item_list.clear();
    reusable_handled_items.clear();
    List<Item> autoroute_item_list = reusable_autoroute_item_list;
    Set<Item> handled_items = reusable_handled_items;
    Iterator<UndoableObjects.UndoableObjectNode> it = board.item_list.start_read_object();
    for (;;) {
      UndoableObjects.Storable curr_ob = board.item_list.read_object(it);
      if (curr_ob == null) {
        break;
      }
      if (curr_ob instanceof Connectable && curr_ob instanceof Item curr_item) {
        // This is a connectable item, like PolylineTrace or Pin
        if (!curr_item.is_routable()) {
          if (!handled_items.contains(curr_item)) {

            // Let's go through all nets of this item
            for (int i = 0; i < curr_item.net_count(); i++) {
              int curr_net_no = curr_item.get_net_no(i);
              Set<Item> connected_set = curr_item.get_connected_set(curr_net_no);
              for (Item curr_connected_item : connected_set) {
                if (curr_connected_item.net_count() <= 1) {
                  handled_items.add(curr_connected_item);
                }
              }
              int net_item_count = board.connectable_item_count(curr_net_no);

              // If the item is not connected to all other items of the net, we add it to the
              // auto-router's to-do list
              if ((connected_set.size() < net_item_count) && (!curr_item.has_ignored_nets())) {
                Net net = board.rules.nets.get(curr_net_no);
                // For plane nets: skip items whose connected set already contains a
                // ConductionArea (copper pour). These items would immediately return
                // CONNECTED_TO_PLANE in autoroute_item(), wasting time and causing
                // spurious normalize_traces() failures on nearby stub geometry.
                // Items not yet connected to the plane are still enqueued so they can
                // be routed to the pour in this pass.
                if (net != null && net.contains_plane()) {
                  boolean alreadyConnectedToPlane = connected_set.stream()
                      .anyMatch(connectedItem -> connectedItem instanceof ConductionArea);
                  if (alreadyConnectedToPlane) {
                    continue;
                  }
                }
                autoroute_item_list.add(curr_item);
                String netName = (net != null) ? net.name : "net#" + curr_net_no;
                if (FRLogger.isDebugEnabled()) {
                  FRLogger.debug("Queuing item for routing: " + curr_item.getClass().getSimpleName() + " on net '"
                      + netName + "' (connected: " + connected_set.size() + "/" + net_item_count + ")");
                }
              }
            }
          }
        }
      }
    }
    return autoroute_item_list;
  }

  /**
   * Multi-threaded version of the router that routes one ripup pass of all items
   * of the board. WARNING: this version is not working as intended yet. It is a
   * work in progress.
   * <p>
   * Returns false if the board is already completely routed.
   */
  /**
   * Whether this pass should race several board copies instead of routing once.
   *
   * <p>Pure so the dispatch rule is testable, because the rule matters more than it looks:
   * wiring racing to {@code maxThreads} would have enabled it for everyone by default,
   * since that setting defaults to {@code availableProcessors - 1}.
   *
   * <p>A one-thread race is the single-threaded path plus a board copy and a join --
   * strictly worse -- so it declines rather than pretending to honour the request.
   */
  static boolean shouldRace(boolean racingEnabled, int threadCount) {
    return racingEnabled && (threadCount > 1);
  }

  /** Fraction of free heap racing may consume; the rest is left for the search itself. */
  private static final int RACING_HEAP_RESERVE_DIVISOR = 2;

  /**
   * How many racing threads the heap can actually afford.
   *
   * <p>Every racing thread works on its own {@code board.deepCopy()}. The dead
   * implementation allocated {@code maxThreads} of them with no reference to available
   * memory, which on a large board is an OutOfMemoryError waiting for a user with more
   * cores than headroom -- and works directly against "it must not eat my RAM".
   *
   * <p>{@code perBoardBytes} is MEASURED from the first real copy rather than estimated.
   * Guessing the size of a routing board from item counts would be a second thing to be
   * wrong about, and the first copy has to be made anyway.
   *
   * <p>Half the free heap is reserved: the router still has to do its work inside whatever
   * racing leaves behind, and a thread count that fits the copies but starves the search
   * has only moved the failure.
   *
   * @param requested      what the user asked for; memory may only ever SUBTRACT from this
   * @param freeHeapBytes  heap currently available
   * @param perBoardBytes  measured cost of one board copy; {@code <= 0} means the
   *                       measurement FAILED (a GC during the probe can reclaim more than
   *                       the copy allocates), and a failed safety measurement must not be
   *                       read as unlimited capacity -- see below
   */
  static int safeThreadCount(int requested, long freeHeapBytes, long perBoardBytes) {
    if (requested <= 1) {
      return 1;
    }
    if (perBoardBytes <= 0) {
      // This used to return `requested`, on the reasoning that refusing to race because a
      // measurement failed would make racing depend on GC timing. That reasoning is wrong,
      // and a reviewer was right to call it: it grants EVERY requested copy without ever
      // consulting free heap, so on a large board under pressure the copy loop can throw
      // OutOfMemoryError -- which the surrounding `catch (Exception)` does not catch, so it
      // takes the process rather than the pass. An unusable measurement means we do not
      // know what a copy costs, and the safe answer to "how many can I afford" when the
      // cost is unknown is one.
      return 1;
    }
    long budget = freeHeapBytes / RACING_HEAP_RESERVE_DIVISOR;
    long affordable = budget / perBoardBytes;
    if (affordable < 1) {
      return 1;
    }
    return (int) Math.min(requested, affordable);
  }

  /**
   * Seed for the run as a whole. Fixed by default so a race replays exactly.
   *
   * <p>The engine is already nondeterministic on some boards (defect 20) and that is
   * documented rather than fixed. A NEW feature adding a second, avoidable source of
   * variance would make the existing one harder to isolate, so this one is reproducible
   * by construction.
   */
  private final long racingRunSeed = 0x5EEDL;

  /**
   * The ordering seed for one racing thread.
   *
   * <p>Must satisfy two properties that the shared-{@code Random} version satisfied
   * neither of: the same coordinates always produce the same ordering, and no two threads
   * in a pass -- nor the same thread across passes -- share one.
   *
   * <p>Mixed rather than combined arithmetically. The obvious {@code pass * k + thread}
   * collides the moment the thread count exceeds {@code k}, and the failure is silent:
   * two threads quietly explore the same ordering and the race pays for a duplicate.
   */
  static long orderingSeedFor(long runSeed, int passNo, int threadIndex) {
    long mixed = runSeed;
    mixed = (mixed * 0x9E3779B97F4A7C15L) ^ (passNo * 0xBF58476D1CE4E5B9L);
    mixed = (mixed * 0x94D049BB133111EBL) ^ (threadIndex * 0xD6E8FEB86659FD93L);
    // Final avalanche, so neighbouring coordinates do not produce neighbouring seeds.
    mixed ^= (mixed >>> 33);
    mixed *= 0xFF51AFD7ED558CCDL;
    mixed ^= (mixed >>> 33);
    return mixed;
  }

  /**
   * The winning thread of a racing pass, ignoring threads that never finished.
   *
   * <p>A board whose thread is still running has no score worth reading -- the statistics
   * call traverses a search tree the thread is still mutating. {@code usable} marks the
   * threads that actually terminated; everything else is not a candidate, however good its
   * half-written board may look.
   *
   * @return the winning index, or -1 if nothing finished
   */
  static int bestThreadIndexByScore(float[] scores, boolean[] usable) {
    int best = -1;
    for (int i = 0; i < scores.length; i++) {
      if (i < usable.length && !usable[i]) {
        continue;
      }
      if (best < 0 || scores[i] > scores[best]) {
        best = i;
      }
    }
    return best;
  }

  /**
   * The winning thread of a racing pass: highest score, ties to the lowest index.
   *
   * <p>Pure and package-visible so the rule can be tested and argued with, because it is
   * the algorithm rather than an implementation detail of it.
   *
   * <p>Ties break to the lowest index deliberately. It is arbitrary, but it is
   * DETERMINISTIC — a tie broken by thread completion order would make the race
   * unreproducible again, which is the thing this stream exists to fix.
   *
   * @return the index of the winner, or -1 when there are no threads
   */
  static int bestThreadIndexByScore(float[] scores) {
    int best = -1;
    float bestScore = 0;
    for (int i = 0; i < scores.length; i++) {
      // Note the (best < 0) guard: scores are normalised and can be negative, so a max
      // initialised to zero would silently adopt the worst board on an all-negative pass.
      if ((best < 0) || (scores[i] > bestScore)) {
        best = i;
        bestScore = scores[i];
      }
    }
    return best;
  }

  private PassOutcome autoroute_pass_multi_thread(int p_pass_no) {
    try {
      List<Item> autoroute_item_list = getAutorouteItems(this.board);

      // If there are no items to route, we're done
      if (autoroute_item_list.isEmpty()) {
        this.air_line = null;
        return PassOutcome.NO_PROGRESS;
      }

      boolean useSlowAlgorithm = false;

      // Measure one board copy before committing to N of them. The first copy has to be
      // made regardless, so the measurement is free -- and it is a measurement rather than
      // an estimate, which is the difference between a thread count that fits and one that
      // was guessed.
      Runtime runtime = Runtime.getRuntime();
      System.gc();
      long heapBeforeProbe = runtime.totalMemory() - runtime.freeMemory();
      RoutingBoard probeBoard = this.board.deepCopy();
      long perBoardBytes = (runtime.totalMemory() - runtime.freeMemory()) - heapBeforeProbe;
      long freeHeapBytes = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());

      int requestedThreads = job.routerSettings.maxThreads;
      int threadCount = safeThreadCount(requestedThreads, freeHeapBytes, perBoardBytes);
      if (threadCount < requestedThreads) {
        job.logWarning("Racing with " + threadCount + " thread(s) instead of the requested "
            + requestedThreads + ": one board copy measured " + (perBoardBytes / (1024 * 1024))
            + " MB and only " + (freeHeapBytes / (1024 * 1024)) + " MB of heap is free."
            + " Give the JVM a larger -Xmx to race wider.");
      }

      BatchAutorouterThread[] autorouterThreads = new BatchAutorouterThread[threadCount];
      BoardHistory bh = new BoardHistory(job.routerSettings.scoring);

      // Prepare the threads
      for (int threadIndex = 0; threadIndex < threadCount; threadIndex++) {
        // Thread 0 reuses the copy already made for the measurement; copying the board
        // twice to decide how many copies we can afford would be its own small joke.
        PerformanceProfiler.start("board.deepCopy");
        RoutingBoard clonedBoard = (threadIndex == 0) ? probeBoard : this.board.deepCopy();
        PerformanceProfiler.end("board.deepCopy");

        // clone the auto-route item list to avoid concurrent modification
        List<Item> clonedAutorouteItemList = new ArrayList<>(getAutorouteItems(clonedBoard));

        // Each thread gets its OWN generator, seeded from (run, pass, thread). Sharing one
        // Random made the orderings depend on which thread reached it first -- so the race
        // did not reproduce -- and guaranteed nothing about the orderings being different
        // from each other, which is the only reason to run more than one of them.
        shuffle(clonedAutorouteItemList,
            new java.util.Random(orderingSeedFor(racingRunSeed, p_pass_no, threadIndex)));

        autorouterThreads[threadIndex] = new BatchAutorouterThread(clonedBoard, clonedAutorouteItemList, p_pass_no,
            job.routerSettings, this.start_ripup_costs,
            this.trace_pull_tight_accuracy, this.remove_unconnected_vias, true);
        autorouterThreads[threadIndex].setName("Router thread #" + p_pass_no + "." + ThreadIndexToLetter(threadIndex));
        autorouterThreads[threadIndex].setDaemon(true);
        autorouterThreads[threadIndex].setPriority(Thread.MIN_PRIORITY);
      }

      // Update the board on the GUI only based on the first thread
      autorouterThreads[0].addBoardUpdatedEventListener(new BoardUpdatedEventListener() {
        @Override
        public void onBoardUpdatedEvent(BoardUpdatedEvent event) {
          air_line = autorouterThreads[0].latest_air_line;
          fireBoardUpdatedEvent(event.getBoardStatistics(), event.getRouterCounters(), event.getBoard());
        }
      });

      // Start the threads
      for (int threadIndex = 0; threadIndex < threadCount; threadIndex++) {
        // start the thread
        autorouterThreads[threadIndex].start();
      }

      // Wait for the threads to finish -- ACTUALLY finish.
      //
      // This used to be a single join(TIME_LIMIT_TO_PREVENT_ENDLESS_LOOP), and that
      // constant is 1000 milliseconds while a pass takes seconds. The wait therefore
      // always expired, and everything below it -- bh.add(), get_statistics() -- then read
      // a board whose own thread was still writing it. get_statistics() walks the search
      // tree, which is exactly where the crashes land:
      //   ShapeSearchTree.overlapping_tree_entries_with_clearance
      //   Item.clearance_violations
      //   ShapeTree$Leaf.compareTo
      // all failing on a null leaf object, at a rate that did not vary with worker count
      // because this read happens once per pass regardless of how many workers there are.
      boolean[] threadFinished = new boolean[threadCount];

      // ONE budget for the whole join phase, not one per worker.
      //
      // Each worker used to get its own MAX_RACING_JOIN_ATTEMPTS slices, so with N workers
      // the join could sit here for N x 600 seconds -- long past the job deadline the user
      // set -- and nothing here consulted the parent's stop flag or the stage deadline. A
      // cancel during a pass was simply not noticed until every worker had been waited out.
      //
      // Same defect class as defect 30: work continuing after the thing that should have
      // stopped it already fired.
      long joinDeadlineMs = System.currentTimeMillis()
          + (long) MAX_RACING_JOIN_ATTEMPTS * TIME_LIMIT_TO_PREVENT_ENDLESS_LOOP;
      Long jobDeadlineMs =
          (this.job != null && this.job.timeoutAt != null) ? this.job.timeoutAt.toEpochMilli() : null;
      if (jobDeadlineMs != null) {
        joinDeadlineMs = Math.min(joinDeadlineMs, jobDeadlineMs);
      }

      for (int threadIndex = 0; threadIndex < threadCount; threadIndex++) {
        BatchAutorouterThread autorouterThread = autorouterThreads[threadIndex];

        // Cancellation and the deadline are checked before every worker, and asking the
        // workers to stop is what makes the wait short rather than merely bounded.
        if (this.thread.isStopRequested() || System.currentTimeMillis() >= joinDeadlineMs) {
          for (BatchAutorouterThread other : autorouterThreads) {
            if (other != null && other.isAlive()) {
              other.requestStop();
            }
          }
        }

        try {
          // Bounded: the loop cannot outlive MAX_RACING_JOIN_ATTEMPTS even if the thread
          // never terminates, so a hung worker degrades this pass instead of the process.
          while (autorouterThread.isAlive() && System.currentTimeMillis() < joinDeadlineMs) {
            autorouterThread.join(TIME_LIMIT_TO_PREVENT_ENDLESS_LOOP);
            if (this.thread.isStopRequested()) {
              // EVERY worker, not just the one currently being joined: the others keep
              // routing until the outer loop reaches them, which on a stuck early worker
              // is exactly when cancellation matters most.
              for (BatchAutorouterThread other : autorouterThreads) {
                if (other != null && other.isAlive()) {
                  other.requestStop();
                }
              }
            }
          }
        } catch (InterruptedException e) {
          job.logError("Autorouter thread #" + p_pass_no + "." + ThreadIndexToLetter(threadIndex) + " was interrupted",
              e);
          this.thread.requestStop();
          break;
        }

        if (autorouterThread.isAlive()) {
          // Not a candidate. Reading its board is what this change exists to prevent.
          job.logWarning("Router thread #" + p_pass_no + "." + ThreadIndexToLetter(threadIndex)
              + " did not finish within the racing join budget; its board is left unread"
              + " rather than sampled mid-write.");
          continue;
        }
        threadFinished[threadIndex] = true;

        bh.add(autorouterThread.getBoard());

        // calculate the new board score
        BoardStatistics clonedBoardStatistics = autorouterThread
            .getBoard()
            .get_statistics();
        float clonedBoardScore = clonedBoardStatistics.getNormalizedScore(job.routerSettings.scoring);

        job.logDebug("Router thread #" + p_pass_no + "." + ThreadIndexToLetter(threadIndex) + " finished with score: "
            + FRLogger.formatScore(clonedBoardScore,
                clonedBoardStatistics.connections.incompleteCount,
                clonedBoardStatistics.clearanceViolations.totalCount));

        // Aggregate resource usage
        job.resourceUsage.cpuTimeUsed += autorouterThread.cpuTimeUsed;
        job.resourceUsage.maxMemoryUsed += autorouterThread.maxMemoryUsed;
      }

      // ONE selection rule. This used to rank the threads twice, independently: this
      // loop chose bestThread by score and decided progress from it, while the board
      // actually adopted came from bh.restoreBestBoard(), which ranks by its own ordering
      // and deduplicates by board hash. Nothing forced the two answers to agree, so the
      // router could keep one thread's board and report another thread's counts -- and in
      // a best-of-N search the selection rule IS the algorithm.
      float[] scores = new float[autorouterThreads.length];
      for (int i = 0; i < autorouterThreads.length; i++) {
        if (!threadFinished[i]) {
          // Do not even ask for the statistics of a live board.
          continue;
        }
        scores[i] = autorouterThreads[i]
            .getBoard()
            .get_statistics()
            .getNormalizedScore(job.routerSettings.scoring);
      }
      int winner = bestThreadIndexByScore(scores, threadFinished);
      if (winner < 0) {
        job.logError("The racing pass finished with no threads to choose from.", null);
        this.air_line = null;
        return PassOutcome.ABORTED;
      }
      BatchAutorouterThread bestThread = autorouterThreads[winner];

      job.logInfo("Racing pass #" + p_pass_no + ": thread "
          + ThreadIndexToLetter(winner) + " won with score "
          + FRLogger.formatScore(scores[winner],
              bestThread.getBoard().get_statistics().connections.incompleteCount,
              bestThread.getBoard().get_statistics().clearanceViolations.totalCount)
          + " (" + autorouterThreads.length + " threads raced).");

      // The board we keep and the counts we report now come from the same thread.
      this.board = bestThread.getBoard();
      bh.clear();

      // Check if we made any progress
      boolean anyProgress = bestThread.getRoutedCount() > 0 || bestThread.getFailedCount() > 0;

      // We are done with this pass
      this.air_line = null;
      return anyProgress ? PassOutcome.PROGRESS : PassOutcome.NO_PROGRESS;
    } catch (Exception e) {
      // ABORTED, not "no progress". Threads may have mutated their board copies before
      // the throw, so this is the wreckage of a pass rather than a pass that finished
      // with nothing left to do -- the defect-17 conflation, still live here because this
      // path was dead code when that was fixed everywhere else.
      job.logError("The racing pass was ended by an exception; treating it as aborted "
          + "rather than as a completed pass with no progress.", e);
      this.air_line = null;
      return PassOutcome.ABORTED;
    }
  }

  /**
   * Auto-routes one ripup pass of all items of the board. Returns false, if the
   * board is already completely routed.
   */
  /**
   * Copies the running tallies of a pass into the counters object published to
   * listeners. Was written out longhand at every publish point, which made the
   * publish sites hard to tell apart from the routing logic around them.
   */
  private void refreshCounters(RouterCounters counters, int p_pass_no, int items_to_go_count,
      int skipped, int ripped_item_count, int not_routed, int routed) {
    counters.passCount = p_pass_no;
    counters.queuedToBeRoutedCount = items_to_go_count;
    counters.skippedCount = skipped;
    counters.rippedCount = ripped_item_count;
    counters.failedToBeRoutedCount = not_routed;
    counters.routedCount = routed;
    counters.incompleteCount = calculateIncompleteCount(board);
  }

  /** Per-net breakdown of what is still unconnected. Diagnostics only. */
  private void logIncompleteDetails(int p_pass_no, DesignRulesChecker drc, int items_to_go_count) {
    job.logDebug(() -> "Pass #" + p_pass_no + ": " + drc.getIncompleteCount() + " incompletes across "
        + items_to_go_count + " items to route");
    for (int netNo = 1; netNo <= board.rules.nets.max_net_no(); netNo++) {
      int netIncompletes = drc.getIncompleteCount(netNo);
      if (netIncompletes > 0) {
        Net net = board.rules.nets.get(netNo);
        String netName = (net != null) ? net.name : "net#" + netNo;
        job.logDebug(() -> "  Net '" + netName + "' has " + netIncompletes + " incomplete(s)");
      }
    }
  }

  private PassOutcome autoroute_pass(int p_pass_no) {
    long passStartTime = System.currentTimeMillis();
    try {
      List<Item> autoroute_item_list = getAutorouteItems(this.board);

      // If there are no items to route, we're done
      if (autoroute_item_list.isEmpty()) {
        this.air_line = null;
        return PassOutcome.NO_PROGRESS;
      }

      int items_to_go_count = autoroute_item_list.size();
      int ripped_item_count = 0;
      int not_routed = 0;
      int routed = 0;
      int skipped = 0;
      BoardStatistics stats = board.get_statistics();
      RouterCounters routerCounters = new RouterCounters();
      routerCounters.phase = "autoroute";
      routerCounters.passCount = p_pass_no;
      routerCounters.queuedToBeRoutedCount = items_to_go_count;
      routerCounters.skippedCount = skipped;
      routerCounters.rippedCount = ripped_item_count;
      routerCounters.failedToBeRoutedCount = not_routed;
      routerCounters.routedCount = routed;
      DesignRulesChecker tempDrc = new DesignRulesChecker(board, null);
      tempDrc.calculateAllIncompletes();
      routerCounters.incompleteCount = tempDrc.getIncompleteCount();

      // Log incomplete details for debugging
      if (routerCounters.incompleteCount > 0) {
        logIncompleteDetails(p_pass_no, tempDrc, items_to_go_count);
      }

      this.fireBoardUpdatedEvent(stats, routerCounters, this.board);

      // Sort items by airline distance (shortest first) for deterministic routing
      // This prioritizes local connections which typically route faster
      // NOTE: Disabled in v2.3 because it negatively impacts convergence compared to
      // v1.9 (natural order)
      // autoroute_item_list.sort(Comparator.comparingDouble(this::calculateItemDistance));

      // Let's go through all items to route
      for (Item curr_item : autoroute_item_list) {
        // ROLE-AWARE, and it must stay that way -- see routingShouldStop().
        //
        // This method runs for TWO callers. For the main routing stage an auto-router stop
        // means stop. For the optimiser's re-route it must NOT, because that state is the
        // signal that routing has finished and the optimiser now owns the board. Using the
        // raw is_stop_auto_router_requested() here made the optimiser queue ~30 items and
        // route zero of them, forever.
        if (routingShouldStop()) {
          break;
        }

        // Let's go through all nets of this item
        for (int i = 0; i < curr_item.net_count(); i++) {
          // The same check again, one level in -- and THIS is the one that actually
          // disabled the optimiser. Fixing only the outer loop looked like a fix and
          // changed nothing measurable, because control broke out here instead.
          if (routingShouldStop()) {
            break;
          }

          if (this.settings.maxItems != null && this.settings.maxItems > 0 && this.totalItemsRouted >= this.settings.maxItems) {
            job.logInfo("Max items limit reached (" + this.settings.maxItems + "). Stopping auto-router.");
            // Call requestStop() (sets ALL) instead of request_stop_auto_router() (sets
            // AUTO_ROUTER_ONLY) so the optimization stage is also skipped.  maxItems is a
            // debugging/test ceiling meant to bound the entire routing job; running the
            // optimizer on a deliberately-incomplete board is not useful and prevents the
            // process from terminating promptly.
            this.thread.requestStop();
            break;
          }
          this.totalItemsRouted++;

          // We visually mark the area of the board, which is changed by the auto-router
          board.start_marking_changed_area();

          // Do the auto-routing step for this item (typically PolylineTrace or Pin)
          // Use a fresh set per item to mirror v1.9 behavior and avoid cross-item side effects.
          SortedSet<Item> ripped_item_list = new TreeSet<>();
          Map<Item, Integer> ripped_item_costs = new LinkedHashMap<>();
          int netItemsBefore = board.get_connectable_items(curr_item.get_net_no(i)).size();
          PerformanceProfiler.start("autoroute_item");
          var autorouterResult = autoroute_item(curr_item, curr_item.get_net_no(i), ripped_item_list, ripped_item_costs, p_pass_no);
          PerformanceProfiler.end("autoroute_item");
          if (!ripped_item_list.isEmpty()) {
            for (Item rippedItem : ripped_item_list) {
              StringBuilder rippedNets = new StringBuilder();
              for (int netIx = 0; netIx < rippedItem.net_count(); netIx++) {
                if (netIx > 0) {
                  rippedNets.append('|');
                }
                rippedNets.append(rippedItem.get_net_no(netIx));
              }
              int ripupCost = ripped_item_costs.getOrDefault(rippedItem, -1);
              final int sourceNetIndex = i;
              FRLogger.trace(
                  "BatchAutorouter.autoroute_pass",
                  "compare_trace_ripped_item",
                  () -> "source_item=" + curr_item.get_id_no()
                      + ", source_net=" + curr_item.get_net_no(sourceNetIndex)
                      + ", ripped_id=" + rippedItem.get_id_no()
                      + ", ripped_type=" + rippedItem.getClass().getSimpleName()
                      + ", ripped_net_count=" + rippedItem.net_count()
                      + ", ripped_nets=" + rippedNets
                      + ", ripup_cost=" + ripupCost,
                  () -> "Net #" + curr_item.get_net_no(sourceNetIndex) + ",Item #" + curr_item.get_id_no(),
                  () -> getImpactedPoints(rippedItem));
            }
          }
          // The most expensive logging-only work in the tree: a full board DRC
          // recomputation, per routed item, per pass, solely to build this message.
          //
          // It used to sit behind if (FRLogger.isTraceEnabled()) -- twice, the inner
          // check being redundant with the outer. That guard had to stay while the
          // message was an already-built String, because a guard is the only thing that
          // defers STATEMENTS; a parameterised "{}" message defers toString() and would
          // not have helped here at all.
          //
          // The whole computation now lives inside the message supplier, which is
          // strictly better than the guard it replaces. Nothing runs unless something
          // consumes the message, AND the debugger still reaches this breakpoint -- the
          // guard suppressed DebugControl.check() along with the DRC, which is the defect
          // F1 exists to fix. When single-step IS active the DRC runs per item, which is
          // what a person who asked to step through the router is asking for.
          final int netIndex = i;
          final int netItemsBeforeRouting = netItemsBefore;
          final int rippedCount = ripped_item_list.size();
          FRLogger.trace(
              "BatchAutorouter.autoroute_pass",
              "compare_trace_route_item",
              () -> {
                // Recompute the NET this move was for, not the entire board.
                //
                // This built a throwaway DesignRulesChecker and called
                // calculateAllIncompletes() -- which walks every item on the board and
                // rebuilds the incomplete set for every net -- once per routed item. For a
                // message about ONE item on ONE net. That is O(items) work per item, and
                // the per-net API that would have avoided it was unreachable from a fresh
                // checker: recalculateNetIncompletes() finds net_incompletes == null and
                // falls straight back to the full sweep. The source even calls it a
                // catch-22.
                //
                // The escape is that the pass ALREADY built a checker a few lines up, for
                // routerCounters.incompleteCount, and its array is populated -- so the
                // per-net path works on it. One net instead of the board.
                //
                // Semantics, stated rather than glossed: the per-net figure is exact and
                // current for the net just routed, which is what this message is about.
                // The total is now "as at pass start, updated for the nets touched since"
                // rather than a fresh whole-board count. For a per-item diagnostic that is
                // the more useful of the two readings, and it is the one that does not cost
                // a board sweep per item.
                int netNo = curr_item.get_net_no(netIndex);
                tempDrc.recalculateNetIncompletes(netNo);
                int tempNetIncomp = tempDrc.getIncompleteCount(netNo);
                int tempIncomp = tempDrc.getIncompleteCount();
                int netItemsAfter = board.get_connectable_items(netNo).size();
                int maxItemId = board.communication.id_no_generator.max_generated_no();
                return "Routing " + curr_item.getClass().getSimpleName() + " -> result=" + autorouterResult.state
                    + ", details=" + autorouterResult.details
                    + ", incompletes=" + tempIncomp + ", netIncomplete=" + tempNetIncomp
                    + ", ripped=" + rippedCount + ", netItems="
                    + netItemsBeforeRouting + "->" + netItemsAfter
                    + ", maxItemId=" + maxItemId;
              },
              () -> "Net #" + curr_item.get_net_no(netIndex) + ",Item #" + curr_item.get_id_no() + ",Type="
                  + curr_item.getClass().getSimpleName(),
              () -> getImpactedPoints(curr_item));


          if (autorouterResult.state == AutorouteAttemptState.ROUTED) {
            // The item was successfully routed
            ++routed;
          } else if ((autorouterResult.state == AutorouteAttemptState.ALREADY_CONNECTED)
              || (autorouterResult.state == AutorouteAttemptState.NO_UNCONNECTED_NETS)
              || (autorouterResult.state == AutorouteAttemptState.CONNECTED_TO_PLANE)) {
            // The item doesn't need to be routed
            ++skipped;
          } else {
            Net net = board.rules.nets.get(curr_item.get_net_no(i));
            String netName = (net != null) ? net.name : "net#" + curr_item.get_net_no(i);

            // Record the failure
            board.failureLog.recordFailure(curr_item, p_pass_no, autorouterResult.state, autorouterResult.details);

            job.logDebug(() -> "Autorouter " + autorouterResult.details);
            // Log details when we're down to last few items or item has many failures
            int failureCount = board.failureLog.getFailureCount(curr_item);
            if (items_to_go_count <= 5 || failureCount >= 3) {
              job.logDebug("Pass #" + p_pass_no + ": Failed to route " + curr_item.getClass().getSimpleName()
                  + " on net '" + netName + "' (" + items_to_go_count + " items remaining, "
                  + failureCount + " failures). State: " + autorouterResult.state);
            }
            ++not_routed;
          }
          --items_to_go_count;
          ripped_item_count += ripped_item_list.size();

          if (shouldFireBoardUpdate()) {
            BoardStatistics boardStatistics = board.get_statistics();
            refreshCounters(routerCounters, p_pass_no, items_to_go_count, skipped,
                ripped_item_count, not_routed, routed);
            this.fireBoardUpdatedEvent(boardStatistics, routerCounters, this.board);
          }
        }
      }

      int incompletesBefore = calculateIncompleteCount(board);
      FRLogger.trace(
          "BatchAutorouter.autoroute_pass",
          "compare_trace_remove_tails",
          () -> "Incompletes before remove_tails=" + incompletesBefore,
          () -> "Autorouter pass #" + p_pass_no,
          () -> Point.EMPTY);

      if (this.remove_unconnected_vias) {
        remove_tails(Item.StopConnectionOption.NONE);
      } else {
        remove_tails(Item.StopConnectionOption.FANOUT_VIA);
      }

      int incompletesAfter = calculateIncompleteCount(board);
      FRLogger.trace(
          "BatchAutorouter.autoroute_pass",
          "compare_trace_remove_tails",
          () -> "Incompletes after remove_tails=" + incompletesAfter,
          () -> "Autorouter pass #" + p_pass_no,
          () -> Point.EMPTY);

      // Fire final update for this pass
      BoardStatistics boardStatistics = board.get_statistics();
      refreshCounters(routerCounters, p_pass_no, items_to_go_count, skipped,
          ripped_item_count, not_routed, routed);
      this.fireBoardUpdatedEvent(boardStatistics, routerCounters, this.board);

      long passDuration = System.currentTimeMillis() - passStartTime;
      int currentRipupCost = this.start_ripup_costs * p_pass_no;
      PerformanceProfiler.recordPass(p_pass_no, routerCounters.incompleteCount, passDuration, currentRipupCost);

      // We are done with this pass
      this.air_line = null;
      return (routed > 0 || not_routed > 0) ? PassOutcome.PROGRESS : PassOutcome.NO_PROGRESS;
    } catch (Exception e) {
      job.logError("Something went wrong during the auto-routing", e);
      this.air_line = null;
      return PassOutcome.ABORTED;
    }
  }

  @Override
  public String getId() {
    return "freerouting-router";
  }

  @Override
  public String getName() {
    return "Freerouting Auto-router";
  }

  @Override
  public String getVersion() {
    return "1.0";
  }

  @Override
  public String getDescription() {
    return "Freerouting Auto-router v1.0";
  }

  /**
   * Builds a human-readable summary of all unrouted connections on the current board,
   * grouped by net. For each unrouted connection the component and pin names of both
   * endpoints are listed so that the user can identify exactly which connections are
   * missing and address them in their design.
   *
   * <p>Example output:
   * <pre>
   *   Net 'GND' (1 unrouted connection):
   *     - J2-A1  ->  U1-1
   *   Net '/MIPI_CSI_D0_N' (1 unrouted connection):
   *     - J2-A2  ->  U1-2
   * </pre>
   *
   * @return a formatted, multi-line string describing every unrouted airline
   */

  @Override
  public NamedAlgorithmType getType() {
    return NamedAlgorithmType.ROUTER;
  }

  /**
   * Returns the initial number of unrouted nets at the start of the routing
   * session.
   */
  public int getInitialUnroutedCount() {
    return this.initialUnroutedCount;
  }

  /**
   * Returns the time when the routing session started.
   */
  public Instant getSessionStartTime() {
    return this.sessionStartTime;
  }

  /**
   * Autoroutes ripup passes until the board is completed or the autorouter is
   * stopped by the user. Returns true if the board is completed.
   */
  public boolean runBatchLoop() {
    boolean anyRoutable = false;
    for (int i = 0; i < this.settings.getLayerCount(); i++) {
      if (this.settings.get_layer_active(i) && this.board.layer_structure.arr[i].is_signal) {
        anyRoutable = true;
        break;
      }
    }
    if (!anyRoutable) {
      FRLogger.warn("Cannot start autorouter: all layers are disabled.");
      this.fireTaskStateChangedEvent(new TaskStateChangedEvent(this, TaskState.CANCELLED, 0, this.board.get_hash()));
      throw new IllegalArgumentException("Cannot start autorouter: all layers are disabled.");
    }

    this.fireTaskStateChangedEvent(new TaskStateChangedEvent(this, TaskState.STARTED, 0, this.board.get_hash()));

    // Capture initial state for session summary
    this.sessionStartTime = Instant.now();
    this.initialUnroutedCount = calculateIncompleteCount(this.board);

    boolean continueAutorouting = true;
    BoardHistory bh = new BoardHistory(job.routerSettings.scoring);

    // Record configuration for profiler
    if (this.settings.getLayerCount() > 0) {
      int layerCount = this.settings.getLayerCount();
      double[] prefCosts = new double[layerCount];
      double[] againstCosts = new double[layerCount];
      for (int i = 0; i < layerCount; i++) {
        prefCosts[i] = this.settings.get_preferred_direction_trace_costs(i);
        againstCosts[i] = this.settings.get_against_preferred_direction_trace_costs(i);
      }
      PerformanceProfiler.recordConfiguration(
          this.settings.get_via_costs(),
          this.settings.get_plane_via_costs(),
          prefCosts,
          againstCosts);
    }

    job.logDebug(() -> "Checking fanout pre-pass. settings.fanout.enabled=" + this.settings.isFanoutEnabled() + ", smd_pins=" + this.board.get_smd_pins().size());
    // Run SMD fanout pre-pass when the board has SMD pins and fanout is enabled
    if (this.settings.isFanoutEnabled()) {
      if (this.board.get_smd_pins().isEmpty()) {
        job.logInfo("Fanout stage is enabled but skipped because the board has no SMD pins.");
      } else {
        float fanoutCpuSecondsStart = sampleCurrentThreadCpuSeconds();
        float fanoutAllocatedMbStart = sampleCurrentThreadAllocatedMb();
        float fanoutPeakHeapMbAtStart = sampleHeapUsageMb();
        final float[] fanoutPeakHeapMbObserved = new float[] { fanoutPeakHeapMbAtStart };
        // Count pins that actually need fanout. BatchFanout only processes SMD pins that
        // belong to a net, so exclude netless pins from the total. Among net-connected
        // pins, count those that are already fully connected (empty unconnected set).
         int netConnectedSmdPins = 0;
         int alreadyConnectedAtStart = 0;
         for (app.freerouting.board.Pin pin : this.board.get_smd_pins()) {
           if (pin.net_count() > 0) {
             netConnectedSmdPins++;
             if (pin.get_unconnected_set(pin.get_net_no(0)).isEmpty()) {
               alreadyConnectedAtStart++;
             }
           }
         }
         int pinsToFanout = netConnectedSmdPins - alreadyConnectedAtStart;
         job.logInfo("Fanout stage started on board '" + this.board.get_hash() + "' with "
             + pinsToFanout + " of " + this.board.get_smd_pins().size() + " SMD pins needing fanout ("
             + alreadyConnectedAtStart + " already connected, "
             + (this.board.get_smd_pins().size() - netConnectedSmdPins) + " netless).");
        // Hand fanout the job's deadline so it lives inside the same envelope every other
        // stage does; without it the stage had no clock of its own by default.
        Long fanoutJobDeadlineMs =
            (this.job != null && this.job.timeoutAt != null) ? this.job.timeoutAt.toEpochMilli() : null;
        BatchFanout.FanoutRunSummary fanoutSummary = BatchFanout.fanout_board(this.board, this.settings, this.thread,
            status -> {
          fanoutPeakHeapMbObserved[0] = Math.max(fanoutPeakHeapMbObserved[0], sampleHeapUsageMb());
          RouterCounters fanoutCounters = new RouterCounters();
          fanoutCounters.phase = "fanout";
          fanoutCounters.passCount = status.passNo();
          fanoutCounters.queuedToBeRoutedCount = status.pinsToGo();
          fanoutCounters.routedCount = status.routedCount();
          fanoutCounters.skippedCount = 0;
          fanoutCounters.rippedCount = 0;
          fanoutCounters.failedToBeRoutedCount = status.notRoutedCount() + status.insertErrorCount();
          fanoutCounters.incompleteCount = status.boardStatistics().connections.incompleteCount;
          fanoutCounters.fanoutExtraViasCount = status.extraViasThisPass();
          this.fireBoardUpdatedEvent(status.boardStatistics(), fanoutCounters, this.board);

          if (status.passCompleted()) {
            String boardHash = this.board.get_hash();
            String fanoutMessage = String.format(java.util.Locale.US,
                "Fanout pass #%d on board '%s' completed in %.2f seconds with %d SMD pin%s fanouted, %d not routed, %d insert error%s, +%d extra via%s (%d SMD pin%s still to check in pass, ripup costs=%d).",
                status.passNo(), boardHash,
                status.passDurationMillis() / 1000.0,
                status.routedCount(), status.routedCount() == 1 ? "" : "s",
                status.notRoutedCount(),
                status.insertErrorCount(), status.insertErrorCount() == 1 ? "" : "s",
                status.extraViasThisPass(), status.extraViasThisPass() == 1 ? "" : "s",
                status.pinsToGo(), status.pinsToGo() == 1 ? "" : "s",
                status.ripupCosts());
            job.logInfo(fanoutMessage);
          }
        }, fanoutJobDeadlineMs);
        this.fanoutTimedOut = fanoutSummary.isTimedOut();

        float fanoutCpuSecondsEnd = sampleCurrentThreadCpuSeconds();
        float fanoutAllocatedMbEnd = sampleCurrentThreadAllocatedMb();

        float fanoutCpuSecondsUsed;
        if (fanoutCpuSecondsStart >= 0f && fanoutCpuSecondsEnd >= fanoutCpuSecondsStart) {
          fanoutCpuSecondsUsed = fanoutCpuSecondsEnd - fanoutCpuSecondsStart;
        } else {
          fanoutCpuSecondsUsed = Math.max(0f, getCpuSecondsSnapshot(job));
        }

        float fanoutAllocatedMb;
        if (fanoutAllocatedMbStart >= 0f && fanoutAllocatedMbEnd >= fanoutAllocatedMbStart) {
          fanoutAllocatedMb = fanoutAllocatedMbEnd - fanoutAllocatedMbStart;
        } else {
          fanoutAllocatedMb = Math.max(0f, getAllocatedMemoryMbSnapshot(job));
        }

        float fanoutPeakHeapMb = Math.max(fanoutPeakHeapMbObserved[0], sampleHeapUsageMb());
        fanoutPeakHeapMb = Math.max(fanoutPeakHeapMb, getPeakHeapMbSnapshot(job));
        BatchFanout.EscapeStatistics finalEscape = fanoutSummary.escapeStatistics();
        String fanoutCompletionStatus = fanoutSummary.isTimedOut() ? "completed with timeout:"
            : (routingShouldStop() ? "interrupted:" : "completed:");
        String fanoutSummaryMessage = String.format(java.util.Locale.US,
            "Fanout stage %s started with %d total SMD pins, completed in %.2f seconds, escaped pins: %d/%d (%.1f%%), using %.2f total CPU seconds, %.2f GB total allocated, and %.1f MB peak heap usage.",
            fanoutCompletionStatus,
            finalEscape.totalSmdPins(),
            fanoutSummary.totalDurationMillis() / 1000.0,
            finalEscape.escapedCount(),
            finalEscape.totalSmdPins(),
            finalEscape.escapedPercentage(),
            fanoutCpuSecondsUsed,
            fanoutAllocatedMb / 1024.0f,
            fanoutPeakHeapMb);
        job.logInfo(fanoutSummaryMessage);
      }
    }

    int currentUnrouted = calculateIncompleteCount(this.board);
    boolean isRouterEnabled = this.settings.getRunRouter() && (this.settings.maxPasses == null || this.settings.maxPasses >= 0);
    if (isRouterEnabled) {
      job.logInfo("Auto-routing stage started on board '" + this.board.get_hash() + "' for "
          + currentUnrouted + " unrouted item" + (currentUnrouted == 1 ? "" : "s") + ".");
    }
    continueAutorouting = isRouterEnabled;

    // The auto-routing stage had no deadline of its own. It reacted to the job only AFTER
    // the outer watchdog had already flipped it to TIMED_OUT -- which is the amputation, not
    // an orderly finish. Fanout and the optimizer both stop just inside the job deadline and
    // hand back a complete pass; this stage now does the same, by the same rule.
    Long autorouteJobDeadlineMs =
        (this.job != null && this.job.timeoutAt != null) ? this.job.timeoutAt.toEpochMilli() : null;
    Long autorouteDeadlineMs =
        StageDeadline.compute(null, autorouteJobDeadlineMs, System.currentTimeMillis());
    int currentPass = 1;
    int consecutiveNoImprovementPasses = 0;
    boolean fanoutRecoveryApplied = false;
    float lastBestScore = Float.NEGATIVE_INFINITY;   // score at last board-restore or improvement
    float globalBestScore = Float.NEGATIVE_INFINITY; // best score seen across all passes
    int passOfBestScore = 0;                         // pass where globalBestScore was achieved
    int incompleteCountAtBestScore = 0;              // incomplete count when globalBestScore was recorded
    while (continueAutorouting && !routingShouldStop()) {
      if (job != null && job.state == RoutingJobState.TIMED_OUT) {
        this.thread.request_stop_auto_router();
      }

      // Checked before starting a pass rather than during one: a pass that has begun is
      // allowed to finish, so the board handed on is whole. Stopping here means the stage
      // ends of its own accord with time still on the job clock, instead of being cut off.
      if (autorouteDeadlineMs != null && System.currentTimeMillis() >= autorouteDeadlineMs) {
        if (job != null) {
          job.logInfo("Auto-routing stage stopping before pass #" + currentPass
              + ": the job's remaining time is spent.");
        }
        thread.request_stop_auto_router();
        break;
      }

      String currentBoardHash = this.board.get_hash();


      if (this.settings.maxPasses != null && this.settings.maxPasses > 0 && currentPass > this.settings.maxPasses) {
        thread.request_stop_auto_router();
        break;
      }

      if (job != null) {
        job.setCurrentPass(currentPass);
      }

      this.fireTaskStateChangedEvent(
          new TaskStateChangedEvent(this, TaskState.RUNNING, currentPass, currentBoardHash));

      float boardScoreBefore = new BoardStatistics(this.board).getNormalizedScore(job.routerSettings.scoring);
      bh.add(this.board);

      FRLogger.traceEntry("BatchAutorouter.autoroute_pass #" + currentPass + " on board '" + currentBoardHash + "'");

      if (Boolean.TRUE.equals(job.routerSettings.racingEnabled)
          && job.routerSettings.maxThreads <= 1 && !racingMismatchWarned) {
        racingMismatchWarned = true;
        // Conflicting flags never resolve silently: racing was requested but the router
        // width is 1, and racing only exists above width 1. Racing loses, and this line
        // is why.
        job.logWarning("racing_enabled is set but --router.max_threads is 1 -- racing "
            + "needs a router width above 1. Routing proceeds single-threaded; raise "
            + "--router.max_threads or drop racing_enabled to silence this.");
      }
      PassOutcome passOutcome =
          shouldRace(Boolean.TRUE.equals(job.routerSettings.racingEnabled),
              job.routerSettings.maxThreads)
              ? autoroute_pass_multi_thread(currentPass)
              : autoroute_pass(currentPass);
      continueAutorouting = passOutcome.shouldContinue();
      if (passOutcome.isAbnormal()) {
        this.endedAbnormally = true;
        // The pass did not finish -- it was cut short by an exception. Say so
        // explicitly: a partially routed board reported as a completed one is how a
        // defect gets mistaken for a hard board.
        job.logError("Auto-routing pass #" + currentPass
            + " was aborted by an error; the board is only partially routed.", null);
      }

      BoardStatistics boardStatisticsAfter = new BoardStatistics(this.board);
      float boardScoreAfter = boardStatisticsAfter.getNormalizedScore(job.routerSettings.scoring);

      if ((bh.size() >= STOP_AT_PASS_MINIMUM) || (routingShouldStop())) {
        if (((currentPass % STOP_AT_PASS_MODULO == 0) && (currentPass >= STOP_AT_PASS_MINIMUM))
            || (routingShouldStop())) {
          // Check if the score improved compared to the previous passes, restore a
          // previous board if not. Use strict ">" so that equally-scored boards do NOT
          // trigger a restore — if every board has the same (possibly zero) score the old
          // ">=" test would restore on every check cycle, growing the history unboundedly
          // and never stopping.
          if (bh.getMaxScore() > boardScoreAfter) {
            var boardToRestore = bh.restoreBoard(MAXIMUM_TRIES_ON_THE_SAME_BOARD);
            if (boardToRestore == null) {
              job.logInfo("The router was not able to improve the board, stopping the auto-router.");
              thread.request_stop_auto_router();
              break;
            }

            int boardToRestoreRank = bh.getRank(boardToRestore);

            if (boardToRestoreRank > BOARD_RANK_LIMIT) {
              thread.request_stop_auto_router();
              break;
            }

            this.board = boardToRestore;
            var boardStatistics = this.board.get_statistics();
            // Reset pass-local stagnation counter when restoring a previous board state
            consecutiveNoImprovementPasses = 0;
            boardStatisticsAfter = boardStatistics;
            boardScoreAfter = boardStatisticsAfter.getNormalizedScore(job.routerSettings.scoring);
            lastBestScore = boardScoreAfter;
            currentBoardHash = this.board.get_hash();
            // Reset the same-hash set after a board restore: the restored board will be
            // routed with a higher ripup budget on subsequent passes, so earlier routing
            // decisions from the same hash may no longer apply.
            job.logDebug("Restoring an earlier board that has the score of "
                    + FRLogger.formatScore(boardScoreAfter,
                        boardStatisticsAfter.connections.incompleteCount,
                        boardStatisticsAfter.clearanceViolations.totalCount)
                    + ".");
          }
        }
      }
      double autorouter_pass_duration = FRLogger
          .traceExit("BatchAutorouter.autoroute_pass #" + currentPass + " on board '" + currentBoardHash + "'");

      String passCompletedMessage = String.format(java.util.Locale.US,
          "Auto-routing pass #%d on board '%s' was completed in %.2f seconds with the score of %s",
          currentPass, currentBoardHash, autorouter_pass_duration,
          FRLogger.formatScore(boardScoreAfter, boardStatisticsAfter.connections.incompleteCount,
              boardStatisticsAfter.clearanceViolations.totalCount));
      if (job.startedAt != null) {
        // The same elapsed/limit pair the GUI shows. A headless user watching a terminal
        // has exactly the same question -- is this alive, and how much longer.
        long elapsedSeconds =
            java.time.Duration.between(job.startedAt, java.time.Instant.now()).getSeconds();
        passCompletedMessage += " ["
            + app.freerouting.core.RoutingProgress.format(elapsedSeconds,
                app.freerouting.util.TextManager.parseTimespanString(
                    job.routerSettings.jobTimeoutString))
            + "]";
      }
      if (job.resourceUsage.cpuTimeUsed > 0) {
        passCompletedMessage += String.format(java.util.Locale.US, ", using %.2f CPU seconds and the job allocated %.2f GB of memory so far.",
            job.resourceUsage.cpuTimeUsed, job.resourceUsage.maxMemoryUsed / 1024.0f);
      } else {
        passCompletedMessage += ".";
      }
      if (!isOptimizerAutorouter) {
        job.logInfo(passCompletedMessage);
      }

      DesignRulesChecker tempDrc = new DesignRulesChecker(this.board, null);
      tempDrc.calculateAllIncompletes();
      StringBuilder perNetBreakdown = new StringBuilder();
      for (int netNo = 1; netNo <= this.board.rules.nets.max_net_no(); netNo++) {
        int netIncomplete = tempDrc.getIncompleteCount(netNo);
        if (netIncomplete > 0) {
          final int reportedPass = currentPass;
          final int reportedNet = netNo;
          final int reportedIncomplete = netIncomplete;
          FRLogger.trace(
              "BatchAutorouter.autoroute_pass",
              "compare_unrouted_net",
              () -> "pass=" + reportedPass + ", net=" + reportedNet + ", incomplete=" + reportedIncomplete,
              () -> "Net #" + reportedNet,
              () -> Point.EMPTY);
          if (!perNetBreakdown.isEmpty()) {
            perNetBreakdown.append(',');
          }
          perNetBreakdown.append(netNo).append('=').append(netIncomplete);
        }
      }
      final int summaryPass = currentPass;
      final var breakdown = perNetBreakdown;
      FRLogger.trace("BatchAutorouter.autoroute_pass", "compare_unrouted_breakdown",
          () -> "pass=" + summaryPass
              + ", total=" + tempDrc.getIncompleteCount()
              + ", breakdown=" + breakdown,
          () -> "",
          () -> Point.EMPTY);

      if (this.settings.save_intermediate_stages) {
        fireBoardSnapshotEvent(this.board);
      }

      // Stagnation detection: abort when the normalized score hasn't improved by
      // at least STAGNATION_SCORE_THRESHOLD over STAGNATION_PASS_LIMIT consecutive
      // passes. This now fires whenever the router is still actively running
      // (continueAutorouting == true) after the mandatory minimum passes, regardless
      // of incompleteCount.  The old condition guarded on incompleteCount > 0, which
      // caused the check to be bypassed — and the counter to be silently reset — for
      // boards where DRC shows 0 incompletes but the router keeps cycling (e.g. when
      // plane-net false-work items kept autoroute_pass() returning true).  If the
      // board is genuinely done (continueAutorouting == false) the while-loop exits
      // naturally and we never reach this block.
      if (currentPass >= STOP_AT_PASS_MINIMUM && continueAutorouting) {

        // --- Pass-local counter (resets after board restores) ---
        if (boardScoreAfter > lastBestScore + STAGNATION_SCORE_THRESHOLD) {
          consecutiveNoImprovementPasses = 0;
          lastBestScore = boardScoreAfter;
        } else {
          consecutiveNoImprovementPasses++;

          // One-time recovery for fanout-enabled jobs: aggressively remove tails, including
          // fanout vias, when score plateaus with remaining incompletes. This gives the
          // autorouter a chance to escape local dead-ends introduced by pre-fanout geometry
          // while keeping fanout enabled as the default behavior.
          if (this.settings.isFanoutEnabled()
              && !fanoutRecoveryApplied
              && boardStatisticsAfter.connections.incompleteCount > 0
              && consecutiveNoImprovementPasses >= FANOUT_RECOVERY_STAGNATION_PASSES) {
            int incompletesBeforeRecovery = boardStatisticsAfter.connections.incompleteCount;
            remove_tails(Item.StopConnectionOption.NONE);
            boardStatisticsAfter = new BoardStatistics(this.board);
            boardScoreAfter = boardStatisticsAfter.getNormalizedScore(job.routerSettings.scoring);
            lastBestScore = boardScoreAfter;
            consecutiveNoImprovementPasses = 0;
            fanoutRecoveryApplied = true;
            job.logDebug("Applied one-time fanout recovery cleanup (removed fanout tails/vias). "
                + "Incompletes: " + incompletesBeforeRecovery + " -> "
                + boardStatisticsAfter.connections.incompleteCount + ".");
          }

          if (consecutiveNoImprovementPasses >= STAGNATION_PASS_LIMIT) {
            String report = buildUnroutedConnectionsReport();
            job.logInfo("The router's score (" + FRLogger.defaultFloatFormat.format(boardScoreAfter)
                + ") has not improved by more than " + STAGNATION_SCORE_THRESHOLD
                + " points in the last " + STAGNATION_PASS_LIMIT + " passes ("
                + boardStatisticsAfter.connections.incompleteCount + " item"
                + (boardStatisticsAfter.connections.incompleteCount == 1 ? "" : "s")
                + " still unconnected). Stopping the auto-router.\n"
                + "The following connections could not be routed -- please review your design "
                + "(e.g. check pad clearances, trace width rules, and available routing space):\n"
                + report);
            thread.request_stop_auto_router();
            break;
          }
        }

        // --- Global best tracker (not reset by board restores) ---
        // Stops the router if no pass anywhere has meaningfully improved the score
        // in the last STAGNATION_PASS_LIMIT passes, even across board-restore cycles.
        if (boardScoreAfter > globalBestScore + STAGNATION_SCORE_THRESHOLD) {
          globalBestScore = boardScoreAfter;
          passOfBestScore = currentPass;
          incompleteCountAtBestScore = boardStatisticsAfter.connections.incompleteCount;
        } else if ((currentPass - passOfBestScore) >= STAGNATION_PASS_LIMIT) {
          String report = buildUnroutedConnectionsReport();
          job.logInfo("The router's best score (" + FRLogger.defaultFloatFormat.format(globalBestScore)
              + ") has not improved by more than " + STAGNATION_SCORE_THRESHOLD
              + " points since pass #" + passOfBestScore
              + ". Stopping the auto-router after " + currentPass + " passes ("
              + incompleteCountAtBestScore + " item"
              + (incompleteCountAtBestScore == 1 ? "" : "s")
              + " still unconnected).\n"
              + "The following connections could not be routed -- please review your design "
              + "(e.g. check pad clearances, trace width rules, and available routing space):\n"
              + report);
          thread.request_stop_auto_router();
          break;
        }

      } else if (boardStatisticsAfter.connections.incompleteCount == 0 && boardScoreAfter > STAGNATION_SCORE_THRESHOLD) {
        // Board is fully routed AND has a positive score (genuine success).
        // A fully-routed board with score == 0 (e.g. caused by clearance violations
        // from plane routing) must NOT reset the stagnation counter; it should keep
        // accumulating until the global tracker fires.
        consecutiveNoImprovementPasses = 0;
        lastBestScore = boardScoreAfter;
      }

      // check if there are still unrouted items
      if (continueAutorouting && !routingShouldStop()) {
        currentPass++;
      }
    }

    // Ensure we finish with the best board ever seen during this routing session.
    // When stagnation or the max-pass limit fires, the loop exits with the board from the last
    // completed pass, which may be worse than an earlier pass that was recorded in the history.
    float currentFinalScore = new BoardStatistics(this.board).getNormalizedScore(job.routerSettings.scoring);
    float bestHistoryScore = bh.getMaxScore();
    if (bestHistoryScore > currentFinalScore) {
      RoutingBoard bestBoard = bh.restoreBestBoard();
      if (bestBoard != null) {
        BoardStatistics currentStats = new BoardStatistics(this.board);
        this.board = bestBoard;
        BoardStatistics bestStats = new BoardStatistics(this.board);
        job.logDebug(() -> "The final board state (score "
            + FRLogger.formatScore(currentFinalScore,
                currentStats.connections.incompleteCount,
                currentStats.clearanceViolations.totalCount)
            + ") is worse than the best board seen during routing (score "
            + FRLogger.formatScore(bestStats.getNormalizedScore(job.routerSettings.scoring),
                bestStats.connections.incompleteCount,
                bestStats.clearanceViolations.totalCount)
            + "). Restoring the best board as the final result.");
      }
    }

    job.board = this.board;

    boolean wasRouterRun = this.settings.getRunRouter() && (this.settings.maxPasses == null || this.settings.maxPasses >= 0);
    if (wasRouterRun && !(this.remove_unconnected_vias || continueAutorouting || routingShouldStop())) {
      // clean up the route if the board is completed and if fanout is used.
      remove_tails(Item.StopConnectionOption.NONE);
    }

    bh.clear();

    // Print all profiling results at the end of session
    PerformanceProfiler.printResults();
    PerformanceProfiler.reset();

    if (!routingShouldStop()) {
      this.fireTaskStateChangedEvent(new TaskStateChangedEvent(this, TaskState.FINISHED,
          currentPass, this.board.get_hash()));
    } else {
      // Distinguish between a user-requested cancellation and a job timeout so that
      // API consumers can tell the two apart via TaskStateChangedEvent.
      boolean isTimedOut = (job != null) && (job.state == RoutingJobState.TIMED_OUT);
      this.fireTaskStateChangedEvent(new TaskStateChangedEvent(this,
          isTimedOut ? TaskState.TIMED_OUT : TaskState.CANCELLED,
          currentPass, this.board.get_hash()));
    }

    // Role-aware for the same reason: for the optimiser's re-route, an auto-router stop
    // does not mean this run was interrupted.
    return !routingShouldStop();
  }


  /**
   * Names every track on the finished board that is narrower than the board's own minimum.
   *
   * <p>Fanout necks down at pin exits to escape fine-pitch packages, and on boards where the
   * net width equals the board minimum there is no legal narrower width -- so it can leave a
   * track the board declares unmanufacturable. That was silent, and the run reported zero
   * violations while carrying them. Measured on public boards: one wire of ninety-five at
   * 0.225 mm against a 0.30 mm minimum, and thirty-five at 0.1124 against 0.15.
   *
   * <p>Built from the BOARD, not from the routing attempts. The router tries a neckdown,
   * shoves, and usually rips it out again; reporting per attempt produced nineteen warnings
   * for one surviving track, and a warning that is usually wrong is one people stop reading.
   *
   * @return a human-readable report, or null when every track meets the minimum
   */
  public static String buildUnderMinimumWidthReport(app.freerouting.board.BasicBoard board) {
    int min_half_width = board.rules.get_min_trace_half_width();
    java.util.Map<Integer, Integer> narrowest = new java.util.TreeMap<>();
    java.util.Map<Integer, Integer> counts = new java.util.TreeMap<>();
    for (app.freerouting.board.Item item : board.get_items()) {
      if (!(item instanceof Trace trace)) {
        continue;
      }
      int half_width = trace.get_half_width();
      if (half_width >= min_half_width) {
        continue;
      }
      for (int i = 0; i < trace.net_count(); i++) {
        int net_no = trace.get_net_no(i);
        counts.merge(net_no, 1, Integer::sum);
        narrowest.merge(net_no, half_width, Math::min);
      }
    }
    if (counts.isEmpty()) {
      return null;
    }
    int total = counts.values().stream().mapToInt(Integer::intValue).sum();
    StringBuilder sb = new StringBuilder();
    sb.append(total).append(total == 1 ? " track is" : " tracks are")
        .append(" narrower than the board minimum of ").append(min_half_width * 2)
        .append(" (board units). The board may not be manufacturable as routed:");
    for (java.util.Map.Entry<Integer, Integer> e : counts.entrySet()) {
      sb.append(System.lineSeparator())
          .append("  net ").append(e.getKey())
          .append(": ").append(e.getValue()).append(e.getValue() == 1 ? " track" : " tracks")
          .append(", narrowest ").append(narrowest.get(e.getKey()) * 2);
    }
    return sb.toString();
  }

  private String buildUnroutedConnectionsReport() {
    // Shared with the final run report (FinalRunReport.unroutedSection) so the mid-run
    // stagnation log and the file a user keeps are the same text by construction.
    return app.freerouting.core.FinalRunReport.unroutedSection(this.board);
  }

  /** Delegates to the shared formatter; other logging call sites in this class use it. */
  private String describeItem(Item item) {
    return app.freerouting.core.FinalRunReport.describeItem(this.board, item);
  }

  private void remove_tails(Item.StopConnectionOption p_stop_connection_option) {
    board.start_marking_changed_area();
    board.remove_trace_tails(-1, p_stop_connection_option);
    board.opt_changed_area(new int[0], null, this.trace_pull_tight_accuracy, this.trace_cost_arr, this.thread,
        TIME_LIMIT_TO_PREVENT_ENDLESS_LOOP);
  }

  // Tries to route an item on a specific net. Returns true, if the item is
  // routed.
  private AutorouteAttemptResult autoroute_item(Item p_item, int p_route_net_no, SortedSet<Item> p_ripped_item_list,
      Map<Item, Integer> p_ripup_costs, int p_ripup_pass_no) {
    try {
      boolean contains_plane = false;

      // Get the net
      Net route_net = board.rules.nets.get(p_route_net_no);
      if (route_net != null) {
        contains_plane = route_net.contains_plane();
      }

      // Get the current via costs based on auto-router settings
      int curr_via_costs;
      if (contains_plane) {
        curr_via_costs = this.settings.get_plane_via_costs();
      } else {
        curr_via_costs = this.settings.get_via_costs();
      }

      // Get and calculate the auto-router settings based on the board and net we are
      // working on
      AutorouteControl autoroute_control = new AutorouteControl(this.board, p_route_net_no, settings, curr_via_costs,
          this.trace_cost_arr);
      autoroute_control.ripup_allowed = true;
      autoroute_control.ripup_costs = this.start_ripup_costs * p_ripup_pass_no;
      autoroute_control.remove_unconnected_vias = this.remove_unconnected_vias;

      // Check if the item is already routed
      Set<Item> unconnected_set = p_item.get_unconnected_set(p_route_net_no);
      if (unconnected_set.isEmpty()) {
        return new AutorouteAttemptResult(AutorouteAttemptState.NO_UNCONNECTED_NETS);
      }

      Set<Item> connected_set = p_item.get_connected_set(p_route_net_no);
      Set<Item> route_start_set;
      Set<Item> route_dest_set;
      if (contains_plane) {
        for (Item curr_item : connected_set) {
          if (curr_item instanceof ConductionArea) {
            return new AutorouteAttemptResult(AutorouteAttemptState.CONNECTED_TO_PLANE);
          }
        }
      }
      if (contains_plane) {
        route_start_set = connected_set;
        route_dest_set = unconnected_set;
      } else {
        route_start_set = unconnected_set;
        route_dest_set = connected_set;
      }

      // Calculate the shortest distance between the two sets of items
      calc_airline(route_start_set, route_dest_set);

      // Calculate the maximum time for this autoroute pass
      double max_milliseconds = 100000 * Math.pow(2, p_ripup_pass_no - 1);
      max_milliseconds = Math.min(max_milliseconds, Integer.MAX_VALUE);
      TimeLimit time_limit = new TimeLimit((int) max_milliseconds);

      // Initialize the auto-router engine
      AutorouteEngine autoroute_engine = board.init_autoroute(p_route_net_no,
          autoroute_control.trace_clearance_class_no, this.thread, time_limit, this.retain_autoroute_database);

      int maxItemIdBeforeRoute = board.communication.id_no_generator.max_generated_no();

      byte[] strictDrcBoardSnapshot = this.settings.isStrictDrc() ? board.serialize(false) : null;

      // Do the auto-routing between the two sets of items
      AutorouteAttemptResult autoroute_result = autoroute_engine.autoroute_connection(route_start_set, route_dest_set,
          autoroute_control, p_ripped_item_list, p_ripup_costs);

      // Update the changed area of the board
      if (autoroute_result.state == AutorouteAttemptState.ROUTED) {
        int maxItemIdBeforeOpt = board.communication.id_no_generator.max_generated_no();
        if (FRLogger.isTraceEnabled()) {
          FRLogger.trace("compare_trace_opt_changed_area_before net=" + p_route_net_no + ", maxItemId=" + maxItemIdBeforeOpt);
        }
        board.opt_changed_area(new int[0], null, this.trace_pull_tight_accuracy, autoroute_control.trace_costs,
            this.thread, TIME_LIMIT_TO_PREVENT_ENDLESS_LOOP);
        int maxItemIdAfterOpt = board.communication.id_no_generator.max_generated_no();
        if (FRLogger.isTraceEnabled()) {
          FRLogger.trace("compare_trace_opt_changed_area_after net=" + p_route_net_no + ", maxItemId=" + maxItemIdAfterOpt + ", delta=" + (maxItemIdAfterOpt - maxItemIdBeforeOpt));
        }
      }

      if ((autoroute_result.state == AutorouteAttemptState.FAILED
          || autoroute_result.state == AutorouteAttemptState.INSERT_ERROR)
          && this.settings.getNeckWidthUm() > 0) {
        AutorouteAttemptResult necked_result = retryConnectionNecked(p_route_net_no, autoroute_control,
            curr_via_costs, route_start_set, route_dest_set, p_ripped_item_list, p_ripup_costs,
            p_ripup_pass_no, time_limit);
        if (necked_result != null) {
          AutorouteAttemptResult strict_result = applyStrictDrcAfterRoute(p_route_net_no,
              maxItemIdBeforeRoute, strictDrcBoardSnapshot);
          if (strict_result != null) {
            return strict_result;
          }
          return necked_result;
        }
      }

      if (autoroute_result.state == AutorouteAttemptState.ROUTED) {
        AutorouteAttemptResult strict_result = applyStrictDrcAfterRoute(p_route_net_no,
            maxItemIdBeforeRoute, strictDrcBoardSnapshot);
        if (strict_result != null) {
          return strict_result;
        }
      }

      return autoroute_result;
    } catch (Exception e) {
      // Name the item. "Error during routing passes" identifies nothing, and on a board
      // with hundreds of nets an unattributed stack trace cannot be turned into a
      // reproduction. describeItem gives the component-pin form (e.g. J2-A3).
      //
      // Rethrow. By the time this fires, items may already have been ripped or inserted,
      // so the board is half-mutated -- and returning FAILED here presents that as ordinary
      // congestion, letting the pass report PROGRESS, leaving endedAbnormally() false, and
      // allowing the scheduler to optimize and persist the wreckage.
      //
      // autoroute_pass already maps a propagated exception to PassOutcome.ABORTED, so this
      // needs no new attempt state and no audit of AutorouteAttemptState's comparison
      // sites. I previously costed that expensive route and declined it without looking
      // for this one.
      FRLogger.error("Auto-routing of item " + describeItem(p_item) + " (net " + p_route_net_no
          + ") failed with an exception; aborting the pass, because the board may be"
          + " partially mutated and must not be presented as a routing result.", e);
      throw new RuntimeException(
          "Auto-routing of item " + describeItem(p_item) + " failed", e);
    }
  }


  /**
   * Width-necking retry: when a connection failed at its net-class trace width and the
   * neck_width_um setting is enabled, retry it ONCE with every layer's trace half-width
   * clamped to the neck width. Fine-pitch pads whose pitch is below (class width +
   * clearance) are unroutable at class width and fail as generic congestion; the operator
   * supplies a legal manufacturable neck width (e.g. the project's densest net class).
   * Returns the retry result when it routed, else null (keep the original failure).
   */
  private AutorouteAttemptResult retryConnectionNecked(int p_route_net_no,
      AutorouteControl p_original_control, int p_via_costs, Set<Item> p_route_start_set,
      Set<Item> p_route_dest_set, SortedSet<Item> p_ripped_item_list,
      Map<Item, Integer> p_ripup_costs, int p_ripup_pass_no, TimeLimit p_time_limit) {
    int boardResolution = Math.max(1, board.communication.resolution);
    int neck_width = (int) Math.round(app.freerouting.board.Unit.scale(
        this.settings.getNeckWidthUm() * boardResolution, app.freerouting.board.Unit.UM,
        board.communication.unit));
    int neck_half_width = Math.max(1, neck_width / 2);
    boolean narrower_somewhere = false;
    for (int i = 0; i < p_original_control.layer_count; i++) {
      if (p_original_control.layer_active[i]
          && p_original_control.trace_half_width[i] > neck_half_width) {
        narrower_somewhere = true;
        break;
      }
    }
    if (!narrower_somewhere) {
      return null;
    }
    AutorouteControl neck_control = new AutorouteControl(this.board, p_route_net_no, settings,
        p_via_costs, this.trace_cost_arr);
    neck_control.ripup_allowed = true;
    neck_control.ripup_costs = this.start_ripup_costs * p_ripup_pass_no;
    neck_control.remove_unconnected_vias = this.remove_unconnected_vias;
    for (int i = 0; i < neck_control.layer_count; i++) {
      int compensation = neck_control.compensated_trace_half_width[i] - neck_control.trace_half_width[i];
      neck_control.trace_half_width[i] = Math.min(neck_control.trace_half_width[i], neck_half_width);
      neck_control.compensated_trace_half_width[i] = neck_control.trace_half_width[i] + compensation;
    }
    AutorouteEngine neck_engine = board.init_autoroute(p_route_net_no,
        neck_control.trace_clearance_class_no, this.thread, p_time_limit, this.retain_autoroute_database);
    AutorouteAttemptResult neck_result = neck_engine.autoroute_connection(p_route_start_set,
        p_route_dest_set, neck_control, p_ripped_item_list, p_ripup_costs);
    if (neck_result.state != AutorouteAttemptState.ROUTED) {
      return null;
    }
    board.opt_changed_area(new int[0], null, this.trace_pull_tight_accuracy, neck_control.trace_costs,
        this.thread, TIME_LIMIT_TO_PREVENT_ENDLESS_LOOP);
    Net route_net = board.rules.nets.get(p_route_net_no);
    FRLogger.info("Necked retry routed net '"
        + (route_net != null ? route_net.name : "#" + p_route_net_no)
        + "' at " + this.settings.getNeckWidthUm() + " um trace width.");
    return neck_result;
  }

  /**
   * When {@code strict_drc} rejects a routed connection, restore the board snapshot taken
   * before {@link AutorouteEngine#autoroute_connection} so rip-up victims removed during
   * routing are not left torn up.
   */
  private AutorouteAttemptResult applyStrictDrcAfterRoute(int p_route_net_no, int p_max_item_id_before,
      byte[] p_board_snapshot_before_route) {
    if (!this.settings.isStrictDrc()) {
      return null;
    }
    AutorouteAttemptResult rejection = enforceStrictDrc(board, p_route_net_no, p_max_item_id_before);
    if (rejection != null && p_board_snapshot_before_route != null) {
      this.board = (RoutingBoard) BasicBoard.deserialize(p_board_snapshot_before_route);
    }
    return rejection;
  }

  /**
   * Strict-DRC enforcement: if any trace/via inserted by the connection that just routed
   * (item id above {@code p_max_item_id_before}) carries a clearance violation, rip the
   * whole set of new items and report the connection FAILED, so the pass counts it as not
   * routed and later passes (higher ripup costs) retry it. Returns null when the connection
   * is clean and may be kept.
   */
  static AutorouteAttemptResult enforceStrictDrc(app.freerouting.board.RoutingBoard board,
      int p_route_net_no, int p_max_item_id_before) {
    List<Item> new_items = new ArrayList<>();
    boolean has_violation = false;
    for (Item curr_item : board.get_connectable_items(p_route_net_no)) {
      if (curr_item.get_id_no() <= p_max_item_id_before
          || !(curr_item instanceof Trace || curr_item instanceof app.freerouting.board.Via)) {
        continue;
      }
      new_items.add(curr_item);
      if (!has_violation && !curr_item.clearance_violations().isEmpty()) {
        has_violation = true;
      }
    }
    if (!has_violation) {
      return null;
    }
    board.remove_items(new_items);
    return new AutorouteAttemptResult(AutorouteAttemptState.FAILED,
        "strict_drc: connection ripped because " + new_items.size()
            + " new item(s) included clearance violations");
  }

  /**
   * Returns the airline of the current autorouted connection or null, if no such
   * airline exists
   */
  public FloatLine get_air_line() {
    if (this.air_line == null) {
      return null;
    }
    if (this.air_line.a == null || this.air_line.b == null) {
      return null;
    }
    return this.air_line;
  }

  // Calculates the shortest distance between two sets of items, specifically
  // between Pin and Via items (pins and vias are connectable DrillItems)
  private void calc_airline(Collection<Item> p_from_items, Collection<Item> p_to_items) {
    FloatPoint from_corner = null;
    FloatPoint to_corner = null;
    double min_distance = Double.MAX_VALUE;
    for (Item curr_from_item : p_from_items) {
      if (!(curr_from_item instanceof DrillItem)) {
        continue;
      }
      FloatPoint curr_from_corner = ((DrillItem) curr_from_item).get_center().to_float();

      for (Item curr_to_item : p_to_items) {
        if (!(curr_to_item instanceof DrillItem)) {
          continue;
        }
        FloatPoint curr_to_corner = ((DrillItem) curr_to_item).get_center().to_float();
        double curr_distance = curr_from_corner.distance_square(curr_to_corner);
        if (curr_distance < min_distance) {
          min_distance = curr_distance;
          from_corner = curr_from_corner;
          to_corner = curr_to_corner;
        }
      }
    }
    this.air_line = new FloatLine(from_corner, to_corner);
  }

  /**
   * Finds the nearest point on a trace to the given point
   */
  private FloatPoint nearest_point_on_trace(PolylineTrace p_trace, FloatPoint p_point) {
    double min_distance = Double.MAX_VALUE;
    FloatPoint nearest_point = null;

    // Get endpoints
    FloatPoint first_corner = p_trace
        .first_corner()
        .to_float();
    FloatPoint last_corner = p_trace
        .last_corner()
        .to_float();

    // Check distance to endpoints first
    double distance_to_first = p_point.distance(first_corner);
    double distance_to_last = p_point.distance(last_corner);

    if (distance_to_first < min_distance) {
      min_distance = distance_to_first;
      nearest_point = first_corner;
    }

    if (distance_to_last < min_distance) {
      min_distance = distance_to_last;
      nearest_point = last_corner;
    }

    // Check distances to line segments
    for (int i = 0; i < p_trace.corner_count() - 1; i++) {
      FloatPoint segment_start = p_trace
          .polyline()
          .corner_approx(i);
      FloatPoint segment_end = p_trace
          .polyline()
          .corner_approx(i + 1);
      FloatLine segment = new FloatLine(segment_start, segment_end);

      FloatPoint projection = segment.perpendicular_projection(p_point);
      if (projection.is_contained_in_box(segment_start, segment_end, 0.01)) {
        double distance = p_point.distance(projection);
        if (distance < min_distance) {
          min_distance = distance;
          nearest_point = projection;
        }
      }
    }

    return nearest_point;
  }

  /**
   * Finds the closest points between two traces
   *
   * @return an array with two FloatPoints: [point_on_first_trace,
   *         point_on_second_trace]
   */
  private FloatPoint[] find_closest_points_between_traces(PolylineTrace p_first_trace, PolylineTrace p_second_trace) {
    double min_distance = Double.MAX_VALUE;
    FloatPoint[] result = new FloatPoint[2];

    // Check endpoints to endpoints
    FloatPoint first_trace_start = p_first_trace
        .first_corner()
        .to_float();
    FloatPoint first_trace_end = p_first_trace
        .last_corner()
        .to_float();
    FloatPoint second_trace_start = p_second_trace
        .first_corner()
        .to_float();
    FloatPoint second_trace_end = p_second_trace
        .last_corner()
        .to_float();

    // Check all endpoint combinations
    double distance = first_trace_start.distance(second_trace_start);
    if (distance < min_distance) {
      min_distance = distance;
      result[0] = first_trace_start;
      result[1] = second_trace_start;
    }

    distance = first_trace_start.distance(second_trace_end);
    if (distance < min_distance) {
      min_distance = distance;
      result[0] = first_trace_start;
      result[1] = second_trace_end;
    }

    distance = first_trace_end.distance(second_trace_start);
    if (distance < min_distance) {
      min_distance = distance;
      result[0] = first_trace_end;
      result[1] = second_trace_start;
    }

    distance = first_trace_end.distance(second_trace_end);
    if (distance < min_distance) {
      min_distance = distance;
      result[0] = first_trace_end;
      result[1] = second_trace_end;
    }

    // Check all segment combinations for closest points
    for (int i = 0; i < p_first_trace.corner_count() - 1; i++) {
      FloatPoint first_segment_start = p_first_trace
          .polyline()
          .corner_approx(i);
      FloatPoint first_segment_end = p_first_trace
          .polyline()
          .corner_approx(i + 1);
      FloatLine first_segment = new FloatLine(first_segment_start, first_segment_end);

      for (int j = 0; j < p_second_trace.corner_count() - 1; j++) {
        FloatPoint second_segment_start = p_second_trace
            .polyline()
            .corner_approx(j);
        FloatPoint second_segment_end = p_second_trace
            .polyline()
            .corner_approx(j + 1);
        FloatLine second_segment = new FloatLine(second_segment_start, second_segment_end);

        // Find closest points between these two line segments
        FloatPoint point_on_first = first_segment.nearest_segment_point(second_segment_start);
        FloatPoint point_on_second = second_segment.perpendicular_projection(point_on_first);

        // Check if projection is on the segment
        if (!point_on_second.is_contained_in_box(second_segment_start, second_segment_end, 0.01)) {
          // If not, use the nearest endpoint
          double dist_to_start = point_on_first.distance(second_segment_start);
          double dist_to_end = point_on_first.distance(second_segment_end);
          point_on_second = dist_to_start < dist_to_end ? second_segment_start : second_segment_end;
        }

        // Recalculate the point on first segment based on the point on second segment
        point_on_first = first_segment.nearest_segment_point(point_on_second);

        distance = point_on_first.distance(point_on_second);
        if (distance < min_distance) {
          min_distance = distance;
          result[0] = point_on_first;
          result[1] = point_on_second;
        }
      }
    }

    return result;
  }

  /**
   * Return an uppercase one-letter, two-letter or three-letter string based on
   * the thread index (0 = A, 1 = B, 2 = C, ..., 26 = AA, 27 = AB, ...).
   *
   * @param threadIndex
   * @return
   */
  private String ThreadIndexToLetter(int threadIndex) {
    if (threadIndex < 0) {
      return "";
    }
    if (threadIndex < 26) {
      return String.valueOf((char) ('A' + threadIndex));
    } else if (threadIndex < 26 * 26) {
      int firstLetterIndex = threadIndex / 26;
      int secondLetterIndex = threadIndex % 26;
      return String.valueOf((char) ('A' + firstLetterIndex)) + (char) ('A' + secondLetterIndex);
    } else {
      int firstLetterIndex = threadIndex / (26 * 26);
      int secondLetterIndex = (threadIndex / 26) % 26;
      int thirdLetterIndex = threadIndex % 26;
      return String.valueOf((char) ('A' + firstLetterIndex)) + (char) ('A' + secondLetterIndex)
          + (char) ('A' + thirdLetterIndex);
    }
  }

  /**
   * Calculates the airline distance for an item to be routed.
   * Returns the shortest distance from the item to any item in its incomplete
   * connections.
   *
   * @param p_item The item to calculate distance for
   * @return The shortest airline distance, or Double.MAX_VALUE if no connections
   *         exist
   */
  private double calculateItemDistance(Item p_item) {
    if (p_item.net_count() == 0) {
      return Double.MAX_VALUE;
    }

    // Get the first net number (items typically have one net)
    int net_no = p_item.get_net_no(0);

    // Get incomplete items for this net
    Set<Item> unconnected_set = p_item.get_unconnected_set(net_no);
    Set<Item> connected_set = p_item.get_connected_set(net_no);

    if (unconnected_set.isEmpty()) {
      return 0; // Already connected, prioritize
    }

    // Calculate minimum distance from connected items to unconnected items
    return calculateMinDistance(connected_set.isEmpty() ? Set.of(p_item) : connected_set, unconnected_set);
  }

  /**
   * Helper method to calculate the minimum distance between two sets of items.
   */
  private double calculateMinDistance(Collection<Item> p_from_items, Collection<Item> p_to_items) {
    double min_distance = Double.MAX_VALUE;

    for (Item from_item : p_from_items) {
      FloatPoint from_point = getItemReferencePoint(from_item);
      if (from_point == null)
        continue;

      for (Item to_item : p_to_items) {
        FloatPoint to_point = getItemReferencePoint(to_item);
        if (to_point == null)
          continue;

        double distance = from_point.distance(to_point);
        if (distance < min_distance) {
          min_distance = distance;
        }
      }
    }

    return min_distance;
  }

  /**
   * Gets a representative point for an item (center for DrillItems, midpoint for
   * traces).
   */
  private FloatPoint getItemReferencePoint(Item p_item) {
    if (p_item instanceof DrillItem drillItem) {
      return drillItem.get_center().to_float();
    } else if (p_item instanceof PolylineTrace trace) {
      // Use the midpoint of the trace as a reference
      FloatPoint first = trace.first_corner().to_float();
      FloatPoint last = trace.last_corner().to_float();
      return new FloatPoint((first.x + last.x) / 2, (first.y + last.y) / 2);
    }
    return null;
  }


}