package app.freerouting.autoroute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Phase C: the memory budget's decisions, pinned. The hard rules come from independent
 * review and are binding: a degradation is a first-class outcome, a budget below the
 * single-clone floor refuses with the numbers named (zero workers is never something to
 * run with), and an unusable safety measurement is never read as unlimited capacity.
 */
class OptimizerMemoryBudgetTest {

  @Test
  @Timeout(10)
  @DisplayName("the derived default is 60% of max heap — numeric, not a silent percentage")
  void defaultIsSixtyPercent() {
    assertEquals(6_000L, OptimizerMemoryBudget.defaultBudgetBytes(10_000L));
  }

  @Test
  @Timeout(10)
  @DisplayName("unset budget means the derived default, quietly")
  void unsetIsQuietlyNull() {
    List<String> errors = new ArrayList<>();
    assertNull(OptimizerMemoryBudget.validateBudgetMb(null, errors::add));
    assertTrue(errors.isEmpty());
  }

  @Test
  @Timeout(10)
  @DisplayName("zero or negative budget is rejected loudly, never silently mode-switched")
  void nonPositiveBudgetIsLoud() {
    List<String> errors = new ArrayList<>();
    assertNull(OptimizerMemoryBudget.validateBudgetMb(0, errors::add));
    assertNull(OptimizerMemoryBudget.validateBudgetMb(-512, errors::add));
    assertEquals(2, errors.size());
    assertTrue(errors.get(0).contains("NOT applied"));
  }

  @Test
  @Timeout(10)
  @DisplayName("width is capped by what the budget can hold")
  void widthCappedByBudget() {
    // budget holds 3 clones, 8 requested -> 3
    assertEquals(3, OptimizerMemoryBudget.effectiveWidth(8, 3_000L, 1_000L));
    // budget holds plenty -> requested wins
    assertEquals(4, OptimizerMemoryBudget.effectiveWidth(4, 100_000L, 1_000L));
  }

  @Test
  @Timeout(10)
  @DisplayName("a budget below one clone REFUSES (returns 0) — zero workers is never run with")
  void belowFloorRefuses() {
    assertEquals(0, OptimizerMemoryBudget.effectiveWidth(8, 500L, 1_000L),
        "the caller must fall back to the in-place single-threaded pass, with the numbers "
            + "named — never degrade to a zero-worker pool and hang");
  }

  @Test
  @Timeout(10)
  @DisplayName("an unusable clone measurement means ONE clone, never unlimited")
  void failedMeasurementIsConservative() {
    assertEquals(1, OptimizerMemoryBudget.effectiveWidth(8, 100_000L, 0L));
    assertEquals(1, OptimizerMemoryBudget.effectiveWidth(8, 100_000L, -1L));
  }

  /**
   * The most-to-gain selector's ordering contract, pinned against PRIORITIZED's. Same data,
   * mirrored bets: PRIORITIZED polls the best after-state first (polish toward perfect);
   * MOST_TO_GAIN polls the worst first (rescue what has most to gain).
   */
  @Test
  @Timeout(10)
  @DisplayName("MOST_TO_GAIN is the exact mirror of PRIORITIZED's ordering")
  void mostToGainMirrorsPrioritized() {
    ItemRouteResult good = new ItemRouteResult(1, 10, 5, 1000.0, 900.0, 3, 1);   // improved a lot
    ItemRouteResult bad = new ItemRouteResult(2, 10, 10, 1000.0, 1000.0, 3, 3);  // unchanged, worse state

    PriorityQueue<ItemRouteResult> best_first = new PriorityQueue<>();
    best_first.add(good);
    best_first.add(bad);
    assertEquals(1, best_first.poll().item_id(), "PRIORITIZED: the best after-state first");

    PriorityQueue<ItemRouteResult> gain_first = new PriorityQueue<>(Comparator.reverseOrder());
    gain_first.add(good);
    gain_first.add(bad);
    assertEquals(2, gain_first.poll().item_id(), "MOST_TO_GAIN: the worst after-state first");
  }
}
