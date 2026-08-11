// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.tui.CommandManager;
import cc.jumpkick.run.BuildPlanView;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.runtime.WorkspaceProgressTracker;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Module listeners drive the tree; aggregate % comes only from engine snapshots. */
class AggregateModuleListenerTest {

    @Test
    void modules_drive_phase_tree_not_the_bar() {
        var buf = new ByteArrayOutputStream();
        CommandManager view =
                CommandManager.plan(new PrintStream(buf, true, StandardCharsets.UTF_8), "Building", false);
        var agg = new AggregateContext(view);

        var a = new AggregateModuleListener(agg, "g:api", List.of(step("compile", "Compile")));
        a.planStart(new BuildPlanView("build", 0, 10, 1, 0, false));
        a.stepStart("compile", "compile", 10);
        a.progress("compile", 10, new BuildPlanView("build", 10, 10, 1, 1, false));
        a.stepFinish("compile", "compile", TaskStatus.SUCCESS, Duration.ZERO);
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
    void concurrent_modules_show_in_phase_tree() {
        var buf = new ByteArrayOutputStream();
        CommandManager view =
                CommandManager.plan(new PrintStream(buf, true, StandardCharsets.UTF_8), "Building", false);
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
        CommandManager view = CommandManager.plan(new PrintStream(buf, true, StandardCharsets.UTF_8), "Build", false);
        var agg = new AggregateContext(view);

        var a = new AggregateModuleListener(agg, "cc.jumpkick:jk-engine", List.of(step("run-tests", "Testing")));
        a.planStart(new BuildPlanView("build", 0, 10, 1, 0, false));
        a.stepStart("run-tests", "test", 10);
        a.stepFinish("run-tests", "test", TaskStatus.SKIPPED, Duration.ZERO);

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
        CommandManager view = CommandManager.plan(new PrintStream(buf, true, StandardCharsets.UTF_8), "Build", false);
        var agg = new AggregateContext(view);

        var a = new AggregateModuleListener(agg, "g:api", List.of(step("compile-java", "Compile")));
        a.planStart(new BuildPlanView("build", 0, 10, 1, 0, false));
        a.stepStart("compile-java", "compile", 10);
        a.error("compile-java", "javac", "cannot find symbol");
        a.stepFinish("compile-java", "compile", TaskStatus.FAIL, Duration.ZERO);

        String all = String.join(
                "\n",
                view.renderBuildPlanLines(120, 0).stream()
                        .map(AggregateModuleListenerTest::strip)
                        .toList());
        assertThat(all).contains("Compile");
        assertThat(all).containsAnyOf("Failed", "cannot find symbol");
    }

    private static Task step(String name, String label) {
        return Task.builder(name).label(label).ticks(1).execute(ctx -> {}).build();
    }

    private static cc.jumpkick.run.BuildPlanResult result(boolean ok) {
        return new cc.jumpkick.run.BuildPlanResult(
                "build", ok, Duration.ZERO, List.of(), List.of(), List.of(), false, false);
    }

    private static String strip(String s) {
        return s.replaceAll("\033\\[[0-9;?]*[a-zA-Z]", "");
    }
}
