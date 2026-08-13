package app.freerouting.api.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * An MCP bridge call must not be able to wait forever.
 *
 * <p>Every {@code tools/call} and {@code tools/list} is served by a Jetty worker that makes
 * an HTTP call back into this same process. Those calls were built with
 * {@code HttpRequest.newBuilder(uri)} and sent through a fresh
 * {@code HttpClient.newHttpClient()} — no connect timeout, no request timeout, and a client
 * that is never closed. There was not one {@code .timeout(} in the whole controller.
 *
 * <p>An unbounded wait on a server thread is a deadlock waiting for a small thread pool.
 * The CI runner with the fewest cores found it first: {@code McpEndpointsTest} hung for the
 * whole job on windows-latest while passing on ubuntu and macos, which is what a
 * pool-exhaustion race looks like from the outside — the platform did not cause it, it
 * just lost the race more reliably.
 *
 * <p>These assertions are deliberately about the REQUEST and CLIENT rather than about
 * observed timing: a test that waits for a real timeout is slow and flaky, while the thing
 * that actually went wrong is that the bound was never set.
 */
class McpBridgeTimeoutTest {

  @Test
  void everyBridgeRequestCarriesARequestTimeout() {
    HttpRequest request = McpControllerV1.bridgeRequestBuilder(URI.create("http://127.0.0.1:1/v1/system/status"))
        .GET()
        .build();

    assertTrue(request.timeout().isPresent(),
        "a bridge request without a timeout can hold a Jetty worker forever");
    assertEquals(McpControllerV1.BRIDGE_REQUEST_TIMEOUT, request.timeout().get());
  }

  @Test
  void theBridgeClientHasAConnectTimeout() {
    assertTrue(McpControllerV1.bridgeClient().connectTimeout().isPresent(),
        "a connect to an unreachable host must fail rather than block");
    assertEquals(McpControllerV1.BRIDGE_CONNECT_TIMEOUT,
        McpControllerV1.bridgeClient().connectTimeout().get());
  }

  @Test
  void theBridgeClientIsSharedRatherThanCreatedPerCall() {
    // Each HttpClient owns a selector thread and an executor. Creating one per call and
    // never closing it leaks both, which is its own way of hanging a JVM at shutdown.
    assertTrue(McpControllerV1.bridgeClient() == McpControllerV1.bridgeClient(),
        "the bridge client must be reused, not built per request");
  }

  @Test
  void theTimeoutsAreShortEnoughToBeUseful() {
    // A bound nobody reaches is not a bound. These must be well under the CI job budget,
    // or the hang they exist to prevent simply takes longer.
    assertTrue(McpControllerV1.BRIDGE_REQUEST_TIMEOUT.compareTo(Duration.ofMinutes(2)) < 0,
        "request timeout must be far below the job budget");
    assertTrue(McpControllerV1.BRIDGE_CONNECT_TIMEOUT.compareTo(McpControllerV1.BRIDGE_REQUEST_TIMEOUT) <= 0,
        "connect timeout must not exceed the overall request timeout");
  }

  @Test
  void theToolInventoryIsBuiltOncePerApplication() throws Exception {
    // THE PATH THAT ACTUALLY HANGS. tools/list makes no HTTP call at all, so the bridge
    // timeouts cannot reach it -- the first version of this fix bounded a different path
    // from the one the Windows log stops in, which the PR review caught.
    //
    // OpenApiMcpToolRegistry.fromApplication runs a JAX-RS/Swagger classpath scan of the
    // whole api package and rebuilds the OpenAPI model. That ran on EVERY tools/list
    // request, at three separate call sites. The inventory cannot change while the
    // application runs, so this asserts it is built once and reused.
    jakarta.ws.rs.core.Application app = new jakarta.ws.rs.core.Application() {};

    Object first = McpControllerV1.toolRegistry(app);
    Object second = McpControllerV1.toolRegistry(app);

    assertTrue(first == second,
        "the tool inventory must be cached, not rebuilt by a classpath scan per request");
  }

  @Test
  void aDifferentApplicationGetsItsOwnInventory() throws Exception {
    // Keyed on the Application instance, so a test that stands up a fresh server does not
    // inherit a stale registry from a previous fixture.
    jakarta.ws.rs.core.Application one = new jakarta.ws.rs.core.Application() {};
    jakarta.ws.rs.core.Application two = new jakarta.ws.rs.core.Application() {};

    Object a = McpControllerV1.toolRegistry(one);
    Object b = McpControllerV1.toolRegistry(two);

    assertTrue(a != b, "a different Application must not reuse another one's inventory");
  }
}
