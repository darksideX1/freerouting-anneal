package app.freerouting.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import app.freerouting.settings.sources.DefaultSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A default assigned in two places, disagreeing, is this fork's most repeated defect.
 *
 * <p>It has happened twice. {@code maxConsecutiveFailures} was assigned 50 in {@link
 * DefaultSettings} while {@code BatchOptimizer} carried a {@code : 150} fallback — which made
 * the fallback unreachable, so a release commit titled "default optimizer stagnation threshold
 * 150" changed dead code and the documented default was wrong for the whole of 1.0.2. And
 * {@code RouterSettings.getRunOptimizer()} fell back to {@code false} while DefaultSettings
 * assigned {@code true}: unreachable through the merger, but wrong-signed, so any future path
 * reading it before the merge would silently report the optimiser disabled.
 *
 * <p>Both were invisible to review because each site is correct in isolation. The defect only
 * exists in the disagreement between them.
 *
 * <p>So this test does not assert a literal — asserting the value the author just edited is
 * precisely how both survived. It reads what DefaultSettings actually assigns and requires the
 * getter's fallback to agree with it.
 */
class SettingsFallbackAgreementTest {

  @Test
  @Timeout(10)
  @DisplayName("getRunOptimizer's fallback agrees with the value DefaultSettings assigns")
  void runOptimizerFallbackMatchesTheRealDefault() {
    RouterSettings merged = new SettingsMerger(new DefaultSettings()).merge();
    assertNotNull(merged.optimizer, "DefaultSettings must populate the optimizer block");
    assertNotNull(merged.optimizer.enabled, "DefaultSettings must assign optimizer.enabled");
    boolean realDefault = merged.optimizer.enabled;

    // A settings object that never went through the merger: the fallback is what answers.
    RouterSettings unmerged = new RouterSettings();
    unmerged.optimizer = new OptimizerSettings();
    unmerged.optimizer.enabled = null;

    assertEquals(realDefault, unmerged.getRunOptimizer(),
        "the fallback in getRunOptimizer() disagrees with DefaultSettings. One of the two "
            + "has been changed without the other — the exact shape of the 150 dead default.");
  }

  @Test
  @Timeout(10)
  @DisplayName("the rounds knob defaults to unset, so the automatic guard is what runs")
  void roundsIsUnsetByDefault() {
    RouterSettings merged = new SettingsMerger(new DefaultSettings()).merge();
    assertNotNull(merged.optimizer);
    assertEquals(null, merged.optimizer.rounds,
        "rounds must stay null by default. Assigning it in DefaultSettings would make the "
            + "null branch unreachable and silently switch every run onto the item cap — "
            + "which is how the 150 fallback became dead code.");
  }
}
