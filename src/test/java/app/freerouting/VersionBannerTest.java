package app.freerouting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The build says which build it is.
 *
 * <p>Every cut of this fork reports itself as {@code v2.3.x-SNAPSHOT}. That number does
 * not distinguish our cuts from each other or from upstream, so the handout had to tell
 * people to record a commit sha by hand and the changelog had to lead with "identity is
 * the commit, not a version string". Asking a human to carry information the program
 * already has is a documentation workaround for a product defect.
 *
 * <p>Three jars were cut today, byte-different, all self-reporting the same version. Given
 * two of them on disk, nobody could say which was which without asking me.
 *
 * <p>A build from a modified tree is marked {@code -dirty}, because it is NOT the commit
 * it names: nobody can reproduce it from that sha, and a build that claims an identity it
 * cannot support is worse than one that admits it has none.
 */
class VersionBannerTest {

  @Test
  void aLaneBuildNamesItsCommit() {
    assertEquals("v2.3.1-SNAPSHOT (build 4ba1b7d1, build-date: 2026-08-08)",
        Freerouting.formatVersionBanner("2.3.1-SNAPSHOT", "4ba1b7d1", "2026-08-08"));
  }

  @Test
  void aDirtyTreeIsAdmitted() {
    // The important one. This jar cannot be reproduced from that sha and must say so.
    String banner = Freerouting.formatVersionBanner("2.3.1-SNAPSHOT", "4ba1b7d1-dirty", "2026-08-08");
    assertTrue(banner.contains("4ba1b7d1-dirty"), banner);
  }

  @Test
  void anUpstreamBuildIsUnchanged() {
    // No git, no sha, a source tarball: the banner is exactly what it always was, so this
    // costs an upstream user nothing and adds no noise where it would mean nothing.
    assertEquals("v2.3.1 (build-date: 2026-08-08)",
        Freerouting.formatVersionBanner("2.3.1", "", "2026-08-08"));
    assertEquals("v2.3.1 (build-date: 2026-08-08)",
        Freerouting.formatVersionBanner("2.3.1", null, "2026-08-08"));
  }

  @Test
  void whitespaceFromTheBuildIsNotTakenAsAnIdentity() {
    // git output arrives with a newline; a blank capture must not render "(build , ...)".
    assertFalse(Freerouting.formatVersionBanner("2.3.1", "   ", "2026-08-08").contains("build "));
  }

  @Test
  void theBannerIsOneGreppableLine() {
    String banner = Freerouting.formatVersionBanner("2.3.1-SNAPSHOT", "4ba1b7d1", "2026-08-08");
    assertFalse(banner.contains("\n"), "a bug report gets pasted as one line");
    assertTrue(banner.startsWith("v"), banner);
  }
}
