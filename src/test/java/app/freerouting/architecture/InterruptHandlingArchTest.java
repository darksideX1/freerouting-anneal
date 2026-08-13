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
 * Catching {@link InterruptedException} without restoring the thread's interrupt status
 * swallows a cancellation request: later blocking calls, and any loop that checks
 * {@code Thread.interrupted()} to decide whether to keep going, will never learn that a
 * stop was asked for.
 *
 * <p>The codebase already follows this convention everywhere it matters -- except one
 * site, where the restoring line is present but commented out, with a comment saying
 * exactly what it is for. This test keeps that from happening again.
 */
class InterruptHandlingArchTest {

  @Test
  void interruptedExceptionHandlersMustNotLeaveTheRestoreLineCommentedOut()
      throws IOException {
    List<String> violations = new ArrayList<>();
    Path root = Path.of("src/main/java/app/freerouting");

    try (Stream<Path> files = Files.walk(root)) {
      for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
          String trimmed = lines.get(i).trim();
          // A commented-out interrupt restoration is never intentional design: either
          // the call belongs there, or the line should be gone along with its comment.
          if (trimmed.startsWith("//") && trimmed.contains("Thread.currentThread().interrupt()")) {
            violations.add(file + ":" + (i + 1) + "  " + trimmed);
          }
        }
      }
    }

    assertTrue(
        violations.isEmpty(),
        "Interrupt-status restoration is commented out. Either restore it, or delete the\n"
            + "dead line and record why cancellation is deliberately swallowed here.\n\n"
            + String.join("\n", violations));
  }
}
