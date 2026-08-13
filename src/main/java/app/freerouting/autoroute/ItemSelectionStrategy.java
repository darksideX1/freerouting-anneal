package app.freerouting.autoroute;

public enum ItemSelectionStrategy {
  SEQUENTIAL, RANDOM,
  /** Best-first: polish what is already good toward perfect. Orders next pass by the
   * previous pass's per-item results, best after-state first. */
  PRIORITIZED,
  /** The mirror bet: rescue what has the most to gain. Same ordering data, reversed --
   * worst after-state first. A genuinely different strategy, measured separately. */
  MOST_TO_GAIN
}