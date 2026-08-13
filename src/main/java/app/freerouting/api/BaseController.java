package app.freerouting.api;

import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import java.util.UUID;

/**
 * Base class for all Freerouting API controllers.
 *
 * <p>Provides shared authentication logic via {@link #AuthenticateUser()}, which resolves the
 * caller's UUID from the standard HTTP request headers. All protected controller methods must call
 * this method before performing any business logic.</p>
 *
 * <h2>Authentication headers</h2>
 * <ul>
 *   <li>{@code Freerouting-Profile-ID} — preferred; must be a valid RFC 4122 UUID string.</li>
 *   <li>{@code Freerouting-Profile-Email} — fallback; email-to-UUID resolution is not yet
 *       implemented (see TODO in {@link #AuthenticateUser()}).</li>
 * </ul>
 *
 * <p>Note: the method name intentionally uses PascalCase to match the original naming convention
 * of this code-base; a rename to camelCase is planned as a separate clean-up.</p>
 */
public class BaseController {

  @Context
  private HttpHeaders httpHeaders;

  public BaseController() {
  }

  /**
   * Resolves and returns the authenticated caller's {@link UUID}.
   *
   * <p>Resolution order:
   * <ol>
   *   <li>Parse {@code Freerouting-Profile-ID} header as a UUID.</li>
   *   <li>If that header is absent or unparsable, fall back to
   *       {@code Freerouting-Profile-Email} (email-to-UUID look-up is not yet implemented).</li>
   * </ol>
   *
   * @return the caller's UUID — never {@code null}.
   * @throws IllegalArgumentException if both headers are missing/empty, or if neither yields a
   *         resolvable UUID.
   */
  protected UUID AuthenticateUser() {
    return resolveUserId(
        httpHeaders.getHeaderString("Freerouting-Profile-ID"),
        httpHeaders.getHeaderString("Freerouting-Profile-Email"),
        isApiAuthenticationEnabled());
  }

  /** The caller when authentication is switched off: anonymous, local, and stable. */
  private static final UUID ANONYMOUS_LOCAL_USER =
      UUID.fromString("00000000-0000-4000-8000-000000000001");

  /**
   * Resolves the calling user, honouring whether authentication is switched on.
   *
   * <p>Pure and package-visible so the precedence can be tested and argued with. Two
   * things were untrue here before:
   *
   * <p><b>The setting did not do what its name says.</b> There are two independent gates
   * on this API: {@code ApiKeyValidationService} honours
   * {@code api_server.authentication.enabled=false} and lets requests through without a
   * Bearer key, while this method consulted nothing and demanded a profile header
   * regardless. So "authentication disabled" disabled one gate of two, and the caller got
   * a 500 about headers they had never heard of.
   *
   * <p><b>The error advertised an option that could not work.</b> It named
   * {@code Freerouting-Profile-Email} as an alternative, but e-mail resolution was an
   * unimplemented TODO, so supplying only an e-mail always failed. It now derives a
   * stable identity from the address. That is identity, not authorization — the API key
   * is the gate, and this only decides which session the caller owns.
   *
   * @throws IllegalArgumentException when authentication is ON and the caller did not
   *     identify itself at all
   */
  static UUID resolveUserId(String idHeader, String emailHeader, boolean authenticationEnabled) {
    boolean noId = (idHeader == null) || idHeader.isEmpty();
    boolean noEmail = (emailHeader == null) || emailHeader.isEmpty();

    if (noId && noEmail) {
      if (!authenticationEnabled) {
        // "Allow all requests through" is the documented intent of disabling
        // authentication -- for embedded uses such as a KiCad plugin. A fixed id rather
        // than a fresh one per request, because session ownership is keyed on it and a
        // caller must be able to retrieve the job it just created.
        return ANONYMOUS_LOCAL_USER;
      }
      throw new IllegalArgumentException(
          "Freerouting-Profile-ID or Freerouting-Profile-Email HTTP request header must be"
              + " set in order to get authenticated. If you are running this server"
              + " locally, --api_server.authentication.enabled=false removes this"
              + " requirement.");
    }

    if (!noId) {
      try {
        return UUID.fromString(idHeader);
      } catch (IllegalArgumentException _) {
        // Not a UUID. The caller still identified itself; fall through to the e-mail.
      }
    }

    if (!noEmail) {
      return UUID.nameUUIDFromBytes(
          emailHeader.trim().toLowerCase(java.util.Locale.ROOT)
              .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    throw new IllegalArgumentException(
        "The Freerouting-Profile-ID header '" + idHeader + "' is not a valid UUID, and no"
            + " Freerouting-Profile-Email was supplied to fall back to.");
  }

  /** Fails CLOSED: if the setting cannot be read, the header stays required. */
  private static boolean isApiAuthenticationEnabled() {
    try {
      var authentication =
          app.freerouting.Freerouting.globalSettings.apiServerSettings.authentication;
      return (authentication == null) || !Boolean.FALSE.equals(authentication.isEnabled);
    } catch (Exception e) {
      return true;
    }
  }
}
