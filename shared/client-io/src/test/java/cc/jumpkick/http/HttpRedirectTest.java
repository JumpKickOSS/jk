// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.testing.LoopbackHttp;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Where a repository credential may travel when the repository answers with a redirect.
 *
 * <p>Two listeners: {@code origin} on {@code 127.0.0.1} plays the private repository that
 * authenticates the request, {@code elsewhere} on {@code localhost} plays the CDN it hands the
 * download to. Same interface, different host name — which is all a redirect target needs to be
 * a different origin.
 */
class HttpRedirectTest {

    private static final String TOKEN = "Bearer s3cr3t-token";

    @RegisterExtension
    final LoopbackHttp origin = new LoopbackHttp();

    @RegisterExtension
    final LoopbackHttp elsewhere = new LoopbackHttp().host("localhost");

    @Test
    void a_cross_host_redirect_is_followed_without_the_authorization_header() throws Exception {
        elsewhere.serve("/cdn/a.jar", "bytes");
        origin.redirect("/repo/a.jar", elsewhere.base().resolve("/cdn/a.jar"));

        HttpResponse<byte[]> response =
                http().get(origin.base().resolve("/repo/a.jar"), Map.of("Authorization", TOKEN));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("bytes");
        assertThat(origin.headersFor("/repo/a.jar"))
                .get()
                .extracting(h -> h.get("Authorization"))
                .as("the repository itself is authenticated")
                .isEqualTo(List.of(TOKEN));
        assertThat(elsewhere.headersFor("/cdn/a.jar"))
                .get()
                .extracting(h -> h.get("Authorization"))
                .as("the host the repository redirected to never sees the repository's credential")
                .isNull();
    }

    @Test
    void a_same_origin_redirect_keeps_the_authorization_header() throws Exception {
        origin.serve("/store/b.jar", "moved");
        origin.redirect("/repo/b.jar", origin.base().resolve("/store/b.jar"));

        HttpResponse<byte[]> response =
                http().get(origin.base().resolve("/repo/b.jar"), Map.of("Authorization", TOKEN));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("moved");
        assertThat(origin.headersFor("/store/b.jar"))
                .get()
                .extracting(h -> h.get("Authorization"))
                .as("a repository that redirects within itself still needs the credential")
                .isEqualTo(List.of(TOKEN));
    }

    @Test
    void a_redirect_loop_is_cut_off() {
        origin.redirect("/loop", origin.base().resolve("/loop"));

        assertThatThrownBy(() -> http().get(origin.base().resolve("/loop")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("redirect");
    }

    /**
     * A 3xx that names no target is not a document. Returned as one, it would be a 200-shaped
     * success with an empty body to every caller that draws the failure line at 400.
     */
    @Test
    void a_redirect_without_a_location_is_an_error_rather_than_an_empty_success() {
        AtomicInteger hits = new AtomicInteger();
        origin.server().createContext("/nowhere", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        assertThatThrownBy(() -> http().get(origin.base().resolve("/nowhere")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("302")
                .hasMessageContaining("Location");
        assertThat(hits).as("a policy answer, not a fault to retry").hasValue(1);
    }

    /**
     * A 300 Multiple Choices or a 305 Use Proxy is a 3xx this client does not follow. Handed back,
     * either would read as a success to a caller drawing the failure line at 400; the answer is an
     * error naming the status and the URL.
     */
    @Test
    void a_3xx_that_is_not_followed_is_an_error_naming_the_status() {
        for (int status : new int[] {300, 305}) {
            String path = "/choices-" + status;
            origin.server().createContext(path, exchange -> {
                exchange.getResponseHeaders()
                        .add("Location", origin.base().resolve("/elsewhere").toString());
                exchange.sendResponseHeaders(status, -1);
                exchange.close();
            });

            assertThatThrownBy(() -> http().get(origin.base().resolve(path)))
                    .as("HTTP " + status)
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining(String.valueOf(status))
                    .hasMessageContaining(path);
        }
    }

    /** The success line is 2xx only; the whole range of it still passes through. */
    @Test
    void every_2xx_is_still_handed_back() throws Exception {
        for (int status : new int[] {200, 201, 204, 206, 299}) {
            String path = "/ok-" + status;
            origin.server().createContext(path, exchange -> {
                exchange.sendResponseHeaders(status, -1);
                exchange.close();
            });

            assertThat(http().get(origin.base().resolve(path)).statusCode())
                    .as("HTTP " + status)
                    .isEqualTo(status);
        }
    }

    /** 304 is the one 3xx that is a document: the answer to a conditional GET. */
    @Test
    void a_304_to_a_conditional_get_is_handed_back() throws Exception {
        origin.server().createContext("/cached", exchange -> {
            exchange.sendResponseHeaders(304, -1);
            exchange.close();
        });

        HttpResponse<byte[]> response =
                http().get(origin.base().resolve("/cached"), Map.of("If-None-Match", "\"etag\""));

        assertThat(response.statusCode()).isEqualTo(304);
    }

    @Test
    void a_downgrade_from_https_to_http_is_not_followed() {
        URI secure = URI.create("https://repo.example.com/a.jar");
        assertThat(Http.followable(secure, URI.create("http://repo.example.com/a.jar")))
                .isFalse();
        assertThat(Http.followable(secure, URI.create("https://cdn.example.com/a.jar")))
                .isTrue();
        assertThat(Http.followable(URI.create("http://repo.example.com/a.jar"), secure))
                .as("upgrading is fine")
                .isTrue();
    }

    /** Production's client, a short backoff. */
    private static Http http() {
        return new Http(Http.standardClient(), new Duration[] {Duration.ofMillis(1)});
    }
}
