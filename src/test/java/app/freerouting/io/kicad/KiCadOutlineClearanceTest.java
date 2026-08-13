package app.freerouting.io.kicad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.Freerouting;
import app.freerouting.board.RoutingBoard;
import app.freerouting.io.BoardReadResult;
import app.freerouting.rules.DefaultItemClearanceClasses;
import app.freerouting.settings.GlobalSettings;
import java.io.StringReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Upstream issue #558 — copper-to-edge clearance, JSON/API path.
 *
 * <p>KiCad knows the board's copper-to-edge clearance and the JSON bridge carries it in
 * {@code outline.clearance}. The reader used to hardcode {@code outlineClearanceNo = 1},
 * so the value arrived and was discarded: traces could be placed at the ordinary
 * conductor-to-conductor clearance from the board edge, and KiCad's own DRC then failed
 * the board that Freerouting had just declared clean.
 *
 * <p>The DSN path already solved this via the {@code copperToEdgeClearanceUm} CLI
 * override. This test covers the JSON path reading the board's own value, so a user does
 * not have to know a CLI flag to get their stated design rule respected.
 */
class KiCadOutlineClearanceTest {

  /** 0.5 mm at resolution 1000 = 500 board units. */
  private static final int EXPECTED_BOARD_UNITS = 500;

  @BeforeEach
  void setUp() {
    Freerouting.globalSettings = new GlobalSettings();
  }

  private static String boardJson(String outlineClearance) {
    return "{\n"
        + "  \"designName\": \"EdgeClearanceBoard\",\n"
        + "  \"unit\": \"MM\",\n"
        + "  \"resolution\": 1000.0,\n"
        + "  \"layers\": [\n"
        + "    {\"index\": 0, \"name\": \"F.Cu\", \"type\": \"signal\"},\n"
        + "    {\"index\": 1, \"name\": \"B.Cu\", \"type\": \"signal\"}\n"
        + "  ],\n"
        + "  \"netClasses\": [\n"
        + "    {\"name\": \"Power\", \"clearance\": 0.25, \"traceWidth\": 0.5,\n"
        + "     \"viaDiameter\": 0.8, \"viaDrill\": 0.4, \"netNames\": [\"VCC\"]}\n"
        + "  ],\n"
        + "  \"nets\": [\n"
        + "    {\"id\": 1, \"name\": \"VCC\", \"className\": \"Power\", \"containsPlane\": false}\n"
        + "  ],\n"
        + "  \"outline\": {\n"
        + "    \"corners\": [\n"
        + "      {\"x\": 0.0, \"y\": 0.0},\n"
        + "      {\"x\": 100.0, \"y\": 0.0},\n"
        + "      {\"x\": 100.0, \"y\": 80.0},\n"
        + "      {\"x\": 0.0, \"y\": 80.0}\n"
        + "    ],\n"
        + "    \"clearance\": " + outlineClearance + "\n"
        + "  },\n"
        + "  \"components\": [],\n"
        + "  \"traces\": [],\n"
        + "  \"vias\": []\n"
        + "}";
  }

  private static RoutingBoard read(String json) {
    BoardReadResult result = KiCadJsonReader.readBoard(new StringReader(json), null, null);
    return switch (result) {
      case BoardReadResult.Success s -> (RoutingBoard) s.board();
      case BoardReadResult.OutlineMissing o -> (RoutingBoard) o.board();
      default -> throw new AssertionError("Board did not load: " + result);
    };
  }

  @Test
  void outlineClearanceFromJsonCreatesADedicatedBoardEdgeClass() {
    RoutingBoard board = read(boardJson("0.5"));

    int boardEdgeClassNo = board.rules.clearance_matrix.get_no("board_edge");
    assertTrue(boardEdgeClassNo >= 0,
        "Expected a board_edge clearance class to be created from outline.clearance.");

    var outline = board.get_outline();
    assertEquals(boardEdgeClassNo, outline.clearance_class_no(),
        "The board outline should be assigned to the board_edge clearance class.");
  }

  @Test
  void boardEdgeClearanceMatchesTheDeclaredValueOnEveryLayer() {
    RoutingBoard board = read(boardJson("0.5"));

    var matrix = board.rules.clearance_matrix;
    int boardEdgeClassNo = matrix.get_no("board_edge");

    for (int layer = 0; layer < matrix.get_layer_count(); layer++) {
      assertEquals(EXPECTED_BOARD_UNITS,
          matrix.get_value(boardEdgeClassNo, 1, layer, false),
          "board_edge clearance should equal outline.clearance on every layer.");
    }
  }

  @Test
  void absentOrZeroOutlineClearanceLeavesTheDefaultBehaviourAlone() {
    // A board that declares no edge clearance must behave exactly as before: no new class,
    // outline on the default area class. Otherwise every existing JSON board silently
    // changes its edge spacing, which is the opposite of what this fix is for.
    RoutingBoard board = read(boardJson("0.0"));

    int defaultAreaClassNo = board.rules.get_default_net_class().default_item_clearance_classes
        .get(DefaultItemClearanceClasses.ItemClass.AREA);
    assertEquals(defaultAreaClassNo, board.get_outline().clearance_class_no(),
        "With no declared edge clearance the outline must keep the default area class.");
  }

  @Test
  void aDeclaredEdgeClearanceIsNotTheOrdinaryConductorClearance() {
    // The defect this issue describes: traces sat at the conductor-to-conductor clearance
    // (0.25 mm here) from the board edge instead of the declared 0.5 mm. The two values
    // must resolve to different classes, or nothing has actually changed.
    RoutingBoard board = read(boardJson("0.5"));

    int boardEdgeClassNo = board.rules.clearance_matrix.get_no("board_edge");
    assertNotEquals(1, boardEdgeClassNo,
        "board_edge must be its own class, not the default conductor class.");
  }
}
