package app.freerouting.logger;

import app.freerouting.Freerouting;
import app.freerouting.board.BasicBoard;
import app.freerouting.debug.DebugControl;
import java.util.function.Supplier;
import app.freerouting.geometry.planar.IntBox;
import app.freerouting.geometry.planar.Point;
import java.text.DecimalFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/// <summary> Provides logging functionality. </summary>
/**
 * Provides centralized logging functionality for the application.
 * Wraps Log4j2 and maintains an internal list of log entries for UI display.
 */
public class FRLogger {

  public static final DecimalFormat defaultFloatFormat = new DecimalFormat("0.00",
      new java.text.DecimalFormatSymbols(java.util.Locale.US));
  public static final DecimalFormat defaultSignedFloatFormat = new DecimalFormat("+0.00;-0.00",
      new java.text.DecimalFormatSymbols(java.util.Locale.US));
  private static final HashMap<Integer, Instant> perfData = new HashMap<>();
  private static final LogEntries logEntries = new LogEntries();
  private static final CopyOnWriteArrayList<TraceEventListener> traceEventListeners = new CopyOnWriteArrayList<>();
  public static boolean granularTraceEnabled = false;
  private static Logger logger;

  private static boolean enabled = true;

  private FRLogger() {
  }

  /**
   * Formats a bounding box for diagnostics as {@code [(llx,lly)..(urx,ury)]}.
   *
   * <p>This existed four times verbatim -- in {@code BasicBoard},
   * {@code ShapeSearchTree45Degree}, {@code ShapeSearchTree90Degree} and
   * {@code Sorted45DegreeRoomNeighbours} -- plus once inlined in {@code MazeSearchAlgo}.
   * Only the {@code BasicBoard} copy checked for null; that is the version kept, because a
   * formatter that throws while describing a problem is worse than useless.
   */
  public static String formatBounds(IntBox p_bounds) {
    if (p_bounds == null) {
      return "null";
    }
    return "[(" + p_bounds.ll.x + "," + p_bounds.ll.y + ")..(" + p_bounds.ur.x + "," + p_bounds.ur.y + ")]";
  }

  /**
   * The Log4j logger, created on first use.
   *
   * <p>Deliberately lazy, and deliberately NOT a {@code static final} initialised at class
   * load. Log4j resolves its configuration on the first {@code getLogger} call, and
   * {@link Log4j2ConfigurationFactory} reads the {@code freerouting.logging.*} system
   * properties at that moment -- properties {@code main} sets from the command line. Making
   * this eager would pin the logging configuration at FRLogger class-load, which can happen
   * first, and the symptom would be silently wrong log levels rather than a failure.
   *
   * <p>This replaced twelve copies of the same {@code if (logger == null)} block. The race
   * on the non-volatile field is benign: {@code getLogger} returns the same instance for the
   * same name, so a lost update costs one redundant lookup and nothing else. Read once into
   * a local so the field cannot change between the check and the return.
   */
  private static Logger logger() {
    Logger local = logger;
    if (local == null) {
      local = LogManager.getLogger(Freerouting.class);
      logger = local;
    }
    return local;
  }

  /**
   * Enables or disables logging globally.
   *
   * @param value true to enable logging, false to disable.
   */
  public static void setEnabled(boolean value) {
    enabled = value;
  }

  /**
   * Formats a duration in seconds into a human-readable string (hours, minutes,
   * seconds).
   *
   * @param totalSeconds The total duration in seconds.
   * @return A formatted string representing the duration.
   */
  public static String formatDuration(double totalSeconds) {
    double seconds = totalSeconds;
    double minutes = seconds / 60.0;
    double hours = minutes / 60.0;

    hours = Math.floor(hours);
    minutes = Math.floor(minutes % 60.0);
    seconds = seconds % 60.0;

    String hoursText = hours > 0 ? (int) hours + (hours == 1 ? " hour " : " hours ") : "";

    String minutesText = minutes > 0 ? (int) minutes + (minutes == 1 ? " minute " : " minutes ") : "";

    return hoursText + minutesText + defaultFloatFormat.format(seconds) + " seconds";
  }

  /**
   * Formats a score with details about incomplete items and violations.
   *
   * @param score      The routing score.
   * @param incomplete The number of unrouted items.
   * @param violations The number of design rule violations.
   * @return A formatted string representing the score and any issues.
   */
  public static String formatScore(float score, int incomplete, int violations) {
    StringBuilder sb = new StringBuilder(defaultFloatFormat.format(score));

    // Always include unrouted and violations for a consistent, parseable format
    sb.append(" (");
    sb.append(incomplete).append(" unrouted");
    sb.append(" and ");
    sb.append(violations).append(violations == 1 ? " violation" : " violations");
    sb.append(")");

    return sb.toString();
  }

  public static String buildTracePayload(String event, String phase, String action, String kvPairs) {
    StringBuilder sb = new StringBuilder();
    sb.append("event=").append(event);
    if (phase != null && !phase.isEmpty()) {
      sb.append(" phase=").append(phase);
    }
    if (action != null && !action.isEmpty()) {
      sb.append(" action=").append(action);
    }
    if (kvPairs != null && !kvPairs.isEmpty()) {
      sb.append(" ").append(kvPairs);
    }
    return sb.toString();
  }

