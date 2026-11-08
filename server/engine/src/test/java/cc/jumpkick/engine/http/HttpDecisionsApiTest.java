// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.decisions.Decision;
import cc.jumpkick.engine.decisions.DecisionForms;
import cc.jumpkick.engine.decisions.DecisionQueue;
import cc.jumpkick.engine.decisions.DecisionSurface;
import cc.jumpkick.jsonl.MiniJson;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code /api/decisions}: bearer-gated, listed and answered from the dashboard, which accepts or
 * declines any decision; every change, from any surface, is an SSE {@code decision} event.
 */
class HttpDecisionsApiTest extends HttpEngineServerHarness {

    private final DecisionQueue queue = new DecisionQueue();

    @BeforeEach
    void decisions() {
        server.setDecisions(queue);
    }

    private Decision open(String kind) {
        return queue.open(new DecisionQueue.Ask(
                kind,
                "Apply 1 move?",
                List.of("jackson 2.18.2 → 3.0.0"),
                DecisionForms.confirm(kind, "Apply 1 move?", "Yes"),
                null,
                "claude-code 3f9a",
                null,
                "/ws/app"));
    }

    private HttpResponse<String> post(String id, String body, String token) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "api/decisions/" + id))
                        .header("Authorization", "Bearer " + token)
                        .header("X-Jk-Engine-Epoch", SNAPSHOT.engineEpoch())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void the_dashboard_accepts_any_decision_with_the_bearer_token_alone() throws Exception {
        Decision d = open("update");

        HttpResponse<String> res = post(d.id(), "{\"action\":\"accept\",\"answers\":{\"answer\":\"yes\"}}", token());

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(MiniJson.str(MiniJson.parse(res.body()), "state")).isEqualTo("accepted");
        assertThat(queue.find(d.id()).orElseThrow().answeredBy()).isEqualTo(DecisionSurface.DASHBOARD);
        assertThat(queue.spend(d.id())).as("the operation proceeds, once").isTrue();
    }

    @Test
    void the_dashboard_declines_a_decision_and_a_late_answer_gets_409() throws Exception {
        Decision d = open("update");
        HttpResponse<String> declined = post(d.id(), "{\"action\":\"decline\"}", token());
        assertThat(declined.statusCode()).isEqualTo(200);
        assertThat(MiniJson.str(MiniJson.parse(declined.body()), "state")).isEqualTo("declined");
        assertThat(MiniJson.str(MiniJson.parse(declined.body()), "answeredBy")).isEqualTo("dashboard");

        HttpResponse<String> late = post(d.id(), "{\"action\":\"decline\"}", token());
        assertThat(late.statusCode()).isEqualTo(409);
    }

    @Test
    void bad_answers_are_refused() throws Exception {
        Decision d = open("disk-clean");
        assertThat(post(d.id(), "{\"action\":\"accept\",\"answers\":{\"answer\":\"maybe\"}}", token())
                        .statusCode())
                .isEqualTo(400);
        assertThat(post(d.id(), "{\"action\":\"accept\",\"answers\":{\"answer\":\"yes\"}}", token())
                        .statusCode())
                .isEqualTo(200);
        assertThat(queue.find(d.id()).orElseThrow().accepted()).isTrue();
        assertThat(post("nope", "{\"action\":\"decline\"}", token()).statusCode())
                .isEqualTo(404);
        assertThat(post(d.id(), "{\"action\":\"accept\"}", "wrong-token").statusCode())
                .isEqualTo(401);
    }

    @Test
    void the_list_and_one_decision_are_served_and_every_change_is_an_sse_decision_event() throws Exception {
        Iterator<String> lines = openEvents("");
        Decision d = open("update");

        String data = awaitSseEvent(lines, "decision");
        assertThat(MiniJson.str(MiniJson.parse(data.substring("data: ".length())), "id"))
                .isEqualTo(d.id());

        HttpResponse<String> list = get("/api/decisions");
        assertThat(list.statusCode()).isEqualTo(200);
        List<?> rows = MiniJson.list(MiniJson.parse(list.body()), "decisions");
        assertThat(rows).singleElement().satisfies(r -> {
            Map<?, ?> row = (Map<?, ?>) r;
            assertThat(row.get("question")).isEqualTo("Apply 1 move?");
            assertThat(row.get("command")).isEqualTo("jk decide " + d.id());
        });
        assertThat(get("/api/decisions/" + d.id()).statusCode()).isEqualTo(200);
        assertThat(get("/api/decisions/nope").statusCode()).isEqualTo(404);

        queue.accept(d.id(), Map.of("answer", "yes"), DecisionSurface.CLI);
        String settled = awaitSseEvent(lines, "decision");
        assertThat(MiniJson.str(MiniJson.parse(settled.substring("data: ".length())), "state"))
                .as("an answer from another surface reaches the banner, which drops the decision")
                .isEqualTo("accepted");
    }
}
