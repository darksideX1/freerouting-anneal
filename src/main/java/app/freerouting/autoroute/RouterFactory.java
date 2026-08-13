package app.freerouting.autoroute;

import app.freerouting.core.RoutingJob;
import app.freerouting.settings.RouterSettings;

/**
 * Resolves the {@code router.algorithm} setting to a router implementation.
 *
 * <p>The selection lived inline in the scheduler, compared only against
 * {@link RouterSettings#ALGORITHM_CURRENT}, reported every other value -- including this
 * project's own {@link RouterSettings#ALGORITHM_V19} -- as "Unknown router algorithm",
 * and then constructed {@code BatchAutorouter} unconditionally. Asking for the v1.9
 * engine silently gave you the v2 engine.
 */
public final class RouterFactory {

  private RouterFactory() {
  }

  /** True when the setting names a router this build can actually run. */
  public static boolean isKnownAlgorithm(String algorithm) {
    return routerClassFor(algorithm) != null;
  }

  /**
   * The implementation a setting selects, or null when the value is not recognised.
   * A null setting means the caller expressed no preference and gets the default.
   */
  public static Class<? extends NamedAlgorithm> routerClassFor(String algorithm) {
    if (algorithm == null || RouterSettings.ALGORITHM_CURRENT.equals(algorithm)) {
      return BatchAutorouter.class;
    }
    if (RouterSettings.ALGORITHM_V19.equals(algorithm)) {
      return BatchAutorouterV19.class;
    }
    return null;
  }

  /** Builds the selected router, falling back to the default for unknown values. */
  public static NamedAlgorithm create(RoutingJob job) {
    Class<? extends NamedAlgorithm> selected = routerClassFor(job.routerSettings.algorithm);
    if (BatchAutorouterV19.class.equals(selected)) {
      return new BatchAutorouterV19(job);
    }
    return new BatchAutorouter(job);
  }
}
