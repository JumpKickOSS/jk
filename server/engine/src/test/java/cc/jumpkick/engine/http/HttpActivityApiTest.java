// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.changes.Change;
import cc.jumpkick.changes.ChangeJournal;
import cc.jumpkick.engine.activity.Activity;
import cc.jumpkick.engine.decisions.Decision;
import cc.jumpkick.engine.decisions.DecisionForms;
import cc.jumpkick.engine.decisions.DecisionQueue;
import cc.jumpkick.engine.decisions.DecisionSurface;
import cc.jumpkick.engine.notices.Notices;
import cc.jumpkick.jsonl.MiniJson;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code /api/activity}: the Activity page's history, filtered and paged behind a cursor, facets on the first page. */
class HttpActivityApiTest extends HttpEngineServerHarness {

    @TempDir
    Path changes;

    private final DecisionQueue queue = new DecisionQueue();

    @BeforeEach
    void activity() {
        server.setActivity(new Activity(() -> new ChangeJournal(changes), queue, new Notices()));
    }

    @Test
    void the_first_page_carries_the_facets_and_a_cursor_reaches_the_rest() throws Exception {
        ChangeJournal j = new ChangeJournal(changes);
        j.record("config.toml", Change.EDIT, "set ui.ask", null);
        j.record("JDK 25", Change.INSTALL, "installed JDK 25", null);
        Decision d = queue.open(new DecisionQueue.Ask(
                "disk-clean",
                "Clean?",
                List.of(),
                DecisionForms.confirm("disk-clean", "Clean?", "Yes"),
                "s1",
                null,
                null,
                "/w/app"));
        queue.accept(d.id(), Map.of(DecisionForms.ANSWER, DecisionForms.YES), DecisionSurface.CLI);

        HttpResponse<String> first = get("/api/activity?limit=2");
        assertThat(first.statusCode()).isEqualTo(200);
        Object body = MiniJson.parse(first.body());
        assertThat(MiniJson.list(body, "entries")).hasSize(2);
        String next = (String) MiniJson.get(body, "next");
        assertThat(next).isNotNull();
        assertThat(MiniJson.get(body, "facets")).isInstanceOf(Map.class);

        Object rest =
                MiniJson.parse(get("/api/activity?limit=2&before=" + URLEncoder.encode(next, StandardCharsets.UTF_8))
                        .body());
        assertThat(MiniJson.list(rest, "entries")).hasSize(1);
        assertThat(MiniJson.get(rest, "next")).isNull();
        assertThat(MiniJson.get(rest, "facets")).isNull();

        Object cli = MiniJson.parse(get("/api/activity?surface=cli").body());
        assertThat(MiniJson.list(cli, "entries"))
                .singleElement()
                .satisfies(e -> assertThat(((Map<?, ?>) e).get("answeredBy")).isEqualTo("cli"));
    }
}
