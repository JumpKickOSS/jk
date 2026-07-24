// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.tui.CommandManager;
import cc.jumpkick.plugin.build.Phase;
import cc.jumpkick.run.PipelineView;
import cc.jumpkick.run.Step;
import cc.jumpkick.run.StepStatus;
import cc.jumpkick.runtime.WorkspaceProgressTracker;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Module listeners drive the tree; aggregate % comes only from engine snapshots (JK-1121). */
class AggregateModuleListenerTest {

    @Test
    void modules_drive_phase_tree_not_the_bar() {
        var buf = new ByteArrayOutputStream();
        CommandManager view =
                CommandManager.pipeline(new PrintStream(buf, true, StandardCharsets.UTF_8), "Building", false);
        var agg = new AggregateContext(view);

        var a = new AggregateModuleListener(agg, "g:api", List.of(step("compile", "Compile")));
        a.pipelineStart(new PipelineView("build", 0, 10, 1, 0, false));
        a.stepStart("compile", Phase.COMPILE, 10);
        a.progress("compile", 10, new PipelineView("build", 10, 10, 1, 1, false));
        a.stepFinish("compile", Phase.COMPILE, StepStatus.SUCCESS, Duration.ZERO);
        a.pipelineFinish(result(true));

        var b = new AggregateModuleListener(agg, "g:web", List.of(step("test", "Test")));
        b.pipelineStart(new PipelineView("build", 0, 10, 1, 0, false));
        b.stepStart("test", Phase.TEST, 10);

        // Engine snapshot paints the bar (not module progress callbacks).
        agg.applySnapshot(new WorkspaceProgressTracker.Snapshot(75, 100, 75.0, "execute", 1, 2));

        String all = String.join(
                "\n",
                view.renderPipelineLines(120, 0).stream()
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
                CommandManager.pipeline(new PrintStream(buf, true, StandardCharsets.UTF_8), "Building", false);
        var agg = new AggregateContext(view);

        var a = new AggregateModuleListener(agg, "g:api", List.of(step("compile", "Compile")), 10);
        a.pipelineStart(new PipelineView("build", 0, 10, 1, 0, false));
        a.stepStart("compile", Phase.COMPILE, 10);

        var b = new AggregateModuleListener(agg, "g:web", List.of(step("test", "Test")), 10);
        b.pipelineStart(new PipelineView("build", 0, 10, 1, 0, false));
        b.stepStart("test", Phase.TEST, 10);

        // 110/120 → ProgressBar.percent rounds to 92
        agg.applySnapshot(new WorkspaceProgressTracker.Snapshot(110, 120, 91.7, "execute", 0, 2));

        String all = String.join(
                "\n",
                view.renderPipelineLines(120, 0).stream()
                        .map(AggregateModuleListenerTest::strip)
                        .toList());
        assertThat(all).contains("92%");
        assertThat(all).contains("Compile").contains("Test");
        assertThat(all).contains("├─").contains("╰─");
    }

    private static Step step(String name, String label) {
        return Step.builder(name).label(label).ticks(1).execute(ctx -> {}).build();
    }

    private static cc.jumpkick.run.PipelineResult result(boolean ok) {
        return new cc.jumpkick.run.PipelineResult(
                "build", ok, Duration.ZERO, List.of(), List.of(), List.of(), false, false);
    }

    private static String strip(String s) {
        return s.replaceAll("\033\\[[0-9;?]*[a-zA-Z]", "");
    }
}
