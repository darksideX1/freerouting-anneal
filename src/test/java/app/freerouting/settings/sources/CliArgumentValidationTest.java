package app.freerouting.settings.sources;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * A flag the program cannot apply must be refused out loud.
 *
 * <p>The defect this pins: {@code --router.fanout.enabled false} was matched by the
 * {@code --} branch, failed the {@code contains("=")} test, and fell out of the loop body
 * having done nothing — no warning, no error, and without consuming its value token, so
 * BOTH arguments vanished. Exit code 0, no message, and the setting silently at its
 * default.
 *
 * <p>That is worse than a crash. Three separate lanes measured the wrong thing because of
 * it: a fanout 2x2 that ran fanout-off in all four cells, a modal-dialog investigation
 * where {@code gui.enabled} never applied, and our own published invocation. The promise
 * the program makes is "no error means your flag applied", and it was not keeping it.
 *
 * <p>Scope note: this validates FORM, not ownership. An unrecognised but well-formed
 * {@code --some.other.flag=value} is left alone, because settings for other components
 * legitimately pass through this parser on their way elsewhere.
 */
class CliArgumentValidationTest {

  @Test
  void spaceSeparatedFormIsRejected() {
    String error = CliSettings.validationErrorFor("--router.fanout.enabled");
    assertNotNull(error, "A --flag with no '=' must be reported, not silently dropped.");
    assertTrue(error.contains("--router.fanout.enabled"),
        "The message must name the offending argument so the user can find it.");
    assertTrue(error.toLowerCase().contains("="),
        "The message must state the form that does work.");
  }

  @Test
  void equalsFormIsAccepted() {
    assertNull(CliSettings.validationErrorFor("--router.fanout.enabled=false"));
  }

  @Test
  void equalsFormWithEmptyValueIsAccepted() {
    // Empty is a value question, not a form question; the setting layer decides.
    assertNull(CliSettings.validationErrorFor("--router.max_passes="));
  }

  @Test
  void unrecognisedButWellFormedFlagsAreLeftAlone() {
    // Owned by other parsers (logging, gui, api server). Rejecting these here would
    // break flags that work today.
    assertNull(CliSettings.validationErrorFor("--logging.file.level=TRACE"));
    assertNull(CliSettings.validationErrorFor("--gui.enabled=false"));
  }

  @Test
  void singleDashLegacyFlagsAreNotAffected() {
    // -de board.dsn / -do out.ses take their value as the next token by design.
    assertNull(CliSettings.validationErrorFor("-de"));
    assertNull(CliSettings.validationErrorFor("-mp"));
  }
}
