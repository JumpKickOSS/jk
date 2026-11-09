// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.notices.Notices;
import cc.jumpkick.jsonl.MiniJson;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Iterator;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code /api/notices}: the bell's notices and its seen marker, read and moved from the dashboard,
 * every change an SSE {@code notice} event so every tab's count agrees.
 */
class HttpNoticesApiTest extends HttpEngineServerHarness {

    private final Notices notices = new Notices();

    @BeforeEach
    void notices() {
        server.setNotices(notices);
    }

    private HttpResponse<String> seen(String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "api/notices/seen"))
                        .header("Authorization", "Bearer " + token())
                        .header("X-Jk-Engine-Epoch", SNAPSHOT.engineEpoch())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void the_bell_lists_notices_and_a_seen_post_clears_the_count_for_every_tab() throws Exception {
        Iterator<String> lines = openEvents("");
        Notices.Notice n = notices.add("decision", "An agent needs you: Go?", "#decision/d1", "d1");
        assertThat(awaitSseEvent(lines, "notice"))
                .contains("\"change\":\"added\"")
                .contains("\"unseen\":1");

        HttpResponse<String> list = get("/api/notices");
        assertThat(list.statusCode()).isEqualTo(200);
        Object body = MiniJson.parse(list.body());
        assertThat(MiniJson.get(body, "unseen")).isEqualTo(1.0);
        assertThat(MiniJson.list(body, "notices"))
                .singleElement()
                .satisfies(row -> assertThat(((Map<?, ?>) row).get("text")).isEqualTo("An agent needs you: Go?"));

        HttpResponse<String> moved = seen("{\"upTo\":" + n.at() + "}");
        assertThat(moved.statusCode()).isEqualTo(200);
        assertThat(MiniJson.get(MiniJson.parse(moved.body()), "unseen")).isEqualTo(0.0);
        assertThat(awaitSseEvent(lines, "notice"))
                .contains("\"change\":\"seen\"")
                .contains("\"unseen\":0");

        assertThat(seen("{}").statusCode()).isEqualTo(400);
    }
}
