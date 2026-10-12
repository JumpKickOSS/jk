// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A request body past the cap is refused with a 413 on every POST handler, never truncated and
 * parsed; one exactly at the cap is still read whole.
 */
@Tag("integration")
class HttpRequestBodyCapTest extends HttpEngineServerHarness {

    @Test
    void a_body_past_the_cap_is_a_413_on_every_post_handler() throws Exception {
        String oversized = "{\"dir\":\"" + "x".repeat(HttpRequests.MAX_BODY_BYTES) + "\"}";
        for (String path : List.of("api/build", "api/cancel", "api/projects", "mcp")) {
            HttpResponse<String> resp = post(path, oversized);
            assertThat(resp.statusCode()).as(path).isEqualTo(413);
            assertThat(resp.body()).as(path).contains("request body too large");
            assertThat(resp.body()).as(path).contains("\"maxBytes\":" + HttpRequests.MAX_BODY_BYTES);
        }
        assertThat(triggeredDirs).isEmpty();
    }

    @Test
    void a_body_exactly_at_the_cap_is_read_whole() throws Exception {
        String head = "{\"jid\":7,\"pad\":\"";
        String tail = "\"}";
        String body = head + "x".repeat(HttpRequests.MAX_BODY_BYTES - head.length() - tail.length()) + tail;
        HttpResponse<String> resp = post("api/cancel", body);
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("\"cancelled\":true");
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + path))
                        .header("Authorization", "Bearer " + token())
                        .header("X-Jk-Engine-Epoch", SNAPSHOT.engineEpoch())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
