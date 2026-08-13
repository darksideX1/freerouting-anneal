package app.freerouting.core;

import app.freerouting.board.Item;
import app.freerouting.board.Pin;
import app.freerouting.board.RoutingBoard;
import app.freerouting.core.scoring.BoardStatistics;
import app.freerouting.drc.AirLine;
import app.freerouting.drc.DesignRulesChecker;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The final run report: what the run did, written down where the user can keep it.
 *
 * <p>Spec: {@code docs/fork/FINAL-REPORT-SPEC.md}. The counts a user needs to decide
 * "re-run or fix the placement" go in the GUI dialog and on the CLI's last lines; the
 * per-pin unrouted list — which can be three lines or five hundred — goes in this file,
 * where it can be kept, copied, or sent to somebody. Each unrouted line names the net,
 * the pin, and the other end it should reach, so a board can be finished by hand from
 * the report alone.
 *
 * <p>A cancelled run writes no report: cancelling means discarding the work, and
 * producing an artifact from it would contradict the instruction (operator ruling in the
 * spec, 2026-08-10).
 */
public final class FinalRunReport {

  private FinalRunReport() {
  }

  private static final DateTimeFormatter STAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

  /** Report file name: board, then run timestamp, so two attempts sit side by side. */
  static String fileNameFor(String boardName, Instant when) {
    String safe = (boardName == null || boardName.isBlank()) ? "board" : boardName;
    safe = safe.replaceAll("[^A-Za-z0-9._-]", "_");
    return safe + "_" + STAMP.format(when) + "_final-report.txt";
  }

