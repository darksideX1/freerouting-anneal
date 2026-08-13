package app.freerouting.board;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import app.freerouting.geometry.planar.IntBox;
import app.freerouting.geometry.planar.TileShape;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The enlargement memo must let go of shapes the item no longer has.
 *
 * <p>Keying on the shape instance was deliberate: a replaced shape is a different object, so
 * it cannot inherit its predecessor's enlargement. An earlier index-keyed version did, and
 * returned the enlargement of a superseded shape for 17% of lookups -- a clearance violation
 * in a board somebody fabricates.
 *
 * <p>That choice made stale entries unreachable instead of wrong, and unreachable was then
 * treated as harmless. It is not: the map still holds them, so a trace repeatedly modified
 * during shoving and pull-tight accumulates every geometry it has ever had. The memo was
 * built to reduce work on long optimisation runs, and those are precisely the runs on which
 * it grows.
 */
class EnlargementMemoEvictionTest {

  @Test
  @Timeout(10)
  @DisplayName("replacing an item's shapes drops the enlargements of the ones it no longer has")
  void supersededShapesAreNotRetained() {
    ItemSearchTreesInfo info = new ItemSearchTreesInfo();

    TileShape original = new IntBox(0, 0, 100, 100);
    TileShape replacement = new IntBox(0, 0, 200, 200);

    // The tree identity is only used to find the right slot; null is a legitimate key here
    // and keeps this a test of the memo rather than of the search tree.
    info.set_precalculated_tree_shapes(new TileShape[] {original}, null);

    TileShape enlargedOnce = info.enlarged(original, 10, null);
    assertSame(enlargedOnce, info.enlarged(original, 10, null),
        "the memo must return the same instance for the same shape and clearance, or it is "
            + "not memoising at all");

    // What change_entries() does: the item's shapes are replaced wholesale.
    info.set_precalculated_tree_shapes(new TileShape[] {replacement}, null);

    assertNotSame(enlargedOnce, info.enlarged(original, 10, null),
        "the enlargement of a shape the item no longer holds must not still be cached; "
            + "recomputing it is the point, since nothing can reach that shape again");
  }

  @Test
  @Timeout(10)
  @DisplayName("shapes the item keeps across a change keep their enlargements")
  void retainedShapesKeepTheirEnlargements() {
    ItemSearchTreesInfo info = new ItemSearchTreesInfo();

    TileShape kept = new IntBox(0, 0, 100, 100);
    TileShape added = new IntBox(0, 0, 200, 200);

    info.set_precalculated_tree_shapes(new TileShape[] {kept}, null);
    TileShape enlargedKept = info.enlarged(kept, 10, null);

    // change_entries keeps a run of shapes at each end and replaces the middle, reusing the
    // very same instances for the kept ones. Evicting those would throw away valid work.
    info.set_precalculated_tree_shapes(new TileShape[] {kept, added}, null);

    assertSame(enlargedKept, info.enlarged(kept, 10, null),
        "a shape the item still holds must not lose its enlargement just because a "
            + "neighbouring shape changed");
  }
}
