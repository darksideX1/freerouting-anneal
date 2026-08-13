package app.freerouting.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * "New version available" must mean a NEWER version is available.
 *
 * <p>The check compared the two strings for inequality, so a build ahead of the latest
 * release announced that release as an upgrade. This fork runs {@code v2.3.1-SNAPSHOT}
 * and told every user on every run: "New version available: v2.3.0".
 *
 * <p>Small, but it lands in the first ten lines a new user ever sees, and it teaches them
 * that this program's INFO output is not to be trusted — which is expensive, because the
 * rest of this fork's work is precisely about making its output trustworthy.
 */
class VersionComparisonTest {

  @Test
  void aGenuinelyNewerReleaseIsOffered() {
    assertTrue(VersionChecker.isNewerVersion("v2.3.0", "v2.4.0"));
    assertTrue(VersionChecker.isNewerVersion("v2.3.0", "v2.3.1"));
    assertTrue(VersionChecker.isNewerVersion("v2.3.0", "v3.0.0"));
  }

  @Test
  void anOlderReleaseIsNotAnUpgrade() {
    // THE BUG, in its exact shape.
    assertFalse(VersionChecker.isNewerVersion("v2.3.1-SNAPSHOT", "v2.3.0"));
    assertFalse(VersionChecker.isNewerVersion("v2.4.0", "v2.3.0"));
  }

  @Test
  void theSameVersionIsNotAnUpgrade() {
    assertFalse(VersionChecker.isNewerVersion("v2.3.0", "v2.3.0"));
    assertFalse(VersionChecker.isNewerVersion("v2.3.0", "2.3.0"));
  }

  @Test
  void aSnapshotIsTreatedAsItsBaseVersion() {
    // A local build of 2.3.1 is not behind the 2.3.1 release, and IS behind 2.3.2.
    assertFalse(VersionChecker.isNewerVersion("v2.3.1-SNAPSHOT", "v2.3.1"));
    assertTrue(VersionChecker.isNewerVersion("v2.3.1-SNAPSHOT", "v2.3.2"));
  }

  @Test
  void unevenSegmentCountsCompareSensibly() {
    assertTrue(VersionChecker.isNewerVersion("v2.3", "v2.3.1"));
    assertFalse(VersionChecker.isNewerVersion("v2.3.1", "v2.3"));
    assertFalse(VersionChecker.isNewerVersion("v2.3.0", "v2.3"));
  }

  @Test
  void unparseableVersionsNeverNag() {
    // If we cannot tell, saying nothing beats claiming an upgrade that may not exist.
    assertFalse(VersionChecker.isNewerVersion("weird", "v2.3.0"));
    assertFalse(VersionChecker.isNewerVersion("v2.3.0", "weird"));
    assertFalse(VersionChecker.isNewerVersion(null, "v2.3.0"));
    assertFalse(VersionChecker.isNewerVersion("v2.3.0", null));
  }
}
