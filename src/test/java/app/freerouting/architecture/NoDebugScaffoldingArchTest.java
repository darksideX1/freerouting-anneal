package app.freerouting.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Production code must not carry developer scaffolding that targets one specific board.
 *
 * <p>Debugging a single net or a single component is a normal thing to do while chasing a
 * defect. Committing it is not: the condition stays in the shipped hot path forever,
 * costs every user a comparison per call, and silently changes what gets logged for
 * boards that happen to use those net numbers or that reference prefix.
 */
class NoDebugScaffoldingArchTest {

  /**
   * Scaffolding that already existed when this guard was written, frozen so it cannot
   * grow. The set may only shrink: removing a site is a behaviour-free cleanup, adding
   * one fails the build.
   *
   * <p>Entries are keyed on WHAT was found and in which file, deliberately NOT on the
   * line number. Line numbers move whenever an unrelated edit lands above them, and a
   * guard that fails spuriously is a guard someone switches off — which is exactly how
   * the debt it protects against survives.
   */
  private static final List<String> FROZEN_DEBT = List.of(
      "autoroute/AutorouteEngine.java|net_no == 33",
      "autoroute/InsertFoundConnectionAlgo.java|U27-",
      "autoroute/LocateFoundConnectionAlgo.java|net_no == 33",
      "autoroute/LocateFoundConnectionAlgo.java|net_no == 98",
      "autoroute/LocateFoundConnectionAlgo45Degree.java|net_no == 33",
      "autoroute/LocateFoundConnectionAlgoAnyAngle.java|net_no == 33",
      "autoroute/MazeSearchAlgo.java|U27-",
      "board/ShapeSearchTree90Degree.java|net_no == 84");

  private static final List<String> PRODUCTION_PACKAGES =
      List.of("autoroute", "board", "geometry", "rules", "datastructures", "interactive");

  /** A net number compared against a literal — board-specific by construction. */
  private static final Pattern HARDCODED_NET = Pattern.compile("net_?[Nn]o\\s*==\\s*\\d+");

  /** A reference-designator prefix literal, e.g. "U27-" — one component on one board. */
  private static final Pattern HARDCODED_REFDES = Pattern.compile("\"[A-Z]{1,3}\\d+-\"");

  @Test
  void productionCodeMustNotContainBoardSpecificDebugScaffolding() throws IOException {
    List<String> violations = new ArrayList<>();

    for (String pkg : PRODUCTION_PACKAGES) {
      Path root = Path.of("src/main/java/app/freerouting", pkg);
      if (!Files.isDirectory(root)) {
        continue;
      }
      try (Stream<Path> files = Files.walk(root)) {
        for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
          List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
          for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.startsWith("*") || trimmed.startsWith("//")) {
              continue; // documentation may legitimately cite an example net
            }
            if (isFrozen(file, line)) {
              continue;
            }
            check(HARDCODED_NET, line, file, i, "net number hardcoded", violations);
            check(HARDCODED_REFDES, line, file, i, "reference designator hardcoded",
                violations);
          }
        }
      }
    }

    assertTrue(
        violations.isEmpty(),
        "Board-specific debug scaffolding found in production code. Debugging one net or\n"
            + "one component is fine; shipping that condition is not.\n\n"
            + String.join("\n", violations));
  }

  /** True when this exact finding, in this file, is already recorded as known debt. */
  private static boolean isFrozen(Path file, String line) {
    String rel = relativeName(file);
    for (String entry : FROZEN_DEBT) {
      int bar = entry.indexOf('|');
      String wantFile = entry.substring(0, bar);
      String wantText = entry.substring(bar + 1);
      if (rel.endsWith(wantFile) && line.contains(wantText)) {
        return true;
      }
    }
    return false;
  }

  /** Package-relative path, e.g. "autoroute/Foo.java". */
  private static String relativeName(Path file) {
    String p = file.toString().replace(java.io.File.separatorChar, '/');
    int cut = p.indexOf("app/freerouting/");
    return cut < 0 ? p : p.substring(cut + "app/freerouting/".length());
  }

  private static void check(Pattern pattern, String line, Path file, int idx, String what,
      List<String> out) {
    Matcher m = pattern.matcher(line);
    if (m.find()) {
      out.add(file + ":" + (idx + 1) + "  (" + what + ": " + m.group() + ")  "
          + line.trim());
    }
  }
}
