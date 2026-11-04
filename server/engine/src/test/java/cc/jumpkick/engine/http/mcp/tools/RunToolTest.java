// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.http.EngineHttpJobs;
import cc.jumpkick.engine.http.StatusSnapshot;
import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpContext;
import cc.jumpkick.engine.jobs.JobSpec;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class RunToolTest {

    @Test
    void run_naming_a_live_jid_instead_of_a_finished_run_waits_on_that_job() {
        AtomicBoolean live = new AtomicBoolean(true);
        McpContext ctx = new McpContext(
                () -> new StatusSnapshot("0", 1L, 0L, 0, 0, 1L, 1L, 1L, -1L, 1, 1L),
                new EngineHttpJobs() {
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
                },
                dir -> Map.of(),
                List::of,
                "0",
                null,
                List::of,
                null,
                null);
        ctx.liveJid(jid -> jid == 3580L && live.get());
        ctx.journalSettleMs(50);
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            live.set(false);
        });

        Map<String, Object> result = new RunTool().call(new McpCall(ctx, Map.of("run", "3580", "timeout_s", 5), null));

        assertThat(String.valueOf(result.get("content")))
                .as("the agent asked for run=3580 holding a TIMEOUT's jid: it waited on the job")
                .doesNotContain("no matching run")
                .contains("jid=3580");
    }
}
