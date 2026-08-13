package app.freerouting.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.logger.AllowErrorLogs;
import org.junit.jupiter.api.Test;

/**
 * Options consumed before the settings model exists must not be reported as refused.
 *
 * <p>Refusing an argument the program cannot apply was the right fix; the cost of getting
 * its scope wrong is that a working option is declared broken. {@code --logging.file.pattern=}
 * is read directly by {@code Freerouting} at startup and installed as a system property,
 * because logging has to be configured before any settings object exists. There is no
 * matching field in {@code FileLoggingSettings}, so the registry check found nothing,
 * recorded it as rejected, and logged an ERROR saying the run was not configured as
 * requested — about the one option that had, in fact, been applied.
 *
 * <p>That is worse than the silence it replaced. Silence lets a user believe a broken
 * setting worked; this made a user disbelieve a setting that worked, and an error message
 * that cries wolf teaches people to ignore the ones that matter.
 *
 * <p>{@code user_data_path} was already exempt for the same reason, by an inline string
 * comparison repeated at two sites. The exemption is now one named list, so the next
 * bootstrap option is added in one place instead of being forgotten in the second.
 */
@AllowErrorLogs("One case here asserts that an unknown property IS refused, which logs at ERROR by design.")
class BootstrapOptionsTest {

  @Test
  void loggingPatternIsNotReportedAsRejected() {
    GlobalSettings settings = new GlobalSettings();
    settings.applyCommandLineArguments(new String[] {"--logging.file.pattern=%d %m%n"});

    assertEquals(0, settings.getRejectedArguments().size(),
        "logging.file.pattern is applied at startup; calling it rejected is a false alarm.");
  }

  @Test
  void userDataPathStaysExempt() {
    GlobalSettings settings = new GlobalSettings();
    settings.applyCommandLineArguments(new String[] {"--user_data_path=/tmp/freerouting-test"});

    assertEquals(0, settings.getRejectedArguments().size(),
        "user_data_path was already exempt and must remain so.");
  }

  @Test
  void aGenuinelyUnknownPropertyIsStillRefused() {
    // The exemption must be a named list, not a widening. This is the guard against
    // fixing the false alarm by turning the alarm off.
    GlobalSettings settings = new GlobalSettings();
    settings.applyCommandLineArguments(new String[] {"--logging.file.no_such_option=1"});

    assertEquals(1, settings.getRejectedArguments().size(),
        "A neighbouring but unknown logging option must still be refused.");
    assertTrue(settings.getRejectedArguments().get(0).contains("no_such_option"));
  }

  @Test
  void theExemptionIsNamedAndNarrow() {
    assertTrue(GlobalSettings.isBootstrapOwnedOption("user_data_path"));
    assertTrue(GlobalSettings.isBootstrapOwnedOption("logging.file.pattern"));
    assertEquals(false, GlobalSettings.isBootstrapOwnedOption("logging.file.level"),
        "file.level resolves through the settings model and must not be exempted.");
    assertEquals(false, GlobalSettings.isBootstrapOwnedOption("router.max_passes"));
  }
}
