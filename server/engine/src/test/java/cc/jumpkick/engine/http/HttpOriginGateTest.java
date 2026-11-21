// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A request that would change something, carrying the bearer token and the engine's epoch, is
 * still refused when its {@code Origin} is another page's, on every route: the API, MCP and the
 * front door's prefix. So is any MCP request, its event stream included. Other reads, and clients
 * that send no {@code Origin}, pass.
 */
class HttpOriginGateTest extends HttpEngineServerHarness {

    @TempDir
    Path project;

    private HttpResponse<String> send(String method, String path, String body, @Nullable String origin)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path.substring(1)))
                .header("Authorization", "Bearer " + token())
                .header("X-Jk-Engine-Epoch", SNAPSHOT.engineEpoch())
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        if (origin != null) b.header("Origin", origin);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String build() {
        return "{\"dir\":\"" + project.toString().replace("\\", "\\\\") + "\"}";
    }

    @Test
    void a_changing_request_from_another_origin_is_refused_on_every_route() throws Exception {
        for (String origin : new String[] {
            "http://evil.example", "null", "http://127.0.0.1:" + (port + 1), "http://localhost:" + port
        }) {
            assertThat(send("POST", "/api/build", build(), origin).statusCode())
                    .as("build from " + origin)
                    .isEqualTo(403);
            assertThat(send("PUT", "/api/project/file", "{}", origin).statusCode())
                    .as("file write from " + origin)
                    .isEqualTo(403);
            assertThat(send("DELETE", "/api/history", "", origin).statusCode())
                    .as("history delete from " + origin)
                    .isEqualTo(403);
            assertThat(send("POST", "/mcp", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}", origin)
                            .statusCode())
                    .as("MCP from " + origin)
                    .isEqualTo(403);
            assertThat(send("POST", "/engines/0123456789ab/api/build", build(), origin)
                            .statusCode())
                    .as("front door from " + origin)
                    .isEqualTo(403);
        }
        assertThat(triggeredDirs).isEmpty();
    }

    @Test
    void an_mcp_event_stream_from_another_origin_is_refused_here_and_through_the_front_door() throws Exception {
        for (String path : new String[] {"/mcp", "/engines/0123456789ab/mcp"}) {
            assertThat(get(
                                    path,
                                    "Authorization",
                                    "Bearer " + token(),
                                    "Accept",
                                    "text/event-stream",
                                    "Origin",
                                    "http://evil.example")
                            .statusCode())
                    .as("GET " + path + " from another origin")
                    .isEqualTo(403);
        }
        assertThat(get("/mcp", "Authorization", "Bearer " + token(), "Origin", "http://127.0.0.1:" + port)
                        .statusCode())
                .as("GET /mcp from the listener's own page")
                .isNotEqualTo(403);
    }

    @Test
    void the_listeners_own_pages_and_clients_without_an_origin_pass() throws Exception {
        assertThat(send("POST", "/api/build", build(), "http://127.0.0.1:" + port)
                        .statusCode())
                .isEqualTo(202);
        assertThat(send("POST", "/api/build", build(), null).statusCode()).isEqualTo(202);
        assertThat(triggeredDirs).hasSize(2);
    }

    @Test
    void a_read_from_another_origin_meets_only_the_token_gate() throws Exception {
        assertThat(get("/api/status", "Origin", "http://evil.example").statusCode())
                .isEqualTo(200);
    }
}
