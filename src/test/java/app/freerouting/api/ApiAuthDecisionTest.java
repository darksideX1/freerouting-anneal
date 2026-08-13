package app.freerouting.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A setting called "authentication.enabled=false" must actually disable authentication.
 *
 * <p>There are two independent gates on this API. {@code ApiKeyValidationService} honours
 * the setting and lets requests through without a Bearer key. {@code AuthenticateUser}
 * did not consult it at all, and demanded a {@code Freerouting-Profile-ID} or
 * {@code -Email} header on every request regardless — so turning authentication off
 * disabled one of the two gates while the name promised both.
 *
 * <p>That is the same defect this whole lane exists to remove: a setting that half-does
 * what it says. It is worse here than a silently-ignored flag, because the user reads the
 * name, believes it, and then gets a 500 that talks about profile headers they never
 * heard of. Discovering it cost four failed attempts against a live server.
 *
 * <p>With authentication off the caller is anonymous and local — that is precisely what
 * "allow all requests through" means, and it is the documented intent for embedded uses
 * such as a KiCad plugin. With authentication ON, nothing changes: the header is still
 * required and its absence is still an error.
 */
class ApiAuthDecisionTest {

  @Test
  void withAuthenticationOnTheProfileHeaderIsStillRequired() {
    // Unchanged behaviour. Turning the gate off is opt-in; it must not weaken the default.
    assertThrows(IllegalArgumentException.class,
        () -> BaseController.resolveUserId(null, null, true));
    assertThrows(IllegalArgumentException.class,
        () -> BaseController.resolveUserId("", "", true));
  }

  @Test
  void withAuthenticationOffAnAnonymousCallerIsAccepted() {
    // THE FIX. No headers, authentication disabled -> a local anonymous identity.
    UUID user = BaseController.resolveUserId(null, null, false);
    assertNotNull(user);
  }

  @Test
  void theAnonymousIdentityIsStableAcrossCalls() {
    // Session ownership is keyed on this. A fresh UUID per request would mean a caller
    // could never retrieve the job it just created.
    assertEquals(
        BaseController.resolveUserId(null, null, false),
        BaseController.resolveUserId(null, null, false));
  }

  @Test
  void aSuppliedProfileIdIsHonouredWhicheverWay() {
    UUID given = UUID.fromString("11111111-2222-3333-4444-555555555555");
    assertEquals(given, BaseController.resolveUserId(given.toString(), null, true));
    assertEquals(given, BaseController.resolveUserId(given.toString(), null, false));
  }

  @Test
  void anUnparseableProfileIdDoesNotCrashTheRequest() {
    // It falls back rather than throwing: the caller identified itself, just not with a
    // UUID, and refusing the request outright would be a worse answer than proceeding.
    assertNotNull(BaseController.resolveUserId("not-a-uuid", "someone@example.com", true));
  }
}
