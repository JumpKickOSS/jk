// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.NetworkConfig;
import cc.jumpkick.testing.LoopbackHttp;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The wire: a request that {@link ProxyEnvironment} routes through a proxy reaches the proxy in
 * absolute form, one it bypasses does not, and the proxy's credential answers its challenge.
 *
 * <p>The proxy is a loopback stub that answers for whatever host it is asked about, so the origin
 * never has to exist: {@code repo.example.test} is a reserved name that resolves nowhere, which is
 * also what makes the bypass case observable — direct, the fetch has nowhere to go.
 */
class HttpProxyTest {

    private static final URI BEHIND_PROXY = URI.create("http://repo.example.test/a.jar");

    @RegisterExtension
    final LoopbackHttp proxy = new LoopbackHttp();

    @RegisterExtension
    final LoopbackHttp origin = new LoopbackHttp();

    /** Production's client with the proxy decision fed from {@code shell}, one attempt, no backoff. */
    private static Http through(Map<String, String> shell) {
        return new Http(new ProxyEnvironment(() -> NetworkConfig.EMPTY, () -> shell::get), new Duration[0]);
    }

    @Test
    void a_fetch_goes_through_the_proxy_the_shell_names_with_the_target_in_the_request() throws Exception {
        proxy.serve("/a.jar", "via-proxy");

        HttpResponse<byte[]> response =
                through(Map.of("http_proxy", proxy.base().toString())).get(BEHIND_PROXY);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("via-proxy");
        assertThat(proxy.headersFor("/a.jar"))
                .get()
                .extracting(h -> h.get("Host"))
                .as("the proxy is told which host the request is for")
                .isEqualTo(List.of("repo.example.test"));
    }

    @Test
    void a_no_proxy_host_is_fetched_directly_and_the_proxy_never_hears_of_it() {
        proxy.serve("/a.jar", "via-proxy");
        Map<String, String> shell = Map.of("http_proxy", proxy.base().toString(), "NO_PROXY", ".example.test");

        assertThatThrownBy(() -> through(shell).get(BEHIND_PROXY)).isInstanceOf(IOException.class);
        assertThat(proxy.requested()).isEmpty();
    }

    @Test
    void a_loopback_origin_is_fetched_directly_however_the_shell_is_set() throws Exception {
        origin.serve("/b.jar", "direct");
        proxy.serve("/b.jar", "via-proxy");

        HttpResponse<byte[]> response = through(
                        Map.of("http_proxy", proxy.base().toString()))
                .get(origin.base().resolve("/b.jar"));

        assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("direct");
        assertThat(proxy.requested()).isEmpty();
    }

    /**
     * The credential in the proxy URL rides the request to the proxy as Basic, on the first request
     * rather than after a 407 — an authenticator on the client would fail every origin 401 jk's
     * callers answer themselves.
     */
    @Test
    void the_proxy_credential_rides_the_request_as_basic() throws Exception {
        List<String> authorizations = new CopyOnWriteArrayList<>();
        HttpServer authProxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        authProxy.createContext("/", exchange -> {
            String sent = exchange.getRequestHeaders().getFirst("Proxy-Authorization");
            authorizations.add(String.valueOf(sent));
            if (sent == null) {
                exchange.getResponseHeaders().add("Proxy-Authenticate", "Basic realm=\"corp\"");
                exchange.sendResponseHeaders(407, -1);
            } else {
                byte[] body = "authed".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        authProxy.start();
        try {
            String withCredential =
                    "http://alice:s3cr3t@127.0.0.1:" + authProxy.getAddress().getPort();

            HttpResponse<byte[]> response =
                    through(Map.of("http_proxy", withCredential)).get(BEHIND_PROXY);

            assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("authed");
            String expected =
                    "Basic " + Base64.getEncoder().encodeToString("alice:s3cr3t".getBytes(StandardCharsets.UTF_8));
            assertThat(authorizations).containsExactly(expected);
        } finally {
            authProxy.stop(0);
        }
    }

    /** An origin's own 401 is still the caller's to answer: a proxy credential changes nothing about it. */
    @Test
    void an_origins_401_is_handed_back_to_the_caller() throws Exception {
        origin.server().createContext("/private", exchange -> {
            exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"registry\"");
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });

        HttpResponse<byte[]> response = through(Map.of(
                        "http_proxy", "http://alice:s3cr3t@" + proxy.base().getAuthority()))
                .get(origin.base().resolve("/private"));

        assertThat(response.statusCode()).isEqualTo(401);
    }

    /**
     * A redirect from behind the proxy to a host that goes direct must not carry the proxy's
     * password to that host: the header is decided afresh for every hop.
     */
    @Test
    void a_redirect_to_a_direct_host_drops_the_proxy_credential() throws Exception {
        origin.serve("/b.jar", "direct");
        proxy.redirect("/a.jar", origin.base().resolve("/b.jar"));

        HttpResponse<byte[]> response = through(Map.of(
                        "http_proxy", "http://alice:s3cr3t@" + proxy.base().getAuthority()))
                .get(BEHIND_PROXY);

        assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("direct");
        assertThat(proxy.headersFor("/a.jar"))
                .get()
                .extracting(h -> h.containsKey("Proxy-authorization"))
                .isEqualTo(true);
        assertThat(origin.headersFor("/b.jar"))
                .get()
                .extracting(h -> h.containsKey("Proxy-authorization"))
                .as("the origin the proxy redirected to never sees the proxy's credential")
                .isEqualTo(false);
    }

    /** A proxy that is down fails the request; the failure names the target, never the proxy's credential. */
    @Test
    void a_failure_through_the_proxy_carries_no_proxy_credential() throws Exception {
        int closed;
        try (var probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = probe.getLocalPort();
        }
        String down = "http://alice:s3cr3t@127.0.0.1:" + closed;

        assertThatThrownBy(() -> through(Map.of("http_proxy", down)).get(BEHIND_PROXY))
                .isInstanceOf(IOException.class)
                .satisfies(e -> {
                    for (Throwable t = e; t != null; t = t.getCause()) {
                        assertThat(String.valueOf(t.getMessage()))
                                .doesNotContain("s3cr3t")
                                .doesNotContain("alice");
                    }
                })
                .hasMessageContaining("repo.example.test/a.jar");
    }
}
