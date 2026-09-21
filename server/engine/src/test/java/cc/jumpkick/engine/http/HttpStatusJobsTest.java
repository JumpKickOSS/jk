// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.jobs.JobRow;
import cc.jumpkick.jsonl.Jsonl;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** {@code GET /api/status} lists every live and queued job with the fields {@code jk engine status} prints. */
@Tag("integration")
class HttpStatusJobsTest extends HttpEngineServerHarness {

    @Test
    void status_lists_live_and_queued_jobs() throws Exception {
        List<JobRow> rows = List.of(
                JobRow.live(739, "test", "/home/me/app", 1_700_000_000_000L, 1, 1_700_000_500_000L),
                JobRow.queued(741, "format", "/home/me/tool", 1_700_000_600_000L, 0));
        snapshot = new StatusSnapshot(
                SNAPSHOT.version(),
                SNAPSHOT.pid(),
                SNAPSHOT.startedAtMillis(),
                SNAPSHOT.activeRequests(),
                1,
                SNAPSHOT.heapUsedBytes(),
                SNAPSHOT.heapCommittedBytes(),
                SNAPSHOT.heapMaxBytes(),
                SNAPSHOT.rssBytes(),
                SNAPSHOT.cores(),
                SNAPSHOT.totalMemoryBytes(),
                SNAPSHOT.availableMemoryBytes(),
                SNAPSHOT.systemCpuLoad(),
                SNAPSHOT.systemLoadAverage(),
                SNAPSHOT.engineEpoch(),
                SNAPSHOT.peakActiveRequests(),
                SNAPSHOT.peakActiveBuildPlans(),
                SNAPSHOT.idleDropped(),
                SNAPSHOT.logBytes(),
                SNAPSHOT.logRolledAt(),
                SNAPSHOT.ignoredSignals(),
                1,
                "",
                JobRow.toJson(rows));

        HttpResponse<String> response = get("/api/status");

        assertThat(response.statusCode()).isEqualTo(200);
        String body = response.body();
        assertThat(Jsonl.intValue(body, "activeBuildPlans", -1)).isEqualTo(1);
        assertThat(Jsonl.intValue(body, "queuedBuildPlans", -1)).isEqualTo(1);
        List<String> jobs = Jsonl.objectArray(body, "jobs");
        assertThat(jobs).hasSize(2);
        assertThat(Jsonl.longValue(jobs.get(0), "jid", -1)).isEqualTo(739);
        assertThat(Jsonl.str(jobs.get(0), "kind")).isEqualTo("test");
        assertThat(Jsonl.str(jobs.get(0), "dir")).isEqualTo("/home/me/app");
        assertThat(Jsonl.str(jobs.get(0), "state")).isEqualTo("live");
        assertThat(Jsonl.longValue(jobs.get(0), "since", -1)).isEqualTo(1_700_000_000_000L);
        assertThat(Jsonl.intValue(jobs.get(0), "workers", -1)).isEqualTo(1);
        assertThat(Jsonl.longValue(jobs.get(0), "lastEventAt", -1)).isEqualTo(1_700_000_500_000L);
        assertThat(Jsonl.str(jobs.get(1), "state")).isEqualTo("queued");
        assertThat(Jsonl.str(jobs.get(1), "kind")).isEqualTo("format");
        assertThat(Jsonl.intValue(jobs.get(1), "ahead", -1)).isZero();
    }

    @Test
    void an_idle_engine_lists_no_jobs() throws Exception {
        HttpResponse<String> response = get("/api/status");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"jobs\":[]");
    }
}
