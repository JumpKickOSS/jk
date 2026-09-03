// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import cc.jumpkick.wire.runtime.ModulePlan;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The shared workspace renderer's own behaviour. These assertions are what "one owner" buys: change
 * a rule here and every verb that renders a workspace changes with it, because
 * {@link WorkspaceRunViewOwnerTest} proves there is nowhere else the rule could be written.
 */
class WorkspaceRunViewTest {

    @BeforeEach
    @AfterEach
    void beginCommand() {
        CliOutput.beginCommand(false);
    }

    private static ModulePlan module(String coord, Path dir) {
        return ModulePlan.fromWire(dir, coord, BuildPlan.builder(coord).build(), 1, false, dir);
    }

    private static WorkspaceRunView headlessView() {
        return new WorkspaceRunView(new WorkspaceRunView.Chrome("Build", true, true), Path.of("/ws"), null, false);
    }

    @Test
    void completion_denominator_is_the_plan_size_not_the_number_finished_so_far() {
        var run = headlessView();
        WorkspaceBuildListener lis = run.headless();
        String out = TestAnsi.strip(Capture.stdout(() -> {
            lis.onPlan(List.of(module("g:a", Path.of("/ws/a")), module("g:b", Path.of("/ws/b"))));
            lis.onModuleFinish(new ModuleOutcome("g:a", Path.of("/ws/a"), true, 0, 5, true));
        }));
        // The nine hand-written copies had three different denominators; two of them rendered
        // `[01 of 01]` for every module of an N-module workspace.
        assertThat(out).contains("[1 of 2]");
        assertThat(run.planned()).isEqualTo(2);
    }

    @Test
    void a_module_with_no_plan_burst_still_numbers_against_itself() {
        var lis = headlessView().headless();
        String out = TestAnsi.strip(
                Capture.stdout(() -> lis.onModuleFinish(new ModuleOutcome("g:a", Path.of("/ws/a"), true, 0, 5, true))));
        assertThat(out).contains("[1 of 1]");
    }

    @Test
    void planned_only_grows_so_a_late_engine_correction_cannot_shrink_the_denominator() {
        var run = headlessView();
        run.seedPlanned(7);
        run.seedPlanned(3);
        assertThat(run.planned()).isEqualTo(7);
        run.seedPlanned(9);
        assertThat(run.planned()).isEqualTo(9);
    }

    @Test
    void buffered_module_output_flushes_as_one_block_ahead_of_its_completion_line() {
        var lis = headlessView().headless();
        Path dir = Path.of("/ws/a");
        String out = TestAnsi.strip(Capture.stdout(() -> {
            lis.onPlan(List.of(module("g:a", dir)));
            var mod = lis.onModuleStart(module("g:a", dir));
            mod.output("compile-java", "first line");
            mod.output("compile-java", "second line");
            lis.onModuleFinish(new ModuleOutcome("g:a", dir, true, 0, 5, true));
        }));
        assertThat(out).contains("first line");
        assertThat(out.indexOf("first line")).isLessThan(out.indexOf("second line"));
        assertThat(out.indexOf("second line")).isLessThan(out.indexOf("[1 of 1]"));
    }

    @Test
    void a_test_failure_diagnostic_is_left_to_the_styled_block_not_repeated_as_an_error_line() {
        var lis = headlessView().headless();
        Path dir = Path.of("/ws/a");
        String out = TestAnsi.strip(Capture.stdout(() -> {
            var mod = lis.onModuleStart(module("g:a", dir));
            mod.error("run-tests", "test-failure", "MyTest.shouldWork failed");
            mod.error("compile-java", "javac", "cannot find symbol");
            lis.onModuleFinish(new ModuleOutcome("g:a", dir, false, 1, 5, true));
        }));
        assertThat(out).doesNotContain("MyTest.shouldWork failed");
        assertThat(out).contains("cannot find symbol");
    }

    @Test
    void non_buffered_chrome_prints_nothing_of_its_own() {
        // `jk compile` / `jk image` / `jk native` open a JkManager region that is dormant under
        // --output json; anything written above it would land in the JSON stream.
        var run =
                new WorkspaceRunView(new WorkspaceRunView.Chrome("Compile", false, false), Path.of("/ws"), null, false);
        Path dir = Path.of("/ws/a");
        String out = Capture.stdout(() -> {
            var mod = run.headless().onModuleStart(module("g:a", dir));
            mod.output("compile-java", "chatter");
        });
        // headless() always buffers (it owns the print); the non-buffered flag is what the live
        // listener reads. What must hold either way is that module chatter is never echoed live.
        assertThat(out).doesNotContain("chatter");
        assertThat(run.deferredOutput()).isEmpty();
    }

    @Test
    void failed_coord_names_the_first_failing_module_and_falls_back_when_none_is_named() {
        var failed = new WorkspaceResult(
                false,
                1,
                List.of(
                        new ModuleOutcome("g:a", Path.of("/ws/a"), true, 0, 1, true),
                        new ModuleOutcome("g:b", Path.of("/ws/b"), false, 1, 1, true)),
                List.of());
        assertThat(WorkspaceRunView.failedCoord(failed, "build")).isEqualTo("g:b");
        var clean = new WorkspaceResult(true, 0, List.of(), List.of());
        assertThat(WorkspaceRunView.failedCoord(clean, "build")).isEqualTo("build");
    }

    @Test
    void a_keep_going_run_reports_every_failure_not_only_the_first() {
        // --continue finishes the graph, so "which module failed" has more than one answer and a
        // report that names the first hides exactly what the flag was typed to reveal.
        var twoFailed = new WorkspaceResult(
                false,
                1,
                List.of(
                        new ModuleOutcome("g:a", Path.of("/ws/a"), false, 1, 1, true),
                        new ModuleOutcome("g:ok", Path.of("/ws/ok"), true, 0, 1, true),
                        new ModuleOutcome("g:c", Path.of("/ws/c"), false, 4, 1, true)),
                List.of());

        assertThat(WorkspaceRunView.failedCoords(twoFailed)).containsExactly("g:a", "g:c");
        assertThat(WorkspaceRunView.failedSubject(twoFailed, "build"))
                .as("the wedge counts them; the roll-call under it names them")
                .isEqualTo("2 modules");
    }

    @Test
    void a_fail_fast_run_still_reads_as_one_named_module() {
        var one = new WorkspaceResult(
                false, 1, List.of(new ModuleOutcome("g:b", Path.of("/ws/b"), false, 1, 1, true)), List.of());
        assertThat(WorkspaceRunView.failedSubject(one, "build")).isEqualTo("g:b");
        var clean = new WorkspaceResult(true, 0, List.of(), List.of());
        assertThat(WorkspaceRunView.failedSubject(clean, "build")).isEqualTo("build");
    }
}
