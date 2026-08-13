package app.freerouting.settings;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Config and logs must live somewhere DURABLE by default. The inherited default was the
 * JVM temp directory: settings and the log the docs point at were wiped by any reboot or
 * tmp cleanup -- "check the log" pointed at a file that no longer existed.
 */
class DefaultUserDataPathTest {

  @Test
  void defaultIsNotTheTempDirectory() {
    // Temp IS the documented last resort -- but only for environments with no home at
    // all. Anywhere a home resolves (every CI and dev box), the default must be durable.
    org.junit.jupiter.api.Assumptions.assumeTrue(
        System.getProperty("user.home") != null && !System.getProperty("user.home").isBlank());
    Path p = GlobalSettings.defaultUserDataPath();
    Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
    assertFalse(p.toAbsolutePath().startsWith(tmp.toAbsolutePath()),
        "user data defaulted into the temp directory: " + p);
  }

  @Test
  void defaultIsPlatformAppData() {
    String p = GlobalSettings.defaultUserDataPath().toString().replace('\\', '/');
    String os = System.getProperty("os.name", "").toLowerCase();
    if (os.contains("win")) {
      assertTrue(p.contains("AppData") || p.contains("freerouting"), p);
    } else if (os.contains("mac")) {
      assertTrue(p.endsWith("Library/Application Support/freerouting"), p);
    } else {
      assertTrue(p.endsWith("freerouting") && (p.contains(".local/share") || p.contains("xdg") || System.getenv("XDG_DATA_HOME") != null), p);
    }
  }
}
