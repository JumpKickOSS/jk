// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.journal.SessionJournal;
import cc.jumpkick.engine.sessions.EpisodeInference;
import cc.jumpkick.engine.sessions.Fact;
import cc.jumpkick.engine.sessions.SessionLog;
import cc.jumpkick.jsonl.MiniJson;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@code GET /api/sessions} and {@code /api/sessions/<id>}: bearer-gated, listed, paged. */
class HttpSessionsApiTest extends HttpEngineServerHarness {

    private static final String LABEL = "claude-code 3f9a";

    private final long[] now = {1_700_000_000_000L};
    private SessionLog log;
    private String id;

    @BeforeEach
    void sessions() {
        log = new SessionLog(
                SessionJournal.under(testJournal().buildsRoot()),
                () -> now[0],
                EpisodeInference.DEFAULT_IDLE_GAP_MILLIS,
                () -> "devbox");
        server.setSessions(log);
        id = log.open("mcp", "claude-code", LABEL, "/ws/app");
        for (int i = 0; i < 3; i++) {
            log.action(
                    "mcp",
                    LABEL,
                    "claude-code",
                    Fact.action(now[0] + i, 2, "/ws/app", Fact.Kind.GRAPH, true, "", 0),
                    "query",
                    "query \"where is the weekend uplift applied\"",
                    "PricingRules.weekendUplift");
        }
    }

    @Test
    void the_list_names_each_session_with_its_current_episode() throws Exception {
        HttpResponse<String> res = get("/api/sessions");
        assertThat(res.statusCode()).isEqualTo(200);
        List<?> sessions = MiniJson.list(MiniJson.parse(res.body()), "sessions");
        assertThat(sessions).hasSize(1);
        Map<?, ?> session = (Map<?, ?>) sessions.getFirst();
        assertThat(session.get("id")).isEqualTo(id);
        assertThat(session.get("label")).isEqualTo(LABEL);
        assertThat(session.get("state")).isEqualTo("live");
        assertThat(MiniJson.str(session.get("current"), "title")).isEqualTo("Exploring the code");
    }

    @Test
    void one_session_pages_its_actions_by_sequence() throws Exception {
        HttpResponse<String> res = get("/api/sessions/" + id + "?limit=2");
        assertThat(res.statusCode()).isEqualTo(200);
        Object body = requireNonNull(MiniJson.parse(res.body()));
        assertThat(MiniJson.str(MiniJson.get(body, "session"), "id")).isEqualTo(id);
        assertThat(MiniJson.list(body, "episodes")).hasSize(1);
        assertThat(MiniJson.list(body, "actions"))
                .extracting(a -> (Object) ((Map<?, ?>) a).get("seq"))
                .containsExactly(2.0, 3.0);
        assertThat(MiniJson.get(body, "next")).isEqualTo(2.0);

        Object older = requireNonNull(
                MiniJson.parse(get("/api/sessions/" + id + "?before=2&limit=2").body()));
        assertThat(MiniJson.list(older, "actions")).hasSize(1);
        assertThat(MiniJson.get(older, "next")).isNull();
    }

    @Test
    void an_unknown_or_malformed_id_is_refused_and_every_route_wants_the_bearer() throws Exception {
        assertThat(get("/api/sessions/mcp-0000-zz").statusCode()).isEqualTo(404);
        assertThat(get("/api/sessions/bad%20id").statusCode()).isEqualTo(400);
        assertThat(get("/api/sessions", "Authorization", "Bearer wrong").statusCode())
                .isEqualTo(401);
        assertThat(get("/api/sessions/" + id, "Authorization", "Bearer wrong").statusCode())
                .isEqualTo(401);
    }
}
