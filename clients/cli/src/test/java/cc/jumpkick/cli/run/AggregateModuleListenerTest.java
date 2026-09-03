// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.tui.JkManager;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.BuildPlanView;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.wire.runtime.WorkspaceProgressTracker;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Module listeners drive the tree; aggregate % comes only from engine snapshots. */
class AggregateModuleListenerTest {

    @Test
    void modules_drive_phase_tree_not_the_bar() {
        var buf = new ByteArrayOutputStream();
        JkManager view = JkManager.plan(new PrintStream(buf, true, StandardCharsets.UTF_8), "Building", false);
        var agg = new AggregateContext(view);

        var a = new AggregateModuleListener(agg, "g:api", List.of(step("compile", "Compile")));
        a.planStart(new BuildPlanView("build", 0, 10, 1, 0, false));
        a.stepStart("compile", "compile", 10);
        a.progress("compile", 10, new BuildPlanView("build", 10, 10, 1, 1, false));
        a.stepFinish("compile", "compile", TaskStatus.SUCCESS, Duration.ZERO, Duration.ZERO);
        a.planFinish(result(true));

        var b = new AggregateModuleListener(agg, "g:web", List.of(step("test", "Test")));
        b.planStart(new BuildPlanView("build", 0, 10, 1, 0, false));
        b.stepStart("test", "test", 10);

        // Engine snapshot paints the bar (not module progress callbacks).
        agg.applySnapshot(new WorkspaceProgressTracker.Snapshot(75, 100, 75.0, "execute", 1, 2, 10_000, 40_000));

        String all = String.join(
                "\n",
                view.renderBuildPlanLines(120, 0).stream()
                        .map(AggregateModuleListenerTest::strip)
                        .toList());
        assertThat(all).contains("75%");
        assertThat(all).contains("Test");
        assertThat(all).doesNotContain("Compile");
        assertThat(all).containsAnyOf("├─", "╰─");
    }

    @Test
    void non_animating_buffered_output_prints_once_via_the_settled_block() {
        // : with a buffer and animate=false, output/error must buffer ONLY — writeAbove
        // prints immediately in that mode and the module-finish block prints the buffer again,
        // so doing both showed every tool and diagnostic line twice in piped/CI workspace builds.
        var buf = new ByteArrayOutputStream();
        JkManager view = JkManager.plan(new PrintStream(buf, true, StandardCharsets.UTF_8), "Building", false);
        var agg = new AggregateContext(view);
        var lis = new AggregateModuleListener(agg, "g:api", List.of(step("compile", "Compile")));
        var outBuf = new ArrayList<String>();
        lis.bufferOutputInto(outBuf);

        lis.output("compile", "tool-line-xyz");
        lis.error("compile", "E1", "boom-message");

        assertThat(buf.toString(StandardCharsets.UTF_8))
                .doesNotContain("tool-line-xyz")
                .doesNotContain("boom-message");
        assertThat(String.join("\n", outBuf)).contains("tool-line-xyz").contains("boom-message");
        view.close();
    }

