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

    @Test
    void same_origin_means_scheme_host_and_port() {
        URI a = URI.create("https://Repo.example.com/x");
        assertThat(Http.sameOrigin(a, URI.create("https://repo.example.com:443/y")))
                .isTrue();
        assertThat(Http.sameOrigin(a, URI.create("https://repo.example.com:8443/y")))
                .isFalse();
        assertThat(Http.sameOrigin(a, URI.create("http://repo.example.com/y"))).isFalse();
        assertThat(Http.sameOrigin(a, URI.create("https://cdn.example.com/y"))).isFalse();
    }

    /** Production's client, a short backoff. */
    private static Http http() {
        return new Http(Http.standardClient(), new Duration[] {Duration.ofMillis(1)});
    }
}
