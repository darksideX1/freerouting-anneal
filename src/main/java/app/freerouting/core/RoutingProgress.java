package app.freerouting.core;

/**
 * How long a run has been going, and how long it is allowed to go.
 *
 * <p>Routing a board can take minutes with no visible change. Without a clock the user
 * cannot tell a working router from a hung one, and the rational response to that
 * uncertainty is to kill it — throwing away work that was about to finish. A ticking
 * elapsed/limit pair answers the only two questions being asked: is it alive, and how much
 * longer might this go on.
 *
 * <p><b>Resolution follows cadence.</b> The headless heartbeat fires every 30 seconds, so
 * a minutes-only display would read 0, 1, 1, 2, 2, 3, 3 — every other update appearing to
 * change nothing, which is precisely the "is it stuck?" impression the clock exists to
 * dispel. Showing {@code m:ss} makes every update visibly move. Seconds are always two
 * digits so the field keeps a constant width and the numbers line up down the log.
 */
public final class RoutingProgress {

  private RoutingProgress() {
  }

  /**
   * Renders elapsed time against the limit, as {@code m:ss}.
   *
   * @param elapsedSeconds seconds since the run started; negative is treated as zero
   * @param limitSeconds   the wall-clock limit, or {@code null} when none applies
   */
  public static String format(long elapsedSeconds, Long limitSeconds) {
    String elapsed = clock(Math.max(0, elapsedSeconds));
    if (limitSeconds == null || limitSeconds <= 0) {
      return "routing " + elapsed;
    }
    // The limit is shown exactly. With seconds on display there is nothing to round, and
    // an exact figure is what a user compares their elapsed time against.
    return "routing " + elapsed + " of " + clock(limitSeconds);
  }

  /** {@code m:ss}, with minutes unpadded and seconds always two digits. */
  private static String clock(long totalSeconds) {
    return (totalSeconds / 60) + ":" + String.format("%02d", totalSeconds % 60);
  }
}
