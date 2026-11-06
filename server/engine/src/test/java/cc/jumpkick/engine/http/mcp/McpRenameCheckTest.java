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
import java.util.function.LongFunction;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class McpRenameCheckTest {

    private final List<JobSpec> submitted = new ArrayList<>();

    @Test
    void the_answers_test_instruction_is_run_with_its_modules_and_classes() {
        McpCall in = new McpCall(context(), Map.of("dir", "/w", "timeout_s", 30), null);
        String renamed = "Renamed Range.contains to includes: 1 declaration, 2 calls, in 2 files.\n"
                + "Every use the compiled classes record is renamed; no search needed. Test the test classes it"
                + " edited: run(kind=test, only=core,app, classes=RangeTest,ShopTest), or jk test -m core,app -S"
                + " RangeTest -S ShopTest. A rename changes no behaviour: once that test passes, the task is done.";

        String answer = McpRenameCheck.verdict(McpRenameCheck.after(in, renamed, Clock.SYSTEM.nanos(), List.of()));

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
        assertThat(McpRenameCheck.verdict(McpRenameCheck.after(in, refused, Clock.SYSTEM.nanos(), List.of())))
                .isEqualTo(refused);
        assertThat(submitted).isEmpty();
    }

    /**
     * A rename that could not see a module's or test set's calls is not done when its check passes:
     * the answer's last line names them to search, where it would say the task is done.
     */
    @Test
    void a_rename_with_sets_it_could_not_see_names_them_instead_of_saying_it_is_done() {
        String renamed = "Renamed Calc.doubled to twice: 1 declaration, 1 call, in 2 files.\n"
                + "Every use the compiled classes record is renamed. Not complete: the note below names the modules"
                + " with no compiled classes. Test next: run(kind=test), or jk test.\n\n[i] compile skipped: the"
                + " code does not compile; tool has no compiled classes, so the calls made there are missing:"
                + " search it for calls too";
        McpCall in = new McpCall(context(jid -> PASSED), Map.of("dir", "/w", "timeout_s", 60), null);

        String answer =
                McpRenameCheck.verdict(McpRenameCheck.after(in, renamed, Clock.SYSTEM.nanos(), List.of("tool")));

        assertThat(submitted).extracting(JobSpec::kind).containsExactly("test", "guard");
        assertThat(answer)
                .doesNotContain("Test next", "The task is done")
                .contains("\nChecked by jk: OK")
                .endsWith(
                        ". tool has no compiled classes, so the calls made there are missing: search it for calls too.");

        submitted.clear();
        McpCall complete = new McpCall(context(jid -> PASSED), Map.of("dir", "/w", "timeout_s", 60), null);
        assertThat(McpRenameCheck.verdict(McpRenameCheck.after(
                        complete, renamed.replace("Not complete", "Complete"), Clock.SYSTEM.nanos(), List.of())))
                .endsWith(". The task is done: no other build, test or search is needed.");
    }

    @Test
    void the_last_sentence_is_done_only_when_every_set_had_compiled_classes() {
        assertThat(McpRenameCheck.done(List.of()))
                .isEqualTo("The task is done: no other build, test or search is needed.");
        assertThat(McpRenameCheck.done(List.of("core", "app/src/test")))
                .isEqualTo("core, app/src/test have no compiled classes, so the calls made there are missing: search"
                        + " them for calls too.");
    }

    private static final String PASSED = "{\"id\":\"r3\",\"buildNumber\":69,\"kind\":\"test\",\"dir\":\"/w\","
            + "\"projectId\":\"q\",\"success\":true,\"exitCode\":0,\"millis\":5,\"coord\":\"g:b\","
            + "\"modules\":[],\"diagnostics\":[]}";

    private McpContext context() {
        return context(null);
    }

    private McpContext context(@Nullable LongFunction<String> finished) {
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
                finished);
        ctx.liveJid(jid -> false);
        ctx.journalSettleMs(20);
        // A wait-second of 10 ms: the 30 s budget the test names runs out in a fraction of a second.
        ctx.waitSecondMs(10);
        return ctx;
    }
}