    @Test
    void live_path_repeats_the_compiler_pill_grouped_path_stacks() {
        // : on the animating (live) path parallel modules interleave in the merged
        // stream, so a headerless second report could land under another module's output.
        var buf = new ByteArrayOutputStream();
        JkManager view = JkManager.plan(new PrintStream(buf, true, StandardCharsets.UTF_8), "Building", true);
        var agg = new AggregateContext(view);
        var live = new AggregateModuleListener(agg, "g:api", List.of(step("compile", "Compile")));
        live.error("compile-java", "javac", "A.java:1: error: boom");
        live.error("compile-java", "javac", "A.java:2: error: boom2");
        long pills = view.outputWindow().linesForDisplay(200).stream()
                .map(TestAnsi::strip)
                .filter(l -> l.contains("Failure") && l.contains("g:api"))
                .count();
        assertThat(pills).isEqualTo(2);
        view.close();

        // Grouped (buffered, non-animating): consecutive same-key reports stack under one pill.
        var buf2 = new ByteArrayOutputStream();
        JkManager plain = JkManager.plan(new PrintStream(buf2, true, StandardCharsets.UTF_8), "Building", false);
        var agg2 = new AggregateContext(plain);
        var grouped = new AggregateModuleListener(agg2, "g:api", List.of(step("compile", "Compile")));
        var outBuf = new ArrayList<String>();
        grouped.bufferOutputInto(outBuf);
        grouped.error("compile-java", "javac", "A.java:1: error: boom");
        grouped.error("compile-java", "javac", "A.java:2: error: boom2");
        long groupedPills = outBuf.stream()
                .flatMap(b -> b.lines())
                .map(TestAnsi::strip)
                .filter(l -> l.contains("Failure") && l.contains("g:api"))
                .count();
        assertThat(groupedPills).isEqualTo(1);
        plain.close();
    }

    @Test
    void concurrent_modules_show_in_phase_tree() {
        var buf = new ByteArrayOutputStream();
        JkManager view = JkManager.plan(new PrintStream(buf, true, StandardCharsets.UTF_8), "Building", false);
        var agg = new AggregateContext(view);

        var a = new AggregateModuleListener(agg, "g:api", List.of(step("compile", "Compile")), 10);
        a.planStart(new BuildPlanView("build", 0, 10, 1, 0, false));
        a.stepStart("compile", "compile", 10);

        var b = new AggregateModuleListener(agg, "g:web", List.of(step("test", "Test")), 10);
        b.planStart(new BuildPlanView("build", 0, 10, 1, 0, false));
        b.stepStart("test", "test", 10);

        // 110/120 → ProgressBar.percent rounds to 92
        agg.applySnapshot(new WorkspaceProgressTracker.Snapshot(110, 120, 91.7, "execute", 0, 2, 5_000, 60_000));

        String all = String.join(
                "\n",
                view.renderBuildPlanLines(120, 0).stream()
                        .map(AggregateModuleListenerTest::strip)
                        .toList());
        assertThat(all).contains("92%");
        assertThat(all).contains("Compile").contains("Test");
        assertThat(all).contains("├─").contains("╰─");
    }

    @Test
    void skipped_step_does_not_paint_phase_failed() {
        // Cache-hit steps terminate SKIPPED; the live tree must not show ✘ Failed.
        var buf = new ByteArrayOutputStream();
        JkManager view = JkManager.plan(new PrintStream(buf, true, StandardCharsets.UTF_8), "Build", false);
        var agg = new AggregateContext(view);

        var a = new AggregateModuleListener(agg, "cc.jumpkick:jk-engine", List.of(step("run-tests", "Testing")));
        a.planStart(new BuildPlanView("build", 0, 10, 1, 0, false));
        a.stepStart("run-tests", "test", 10);
        a.stepFinish("run-tests", "test", TaskStatus.SKIPPED, Duration.ZERO, Duration.ZERO);

        // Successful SKIPPED → phase drops from the live chain (same as SUCCESS).
        String all = String.join(
                "\n",
                view.renderBuildPlanLines(120, 0).stream()
                        .map(AggregateModuleListenerTest::strip)
                        .toList());
        assertThat(all).doesNotContain("Failed");
        assertThat(all).doesNotContain("✘");
        assertThat(all).doesNotContain("Test"); // success → removed from chain
    }

