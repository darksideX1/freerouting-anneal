package app.freerouting.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Spec: docs/fork/FINAL-REPORT-SPEC.md — the pure core, pinned red-first. */
class FinalRunReportTest {

  @Test
  void fileNameCarriesBoardAndTimestampSoTwoRunsNeverCollide() {
    Instant t1 = Instant.parse("2026-08-12T10:15:30Z");
    Instant t2 = Instant.parse("2026-08-12T10:16:31Z");
    String a = FinalRunReport.fileNameFor("myboard", t1);
    String b = FinalRunReport.fileNameFor("myboard", t2);
    assertTrue(a.startsWith("myboard_"), a);
    assertTrue(a.endsWith("_final-report.txt"), a);
    assertFalse(a.equals(b), "two runs of the same board must produce two files");
  }

  @Test
  void fileNameSanitizesHostilePathCharacters() {
    String n = FinalRunReport.fileNameFor("../we ird/bo:ard", Instant.parse("2026-08-12T10:15:30Z"));
    assertFalse(n.contains("/"), n);
    assertFalse(n.contains(":"), n);
    assertFalse(n.contains(" "), n);
  }

  @Test
  void composeStatesEndingCountsAndGap() {
    String r = FinalRunReport.compose("Ran out of time.", Duration.ofMinutes(15), 320, 8, 0,
        "  Net 'GND' (1 unrouted connection):\n    - J2-A3  ->  U1-4");
    assertTrue(r.startsWith("Ran out of time.\n"), r);
    assertTrue(r.contains("Routed:     312 of 320 connections"), r);
    assertTrue(r.contains("Unrouted:   8"), r);
    assertTrue(r.contains("Violations: 0"), r);
    assertTrue(r.contains("J2-A3  ->  U1-4"), r);
  }

  @Test
  void unroutedSectionAbsentRatherThanEmptyWhenNothingIsUnrouted() {
    String r = FinalRunReport.compose("Pass finished.", Duration.ofSeconds(90), 320, 0, 2, null);
    assertFalse(r.contains("Unrouted connections"), r);
    assertTrue(r.contains("Unrouted:   0"), r);
  }

  @Test
  void cancelledRunWritesNoReport() {
    RoutingJob job = new RoutingJob();
    job.state = RoutingJobState.CANCELLED;
    assertNull(FinalRunReport.write(job, null, "Cancelled on request."),
        "cancelling means discarding the work; no artifact may be produced from it");
  }

  @Test
  void reportCountsComeFromTheJobNotACopy() {
    // Guard against drift: the compose(job, board, ...) overload must read the SAME
    // duration object the job holds.
    RoutingJob job = new RoutingJob();
    job.startedAt = Instant.parse("2026-08-12T10:00:00Z");
    job.finishedAt = Instant.parse("2026-08-12T10:15:00Z");
    String r = FinalRunReport.compose(job, null, "Stopped on request.");
    assertTrue(r.contains("0:15:00"), r);
    assertEquals("Stopped on request.", r.lines().findFirst().orElse(""));
  }
}
