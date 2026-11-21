// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.jsonl.MiniJson;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@code GET /api/stats}: the savings rows {@code jk stats} prints, for the period {@code since} opens. */
class HttpStatsApiTest extends HttpEngineServerHarness {

    private static final long T = 1_700_000_001_000L;

    @Test
    void the_rows_are_the_total_then_each_project_for_the_runs_since_the_time_asked() throws Exception {
        BuildJournal journal = testJournal();
        journal.append(record("p-1", T, new BuildRecord.CacheBenefit(9_000, 4_000, 2, 2)), BuildJournal.Snapshot.NONE);
        journal.append(
                record("p-1", T - 60_000, new BuildRecord.CacheBenefit(9_000, 8_000, 1, 1)),
                BuildJournal.Snapshot.NONE);

        HttpResponse<String> resp = get("/api/stats?since=" + (T - 1_000));

        assertThat(resp.statusCode()).isEqualTo(200);
        List<Map<String, Object>> rows = rows(resp.body());
        assertThat(rows).extracting(r -> r.get("scope")).containsExactly("all", "project");
        assertThat(rows.getFirst()).containsEntry("savedMillis", 4_000L).containsEntry("runs", 1L);
        assertThat(rows.get(1)).containsEntry("projectId", "p-1").containsEntry("coord", "g:a");
        assertThat(rows(get("/api/stats").body()).getFirst())
                .as("no since: every run kept")
                .containsEntry("runs", 2L);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(String body) {
        Map<String, Object> parsed = (Map<String, Object>) requireNonNull(MiniJson.parse(body));
        return (List<Map<String, Object>>) requireNonNull(parsed.get("rows"));
    }

    @Test
    void a_since_that_is_not_a_number_is_refused() throws Exception {
        assertThat(get("/api/stats?since=yesterday").statusCode()).isEqualTo(400);
    }

    private static BuildRecord record(String projectId, long finishedAt, BuildRecord.CacheBenefit benefit) {
        return new BuildRecord(
                null,
                0L,
                BuildRecord.SCHEMA,
                "build",
                "/ws/a",
                "g:a",
                projectId,
                finishedAt - 1_000,
                finishedAt,
                1_000,
                true,
                false,
                0,
                "9.9-test",
                null,
                List.of(),
                List.of(),
                List.of(),
                "cli",
                null,
                null,
                benefit,
                false,
                null,
                0L,
                null,
                List.of());
    }
}
