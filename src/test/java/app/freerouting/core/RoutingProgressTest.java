package app.freerouting.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The clock a user watches while the router works.
 *
 * <p>Its job is to answer "is this alive, and how much longer?" — so the failure that
 * matters is not an off-by-one, it is any rendering that makes a working router look
 * finished, stuck, or past its deadline.
 */
class RoutingProgressTest {

  @Test
  void showsElapsedAgainstTheLimit() {
    assertEquals("routing 3:00 of 15:00", RoutingProgress.format(3 * 60, 15L * 60));
  }

  @Test
  void showsElapsedAloneWhenThereIsNoLimit() {
    assertEquals("routing 7:00", RoutingProgress.format(7 * 60, null));
    assertEquals("routing 7:00", RoutingProgress.format(7 * 60, 0L));
  }

  @Test
  void everyThirtySecondTickVisiblyMoves() {
    // The reason for seconds at all. At a 30-second cadence a minutes-only clock reads
    // 0, 1, 1, 2 — half the updates appearing to change nothing, which is exactly the
    // "is it stuck?" impression the clock exists to remove.
    Long limit = 2L * 60;
    assertEquals("routing 0:30 of 2:00", RoutingProgress.format(30, limit));
    assertEquals("routing 1:00 of 2:00", RoutingProgress.format(60, limit));
    assertEquals("routing 1:30 of 2:00", RoutingProgress.format(90, limit));
    assertEquals("routing 2:00 of 2:00", RoutingProgress.format(120, limit));
  }

  @Test
  void secondsAreAlwaysTwoDigitsSoTheFieldKeepsItsWidth() {
    // Ragged widths down a log column read as noise; a fixed one reads as a clock.
    assertEquals("routing 0:05 of 2:00", RoutingProgress.format(5, 2L * 60));
    assertEquals("routing 0:00 of 2:00", RoutingProgress.format(0, 2L * 60));
  }

  @Test
  void theLimitIsShownExactly() {
    // No rounding now that seconds are visible — a 90-second limit is 1:30, not "2 min".
    assertEquals("routing 0:30 of 1:30", RoutingProgress.format(30, 90L));
  }

  @Test
  void aClockThatRanPastItsLimitStillReadsHonestly() {
    // The grace period means elapsed CAN exceed the limit. Clamping it would hide that
    // the deadline had passed, which is information the user wants.
    assertEquals("routing 2:15 of 2:00", RoutingProgress.format(135, 2L * 60));
  }

  @Test
  void negativeElapsedIsTreatedAsZero() {
    // Clock skew or a start time set after the first tick must not render "-1:00".
    assertEquals("routing 0:00 of 15:00", RoutingProgress.format(-30, 15L * 60));
  }

  @Test
  void aLongRunKeepsCountingInMinutes() {
    // No hours field: 125 minutes reads as 125:00, not 2:05:00. Anyone routing this long
    // is comparing against a limit they set in minutes.
    assertEquals("routing 125:00 of 120:00", RoutingProgress.format(125 * 60, 120L * 60));
  }
}
