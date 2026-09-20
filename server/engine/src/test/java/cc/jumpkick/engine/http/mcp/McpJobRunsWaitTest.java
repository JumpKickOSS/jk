// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.http.EngineHttpJobs;
import cc.jumpkick.engine.http.StatusSnapshot;
import cc.jumpkick.engine.jobs.JobSpec;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * A parked {@code jk_job action=wait} needs one fact twenty times a second: is this jid still
 * live. It asks the job's own liveness probe, not the dashboard's live-run snapshot, which copies
 * every in-flight module and step map under their monitors on each call — for as long as an hour
 * per parked agent.
 */
class McpJobRunsWaitTest {

    @Test
    void a_wait_watches_the_jobs_own_liveness_and_never_rebuilds_the_live_run_snapshot() throws Exception {
        AtomicInteger snapshots = new AtomicInteger();
        AtomicBoolean live = new AtomicBoolean(true);
        McpContext ctx = new McpContext(
                () -> new StatusSnapshot("0", 1L, 0L, 0, 0, 1L, 1L, 1L, -1L, 1, 1L),
                noJobs(),
                dir -> Map.of(),
                List::of,
                "0",
                null,
                () -> {
                    snapshots.incrementAndGet();
                    return List.of();
                },
                null,
                null);
        ctx.liveJid(jid -> jid == 7L && live.get());
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            live.set(false);
        });

        Map<String, Object> result =
                McpJobRuns.job(new McpCall(ctx, Map.of("action", "wait", "jid", 7L, "timeout_s", 5), null));

        @SuppressWarnings("unchecked")
        Map<String, Object> envelope = (Map<String, Object>) requireNonNull(result.get("structuredContent"));
        assertThat(envelope.get("finished")).isEqualTo(true);
        assertThat(snapshots).as("the wait loop never took a live-run snapshot").hasValue(0);
    }

    private static EngineHttpJobs noJobs() {
        return new EngineHttpJobs() {
            @Override
            public long trigger(JobSpec spec) {
                return 1L;
            }

            @Override
            public boolean cancel(long requestId) {
                return false;
            }

            @Override
            public int cancelDir(String dir) {
                return 0;
            }
        };
    }
}
