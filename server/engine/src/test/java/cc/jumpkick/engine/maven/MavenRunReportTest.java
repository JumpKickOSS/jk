// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.maven;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.journal.BuildAccumulator;
import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.engine.journal.JkResultsMarkdown;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.test.MarkdownTestReport;
import cc.jumpkick.test.RunResults;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenRunReportTest {

    @TempDir
    Path tmp;

    @Test
    void events_and_surefire_xml_fold_into_modules_steps_and_tests() throws Exception {
        Path events = MavenRunFixture.write(tmp);
        MavenRunReport report = MavenRunReport.read(tmp, events);

        assertThat(report.modules())
                .extracting(MavenRunReport.Module::coord)
                .containsExactly("com.example:lib", "com.example:app");
        MavenRunReport.Module lib = report.modules().get(0);
        assertThat(lib.success()).isTrue();
        assertThat(lib.millis()).isEqualTo(400);
        assertThat(lib.steps()).containsExactly(new MavenEvents.Step("compiler:compile", "SUCCESS", 120));
        assertThat(lib.errors()).isEmpty();

        MavenRunReport.Module app = report.modules().get(1);
        assertThat(app.success()).isFalse();
        assertThat(app.millis()).isEqualTo(1200);
        assertThat(app.steps()).containsExactly(new MavenEvents.Step("surefire:test", "FAIL", 340));
        // The failing test explains the failed mojo, so no second diagnostic repeats it.
        assertThat(app.errors()).isEmpty();
        assertThat(app.tests()).hasSize(2);
        MarkdownTestReport.Entry failed = app.tests().get(1);
        assertThat(failed.className()).isEqualTo(MavenRunFixture.FAILING_TEST);
        assertThat(failed.displayName()).isEqualTo(MavenRunFixture.FAILING_METHOD);
        assertThat(failed.failureMessage()).isEqualTo(MavenRunFixture.FAILURE_MESSAGE);
        assertThat(failed.failureStack()).contains(MavenRunFixture.STACK_FRAME);
        assertThat(report.tests().failed()).isEqualTo(1);
        assertThat(report.tests().total()).isEqualTo(2);
    }

    @Test
    void a_compiler_failure_becomes_one_diagnostic_per_site_with_a_locus_header() throws Exception {
        String message = "Compilation failure\n/ws/lib/src/A.java:[5,12] cannot find symbol\n  symbol: class Missing";
        MavenEvents.Module lib = new MavenEvents.Module(
                "g:lib",
                "/ws/lib",
                "FAIL",
                10,
                List.of(),
                new MavenEvents.Failure("compiler:compile", "x.Ex", message));
        List<BuildPlanResult.Diagnostic> errors = MavenRunReport.errorsOf(lib, List.of());
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).step()).isEqualTo("compiler:compile");
        assertThat(errors.get(0).message())
                .isEqualTo("/ws/lib/src/A.java:5:12: cannot find symbol\n  symbol: class Missing");
    }

    @Test
    void a_failed_mojo_without_sites_or_tests_keeps_its_own_message() {
        MavenEvents.Module lib = new MavenEvents.Module(
                "g:lib",
                "/ws/lib",
                "FAIL",
                10,
                List.of(),
                new MavenEvents.Failure("enforcer:enforce", "x.Ex", "Rule 0 failed"));
        List<BuildPlanResult.Diagnostic> errors = MavenRunReport.errorsOf(lib, List.of());
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).code()).isEqualTo("mojo");
        assertThat(errors.get(0).message()).isEqualTo("Rule 0 failed");
        assertThat(errors.get(0).exceptionClass()).isEqualTo("x.Ex");
    }

    @Test
    void an_empty_or_missing_event_file_is_an_empty_run() throws Exception {
        assertThat(MavenRunReport.read(tmp, tmp.resolve("none.jsonl")).isEmpty())
                .isTrue();
    }

    /** The report fed through the accumulator the way the verb feeds it, rendered as jk-results.md. */
    @Test
    void renders_the_same_results_shape_as_a_jk_build() throws Exception {
        Path events = MavenRunFixture.write(tmp);
        MavenRunReport report = MavenRunReport.read(tmp, events);
        BuildAccumulator acc = new BuildAccumulator("mvn", tmp.toString(), "com.example:reactor", "cli");
        RunResults.open(acc.results());
        for (MavenRunReport.Module m : report.modules()) {
            String dir = m.dir().toString();
            for (MavenEvents.Step s : m.steps()) acc.addTask(dir, s.goal(), "", s.status(), s.millis(), 0);
            acc.addBuildPlan(
                    dir,
                    new BuildPlanResult(
                            "mvn",
                            m.success(),
                            Duration.ofMillis(m.millis()),
                            List.of(),
                            List.of(),
                            m.errors(),
                            false,
                            false));
            acc.addModule(new ModuleOutcome(
                    m.coord(), m.dir(), m.success(), m.success() ? 0 : 1, m.millis(), true, false, null));
            if (!m.tests().isEmpty()) MarkdownTestReport.publish(dir, m.coord(), m.tests());
        }
        acc.addTests(report.tests());
        acc.stamp(JobOutcome.failed(1));
        BuildRecord record = acc.toRecord(5_000, false, 1_600, "0.13.7", null);
        RunResults.close();
        String md = JkResultsMarkdown.render(record, null, null, acc.results().takeTests());

        assertThat(md).startsWith("# jk results — FAIL\n\n**FAIL** · `com.example:reactor`");
        assertThat(md).contains("trigger: cli · tool: mvn");
        assertThat(md).contains("Modules: 2 (**1 failed**)");
        assertThat(md).contains("Tests: **1 failed** · 1 passed (2 total)");
        assertThat(md).contains("## Tests");
        assertThat(md).contains("#### " + MavenRunFixture.FAILING_TEST + "\n");
        assertThat(md).contains("##### `" + MavenRunFixture.FAILING_METHOD + "`");
        assertThat(md).contains(MavenRunFixture.FAILURE_MESSAGE);
        assertThat(md).contains(MavenRunFixture.STACK_FRAME);
        assertThat(md).contains("## Failed steps");
        assertThat(md).as("mojo steps carry the time Maven spent in them").contains("`surefire:test` | FAIL | 340ms |");
        assertThat(md).contains("## Modules");
        assertThat(md).contains("| com.example:app | FAIL |");
        assertThat(md).contains("| com.example:lib | OK |");
        assertThat(md).contains("| com.example:app | `surefire:test` | FAIL |");
    }
}
