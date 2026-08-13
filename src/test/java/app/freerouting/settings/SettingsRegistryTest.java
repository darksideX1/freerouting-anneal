package app.freerouting.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.logger.AllowErrorLogs;
import org.junit.jupiter.api.Test;

/**
 * A setting the program cannot apply must be refused by name, not merely mentioned.
 *
 * <p>The facility was already half-built: {@code setValue} returns false and logs
 * "Unknown settings property" for a name that does not resolve. Every caller ignored the
 * return value, so the run continued as though the setting had taken effect — which is
 * the same broken promise as the flag-form defect, reached by a typo instead of a space.
 *
 * <p>The {@code debug.*} branch had the same shape for a different reason: a chain of
 * if/else-if with no final else, so {@code --debug.anything_unrecognised=1} matched
 * nothing and was dropped in silence.
 */
@AllowErrorLogs("Refusing an unapplied argument logs at ERROR by design -- that IS the behaviour under test.")
class SettingsRegistryTest {

  @Test
  void unknownSettingsPropertyIsRecordedAsRejected() {
    GlobalSettings settings = new GlobalSettings();
    settings.applyCommandLineArguments(new String[] {"--router.no_such_setting_exists=5"});

    assertEquals(1, settings.getRejectedArguments().size(),
        "An unresolvable property must be recorded, not just warned about and forgotten.");
    assertTrue(settings.getRejectedArguments().get(0).contains("no_such_setting_exists"),
        "The record must name the offending argument.");
  }

  @Test
  void unknownDebugFlagIsRecordedAsRejected() {
    GlobalSettings settings = new GlobalSettings();
    settings.applyCommandLineArguments(new String[] {"--debug.no_such_debug_flag=true"});

    assertEquals(1, settings.getRejectedArguments().size(),
        "The debug branch had no final else, so unrecognised debug flags were dropped.");
  }

  @Test
  void aValidSettingIsNotRejected() {
    GlobalSettings settings = new GlobalSettings();
    settings.applyCommandLineArguments(new String[] {"--debug.enable_detailed_logging=true"});

    assertTrue(settings.getRejectedArguments().isEmpty(),
        "A setting that applies cleanly must not be reported as rejected.");
  }

  @Test
  void nothingIsRejectedWhenNoArgumentsAreGiven() {
    GlobalSettings settings = new GlobalSettings();
    settings.applyCommandLineArguments(new String[] {});

    assertTrue(settings.getRejectedArguments().isEmpty());
  }
}