  public static String formatNetLabel(BasicBoard board, int[] netNoArr) {
    if (netNoArr == null || netNoArr.length == 0) {
      return "No net";
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < netNoArr.length; i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(formatNetLabel(board, netNoArr[i]));
    }
    return sb.toString();
  }

  public static String formatNetLabel(BasicBoard board, int netNo) {
    if (board == null || board.rules == null || board.rules.nets == null) {
      return "Net #" + netNo + " (Unknown)";
    }
    if (netNo <= board.rules.nets.max_net_no()) {
      return board.rules.nets.get(netNo).toString();
    }
    return "Net #" + netNo + " (Unknown)";
  }

  /**
   * Records the start time for a performance trace.
   *
   * @param perfId A unique identifier for the operation being traced (often the
   *               method name).
   */
  public static void traceEntry(String perfId) {
    if (!enabled) {
      return;
    }
    perfData.put(perfId.hashCode(), Instant.now());
  }

  /**
   * Records the end of a performance trace and logs the duration.
   *
   * @param perfId A unique identifier for the operation being traced.
   * @return The duration of the operation in seconds.
   */
  public static double traceExit(String perfId) {
    if (!enabled) {
      return 0.0;
    }
    return traceExit(perfId, null);
  }

  /**
   * Records the end of a performance trace with an optional result object and
   * logs the duration.
   *
   * @param perfId A unique identifier for the operation being traced.
   * @param result An optional result object to include in the log message.
   * @return The duration of the operation in seconds.
   */
  public static double traceExit(String perfId, Object result) {
    if (!enabled) {
      return 0.0;
    }
    long timeElapsed = 0;
    try {
      timeElapsed = Duration
          .between(perfData.get(perfId.hashCode()), Instant.now())
          .toMillis();
    } catch (Exception _) {
      // we can ignore this exception
    }

    perfData.remove(perfId.hashCode());
    if (timeElapsed < 0) {
      timeElapsed = 0;
    }

    String logMessage = "Method '" + perfId.replace("{}", result != null ? result.toString() : "(null)")
        + "' was performed in " + FRLogger.formatDuration(timeElapsed / 1000.0) + ".";

    FRLogger.trace(logMessage);

    return timeElapsed / 1000.0;
  }

  /**
   * Logs an INFO message.
   *
   * @param msg   The message to log.
   * @param topic An optional topic UUID associated with the message.
   * @return The created LogEntry.
   */
  public static LogEntry info(String msg, UUID topic) {
    LogEntry logEntry = logEntries.add(LogEntryType.Info, msg, topic);

    if (!enabled) {
      return null;
    }
    logger().info(msg);

    return logEntry;
  }

  /**
   * Logs an INFO message without a topic.
   *
   * @param msg The message to log.
   * @return The created LogEntry.
   */
  public static LogEntry info(String msg) {
    return info(msg, null);
  }

  /**
   * Logs a WARNING message.
   *
   * @param msg   The message to log.
   * @param topic An optional topic UUID associated with the message.
   * @return The created LogEntry.
   */
  public static LogEntry warn(String msg, UUID topic) {
    LogEntry logEntry = logEntries.add(LogEntryType.Warning, msg, topic);

    if (!enabled) {
      return null;
    }
    logger().warn(msg);

    return logEntry;
  }

  /**
   * Logs a WARNING message without a topic.
   *
   * @param msg The message to log.
   * @return The created LogEntry.
   */
  public static LogEntry warn(String msg) {
    return warn(msg, null);
  }

  /**
   * Logs a DEBUG message.
   *
   * @param msg   The message to log.
   * @param topic An optional topic UUID associated with the message.
   * @return The created LogEntry.
   */
  public static LogEntry debug(String msg, UUID topic) {
    if (!enabled) {
      return null;
    }
    logger().debug(msg);

    return null;
  }

  /**
   * Logs a DEBUG message without a topic.
   *
   * @param msg The message to log.
   * @return The created LogEntry.
   */
  public static LogEntry debug(String msg) {
    return debug(msg, null);
  }

  /**
   * Logs an ERROR message with an exception.
   *
   * @param msg       The message to log.
   * @param topic     An optional topic UUID associated with the message.
   * @param exception The exception to log.
   * @return The created LogEntry.
   */
  public static LogEntry error(String msg, UUID topic, Throwable exception) {
    LogEntry logEntry = logEntries.add(LogEntryType.Error, msg, topic, exception);

    if (!enabled) {
      return null;
    }
    if (exception == null) {
      logger().error(msg);
    } else {
      logger().error(msg, exception);
    }

    return logEntry;
  }

  /**
   * Logs an ERROR message with an exception, but without a topic.
   *
   * @param msg       The message to log.
   * @param exception The exception to log.
   * @return The created LogEntry.
   */
  public static LogEntry error(String msg, Throwable exception) {
    return error(msg, null, exception);
  }

