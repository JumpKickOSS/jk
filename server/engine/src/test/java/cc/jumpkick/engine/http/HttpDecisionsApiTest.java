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
 * {@code /api/decisions}: bearer-gated, listed and answered from the dashboard. Holding the bearer
 * token is not proof that a person decided (an agent can read it and drive a browser), so a
 * token-only {@code POST} declines a trust-changing decision but cannot accept it.
 */
class HttpDecisionsApiTest extends HttpEngineServerHarness {

    private final DecisionQueue queue = new DecisionQueue();

    @BeforeEach
    void decisions() {
        server.setDecisions(queue);
    }

    private Decision open(String kind, boolean trust) {
        return queue.open(new DecisionQueue.Ask(
                kind,
                "Apply 1 move?",
                List.of("jackson 2.18.2 → 3.0.0"),
                DecisionForms.confirm(kind, "Apply 1 move?", "Yes"),
                trust,
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
    void a_bearer_token_post_cannot_accept_a_trust_changing_decision() throws Exception {
        Decision d = open("update", true);

        HttpResponse<String> res = post(d.id(), "{\"action\":\"accept\",\"answers\":{\"answer\":\"yes\"}}", token());

        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(MiniJson.str(MiniJson.parse(res.body()), "error")).contains("trusts", "jk decide " + d.id());
        assertThat(queue.find(d.id()).orElseThrow().open()).isTrue();
        assertThat(queue.spend(d.id())).isFalse();
    }

    @Test
    void the_dashboard_declines_a_trust_changing_decision_and_a_late_answer_gets_409() throws Exception {
        Decision d = open("update", true);
        HttpResponse<String> declined = post(d.id(), "{\"action\":\"decline\"}", token());
        assertThat(declined.statusCode()).isEqualTo(200);
        assertThat(MiniJson.str(MiniJson.parse(declined.body()), "state")).isEqualTo("declined");
        assertThat(MiniJson.str(MiniJson.parse(declined.body()), "answeredBy")).isEqualTo("dashboard");

        HttpResponse<String> late = post(d.id(), "{\"action\":\"decline\"}", token());
        assertThat(late.statusCode()).isEqualTo(409);
    }

    @Test
    void the_page_the_host_opened_holds_the_key_that_accepts_a_trust_changing_decision() throws Exception {
        Decision d = open("update", true);
        String body =
                "{\"action\":\"accept\",\"answers\":{\"answer\":\"yes\"},\"key\":\"" + queue.hostKey(d.id()) + "\"}";
        HttpResponse<String> res = post(d.id(), body, token());
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(queue.find(d.id()).orElseThrow().answeredBy()).isEqualTo(DecisionSurface.HOST_URL);
    }

    @Test
    void any_other_decision_is_answered_from_the_dashboard_and_bad_answers_are_refused() throws Exception {
        Decision d = open("disk-clean", false);
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
        Decision d = open("update", true);

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
            assertThat(row.toString()).doesNotContain(String.valueOf(queue.hostKey(d.id())));
        });
        assertThat(get("/api/decisions/" + d.id()).statusCode()).isEqualTo(200);
        assertThat(get("/api/decisions/nope").statusCode()).isEqualTo(404);
    }
}
