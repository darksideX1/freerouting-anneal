package app.freerouting.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The routing hot path must not build log messages it is not going to emit.
 *
 * <p>{@code FRLogger.trace(..)} and {@code FRLogger.debug(..)} take already-constructed
 * Strings, so any concatenation in the argument list is evaluated on every call --
 * before the logger decides whether the level is even enabled. In the routing inner
 * loop that cost is paid millions of times per board and cannot be configured away:
 * turning the log level down does not help, because the work happens first.
 *
 * <p>{@code FRLogger.isTraceEnabled()} exists precisely to avoid this and was
 * essentially unused. This test keeps the hot path honest: inside the packages below,
 * a log call that concatenates must sit behind an enabled-check.
 */
class HotPathLoggingArchTest {

  /**
   * Packages on the routing hot path, where per-call allocation actually matters.
   *
   * <p>"board" IS in scope, and the story of how it got here is the useful part.
   *
   * <p>It was parked once, on the observation that guarding it changed DAC2020 bm01
   * from 28 passes / 2 violations to 22 passes / 0 violations with a different .ses.
   * That observation came from a SINGLE RUN of each arm, taken before we understood
   * that this engine is nondeterministic: the same jar, board and settings produce
   * different output run to run. A one-run difference is therefore not evidence of a
   * behaviour change -- it is one sample from a distribution.
   *
   * <p>Re-measured at n=5 per arm, guarding board/ cut allocation by 28% and routing
   * quality was equal or better, so it was re-included. The lesson worth keeping is
   * that on a nondeterministic engine you CANNOT prove a change is behaviour-free by
   * comparing output bytes; you can only show the quality distribution did not get
   * worse. ShapeSearchTree's own comment -- "the non-deterministic order of tree
   * traversal causes different room partitioning" -- is why.
   */
  private static final List<String> HOT_PATH_PACKAGES =
      List.of("autoroute", "board", "geometry", "rules", "datastructures");

  /** How far above a call an enabled-check may sit and still be considered its guard. */
  private static final int GUARD_LOOKBEHIND_LINES = 12;

  @Test
  void hotPathLogCallsMustNotConcatenateWithoutAnEnabledCheck() throws IOException {
    List<String> violations = new ArrayList<>();

    for (String pkg : HOT_PATH_PACKAGES) {
      Path root = Path.of("src/main/java/app/freerouting", pkg);
      if (!Files.isDirectory(root)) {
        continue;
      }
      try (Stream<Path> files = Files.walk(root)) {
        for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
          violations.addAll(scan(file));
        }
      }
    }

    assertTrue(
        violations.isEmpty(),
        "Log messages are built before the level is checked, on the routing hot path.\n"
            + "Pass the message as a supplier -- FRLogger.trace(.., () -> \"a\" + b, ..) --\n"
            + "so nothing is built when the level is off. An `if (FRLogger.isTraceEnabled())`\n"
            + "guard also satisfies this rule, but prefer the supplier: on the 5-argument\n"
            + "trace the guard ALSO suppresses DebugControl single-step execution. Or pass a\n"
            + "pre-built constant) so nothing is allocated when the level is off.\n\n"
            + String.join("\n", violations));
  }

  /** Returns "file:line" for each concatenating, unguarded trace/debug call. */
  private static List<String> scan(Path file) throws IOException {
    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    List<String> violations = new ArrayList<>();

    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      int callStart = indexOfCall(line);
      if (callStart < 0) {
        continue;
      }
      // A call may span several lines; collect it up to balanced parentheses.
      String call = collectCall(lines, i);
      if (!concatenatesAString(call)) {
        continue;
      }
      if (defersItsArguments(call)) {
        // A supplier argument is a BETTER answer than a guard, not a worse one, so it
        // satisfies this rule. The concatenation inside the lambda body runs only if
        // something invokes the supplier, which happens only when the level is on --
        // exactly the property this test protects.
        continue;
      }
      if (hasEnabledCheckAbove(lines, i)) {
        continue;
      }
      violations.add(file + ":" + (i + 1) + "  " + line.trim());
    }
    return violations;
  }

  private static int indexOfCall(String line) {
    int t = line.indexOf("FRLogger.trace(");
    int d = line.indexOf("FRLogger.debug(");
    if (t < 0) {
      return d;
    }
    return d < 0 ? t : Math.min(t, d);
  }

  private static String collectCall(List<String> lines, int startLine) {
    StringBuilder sb = new StringBuilder();
    int depth = 0;
    boolean started = false;
    for (int i = startLine; i < lines.size() && i < startLine + 40; i++) {
      String l = lines.get(i);
      sb.append(l).append('\n');
      for (char c : l.toCharArray()) {
        if (c == '(') {
          depth++;
          started = true;
        } else if (c == ')') {
          depth--;
        }
      }
      if (started && depth <= 0) {
        break;
      }
    }
    return sb.toString();
  }

  /**
   * True when the call joins a string literal to something else. Deliberately narrow:
   * we are looking for message building, not arithmetic inside an argument.
   */
  private static boolean concatenatesAString(String call) {
    return call.matches("(?s).*\"\\s*\\+.*") || call.matches("(?s).*\\+\\s*\".*");
  }

  /**
   * True when the call passes its message as a supplier rather than a built String.
   *
   * <p>Preferred over the enabled-check, and the reason this test had to learn about it:
   * the 5-argument {@code FRLogger.trace} also drives {@code DebugControl.check()}, which
   * implements single-step execution. Guarding that call with {@code isTraceEnabled()}
   * therefore silenced the DEBUGGER whenever the level was above TRACE -- 37 call sites
   * did exactly that. Deferring the arguments allocates nothing when the level is off AND
   * leaves the debugger reachable, so a guard is no longer the remedy this test should be
   * asking for.
   */
  private static boolean defersItsArguments(String call) {
    return call.contains("() ->");
  }

  private static boolean hasEnabledCheckAbove(List<String> lines, int callLine) {
    int from = Math.max(0, callLine - GUARD_LOOKBEHIND_LINES);
    for (int i = from; i <= callLine; i++) {
      String l = lines.get(i);
      if (l.contains("isTraceEnabled()") || l.contains("isDebugEnabled()")) {
        return true;
      }
    }
    return false;
  }
}
