package app.freerouting.autoroute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Racing threads must explore DIFFERENT orderings, reproducibly.
 *
 * <p>The dead multi-threaded pass shuffled every thread's work list from one shared
 * {@code Random}. That fails at both halves of its own idea:
 *
 * <ul>
 *   <li><b>Not reproducible.</b> Threads draw from the shared generator in whatever order
 *       they happen to reach it, so the same run does not repeat — and racing is a
 *       best-of-N search, so an unreproducible race cannot be measured, compared, or
 *       debugged. Freerouting is already nondeterministic (defect 20); a new feature must
 *       not add to it.</li>
 *   <li><b>Not guaranteed diverse.</b> Nothing makes thread 2's ordering differ from
 *       thread 1's. N threads that explore the same order are N times the cost for one
 *       result, which is the opposite of the point.</li>
 * </ul>
 *
 * <p>A seed derived from (run, pass, thread) fixes both: each thread gets its own
 * generator, the set is distinct by construction, and the whole race replays exactly from
 * the run seed.
 */
class RacingSeedTest {

  @Test
  void theSameCoordinatesAlwaysGiveTheSameSeed() {
    // Reproducibility: the entire race replays from the run seed alone.
    assertEquals(
        BatchAutorouter.orderingSeedFor(12345L, 3, 2),
        BatchAutorouter.orderingSeedFor(12345L, 3, 2));
  }

  @Test
  void threadsInTheSamePassGetDifferentSeeds() {
    // Diversity: this is what makes it a race rather than N copies of one search.
    Set<Long> seeds = new HashSet<>();
    for (int thread = 0; thread < 16; thread++) {
      assertTrue(seeds.add(BatchAutorouter.orderingSeedFor(42L, 1, thread)),
          "thread " + thread + " repeated a seed already used in this pass");
    }
  }

  @Test
  void thesamethreadGetsADifferentSeedEachPass() {
    // Otherwise thread 0 re-explores its identical ordering on every pass and the later
    // passes contribute nothing.
    Set<Long> seeds = new HashSet<>();
    for (int pass = 1; pass <= 16; pass++) {
      assertTrue(seeds.add(BatchAutorouter.orderingSeedFor(42L, pass, 0)),
          "pass " + pass + " repeated a seed already used by this thread");
    }
  }

  @Test
  void adifferentRunSeedGivesADifferentRace() {
    assertNotEquals(
        BatchAutorouter.orderingSeedFor(1L, 1, 0),
        BatchAutorouter.orderingSeedFor(2L, 1, 0));
  }

  @Test
  void seedsDoNotCollideAcrossTheWholeGrid() {
    // The cheap mistake here is pass*k+thread arithmetic, which collides as soon as the
    // thread count exceeds k. Walk a realistic grid and assert every seed is distinct.
    Set<Long> seeds = new HashSet<>();
    int expected = 0;
    for (int pass = 1; pass <= 40; pass++) {
      for (int thread = 0; thread < 32; thread++) {
        seeds.add(BatchAutorouter.orderingSeedFor(7L, pass, thread));
        expected++;
      }
    }
    assertEquals(expected, seeds.size(), "seed collision across the pass/thread grid");
  }
}
