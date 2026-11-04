// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.http.EngineHttpJobs;
import cc.jumpkick.engine.http.StatusSnapshot;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.host.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpRenameCheckTest {

    private final List<JobSpec> submitted = new ArrayList<>();

    @Test
    void the_answers_test_instruction_is_run_with_its_modules_and_classes() {
        McpCall in = new McpCall(context(), Map.of("dir", "/w", "timeout_s", 5), null);
        String renamed = "Renamed Range.contains to includes: 1 declaration, 2 calls, in 2 files.\n"
                + "Every use the compiled classes record is renamed; no search needed. Test the test classes it"
                + " edited: run(kind=test, only=core,app, classes=RangeTest,ShopTest), or jk test -m core,app -S"
                + " RangeTest -S ShopTest. A rename changes no behaviour: once that test passes, the task is done.";

        String answer = McpRenameCheck.verdict(McpRenameCheck.after(in, renamed, Clock.SYSTEM.nanos()));

        assertThat(submitted).hasSize(1);
        assertThat(submitted.getFirst().kind()).isEqualTo("test");
        assertThat(submitted.getFirst().modules()).containsExactly("core", "app");
        assertThat(submitted.getFirst().classes()).containsExactly("RangeTest", "ShopTest");
        assertThat(answer)
                .startsWith("Renamed Range.contains to includes")
                .as("the instruction is replaced by what the check found")
                .doesNotContain("Test the test classes it edited")
                .contains("Checked by jk, the edited tests: FAIL");
    }

    @Test
    void an_answer_with_no_test_instruction_is_returned_as_it_is() {
        McpCall in = new McpCall(context(), Map.of("dir", "/w"), null);
        String refused = "Not renamed: Range.equals overrides Object's.";
        assertThat(McpRenameCheck.verdict(McpRenameCheck.after(in, refused, Clock.SYSTEM.nanos())))
                .isEqualTo(refused);
        assertThat(submitted).isEmpty();
    }

    private McpContext context() {
        McpContext ctx = new McpContext(
                () -> new StatusSnapshot("0", 1L, 0L, 0, 0, 1L, 1L, 1L, -1L, 1, 1L),
                new EngineHttpJobs() {
                    @Override
                    public long trigger(JobSpec spec) {
                        submitted.add(spec);
                        return 41L;
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
        ctx.liveJid(jid -> false);
        ctx.journalSettleMs(20);
        return ctx;
    }
}
