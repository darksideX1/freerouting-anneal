package app.freerouting.board;

import static org.junit.jupiter.api.Assertions.assertEquals;

import app.freerouting.board.ShapeSearchTree.EntrySortedByClearance;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Defect 27. The overlap candidates for a clearance check must all survive being ordered.
 *
 * <p>They were collected into a {@code TreeSet}, and {@code EntrySortedByClearance.compareTo}
 * breaks ties on an id drawn from a <b>static, unsynchronised</b> counter shared by every
 * thread and every cloned board. Racing runs several router threads through this path at
 * once, so two of them can be handed the same id; two entries with the same id AND the same
 * clearance compare equal, and a Set quietly keeps one of them.
 *
 * <p>A candidate that never arrives is a clearance check that never runs, so the router
 * believes the space is free. That is a wrong board, not a slower one.
 *
 * <p>The collision is forced here by rewinding the counter rather than by running threads:
 * the race is real but a test that depends on interleaving proves nothing on a good day.
 */
class ClearanceCandidateOrderingTest {

  /** Builds an entry whose id collides with the previous one, the way the race does. */
  private static EntrySortedByClearance withCollidingId(int clearance) throws Exception {
    Field counter = ShapeSearchTree.class.getDeclaredField("last_generated_id_no");
    counter.setAccessible(true);
    int before = counter.getInt(null);
    EntrySortedByClearance entry = new EntrySortedByClearance(null, clearance);
    counter.setInt(null, before); // next entry is handed the same id
    return entry;
  }

  @Test
  @DisplayName("two candidates that collide on id and clearance are both kept")
  void collidingCandidatesAreNotDiscarded() throws Exception {
    List<EntrySortedByClearance> candidates = new ArrayList<>();
    candidates.add(withCollidingId(100));
    candidates.add(new EntrySortedByClearance(null, 100));

    List<EntrySortedByClearance> sorted = ShapeSearchTree.sortByClearance(candidates);

    assertEquals(2, sorted.size(),
        "a candidate was dropped while ordering; that is a clearance check that never runs");
  }

  @Test
  @DisplayName("candidates come back grouped by clearance, smallest first")
  void candidatesAreGroupedByAscendingClearance() {
    List<EntrySortedByClearance> candidates = new ArrayList<>();
    candidates.add(new EntrySortedByClearance(null, 50));
    candidates.add(new EntrySortedByClearance(null, 10));
    candidates.add(new EntrySortedByClearance(null, 50));
    candidates.add(new EntrySortedByClearance(null, 30));

    List<EntrySortedByClearance> sorted = ShapeSearchTree.sortByClearance(candidates);

    // The consumer only rebuilds the enlarged shape when the clearance changes, so equal
    // clearances have to stay adjacent or the work is done more than once.
    assertEquals(List.of(10, 30, 50, 50), sorted.stream().map(e -> e.clearance).toList(),
        "candidates must be grouped by ascending clearance");
  }
}
