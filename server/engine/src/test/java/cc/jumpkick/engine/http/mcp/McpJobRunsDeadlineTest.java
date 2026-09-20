// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.engine.http.EngineHttpJobs;
import cc.jumpkick.engine.http.StatusSnapshot;
import cc.jumpkick.engine.jobs.JobSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code jk_run deadline_s}: a detached job's only bound is its wall deadline, so an agent may set
 * it per job in the unit it thinks in. Absent leaves the engine's detached default, {@code 0}
 * lifts the cap, and a negative is the caller's error.
 */
class McpJobRunsDeadlineTest {

    private final List<JobSpec> admitted = new ArrayList<>();

    @Test
    void deadline_s_rides_the_spec_in_milliseconds() {
        McpContext ctx = context();
        McpJobRuns.run(new McpCall(ctx, Map.of("kind", "build", "dir", "/ws", "wait", false), null), null);
        McpJobRuns.run(
                new McpCall(ctx, Map.of("kind", "build", "dir", "/ws", "wait", false, "deadline_s", 90), null), null);
        McpJobRuns.run(
                new McpCall(ctx, Map.of("kind", "build", "dir", "/ws", "wait", false, "deadline_s", 0), null), null);
        assertThat(admitted).extracting(JobSpec::deadlineMs).containsExactly(null, 90_000L, 0L);
    }

    @Test
    void a_negative_deadline_is_an_invalid_argument() {
        McpContext ctx = context();
        assertThatThrownBy(() -> McpJobRuns.run(
                        new McpCall(ctx, Map.of("kind", "build", "dir", "/ws", "wait", false, "deadline_s", -1), null),
                        null))
                .isInstanceOf(McpError.class)
                .hasMessageContaining("deadline_s");
        assertThat(admitted).isEmpty();
    }

    private McpContext context() {
        return new McpContext(
                () -> new StatusSnapshot("0", 1L, 0L, 0, 0, 1L, 1L, 1L, -1L, 1, 1L),
                new EngineHttpJobs() {
                    @Override
                    public long trigger(JobSpec spec) {
                        admitted.add(spec);
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
    }
}
