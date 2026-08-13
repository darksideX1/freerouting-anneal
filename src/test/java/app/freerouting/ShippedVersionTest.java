package app.freerouting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.constants.Constants;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The version this fork ships under, everywhere it is written down.
 *
 * <p>The first version of this test asserted only {@code Constants.FREEROUTING_VERSION} --
 * the one value the release commit had changed. It passed while
 * {@code integrations/KiCad/.../plugin.ini} still launched {@code freerouting-2.3.0.jar} and
 * {@code integrations/mcp-server/package.json} still declared 2.3.0, so a KiCad user would
 * have installed the release and run the previous binary. A test that checks the edit you
 * just made is a test that agrees with you.
 *
 * <p>So the contract here is agreement across every artifact a user receives, plus one
 * thing no version string can tell you: that the jar the KiCad plugin names is actually
 * present. Repointing that line at a file that does not exist is worse than leaving it
 * stale -- stale launches the wrong router, missing launches nothing.
 */
class ShippedVersionTest {

  // NOTE for anyone running these locally: they read build files, workflows and manifests
  // that Gradle does not track as inputs to the test task, so an incremental run will
  // report the task up-to-date and skip them after an edit to those files. Use
  // --rerun-tasks when you have changed one. CI checks out fresh, so it always runs them.


  /** Repo root, whether the test runs from the project dir or a nested working dir. */
  private static Path repoRoot() {
    Path here = Paths.get("").toAbsolutePath();
    while (here != null && !Files.exists(here.resolve("gradle/project-info.gradle"))) {
      here = here.getParent();
    }
    return here;
  }

  private static String read(String relative) throws IOException {
    Path root = repoRoot();
    assertTrue(root != null, "could not locate the repo root from the test working directory");
    Path file = root.resolve(relative);
    assertTrue(Files.exists(file), "shipped artifact is missing: " + relative);
    return Files.readString(file, StandardCharsets.UTF_8);
  }

  @Test
  @Timeout(10)
  @DisplayName("the build stamps a usable version")
  void versionIsStamped() {
    String version = Constants.FREEROUTING_VERSION;
    assertFalse(version == null || version.isBlank(), "the build must stamp a version");
    assertTrue(version.matches("\\d+\\.\\d+\\.\\d+(-.+)?"),
        "version should be semver-shaped, was: " + version);
  }

  @Test
  @Timeout(10)
  @DisplayName("every shipped integration artifact declares the jar's version")
  void integrationsAgreeWithTheJar() throws IOException {
    String version = Constants.FREEROUTING_VERSION;

    for (String artifact : new String[] {
        "integrations/mcp-server/package.json",
        "integrations/KiCad/metadata.json",
        "integrations/KiCad/kicad-freerouting/metadata.json",
    }) {
      String body = read(artifact);
      assertTrue(body.contains("\"" + version + "\""),
          artifact + " does not declare " + version
              + " -- a release that leaves an integration behind ships two different"
              + " versions of itself");
    }
  }

  @Test
  @Timeout(10)
  @DisplayName("the KiCad plugin names a jar that exists and is ours")
  void kicadPluginPointsAtARealJar() throws IOException {
    String ini = read("integrations/KiCad/kicad-freerouting/plugins/plugin.ini");

    String marker = "location = ";
    int at = ini.indexOf(marker);
    assertTrue(at >= 0, "plugin.ini must declare a jar location");
    String location = ini.substring(at + marker.length()).split("\\R", 2)[0].trim();

    assertTrue(location.contains(Constants.FREEROUTING_VERSION),
        "the KiCad plugin launches " + location + ", not " + Constants.FREEROUTING_VERSION
            + " -- users would install this release and run a different router");

    // The jar is no longer committed -- the kicadPlugin task stages it at build time,
    // because a jar sitting in the tree makes the tree dirty and so makes itself
    // irreproducible. What must still hold is that the task produces exactly the name
    // plugin.ini asks for: plugin.py launches whatever that line names, so a mismatch is
    // a plugin that starts nothing at all.
    String buildFile = read("build.gradle");
    assertTrue(buildFile.contains("tasks.register('kicadPlugin'"),
        "something must stage the KiCad jar now that the repository does not carry it");
    assertTrue(
        buildFile.contains("\"${publishInfo.artifactId}-${publishInfo.versionId}.jar\""),
        "the kicadPlugin task must stage the jar under the artifact-and-version name that"
            + " plugin.ini asks for, or the plugin launches a file that is not there");
  }

