package app.freerouting.datastructures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.datastructures.ShapeTree.Leaf;
import app.freerouting.geometry.planar.IntBox;
import app.freerouting.geometry.planar.OrthogonalBoundingDirections;
import app.freerouting.geometry.planar.TileShape;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link MinAreaTree#overlaps} collected its results into a {@code TreeSet}, which costs a
 * red-black node per result. JFR attributed 3.36 GB to this one method on a single power-b2
 * route -- 42% of every {@code TreeMap$Entry} allocated.
 *
 * <p>The Set was never doing Set work: the walk descends a tree, every node has exactly one
 * parent, so a leaf is reached at most once and a duplicate cannot arise. What it WAS doing
 * is ordering, and callers depend on that, so both properties are pinned here -- the results
 * must still come back complete and in {@link Leaf} order, and getting them must not cost a
 * tree node per result.
 */
class MinAreaTreeOverlapsAllocationTest {

  /** Minimum plausible size of a red-black node: header, three references, colour, key. */
  private static final int TREE_NODE_BYTES = 32;

  private static final int LEAF_COUNT = 200;

  /** Keeps results reachable so the JIT cannot elide the allocation being measured. */
  private static Object sink;

  /** Smallest thing that can live in a tree: ordered by id, one shape, nothing else. */
  private record Stub(int id) implements ShapeTree.Storable {
    @Override
    public int compareTo(Object other) {
      return Integer.compare(this.id, ((Stub) other).id);
    }

    @Override
    public int tree_shape_count(ShapeTree p_shape_tree) {
      return 1;
    }

    @Override
    public TileShape get_tree_shape(ShapeTree p_tree, int p_index) {
      return new IntBox(0, 0, 1000, 1000);
    }

    @Override
    public void set_search_tree_entries(Leaf[] p_entries, ShapeTree p_tree) {
      // Nothing to remember: this stub is never deleted from the tree.
    }
  }

  private static MinAreaTree treeWithOverlappingLeaves() {
    MinAreaTree tree = new MinAreaTree(OrthogonalBoundingDirections.INSTANCE);
    for (int i = 0; i < LEAF_COUNT; i++) {
      // All boxes contain the query box, so every leaf is a hit and the result set is the
      // whole tree -- which is the shape of the calls that dominated the profile.
      tree.insert(new Leaf(new Stub(i), 0, null, new IntBox(0, 0, 1000 + i, 1000 + i)));
    }
    return tree;
  }

  private static long allocatedBytes() {
    ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    bean.setThreadAllocatedMemoryEnabled(true);
    return bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
  }

  @Test
  @DisplayName("collecting overlaps must not cost a tree node per result")
  void overlapsMustNotAllocateATreeNodePerResult() {
    MinAreaTree tree = treeWithOverlappingLeaves();
    IntBox query = new IntBox(0, 0, 1000, 1000);
    final int iterations = 200;

    for (int i = 0; i < 50; i++) {
      sink = tree.overlaps(query); // warm up
    }

    long before = allocatedBytes();
    for (int i = 0; i < iterations; i++) {
      sink = tree.overlaps(query);
    }
    long used = allocatedBytes() - before;

    long nodeCost = (long) iterations * LEAF_COUNT * TREE_NODE_BYTES;
    assertTrue(
        used < nodeCost,
        "overlaps() allocated "
            + used
            + " bytes for "
            + iterations
            + " queries of "
            + LEAF_COUNT
            + " results. A red-black node per result would cost at least "
            + nodeCost
            + " bytes. The result collection is paying per-element tree overhead it does not "
            + "need: the walk cannot produce a duplicate, so nothing here requires a Set.");
  }

  @Test
  @DisplayName("results still come back complete and in Leaf order")
  void overlapsStillReturnsEveryLeafInOrder() {
    MinAreaTree tree = treeWithOverlappingLeaves();

    // Typed as Collection deliberately: this compiles against the old Set-returning
    // signature too, so reverting the implementation produces an ASSERTION failure rather
    // than a compile error. A red that is only "it does not build" proves the API moved,
    // not that the allocation was ever excessive.
    Collection<Leaf> found = tree.overlaps(new IntBox(0, 0, 1000, 1000));

    assertEquals(LEAF_COUNT, found.size(), "every overlapping leaf must be returned");
    List<Leaf> ordered = new ArrayList<>(found);
    for (int i = 1; i < ordered.size(); i++) {
      assertTrue(
          ordered.get(i - 1).compareTo(ordered.get(i)) <= 0,
          "results must be in Leaf order; the caller rebuilds state only when the key changes");
    }
  }
}
