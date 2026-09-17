// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The stub's base names one start, not a port: a fresh server is a fresh repository to every
 * URL-keyed memo, while the paths a test seeds and reads back stay bare.
 */
class LoopbackHttpTest {

    private final LoopbackHttp http = new LoopbackHttp();
    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void stop() {
        http.stop();
    }

    @Test
    void the_base_carries_a_token_and_a_request_under_it_is_served_bare() throws Exception {
        http.start();
        http.serve("/g/a/1.0/a-1.0.pom", "<project/>");

        assertThat(http.base().getPath()).as("one segment, no trailing slash").matches("/[0-9a-z]+");
        assertThat(http.baseUrl()).endsWith("/");
        HttpResponse<String> under = get(URI.create(http.baseUrl() + "g/a/1.0/a-1.0.pom"));
        assertThat(under.statusCode()).isEqualTo(200);
        assertThat(under.body()).isEqualTo("<project/>");
        HttpResponse<String> sha1 = get(URI.create(http.baseUrl() + "g/a/1.0/a-1.0.pom.sha1"));
        assertThat(sha1.statusCode()).isEqualTo(200);

        HttpResponse<String> bare = get(http.base().resolve("/g/a/1.0/a-1.0.pom"));
        assertThat(bare.statusCode())
                .as("a path spelled without the token is the same path")
                .isEqualTo(200);
        assertThat(http.requested())
                .containsExactly("/g/a/1.0/a-1.0.pom", "/g/a/1.0/a-1.0.pom.sha1", "/g/a/1.0/a-1.0.pom");
        assertThat(http.requestsFor("/g/a/1.0/a-1.0.pom")).isEqualTo(2);
    }

    @Test
    void every_start_is_a_different_base() throws Exception {
        http.start();
        URI first = http.base();
        http.stop();
        http.start();

        assertThat(http.base()).isNotEqualTo(first);
        assertThat(http.base().getPath()).isNotEqualTo(first.getPath());
    }

    @Test
    void bare_strips_only_this_start_token() {
        assertThat(LoopbackHttp.bare("/abc/g/a", "/abc")).isEqualTo("/g/a");
        assertThat(LoopbackHttp.bare("/abc", "/abc")).isEqualTo("/");
        assertThat(LoopbackHttp.bare("/abcd/g/a", "/abc")).isEqualTo("/abcd/g/a");
        assertThat(LoopbackHttp.bare("/g/a", "/abc")).isEqualTo("/g/a");
    }

    private HttpResponse<String> get(URI uri) throws Exception {
        return client.send(
                HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