  @Test
  @Timeout(10)
  @DisplayName("the gradle version and the compiled constant have not drifted apart")
  void gradleAndConstantAgree() throws IOException {
    String gradle = read("gradle/project-info.gradle");
    String declared = gradle.split("ext\\.publishInfo\\.versionId = '", 2)[1].split("'", 2)[0];
    assertEquals(declared, Constants.FREEROUTING_VERSION,
        "the jar was built from a different version than the build file now declares");
  }

  @Test
  @Timeout(10)
  @DisplayName("the release actually builds and uploads the KiCad archive")
  void releaseShipsTheKicadPlugin() throws IOException {
    String release = read(".github/workflows/create-release.yml");

    assertTrue(release.contains("gradlew kicadPlugin"),
        "the release must build the KiCad archive. The jar is deliberately not committed,"
            + " so the release is the ONLY thing that can produce it -- if the release does"
            + " not run this task, the plugin ships with no jar at all");
    assertTrue(release.contains("kicad-freerouting-${{ steps.tagName.outputs.tag }}.zip"),
        "building the archive is not shipping it; the release must upload it as an asset");
  }

  @Test
  @Timeout(10)
  @DisplayName("the KiCad manifest points where the archive actually lands")
  void kicadManifestPointsAtOurRelease() throws IOException {
    String version = Constants.FREEROUTING_VERSION;

    for (String manifest : new String[] {
        "integrations/KiCad/metadata.json",
        "integrations/KiCad/kicad-freerouting/metadata.json",
    }) {
      String body = read(manifest);
      String archive = "kicad-freerouting-" + version + ".zip";
      int at = body.indexOf(archive);
      if (at < 0) {
        continue; // no entry for the current version in this manifest
      }
      // Walk back to the enclosing download_url value and check whose repo it names.
      int urlStart = body.lastIndexOf("https://", at);
      String url = body.substring(urlStart, at + archive.length());

      assertTrue(url.contains("darksideX1/freerouting-anneal"),
          manifest + " advertises " + archive + " at " + url + " -- that is not our"
              + " repository, so the file will never be there. Historical entries may point"
              + " upstream because those releases exist there; ours does not.");
      assertFalse(url.contains("/raw/master/"),
          manifest + " points at a path in the repository tree, but the archive is a"
              + " release asset and is deliberately not committed: " + url);
    }
  }

  @Test
  @Timeout(10)
  @DisplayName("the committed KiCad manifest claims no checksum it cannot know")
  void kicadChecksumIsGeneratedAtReleaseTime() throws IOException {
    String manifest = read("integrations/KiCad/metadata.json");
    String version = Constants.FREEROUTING_VERSION;

    int at = manifest.indexOf("\"version\": \"" + version + "\"");
    assertTrue(at >= 0, "the manifest has no entry for the shipped version " + version);
    String entry = manifest.substring(at, Math.min(manifest.length(), at + 600));

    // The archive embeds a jar stamped with its build date and commit, so it is never
    // byte-identical to one built anywhere else. Any hash committed here would therefore
    // describe a different file than the one users download, and KiCad refuses an archive
    // whose hash or size disagrees with its manifest -- it would reject the install
    // outright. The real values are computed by the release from the artifact it built.
    assertTrue(entry.contains("\"download_sha256\": \"" + "0".repeat(64) + "\""),
        "the current version's checksum must stay a zero placeholder in the tree: a"
            + " committed hash cannot match a release-built archive, and a stale one makes"
            + " KiCad reject the download. Entry was: " + entry);

    String release = read(".github/workflows/create-release.yml");
    assertTrue(release.contains("kicad-manifest-checksums.py"),
        "something must fill those placeholders at release time, or the manifest ships"
            + " advertising a zero hash");
    assertTrue(release.contains("build/dist/metadata.json"),
        "the filled manifest must be uploaded, or nobody can see the real checksum");
  }
}
