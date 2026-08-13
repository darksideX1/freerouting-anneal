package app.freerouting.board;

import app.freerouting.datastructures.ShapeTree;
import app.freerouting.geometry.planar.TileShape;
import java.util.Collection;
import java.util.LinkedList;

/**
 * Stores information about the search trees of the board items, which is precalculated for performance reasons.
 */
class ItemSearchTreesInfo {

  private final Collection<SearchTreeInfo> tree_list;

  /**
   * Creates a new instance of ItemSearchTreeEntries
   */
  public ItemSearchTreesInfo() {
    this.tree_list = new LinkedList<>();
  }

  /**
   * Returns the tree entries for the tree with identification number p_tree_no, or null, if for this tree no entries of this item are inserted.
   */
  public ShapeTree.Leaf[] get_tree_entries(ShapeTree p_tree) {
    for (SearchTreeInfo curr_tree_info : this.tree_list) {
      if (curr_tree_info.tree == p_tree) {
        return curr_tree_info.entry_arr;
      }
    }
    return null;
  }

  /**
   * Sets the item tree entries for the tree with identification number p_tree_no.
   */
  public void set_tree_entries(ShapeTree.Leaf[] p_tree_entries, ShapeTree p_tree) {
    for (SearchTreeInfo curr_tree_info : this.tree_list) {
      if (curr_tree_info.tree == p_tree) {
        curr_tree_info.entry_arr = p_tree_entries;
        return;
      }
    }
    SearchTreeInfo new_tree_info = new SearchTreeInfo(p_tree);
    new_tree_info.entry_arr = p_tree_entries;
    this.tree_list.add(new_tree_info);
  }

  /**
   * Returns the precalculated tiles shapes for the tree with identification number p_tree_no, or null, if the tile shapes of this tree are not yet precalculated.
   */
  public TileShape[] get_precalculated_tree_shapes(ShapeTree p_tree) {
    for (SearchTreeInfo curr_tree_info : this.tree_list) {
      if (curr_tree_info.tree == p_tree) {
        return curr_tree_info.precalculated_tree_shapes;
      }
    }
    return null;
  }

  /**
   * Sets the item tree entries for the tree with identification number p_tree_no.
   */
  public void set_precalculated_tree_shapes(TileShape[] p_tile_shapes, ShapeTree p_tree) {
    for (SearchTreeInfo curr_tree_info : this.tree_list) {
      if (curr_tree_info.tree == p_tree) {
        curr_tree_info.precalculated_tree_shapes = p_tile_shapes;
        drop_enlargements_of_superseded_shapes(curr_tree_info, p_tile_shapes);
        return;
      }
    }
    SearchTreeInfo new_tree_info = new SearchTreeInfo(p_tree);
    new_tree_info.precalculated_tree_shapes = p_tile_shapes;
    this.tree_list.add(new_tree_info);
  }


  /**
   * Forgets enlargements of shapes this item no longer has.
   *
   * <p>Identity keying made a stale entry unreachable rather than wrong, and unreachable was
   * then treated as harmless. It is not. {@code change_entries} replaces a trace's shapes on
   * every shove and pull-tight, and each replacement left the previous geometry in this map,
   * strongly referenced, for the lifetime of the item. A memo built to save work on long
   * optimisation runs therefore grew fastest on exactly those runs.
   *
   * <p>Evicts by what the item now holds rather than clearing the whole map, because
   * {@code change_entries} keeps a run of shapes at each end and reuses those very
   * instances. Clearing would throw away enlargements that are still valid and still wanted.
   *
   * <p>Identity again, deliberately: two equal shapes are not the same cache entry, and
   * asking {@code equals} here would reintroduce the confusion between a shape and its
   * replacement that the index-keyed version was withdrawn for.
   */
  private static void drop_enlargements_of_superseded_shapes(
      SearchTreeInfo p_tree_info, TileShape[] p_current_shapes) {
    if (p_tree_info.enlarged_shapes == null || p_tree_info.enlarged_shapes.isEmpty()) {
      return;
    }
    java.util.Set<TileShape> still_held =
        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    if (p_current_shapes != null) {
      for (TileShape shape : p_current_shapes) {
        if (shape != null) {
          still_held.add(shape);
        }
      }
    }
    p_tree_info.enlarged_shapes.keySet().removeIf(shape -> !still_held.contains(shape));
  }

  /**
   * clears the stored information about the precalculated tree shapes for all search trees.
   */
  public void clear_precalculated_tree_shapes() {
    for (SearchTreeInfo curr_tree_info : this.tree_list) {

      curr_tree_info.precalculated_tree_shapes = null;
      // Not required for correctness -- entries for replaced shapes are already unreachable,
      // since nothing can look up a shape instance it no longer holds. Dropped here only so
      // the map does not accumulate entries for shapes the board has finished with.
      curr_tree_info.enlarged_shapes = null;
    }
  }

  /**
   * Returns p_shape enlarged by p_half_clearance, computing it once per shape instance.
   *
   * <p>Keyed on the shape OBJECT, not on its index. A replaced shape is a different instance,
   * so it cannot inherit its predecessor's enlargement -- which is exactly the bug that made
   * an index-keyed version return the enlargement of a superseded shape for 17% of lookups
   * and produce a clearance violation. Old entries become unreachable rather than wrong, so
   * there is no invalidation step to get wrong or to forget on a new write path.
   */
  public TileShape enlarged(TileShape p_shape, int p_half_clearance, ShapeTree p_tree) {
    for (SearchTreeInfo curr_tree_info : this.tree_list) {
      if (curr_tree_info.tree == p_tree) {
        if (curr_tree_info.enlarged_shapes == null) {
          curr_tree_info.enlarged_shapes = new java.util.IdentityHashMap<>();
        }
        java.util.Map<Integer, TileShape> byClearance =
            curr_tree_info.enlarged_shapes.computeIfAbsent(p_shape, s -> new java.util.HashMap<>());
        TileShape cached = byClearance.get(p_half_clearance);
        if (cached == null) {
          cached = (TileShape) p_shape.enlarge(p_half_clearance);
          byClearance.put(p_half_clearance, cached);
        }
        return cached;
      }
    }
    return (TileShape) p_shape.enlarge(p_half_clearance);
  }

  private static class SearchTreeInfo {

    final ShapeTree tree;
    ShapeTree.Leaf[] entry_arr;
    TileShape[] precalculated_tree_shapes;
    /** shape instance -> half clearance -> that shape enlarged. Identity-keyed on purpose. */
    java.util.IdentityHashMap<TileShape, java.util.Map<Integer, TileShape>> enlarged_shapes;

    SearchTreeInfo(ShapeTree p_tree) {
      tree = p_tree;
      entry_arr = null;
      precalculated_tree_shapes = null;
    }
  }
}