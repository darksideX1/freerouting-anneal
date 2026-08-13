package app.freerouting.settings;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.freerouting.util.ReflectionUtil;
import org.junit.jupiter.api.Test;

/**
 * Settings that exist must be settable by the names the documentation uses.
 *
 * <p>Defect 26. `--router.optimizer.improvement_threshold=0` is refused at startup with
 * "Argument NOT applied (unknown settings property)", and so is the Java field-name
 * spelling. Meanwhile `--router.optimizer.enabled=true` — same namespace, same depth —
 * resolves and works. Two hypotheses were tested against that pair and both were wrong:
 * it is not a {@code @SerializedName} mapping problem, and it is not a path-depth problem.
 *
 * <p>This calls the resolver directly rather than through a process, so the failure is a
 * stack trace in a test report instead of one line of ERROR in a router log. That is the
 * difference between diagnosing this and guessing at it a fourth time.
 *
 * <p>It matters because that threshold is the only thing stopping our optimiser after a
 * single pass: it stops when {@code scoreImprovement < threshold}, and the first pass
 * measures exactly 0.0000 against a default of 0.01. With the threshold at zero the
 * comparison is false and it would keep going — which is the experiment that decides
 * whether defect 25 is a giving-up problem or an algorithmic one.
 */
class SettingsPathResolutionTest {

  @Test
  void theControlCase_optimizerEnabled_resolves() {
    // This one works in production today. If it ever fails, the diagnosis below is void
    // because the whole namespace would be broken rather than one field.
    GlobalSettings settings = new GlobalSettings();
    assertDoesNotThrow(
        () -> ReflectionUtil.setFieldValue(settings, "router.optimizer.enabled", "true"),
        "router.optimizer.enabled is the control -- it resolves in production");
  }

  @Test
  void theThresholdIsSettableByItsJsonName() throws Exception {
    GlobalSettings settings = new GlobalSettings();

    ReflectionUtil.setFieldValue(settings, "router.optimizer.improvement_threshold", "0");

    assertEquals(0.0f, settings.routerSettings.optimizer.optimizationImprovementThreshold,
        "the name used in freerouting.json and in the documentation must resolve");
  }

  @Test
  void theCamelCaseJavaNameIsRefusedON_PURPOSE() {
    // I originally asserted the opposite here, and it was wrong. getFieldByNameOrSerializedName
    // deliberately refuses a field that HAS a SerializedName when it is queried by its
    // camelCase Java name, so that every setting has exactly one canonical spelling. The
    // comment saying so is right there in the method.
    //
    // Kept as a test rather than deleted, because an assertion that something is refused
    // ON PURPOSE is what stops the next person -- me, an hour ago -- from "fixing" it.
    GlobalSettings settings = new GlobalSettings();

    assertThrows(NoSuchFieldException.class,
        () -> ReflectionUtil.setFieldValue(
            settings, "router.optimizer.optimizationImprovementThreshold", "0"),
        "a field with a SerializedName is addressable only by that name, by design");
  }
}
