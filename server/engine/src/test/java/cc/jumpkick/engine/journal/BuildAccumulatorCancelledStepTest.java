// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.wire.runtime.ModuleOutcome;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A cancel that lands with a step in flight leaves that step in the record: a {@code CANCELLED}
 * row carrying the time it had run, and one error naming it with the last lines its fork printed.
 */
class BuildAccumulatorCancelledStepTest {

    private static final long STARTED = 1_000L;
    private static final long RAN = 1_243_404L;

    @Test
    void a_step_in_flight_at_the_cancel_is_a_cancelled_row_with_its_time_and_the_forks_last_lines() {
        BuildAccumulator a = new BuildAccumulator("test", "/proj", "g:a", "cli");
        a.addTask("", "compile-test", "test", "SUCCESS", 22_014, 68);
        a.noteTaskStart("", "run-tests", "test", STARTED);
        for (int i = 1; i <= 70; i++) a.noteForkOutput("", "run-tests", "line " + i);
        a.markUserCancelled(false, "the client disconnected before the job finished");

        BuildRecord r = a.toRecord(STARTED + RAN, true, RAN, "9.9", null);

        assertThat(r.cancelled()).isTrue();
        BuildRecord.Task run = r.steps().stream()
                .filter(t -> t.name().equals("run-tests"))
                .findFirst()
                .orElseThrow();
        assertThat(run.status()).isEqualTo("CANCELLED");
        assertThat(run.millis()).isEqualTo(RAN);
        BuildRecord.Diag d = r.diagnostics().stream()
                .filter(x -> "error".equals(x.severity()))
                .findFirst()
                .orElseThrow();
        assertThat(d.step()).isEqualTo("run-tests");
        assertThat(d.code()).isEqualTo("cancelled");
        assertThat(d.message())
                .isEqualTo(
                        "`run-tests` was in flight for 20m 43s when the run was cancelled — the fork's last 60 lines:");
        String tail = requireNonNull(d.stack());
        assertThat(tail).startsWith("line 11\n").endsWith("line 70");
        assertThat(tail.lines().count()).as("the tail is bounded").isEqualTo(BuildAccumulator.FORK_TAIL_LINES);

        String md = JkResultsMarkdown.render(r);
        assertThat(md).startsWith("# jk results — CANCELLED");
        assertThat(md).contains("## Failures").contains("`run-tests` was in flight for 20m 43s");
        assertThat(md).contains("line 11").contains("line 70").doesNotContain("line 10\n");
        assertThat(md).contains("## Failed steps").contains("`run-tests`").contains("20m 43s");
    }

    @Test
    void a_fork_that_printed_nothing_says_so_and_a_finished_step_drops_its_tail() {
        BuildAccumulator a = new BuildAccumulator("build", "/proj", "g:a", "cli");
        a.noteTaskStart("", "compile-java", "compile", STARTED);
        a.noteForkOutput("", "compile-java", "javac chatter");
        a.addTask("", "compile-java", "compile", "SUCCESS", 500, 0);
        a.noteTaskStart("", "package-jar", "package", STARTED + 500);
        a.markUserCancelled(true, "cancelled by the user");

        BuildRecord r = a.toRecord(STARTED + 900, true, 900, "9.9", null);

        assertThat(r.steps()).extracting(BuildRecord.Task::status).containsExactly("SUCCESS", "CANCELLED");
        assertThat(r.steps().get(1).millis()).isEqualTo(400);
        List<BuildRecord.Diag> errors = r.diagnostics().stream()
                .filter(x -> "error".equals(x.severity()))
                .toList();
        assertThat(errors).singleElement().satisfies(d -> {
            assertThat(d.step()).isEqualTo("package-jar");
            assertThat(d.message())
                    .isEqualTo(
                            "`package-jar` was in flight for 400ms when the run was cancelled; its fork printed nothing");
            assertThat(d.stack()).isNull();
        });
        assertThat(JkResultsMarkdown.render(r)).doesNotContain("javac chatter");
    }

    @Test
    void a_run_that_finished_keeps_its_running_rows_as_they_were() {
        BuildAccumulator a = new BuildAccumulator("build", "/proj", "g:a", "cli");
        a.noteTaskStart("", "run-tests", "test", STARTED);
        a.addTask("", "compile-java", "compile", "SUCCESS", 500, 0);

        BuildRecord r = a.toRecord(STARTED + 900, false, 900, "9.9", null);

        assertThat(r.cancelled()).isFalse();
        assertThat(r.steps()).extracting(BuildRecord.Task::status).containsExactly("RUN", "SUCCESS");
        assertThat(r.diagnostics()).noneMatch(d -> "error".equals(d.severity()));
    }

    @Test
    void a_workspace_module_caught_mid_flight_gets_a_module_row_for_its_cancelled_step() {
        BuildAccumulator a = new BuildAccumulator("test", "/ws", "g:ws", "cli");
        a.addModule(new ModuleOutcome("g:lib", Path.of("/ws/lib"), true, 0, 300, true, false, null));
        a.addTask("/ws/lib", "run-tests", "test", "SUCCESS", 300, 0);
        a.noteTaskStart("/ws/app", "run-tests", "test", STARTED);
        a.markUserCancelled(true, "cancelled by the user");

        BuildRecord r = a.toRecord(STARTED + 5_000, true, 5_000, "9.9", null);

        assertThat(r.modules()).extracting(BuildRecord.Module::dir).containsExactly("/ws/lib", "/ws/app");
        BuildRecord.Module app = r.modules().get(1);
        assertThat(app.success()).isFalse();
        assertThat(app.steps()).singleElement().satisfies(t -> {
            assertThat(t.status()).isEqualTo("CANCELLED");
            assertThat(t.millis()).isEqualTo(5_000);
        });
        assertThat(JkResultsMarkdown.render(r))
                .contains("`run-tests` was in flight for 5.0s when the run was cancelled");
    }
}
