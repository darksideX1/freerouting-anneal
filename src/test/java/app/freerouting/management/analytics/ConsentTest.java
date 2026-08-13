package app.freerouting.management.analytics;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Withdrawing consent used to be the act that transmitted the user's email.
 *
 * <p>Consent was read once, at startup, into the flag that decided whether the analytics
 * client was built. The telemetry checkbox listener then set a settings field and nothing
 * else, so the client stayed alive for the rest of the session: the next {@code
 * refreshIdentity()} sent {@code user_email} alongside {@code allow_telemetry=false}, and
 * events kept leaving the process until restart.
 *
 * <p>Consent is now read live before every send, and the checkbox disables the client. These
 * pin the decision itself; the wiring is covered by the call sites being gated on it.
 */
class ConsentTest {

  @Test
  @Timeout(10)
  @DisplayName("consent given and analytics not disabled: sending is allowed")
  void consentAllowsSending() {
    assertTrue(FRAnalytics.sendingAllowed(false, true));
  }

  @Test
  @Timeout(10)
  @DisplayName("consent withdrawn: nothing may be sent, including the identity refresh")
  void withdrawnConsentBlocksSending() {
    assertFalse(FRAnalytics.sendingAllowed(false, false),
        "this is the defect: the refresh that follows an opt-out carried the user's email");
  }

  @Test
  @Timeout(10)
  @DisplayName("analytics disabled outright wins regardless of the telemetry flag")
  void disabledWins() {
    assertFalse(FRAnalytics.sendingAllowed(true, true));
    assertFalse(FRAnalytics.sendingAllowed(true, false));
  }

  @Test
  @Timeout(10)
  @DisplayName("unknown consent counts as withheld")
  void unknownConsentIsWithheld() {
    assertFalse(FRAnalytics.sendingAllowed(false, null),
        "the field defaults to true so null should not occur; if it ever does, not sending "
            + "is the failure worth having");
    assertFalse(FRAnalytics.sendingAllowed(null, null));
    assertTrue(FRAnalytics.sendingAllowed(null, true),
        "an absent disable-flag is not a withdrawal of consent");
  }
}
