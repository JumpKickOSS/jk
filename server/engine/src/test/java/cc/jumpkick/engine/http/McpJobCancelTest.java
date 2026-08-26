// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.jobs.JobSpec;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** {@code jk_job action=cancel} target selection — no HTTP bind. */
class McpJobCancelTest {

    private final List<Long> cancelled = new CopyOnWriteArrayList<>();

    private final EngineHttpJobs jobs = new EngineHttpJobs() {
        @Override
        public long trigger(JobSpec spec) {
            return 1L;
        }

        @Override
        public boolean cancel(long requestId) {
            cancelled.add(requestId);
            return true;
        }

        public int cancelDir(String dir) {
            return 0;
        }
    };

    private McpHandler handler(List<HttpLive.Run> live) {
        return new McpHandler(
                () -> new StatusSnapshot(
                        "0.12.0",
                        1L,
                        System.currentTimeMillis(),
                        0,
                        0,
                        1L << 20,
                        2L << 20,
                        256L << 20,
                        -1L,
                        0,
                        8,
                        16L << 30),
                jobs,
                dir -> Map.of(),
                List::of,
                "0.12.0",
                new ProgressTokenRegistry(),
                () -> live,
                AdmissionYield.NONE,
                null);
    }

    private static HttpLive.Run run(long jid, long startedAt) {
        return new HttpLive.Run(jid, jid, "build", "/ws", "g:a", startedAt, 50.0, "j-" + jid);
    }

    @Test
    void omitted_jid_cancel_picks_the_newest_job_not_iteration_order() {
        // Newest first in the snapshot — the old last-element pick would choose jid 9.
        McpHandler mcp = handler(List.of(run(7L, 2_000L), run(9L, 1_000L)));
        mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"jk_bind\",\"arguments\":{\"dir\":\"/ws\"}}}");
        String body = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"jk_job\",\"arguments\":{\"action\":\"cancel\"}}}");
        assertThat(cancelled).containsExactly(7L);
        assertThat(body).contains("\"cancelled\":true");
    }

    @Test
    void tied_start_times_prefer_the_higher_jid() {
        McpHandler mcp = handler(List.of(run(3L, 1_000L), run(5L, 1_000L)));
        mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"jk_bind\",\"arguments\":{\"dir\":\"/ws\"}}}");
        mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"jk_job\",\"arguments\":{\"action\":\"cancel\"}}}");
        assertThat(cancelled).containsExactly(5L);
    }

    @Test
    void unbound_cancel_without_jid_is_an_error_not_a_kill() {
        McpHandler mcp = handler(List.of(run(7L, 2_000L)));
        String body = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"jk_job\",\"arguments\":{\"action\":\"cancel\"}}}");
        assertThat(body).contains("-32602");
        assertThat(body).contains("jid");
        assertThat(cancelled).isEmpty();
    }
}
