// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.http.api.EngineHttpJobs;
import cc.jumpkick.engine.http.api.StatusSnapshot;
import cc.jumpkick.engine.http.mcp.McpContext;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.jsonl.MiniJson;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code run} refuses what it does not take, by name, and starts nothing for it. */
class RunToolTest {

    private final McpContext ctx = new McpContext(
            () -> new StatusSnapshot("0", 1L, 0L, 0, 0, 1L, 1L, 1L, -1L, 1, 1L),
            new EngineHttpJobs() {
                @Override
                public long trigger(JobSpec spec) {
                    throw new AssertionError("a refused call starts no build");
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

    @Test
    void an_unknown_argument_a_missing_action_or_an_unknown_one_is_refused_by_name(@TempDir Path dir) {
        String unknown = call(Map.of("dir", dir.toString(), "action", "start", "wait", false));
        String missing = call(Map.of("dir", dir.toString()));
        String action = call(Map.of("dir", dir.toString(), "action", "restart"));

        assertThat(unknown).contains("\"isError\":true").contains("run does not take wait");
        assertThat(missing).contains("\"isError\":true").contains("run needs action: start, logs or stop");
        assertThat(action).contains("\"isError\":true").contains("action is start, logs or stop");
    }

    @Test
    void a_kind_is_refused_in_one_line_that_points_at_build(@TempDir Path dir) {
        String refused = call(Map.of("dir", dir.toString(), "kind", "test"));
        String withAction = call(Map.of("dir", dir.toString(), "action", "start", "kind", "build"));

        for (String r : List.of(refused, withAction)) {
            assertThat(r)
                    .contains("\"isError\":true")
                    .contains(RunTool.NOT_A_BUILD)
                    .doesNotContain("does not take");
        }
        assertThat(RunTool.NOT_A_BUILD).doesNotContain("\n").hasSizeLessThanOrEqualTo(78);
    }

    @Test
    void logs_before_any_start_say_how_to_start_one(@TempDir Path dir) {
        String logs = call(Map.of("dir", dir.toString(), "action", "logs"));

        assertThat(logs).contains("\"isError\":true").contains("run(action=start) starts the app");
    }

    @Test
    void a_request_between_slashes_that_is_no_regex_is_refused(@TempDir Path dir) {
        String logs = call(Map.of("dir", dir.toString(), "action", "logs", "request", "/req=(/"));

        assertThat(logs).contains("\"isError\":true").contains("request /req=(/ is not a Java regex");
    }

    @Test
    void the_card_requires_an_action_names_stdout_and_stderr_and_takes_no_goal() {
        String card = MiniJson.write(new RunTool().spec().listed());

        assertThat(card)
                .contains("\"start\"", "\"logs\"", "\"stop\"", "\"request\"", "\"log\"")
                .contains("stdout and stderr")
                .contains("\"required\":[\"action\"]")
                .doesNotContain("\"goal\"");
    }

    @Test
    void run_is_on_the_extended_list_only() {
        McpTools tools = McpTools.standard();

        assertThat(tools.names()).contains("run");
        assertThat(tools.loopNames()).doesNotContain("run");
    }

    private String call(Map<String, Object> args) {
        return MiniJson.write(McpTools.standard().call(ctx, Map.of("name", "run", "arguments", args)));
    }
}