  /**
   * The report text, from parts. Pure so the tests can pin the format; the job/board
   * overload below assembles the parts from the live objects.
   */
  static String compose(String ending, Duration elapsed, Integer totalConnections,
      Integer unrouted, Integer violations, String unroutedSection) {
    StringBuilder sb = new StringBuilder();
    sb.append(ending == null || ending.isBlank() ? "Finished." : ending).append('\n');
    if (elapsed != null) {
      long s = elapsed.toSeconds();
      sb.append("Elapsed:    ").append(String.format("%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)).append('\n');
    }
    if (totalConnections != null && unrouted != null) {
      sb.append("Routed:     ").append(totalConnections - unrouted).append(" of ")
          .append(totalConnections).append(" connections").append('\n');
    }
    if (unrouted != null) {
      sb.append("Unrouted:   ").append(unrouted).append('\n');
    }
    if (violations != null) {
      sb.append("Violations: ").append(violations).append('\n');
    }
    // Present when there is something to list; absent rather than empty when there is
    // nothing (spec test 5) -- an empty section reads as a truncated file.
    if (unrouted != null && unrouted > 0 && unroutedSection != null && !unroutedSection.isBlank()) {
      sb.append('\n').append("Unrouted connections, by net (pin -> the other end it should reach):").append('\n');
      sb.append(unroutedSection).append('\n');
    }
    return sb.toString();
  }

  /** Assembles the report for a finished job. Returns null when there is nothing to report on. */
  public static String compose(RoutingJob job, RoutingBoard board, String ending) {
    return compose(job, board, ending, board == null ? null : new BoardStatistics(board));
  }

  /** Overload for callers that already computed the statistics -- one pass, not two. */
  public static String compose(RoutingJob job, RoutingBoard board, String ending, BoardStatistics stats) {
    if (board == null || stats == null) {
      return compose(ending, job == null ? null : job.getDuration(), null, null, null, null);
    }
    Integer total = stats.connections.maximumCount;
    Integer unrouted = stats.connections.incompleteCount;
    Integer violations = stats.clearanceViolations == null ? null : stats.clearanceViolations.totalCount;
    String section = (unrouted != null && unrouted > 0) ? unroutedSection(board) : null;
    return compose(ending, job == null ? null : job.getDuration(), total, unrouted, violations, section);
  }

  /**
   * Writes the report beside the log (one place for run artifacts) and returns its path.
   * Returns null for a cancelled run (no artifact from discarded work) or when no board
   * exists to report on.
   */
  public static Path write(RoutingJob job, RoutingBoard board, String ending) {
    return write(job, board, ending, board == null ? null : new BoardStatistics(board));
  }

  /** Overload for callers that already computed the statistics. */
  public static Path write(RoutingJob job, RoutingBoard board, String ending, BoardStatistics stats) {
    if (job == null || job.state == RoutingJobState.CANCELLED || board == null) {
      return null;
    }
    Instant when = job.finishedAt != null ? job.finishedAt : Instant.now();
    Path dir = reportDirectory();
    Path target = dir.resolve(fileNameFor(job.name, when));
    try {
      Files.createDirectories(dir);
      String text = compose(job, board, ending, stats);
      // CREATE_NEW, never truncate: the timestamp is second-granular, and two jobs on
      // the same board can finish inside one second. A sibling suffix keeps the
      // two-attempts-two-files promise instead of silently overwriting the first.
      for (int attempt = 2; Files.exists(target) && attempt < 100; attempt++) {
        String base = fileNameFor(job.name, when);
        target = dir.resolve(base.replace("_final-report.txt", "_" + attempt + "_final-report.txt"));
      }
      Files.writeString(target, text, StandardCharsets.UTF_8,
          java.nio.file.StandardOpenOption.CREATE_NEW);
      return target;
    } catch (IOException e) {
      // The report is a convenience artifact; failing to write it must not fail the run.
      // But it fails loudly -- a promised file that silently does not appear is the
      // defect this feature exists to end.
      job.logError("Could not write the final run report to '" + target + "'", e);
      return null;
    }
  }

  /** The directory the log lands in: a user who has found the log has found the reports. */
  static Path reportDirectory() {
    String logLocation = System.getProperty("freerouting.logging.file.location");
    if (logLocation != null && !logLocation.isBlank()) {
      Path parent = Path.of(logLocation).getParent();
      if (parent != null) {
        return parent;
      }
    }
    return app.freerouting.settings.GlobalSettings.defaultUserDataPath().resolve("logs");
  }

  /**
   * The per-net unrouted list, grouped, one line per missing connection. Moved here from
   * {@code BatchAutorouter} so the mid-run stagnation log and the final report are the
   * same text by construction.
   */
  public static String unroutedSection(RoutingBoard board) {
    DesignRulesChecker tempDrc = new DesignRulesChecker(board, null);
    tempDrc.calculateAllIncompletes();
    AirLine[] airlines = tempDrc.getAllAirlines();

    if (airlines == null || airlines.length == 0) {
      return "  (no unrouted connections found)";
    }

    Map<String, List<String>> byNet = new LinkedHashMap<>();
    for (AirLine al : airlines) {
      String netName = al.net != null ? al.net.name : "(unknown net)";
      String fromDesc = describeItem(board, al.from_item);
      String toDesc = describeItem(board, al.to_item);
      byNet.computeIfAbsent(netName, k -> new ArrayList<>())
          .add("    - " + fromDesc + "  ->  " + toDesc);
    }

    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, List<String>> entry : byNet.entrySet()) {
      int count = entry.getValue().size();
      sb.append("  Net '").append(entry.getKey()).append("' (")
          .append(count).append(" unrouted connection").append(count == 1 ? "" : "s").append("):\n");
      for (String line : entry.getValue()) {
        sb.append(line).append('\n');
      }
    }
    return sb.toString().stripTrailing();
  }

  /** {@code ComponentName-PinName} for pins (J2-A3); a generic fallback otherwise. */
  public static String describeItem(RoutingBoard board, Item item) {
    if (item instanceof Pin pin && board != null && board.components != null) {
      try {
        app.freerouting.board.Component comp = board.components.get(pin.get_component_no());
        if (comp != null) {
          app.freerouting.core.Package pkg = comp.get_package();
          if (pkg != null) {
            app.freerouting.core.Package.Pin pkgPin = pkg.get_pin(pin.pin_no);
            if (pkgPin != null) {
              return comp.name + "-" + pkgPin.name;
            }
          }
          return comp.name + " (pin #" + pin.pin_no + ")";
        }
      } catch (Exception e) {
        // fall through to generic
      }
    }
    return item != null ? item.toString() : "(unknown)";
  }
}
