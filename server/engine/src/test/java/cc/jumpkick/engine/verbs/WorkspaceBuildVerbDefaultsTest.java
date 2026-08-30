// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.jsonl.Jsonl;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * One set of workspace-build defaults for every client (JK-2213): HTTP/MCP jobs and bare wire
 * requests behave exactly like the CLI — parallel module tests, streaming scheduler concurrency.
 */
class WorkspaceBuildVerbDefaultsTest {

    @Test
    void http_and_mcp_jobs_default_to_parallel_module_tests() {
        String line = new WorkspaceBuildVerb(null)
                .decodeJob(new JobSpec("build", "/tmp/ws", List.of(), List.of(), List.of(), List.of(), false, false));
        assertThat(Jsonl.bool(line, "parallelTests", false))
                .as("decodeJob must emit the CLI's default, not a serial one")
                .isTrue();
    }

    @Test
    void non_positive_module_concurrency_resolves_to_effective_jobs() {
        int resolved = WorkspaceBuildVerb.effectiveModuleConcurrency(0);
        assertThat(resolved)
                .as("0 must resolve to the streaming path's positive jobs count")
                .isPositive();
        assertThat(WorkspaceBuildVerb.effectiveModuleConcurrency(-3)).isPositive();
        // Explicit wire values (any client, any age) win verbatim.
        assertThat(WorkspaceBuildVerb.effectiveModuleConcurrency(1)).isEqualTo(1);
        assertThat(WorkspaceBuildVerb.effectiveModuleConcurrency(7)).isEqualTo(7);
    }
}
