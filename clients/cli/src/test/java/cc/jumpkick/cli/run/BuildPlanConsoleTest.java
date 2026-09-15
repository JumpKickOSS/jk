// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.Task;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildPlanConsoleTest {

    private static BuildPlan oneStepInteractivePlan() {
        Task step = Task.builder("ensure-jdk")
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("downloading Temurin 21 50%");
                    ctx.progress(1);
                })
                .build();
        return BuildPlan.builder("toolchain").interactive(true).addTask(step).build();
    }

    @Test
    void interactive_plan_emits_jsonl_under_output_json(@TempDir Path cache) {
        BuildPlanResult[] result = new BuildPlanResult[1];
        String out = Capture.stdout(
                () -> result[0] = BuildPlanConsole.run(oneStepInteractivePlan(), BuildPlanConsole.Mode.JSON, cache));
        assertThat(result[0].success()).isTrue();
        assertThat(out.lines())
                .allSatisfy(line -> assertThat(line).startsWith("{").endsWith("}"));
        assertThat(out)
                .contains("\"type\":\"task-start\"")
                .contains("\"ensure-jdk\"")
                .contains("downloading Temurin 21 50%")
                .contains("\"type\":\"task-finish\"");
    }

    @Test
    void interactive_plan_stays_silent_on_a_terminal(@TempDir Path cache) {
        String out =
                Capture.stdout(() -> BuildPlanConsole.run(oneStepInteractivePlan(), BuildPlanConsole.Mode.AUTO, cache));
        assertThat(out).doesNotContain("ensure-jdk").doesNotContain("Temurin");
    }
}