  /**
   * Checks if TRACE level logging is enabled.
   *
   * @return true if TRACE logging is enabled, false otherwise.
   */
  /**
   * Checks if DEBUG level logging is enabled.
   *
   * @return true if DEBUG logging is enabled, false otherwise.
   */
  public static boolean isDebugEnabled() {
    if (!enabled) {
      return false;
    }
    return logger().isDebugEnabled();
  }

  public static boolean isTraceEnabled() {
    if (!enabled) {
      return false;
    }
    return logger().isTraceEnabled();
  }

  /**
   * Logs a TRACE message.
   *
   * @param msg The message to log.
   * @return The created LogEntry.
   */
  public static LogEntry trace(String msg) {
    if (!enabled) {
      return null;
    }
    logger().trace(msg);

    return null;
  }

  public static boolean trace(String method, String operation, String message, String impactedItems) {
    return trace(method, operation, message, impactedItems, null);
  }

  /**
   * Logs a granular TRACE message and triggers a debug check.
   *
   * @param method        The method name where the log originates (e.g.
   *                      "InsertFoundConnectionAlgo").
   * @param operation     The operation type (e.g. "insertion", "removal").
   * @param message       The details of the log message.
   * @param impactedItems A string describing the impacted items, separated by comma
   *                      (e.g. "Net #1,Trace #123").
   *                      This string is used by DebugControl to filter execution.
   * @param impactedPoints List of points that the operation focused on
   */
  /**
   * Trace with deferred arguments.
   *
   * <p>The five-argument form is not a logging call: it runs
   * {@code DebugControl.check(...)}, which implements single-step execution and can pause
   * the router, and it publishes a {@link TraceEvent}. Because its {@code message} was an
   * already-built String, callers wrapped it in {@code if (isTraceEnabled())} to avoid the
   * concatenation -- and 38 of those guards therefore disabled the DEBUGGER whenever the
   * level was above TRACE. A root logger of {@code Level.ALL} hid that by making every
   * guard true.
   *
   * <p>The cure is not more guards. With suppliers nothing is built unless something will
   * consume it, so the call site can drop its guard, and dropping the guard is what gives
   * the debugger its breakpoint back.
   *
   * <p>All three expensive arguments are deferred, not just the message: at the hottest
   * site the impacted-items string and {@code getImpactedPoints(...)} allocate too.
   */
  public static boolean trace(String method, String operation, Supplier<String> message,
      Supplier<String> impactedItems, Supplier<Point[]> impactedPoints) {
    boolean granular = enabled && granularTraceEnabled;
    boolean debuggerActive = DebugControl.getInstance().isActive();
    if (!granular && !debuggerActive) {
      // The overwhelmingly common case. Field reads only; no supplier is invoked, so this
      // costs strictly less than the guarded String form it replaces.
      return false;
    }
    return trace(method, operation, message.get(), impactedItems.get(), impactedPoints.get());
  }

  public static boolean trace(String method, String operation, String message, String impactedItems, Point[] impactedPoints) {
    DebugControl debugControl = DebugControl.getInstance();
    // Parse the impacted-items string ONCE. Both questions below used to parse it
    // separately with the same regex, so every granular trace paid twice.
    int netNo = debugControl.parseNetNo(impactedItems);

    if (enabled) {
      if (granularTraceEnabled && (impactedItems.isEmpty() || debugControl.isInterestedInNet(netNo))) {
        String formattedMessage = String.format("[%s] [%s] %s: %s", method, operation, message, impactedItems);
        logger().trace(formattedMessage);
      }
    }

    // isActive() is precisely the null-settings guard plus the singleStep/delay
    // early-return that check(String, String) performed BEFORE delegating. The
    // three-argument overload does neither, so calling it directly without this would
    // turn a null-settings run into a NullPointerException.
    boolean wasInterestingTraceEvent =
        debugControl.isActive() && debugControl.check(operation, netNo, null);
    if (wasInterestingTraceEvent) {
      publishTraceEvent(new TraceEvent(method, operation, message, impactedItems, impactedPoints, Instant.now()));
    }

    return wasInterestingTraceEvent;
  }

  /**
   * Disables logging.
   */
  public static void disableLogging() {
    enabled = false;
  }

  /**
   * Gets the collection of log entries recorded by this logger.
   *
   * @return The LogEntries collection.
   */
  public static LogEntries getLogEntries() {
    return logEntries;
  }

  /**
   * Gets the underlying Log4j2 Logger instance.
   *
   * @return The Logger instance.
   */
  public static Logger getLogger() {
    return logger;
  }

  /** Adds a listener that will be notified of interesting trace events. */
  public static void addTraceEventListener(TraceEventListener listener) {
    traceEventListeners.add(listener);
  }

  /** Removes a listener from the list of trace event listeners. */
  public static void removeTraceEventListener(TraceEventListener listener) {
    traceEventListeners.remove(listener);
  }

  private static void publishTraceEvent(TraceEvent event) {
    for (TraceEventListener listener : traceEventListeners) {
      listener.onTraceEvent(event);
    }
  }
}