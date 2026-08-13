package app.freerouting.autoroute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.settings.RouterSettings;
import org.junit.jupiter.api.Test;

/**
 * The router selector must agree with the algorithm constants the settings layer
 * publishes.
 *
 * <p>Before this existed, the scheduler compared the setting against
 * {@code ALGORITHM_CURRENT} only, logged every other value — including the project's own
 * {@code ALGORITHM_V19} — as "Unknown router algorithm", and then hardcoded
 * {@code new BatchAutorouter(job)} regardless. Selecting the v1.9 engine silently gave
 * you the v2 engine, which makes the one in-tree reference implementation unreachable
 * from the CLI and the API.
 */
class RouterFactoryTest {

  @Test
  void theCurrentAlgorithmIsKnownAndSelectsTheCurrentRouter() {
    assertTrue(RouterFactory.isKnownAlgorithm(RouterSettings.ALGORITHM_CURRENT));
    assertEquals(BatchAutorouter.class,
        RouterFactory.routerClassFor(RouterSettings.ALGORITHM_CURRENT));
  }

  @Test
  void theV19AlgorithmIsKnownAndSelectsTheV19Router() {
    assertTrue(
        RouterFactory.isKnownAlgorithm(RouterSettings.ALGORITHM_V19),
        "ALGORITHM_V19 is a constant this project publishes; the selector must know it");
    assertEquals(
        BatchAutorouterV19.class,
        RouterFactory.routerClassFor(RouterSettings.ALGORITHM_V19),
        "selecting the v1.9 algorithm must actually run the v1.9 router");
  }

  @Test
  void anUnrecognisedAlgorithmIsReportedAsUnknown() {
    assertFalse(RouterFactory.isKnownAlgorithm("freerouting-router-from-mars"));
    assertNull(RouterFactory.routerClassFor("freerouting-router-from-mars"));
  }

  @Test
  void anAbsentAlgorithmFallsBackToTheCurrentRouterWithoutBeingCalledUnknown() {
    // null means "caller expressed no preference" — that is the default, not an error.
    assertTrue(RouterFactory.isKnownAlgorithm(null));
    assertEquals(BatchAutorouter.class, RouterFactory.routerClassFor(null));
  }
}