    @Test
    void real_fail_still_paints_phase_failed() {
        var buf = new ByteArrayOutputStream();
        JkManager view = JkManager.plan(new PrintStream(buf, true, StandardCharsets.UTF_8), "Build", false);
        var agg = new AggregateContext(view);

        var a = new AggregateModuleListener(agg, "g:api", List.of(step("compile-java", "Compile")));
        a.planStart(new BuildPlanView("build", 0, 10, 1, 0, false));
        a.stepStart("compile-java", "compile", 10);
        a.error("compile-java", "javac", "cannot find symbol");
        a.stepFinish("compile-java", "compile", TaskStatus.FAIL, Duration.ZERO, Duration.ZERO);

        String all = String.join(
                "\n",
                view.renderBuildPlanLines(120, 0).stream()
                        .map(AggregateModuleListenerTest::strip)
                        .toList());
        assertThat(all).contains("Compile");
        assertThat(all).containsAnyOf("Failed", "cannot find symbol");
    }

    @Test
    void buffered_failure_block_survives_a_plan_that_never_reaches_step_finish() {
        // Cancel/disconnect between the block's lines and stepFinish must still print
        // the already-received report; before, planFinish settled without flushing it.
        var buf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);
        JkManager view = JkManager.plan(out, "Building", false);
        var agg = new AggregateContext(view);
        var a = new AggregateModuleListener(agg, "g:api", List.of(step("run-tests", "Test")));
        a.planStart(new BuildPlanView("build", 0, 10, 1, 0, false));
        a.stepStart("run-tests", "test", 10);
        a.output("run-tests", "Test Failure");
        a.output("run-tests", "1 test failed");
        a.output("run-tests", "");
        a.output("run-tests", "FAILED Foo.bar()");
        // No stepFinish — the plan is torn down (cancel).
        a.planFinish(result(false));
        String all = strip(buf.toString(StandardCharsets.UTF_8));
        assertThat(all).contains("FAILED Foo.bar()");
    }

    @Test
    void plan_finish_clears_orphan_active_steps_so_resolve_jdk_cannot_linger() {
        // A lost task-finish (concurrent wire interleave) used to leave ensure-jdk ACTIVE for the
        // rest of the workspace build with detail "resolve JDK".
        var buf = new ByteArrayOutputStream();
        JkManager view = JkManager.plan(new PrintStream(buf, true, StandardCharsets.UTF_8), "Build", false);
        var agg = new AggregateContext(view);

        var a = new AggregateModuleListener(
                agg, "cc.jumpkick:jk-client-io", List.of(step("ensure-jdk", "JDK"), step("compile-java", "Compile")));
        a.planStart(new BuildPlanView("build", 0, 10, 2, 0, false));
        a.stepStart("ensure-jdk", "resolve", 1);
        a.label("ensure-jdk", "resolve JDK");
        // No stepFinish for ensure-jdk — wire drop / cancel mid-step.
        a.stepStart("compile-java", "compile", 1);
        a.stepFinish("compile-java", "compile", TaskStatus.SUCCESS, Duration.ZERO, Duration.ZERO);
        a.planFinish(result(true));

        // Sibling module still running — the orphan row must not stay in the live tree.
        var b = new AggregateModuleListener(agg, "cc.jumpkick:jk-cli", List.of(step("native-image", "Native")));
        b.planStart(new BuildPlanView("build", 0, 10, 1, 0, false));
        b.stepStart("native-image", "native", 1);
        b.label("native-image", "[2/8] Performing analysis...");

        String all = String.join(
                "\n",
                view.renderBuildPlanLines(120, 0).stream()
                        .map(AggregateModuleListenerTest::strip)
                        .toList());
        assertThat(all).contains("jk-cli").contains("Native");
        assertThat(all).doesNotContain("jk-client-io");
        assertThat(all).doesNotContain("resolve JDK");
        assertThat(all).doesNotContain("Resolve");
    }

    private static Task step(String name, String label) {
        return Task.builder(name).label(label).ticks(1).execute(ctx -> {}).build();
    }

    private static BuildPlanResult result(boolean ok) {
        return new BuildPlanResult("build", ok, Duration.ZERO, List.of(), List.of(), List.of(), false, false);
    }

    private static String strip(String s) {
        return s.replaceAll("\033\\[[0-9;?]*[a-zA-Z]", "");
    }
}
