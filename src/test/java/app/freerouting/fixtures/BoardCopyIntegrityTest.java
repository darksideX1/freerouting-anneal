package app.freerouting.fixtures;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.board.Item;
import app.freerouting.board.RoutingBoard;
import app.freerouting.core.RoutingJob;
import app.freerouting.settings.sources.TestingSettings;
import org.junit.jupiter.api.Test;

/**
 * Is a copied board self-consistent?
 *
 * <p>This test exists to settle a diagnosis rather than to defend a fix. Racing crashed on
 * two runs in three with
 *
 * <pre>
 *   NullPointerException: Item.shape_layer(int) -- "curr_item" is null
 *   NullPointerException: ShapeTree$Storable.get_tree_shape(...) -- "tmp_entry.leaf.object" is null
 * </pre>
 *
 * <p>Nulls where objects are expected have two very different causes, and the fix differs
 * completely between them:
 *
 * <ul>
 *   <li><b>Lossy copy.</b> {@code deepCopy()} serialises the board, and serialisation drops
 *       {@code transient} fields — {@code Item.board} and {@code Item.search_trees_info} are
 *       both transient. If the restore path misses any item, the copy is born with nulls in
 *       it and threading is irrelevant: routing that copy on ONE thread would fail too.
 *   <li><b>Data race.</b> The copy is fine and concurrent workers corrupt it, in which case
 *       these assertions pass and the fault is in what the workers share.
 * </ul>
 *
 * <p>A single-threaded assertion over a freshly copied board separates the two. It is
 * deliberately not a concurrency test: a concurrency test that passes proves nothing, and
 * one that fails does not say why.
 */
class BoardCopyIntegrityTest extends RoutingFixtureTest {

  @Test
  void everyItemOnACopiedBoardKnowsTheCopyAsItsBoard() {
    TestingSettings settings = new TestingSettings();
    settings.setMaxPasses(1);
    settings.setMaxItems(2);
    settings.setJobTimeoutString("00:01:00");

    RoutingJob job = GetRoutingJob("Issue508-DAC2020_bm01.dsn", settings);
    RunRoutingJob(job);

    RoutingBoard original = job.board;
    assertNotNull(original, "the fixture must produce a board to copy");

    RoutingBoard copy = original.deepCopy();
    assertNotNull(copy, "deepCopy returns null on failure rather than throwing -- catch that here");

    // Invariant: the back-reference is transient, so it survives only if the restore path
    // reaches every item. An item whose board is null is exactly the "curr_item is null"
    // shape seen in the crash.
    int checked = 0;
    for (Item item : copy.get_items()) {
      assertNotNull(item.board, "item " + item.get_id_no() + " came back from the copy with a null board");
      assertSame(copy, item.board,
          "item " + item.get_id_no() + " points at a DIFFERENT board than the copy it belongs to");
      checked++;
    }
    assertTrue(checked > 0, "the copy must contain items, or this test proves nothing");
  }

  @Test
  void aCopiedBoardCanAnswerTheQueriesRoutingWillAsk() {
    // The crash was not on reading item.board directly -- it was inside shape/tree lookups
    // that depend on the transient per-item search-tree info. Exercise that path on every
    // item of the copy, single-threaded. If this throws, the copy is incomplete and no
    // amount of thread isolation will help.
    TestingSettings settings = new TestingSettings();
    settings.setMaxPasses(1);
    settings.setMaxItems(2);
    settings.setJobTimeoutString("00:01:00");

    RoutingJob job = GetRoutingJob("Issue508-DAC2020_bm01.dsn", settings);
    RunRoutingJob(job);

    RoutingBoard copy = job.board.deepCopy();
    assertNotNull(copy);

    int probed = 0;
    for (Item item : copy.get_items()) {
      int layerCount = item.tile_shape_count();
      for (int i = 0; i < layerCount; i++) {
        // Any of these throwing is the reproducer.
        item.get_tree_shape(copy.search_tree_manager.get_default_tree(), i);
        probed++;
      }
    }
    assertTrue(probed > 0, "no shapes probed -- the test would pass vacuously");
  }
}
