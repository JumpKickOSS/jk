// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.Task;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
    void an_interactive_plan_leaves_no_progress_rider_for_the_build_that_follows(@TempDir Path cache) {
        LiveProgress.get().clear();
        String plan =
                Capture.stdout(() -> BuildPlanConsole.run(oneStepInteractivePlan(), BuildPlanConsole.Mode.JSON, cache));
        // The build's first line, emitted by its own listener right after the plan.
        String first = Capture.stdout(() -> new JsonlListener(System.out).stepStart("parse-build", null, 1));

        assertThat(plan.lines()).allSatisfy(line -> assertThat(line).contains("\"progress\":null"));
        assertThat(LiveProgress.get().percent()).isNull();
        assertThat(first).contains("\"progress\":null").doesNotContain("\"progress\":100");
    }

    @Test
    void a_hosted_runs_console_mirrors_its_step_events_into_the_open_transcript(@TempDir Path project)
            throws Exception {
        CliSessionTranscript session = requireNonNull(CliSessionTranscript.open(project, "test", List.of("test")));
        try {
            Path details = project.resolve("runs").resolve("7").resolve(CliSessionTranscript.FILE_NAME);
            session.bindJob(42, 7, details.toString(), 0);
            BuildPlanListener console =
                    BuildPlanConsole.chooseConsoleListener("test", "app", List.of(), BuildPlanConsole.Mode.QUIET);
            console.stepStart("run-tests", "test", 1);
            assertThat(Files.readAllLines(details))
                    .anyMatch(l -> l.contains("\"type\":\"task-start\"") && l.contains("\"run-tests\""));
        } finally {
            session.finish(0);
        }
    }

    @Test
    void interactive_plan_stays_silent_on_a_terminal(@TempDir Path cache) {
        String out =
                Capture.stdout(() -> BuildPlanConsole.run(oneStepInteractivePlan(), BuildPlanConsole.Mode.AUTO, cache));
        assertThat(out).doesNotContain("ensure-jdk").doesNotContain("Temurin");
    }
}
