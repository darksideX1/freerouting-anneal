package app.freerouting.autoroute;

import app.freerouting.autoroute.events.BoardSnapshotEvent;
import app.freerouting.autoroute.events.BoardSnapshotEventListener;
import app.freerouting.autoroute.events.BoardUpdatedEvent;
import app.freerouting.autoroute.events.BoardUpdatedEventListener;
import app.freerouting.autoroute.events.TaskStateChangedEvent;
import app.freerouting.autoroute.events.TaskStateChangedEventListener;
import app.freerouting.board.RoutingBoard;
import app.freerouting.core.RouterCounters;
import app.freerouting.core.StoppableThread;
import app.freerouting.core.scoring.BoardStatistics;
import app.freerouting.settings.RouterSettings;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Interface for named algorithms, e.g. "Freerouting Classic Fast Auto-router v1.0" for auto-router, "Freerouting Classic Optimizer v1.0" for route-optimization.
 */
public abstract class NamedAlgorithm implements Serializable {

  protected final transient StoppableThread thread;
  protected final transient List<BoardSnapshotEventListener> boardSnapshotEventListeners = new ArrayList<>();
  protected final transient List<BoardUpdatedEventListener> boardUpdatedEventListeners = new ArrayList<>();
  protected final transient List<TaskStateChangedEventListener> taskStateChangedEventListeners = new ArrayList<>();
  protected final RouterSettings settings;
  // The routing board.
  // NOTE: Declared transient so that both Java object serialisation and Gson skip it.
  // RoutingBoard is not JSON-serialisable, so it must be excluded from any serialised
  // representation of a NamedAlgorithm. Callers are responsible for re-injecting the
  // board reference after deserialisation if needed.
  protected transient RoutingBoard board;

  /**
   * CPU seconds burned by the calling thread, or -1 when the JVM will not say.
   *
   * <p>Lived identically in {@code BatchAutorouter} and {@code BatchOptimizer}. Both extend
   * this class, so it belongs here.
   */
  protected static float sampleCurrentThreadCpuSeconds() {
    try {
      com.sun.management.ThreadMXBean threadMxBean =
          (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
      long cpuNanos = threadMxBean.getThreadCpuTime(Thread.currentThread().threadId());
      return cpuNanos < 0 ? -1f : cpuNanos / 1_000_000_000.0f;
    } catch (Throwable t) {
      return -1f;
    }
  }

  /** Megabytes allocated by the calling thread, or -1 when the JVM will not say. */
  protected static float sampleCurrentThreadAllocatedMb() {
    try {
      com.sun.management.ThreadMXBean threadMxBean =
          (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
      threadMxBean.setThreadAllocatedMemoryEnabled(true);
      long allocatedBytes = threadMxBean.getThreadAllocatedBytes(Thread.currentThread().threadId());
      return allocatedBytes < 0 ? -1f : allocatedBytes / (1024.0f * 1024.0f);
    } catch (Throwable t) {
      return -1f;
    }
  }

  /** Heap megabytes in use, or 0 when the JVM will not say. */
  protected static float sampleHeapUsageMb() {
    try {
      long heapUsed = java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
      return heapUsed / (1024.0f * 1024.0f);
    } catch (Throwable t) {
      return 0f;
    }
  }

  /** Incomplete connections on a board, via a throwaway DRC pass. */
  protected int calculateIncompleteCount(RoutingBoard board) {
    app.freerouting.drc.DesignRulesChecker tempDrc = new app.freerouting.drc.DesignRulesChecker(board, null);
    tempDrc.calculateAllIncompletes();
    return tempDrc.getIncompleteCount();
  }

  protected NamedAlgorithm(StoppableThread thread, RoutingBoard board, RouterSettings settings) {
    this.thread = thread;
    this.board = board;
    this.settings = settings;
  }

  /**
   * Returns the id of the algorithm.
   *
   * @return The id of the algorithm.
   */
  protected abstract String getId();

  /**
   * Returns the name of the algorithm.
   *
   * @return The name of the algorithm.
   */
  protected abstract String getName();

  /**
   * Returns the version of the algorithm.
   *
   * @return The version of the algorithm.
   */
  protected abstract String getVersion();

  /**
   * Returns the description of the algorithm.
   *
   * @return The description of the algorithm.
   */
  protected abstract String getDescription();

  /**
   * Returns the type of the algorithm.
   *
   * @return The type of the algorithm.
   */
  protected abstract NamedAlgorithmType getType();

  public void addBoardSnapshotEventListener(BoardSnapshotEventListener listener) {
    boardSnapshotEventListeners.add(listener);
  }

  public void fireBoardSnapshotEvent(RoutingBoard board) {
    BoardSnapshotEvent event = new BoardSnapshotEvent(this, board);
    for (BoardSnapshotEventListener listener : boardSnapshotEventListeners) {
      listener.onBoardSnapshotEvent(event);
    }
  }

  public void addBoardUpdatedEventListener(BoardUpdatedEventListener listener) {
    boardUpdatedEventListeners.add(listener);
  }

  /**
   * Fires a board updated event. This happens when the board has been updated, e.g. after a route has been added.
   */
  public void fireBoardUpdatedEvent(BoardStatistics boardStatistics, RouterCounters routerCounters, RoutingBoard board) {
    BoardUpdatedEvent event = new BoardUpdatedEvent(this, boardStatistics, routerCounters, board);
    for (BoardUpdatedEventListener listener : boardUpdatedEventListeners) {
      listener.onBoardUpdatedEvent(event);
    }
  }

  public void addTaskStateChangedEventListener(TaskStateChangedEventListener listener) {
    taskStateChangedEventListeners.add(listener);
  }

  /**
   * Fires a task state changed event. This happens when the state of the task changes, e.g. from running to stopped, or we start a new pass of the current process.
   */
  public void fireTaskStateChangedEvent(TaskStateChangedEvent event) {
    for (TaskStateChangedEventListener listener : taskStateChangedEventListeners) {
      listener.onTaskStateChangedEvent(event);
    }
  }
}