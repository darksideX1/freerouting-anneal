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
 * A zero-length array carries no state, so allocating a fresh one per call is pure
 * garbage. {@code new Point[0]} appears 27 times, almost all of them as the
 * "impacted points" argument of a diagnostic call in the routing path — allocated
 * whether or not the diagnostic is ever emitted.
 *
 * <p>{@code Point.EMPTY} exists for this. One shared immutable instance, no allocation.
 */
class NoEmptyArrayAllocationArchTest {

  @Test
  void productionCodeMustNotAllocateEmptyPointArrays() throws IOException {
    List<String> violations = new ArrayList<>();
    Path root = Path.of("src/main/java/app/freerouting");

    try (Stream<Path> files = Files.walk(root)) {
      for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
        if (file.endsWith("Point.java")) {
          continue; // the constant itself has to allocate exactly once
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
          String line = lines.get(i);
          String trimmed = line.trim();
          if (trimmed.startsWith("*") || trimmed.startsWith("//")) {
            continue;
          }
          if (line.contains("new Point[0]")
              || line.contains("new app.freerouting.geometry.planar.Point[0]")) {
            violations.add(file + ":" + (i + 1) + "  " + trimmed);
          }
        }
      }
    }

    assertTrue(
        violations.isEmpty(),
        "Empty Point arrays are allocated per call. Use the shared Point.EMPTY constant —\n"
            + "a zero-length array holds no state, so one instance serves every caller.\n\n"
            + String.join("\n", violations));
  }
}
