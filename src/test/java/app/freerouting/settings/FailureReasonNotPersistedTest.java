package app.freerouting.settings;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.util.gson.GsonProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The reason a setValue call failed is a diagnostic for the very next log line, not a
 * preference. GlobalSettings is serialized wholesale to the user's freerouting.json, and Gson
 * writes private fields unless they are transient -- so a field added for a CLI error message
 * silently becomes a persisted config key, and one bad --flag leaves it in the user's file
 * forever. Every other runtime-only field in the class is already transient for this reason.
 */
class FailureReasonNotPersistedTest {

  @Test
  @DisplayName("the setValue failure reason never reaches the saved settings file")
  void failureReasonIsNotSerialized() {
    GlobalSettings settings = new GlobalSettings();

    // Provoke a real failure so the diagnostic holds a non-default value; if it were
    // serialized, this is exactly the string that would land in the user's file.
    settings.setValue("router.this_property_does_not_exist", "1");

    String json = GsonProvider.GSON.toJson(settings);

    assertFalse(
        json.contains("lastSetValueFailureReason"),
        "the failure-reason diagnostic was written into the settings JSON; it must be transient");
    assertFalse(
        json.contains("unknown settings property"),
        "the failure-reason text leaked into the settings JSON");
  }

  @Test
  @DisplayName("making it transient does not stop setValue from reporting why it failed")
  void failureReasonStillReadableAtRuntime() {
    GlobalSettings settings = new GlobalSettings();

    assertFalse(
        settings.setValue("router.this_property_does_not_exist", "1"),
        "setting an unknown property should fail");
    assertTrue(
        settings.getLastSetValueFailureReason().contains("unknown settings property"),
        "the reason must still be available in memory for the error message");
  }
}
