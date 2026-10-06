// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.http.EngineHttpJobs;
import cc.jumpkick.engine.http.McpHandler;
import cc.jumpkick.engine.http.StatusSnapshot;
import cc.jumpkick.engine.jobs.JobSpec;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code notifications/cancelled} cancels the job a call started, found through the call's progress token. */
class McpCancelledTest {

    private final List<Long> cancelled = new CopyOnWriteArrayList<>();

    private final EngineHttpJobs jobs = new EngineHttpJobs() {
        @Override
        public long trigger(JobSpec spec) {
            return 77L;
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

    private final McpHandler mcp = new McpHandler(
            () -> new StatusSnapshot("0.12.0", 1L, 0L, 0, 0, 1L << 20, 2L << 20, 256L << 20, -1L, 8, 16L << 30),
            jobs,
            dir -> Map.of(),
            List::of,
            "0.12.0");

    private void startRun(Path dir, String token) {
        String dirJson = dir.toString().replace("\\", "\\\\");
        mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"run\","
                + "\"arguments\":{\"dir\":\"" + dirJson + "\",\"wait\":false},"
                + "\"_meta\":{\"progressToken\":\"" + token + "\"}}}");
    }

    private static String cancel(String meta) {
        return "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":3" + meta + "}}";
    }

    @Test
    void a_cancel_carrying_the_calls_token_cancels_its_job_and_answers_nothing(@TempDir Path dir) {
        startRun(dir, "tok-9");
        assertThat(mcp.handleBody(cancel(",\"_meta\":{\"progressToken\":\"tok-9\"}")))
                .isEmpty();
        assertThat(cancelled).containsExactly(77L);
    }

    @Test
    void a_cancel_without_a_known_token_cancels_nothing(@TempDir Path dir) {
        startRun(dir, "tok-9");
        mcp.handleBody(cancel(""));
        mcp.handleBody(cancel(",\"_meta\":{\"progressToken\":\"someone-else\"}"));
        assertThat(cancelled).isEmpty();
    }
}
