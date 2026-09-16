// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.test.MarkdownTestReport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JkResultsMarkdownTest {

    @Test
    void success_is_short_and_points_at_details() {
        BuildRecord r = record(true, List.of(), List.of(), List.of(task("compile-java", "compile", "SUCCESS", 200)));
        String md = JkResultsMarkdown.render(
                r, Path.of("/state/runs/3/details.jsonl"), Path.of("/ws/target/jk-results.md"));
        assertThat(md).startsWith("# jk results — OK");
        assertThat(md).contains("build · `g:a` · #3");
        assertThat(md).contains("exit 0");
        assertThat(md).doesNotContain("**exit");
        assertThat(md).contains("details.jsonl");
        assertThat(md).contains("target/jk-results.md");
        assertThat(md).doesNotContain("## Failures");
        assertThat(md).doesNotContain("## Failed steps");
        assertThat(md).doesNotContain("## Warnings");
        assertThat(md).doesNotContain("- failed");
        assertThat(md).doesNotContain("- cancelled");
    }

    @Test
    void header_names_the_trigger_and_the_session_that_asked() {
        BuildRecord cli = record(true, List.of(), List.of(), List.of(task("compile-java", "compile", "SUCCESS", 200)));
        assertThat(JkResultsMarkdown.render(cli)).contains("\ntrigger: cli · jk 9.9\n");

        BuildRecord mcp = new BuildRecord(
                cli.id(),
                cli.buildNumber(),
                cli.schema(),
                cli.kind(),
                cli.dir(),
                cli.coord(),
                cli.projectId(),
                cli.startedAt(),
                cli.finishedAt(),
                cli.millis(),
                cli.success(),
                cli.cancelled(),
                cli.exitCode(),
                cli.jkVersion(),
                cli.tests(),
                cli.modules(),
                cli.steps(),
                cli.diagnostics(),
                "mcp",
                "claude-code 3f9a",
                cli.commit(),
                cli.benefit(),
                cli.running(),
                cli.io(),
                cli.requestId(),
                null);
        String md = JkResultsMarkdown.render(mcp);
        assertThat(md)
                .startsWith("# jk results — OK\n\n**OK** · build · `g:a` · #3 · 100ms · exit 0\n"
                        + "trigger: mcp · session: claude-code 3f9a · jk 9.9\n\n");
    }

    @Test
    void compile_error_surfaces_file_line_and_snippet() {
        BuildRecord.Diag err = new BuildRecord.Diag(
                "error",
                "/ws/core",
                "compile-java",
                "javac",
                "cannot find symbol",
                "",
                "",
                "g:core",
                "",
                "",
                "",
                "",
                "Foo.java",
                12,
                5,
                10,
                List.of("    missing();", "    ^"),
                0);
        BuildRecord r = record(false, List.of(), List.of(err), List.of(task("compile-java", "compile", "FAIL", 80)));
        String md = JkResultsMarkdown.render(r);
        assertThat(md).startsWith("# jk results — FAIL");
        assertThat(md).contains("**exit 1**");
        assertThat(md).contains("- `g:core` `compile-java`: cannot find symbol");
        assertThat(md).contains("## Failures");
        assertThat(md).contains("compile-java — g:core");
        assertThat(md).contains("`Foo.java:12:5`");
        assertThat(md).contains("cannot find symbol");
        assertThat(md).contains("missing();");
        assertThat(md).contains("## Failed steps");
        assertThat(md).contains("compile-java");
        assertThat(md).doesNotContain("**100%**");
    }

    @Test
    void test_failure_uses_class_method_and_stack() {
        BuildRecord.Diag err = new BuildRecord.Diag(
                "error",
                "/ws/core",
                "run-tests",
                "test-failure",
                "expected: <1> but was: <2>",
                "",
                "org.opentest4j.AssertionFailedError",
                "g:core",
                "junit",
                "com.acme.FooTest",
                "bar",
                "at com.acme.FooTest.bar(FooTest.java:9)\nat java.base/...",
                "FooTest.java",
                9,
                0,
                0,
                List.of(),
                2);
        BuildRecord r = record(
                false,
                List.of(),
                List.of(err),
                List.of(task("run-tests", "test", "FAIL", 400)),
                new BuildRecord.Tests(10, 9, 1, 0));
        String md = JkResultsMarkdown.render(r);
        assertThat(md).contains("**exit 1**");
        assertThat(md).contains("- `g:core` `run-tests`: expected: <1> but was: <2>");
        assertThat(md).contains("Tests: **1 failed**, 9 passed (10 total)");
        assertThat(md).contains("## Failures");
        assertThat(md).contains("### Tests");
        assertThat(md).contains("`g:core :: com.acme.FooTest.bar`");
        assertThat(md).contains("AssertionFailedError");
        assertThat(md).contains("expected: <1> but was: <2>");
        assertThat(md).contains("JUnit XML: `target/reports/test-results/`");
    }

    @Test
    void test_details_fold_package_table_and_skip_diag_duplicate() {
        MarkdownTestReport.Entry fail = new MarkdownTestReport.Entry(
                "com.acme.FooTest",
                "bar()",
                12,
                "expected: <1> but was: <2>",
                "at com.acme.FooTest.bar(FooTest.java:9)",
                null);
        MarkdownTestReport.Entry pass = new MarkdownTestReport.Entry("com.acme.FooTest", "ok()", 4, null, null, null);
        MarkdownTestReport.Entry skip =
                new MarkdownTestReport.Entry("com.acme.other.BarTest", "pending()", 0, null, null, "disabled");
        var run = new MarkdownTestReport.ModuleRun("/ws/core", "g:core", List.of(fail, pass, skip));
        BuildRecord.Diag diag = new BuildRecord.Diag(
                "error",
                "/ws/core",
                "run-tests",
                "test-failure",
                "expected: <1> but was: <2>",
                "",
                "org.opentest4j.AssertionFailedError",
                "g:core",
                "junit",
                "com.acme.FooTest",
                "bar",
                "at com.acme.FooTest.bar(FooTest.java:9)",
                "",
                0,
                0,
                0,
                List.of(),
                0);
        BuildRecord r = record(
                false,
                List.of(),
                List.of(diag),
                List.of(task("run-tests", "test", "FAIL", 400)),
                new BuildRecord.Tests(3, 1, 1, 1));
        String md = JkResultsMarkdown.render(r, null, null, List.of(run));
        assertThat(md).contains("## Tests");
        assertThat(md).contains("| Package | Fail | Skip | Pass | Total |");
        assertThat(md).contains("| com.acme | 1 | 0 | 1 | 2 |");
        assertThat(md).contains("| com.acme.other | 0 | 1 | 0 | 1 |");
        assertThat(md).contains("### Failed tests");
        assertThat(md).contains("`bar()`");
        assertThat(md).contains("_took 12ms_");
        assertThat(md).contains("**67%** pass");
        assertThat(md).doesNotContain("**100%** pass");
        assertThat(md).doesNotContain("### Tests");
        assertThat(md).doesNotContain("`g:core :: com.acme.FooTest.bar`");
    }

    @Test
    void deliverables_table_covers_native_image_install_publish() {
        var tasks = List.of(
                task("package-jar", "package", "SUCCESS", 50),
                task("native-image", "native", "SUCCESS", 8_000),
                task("write-image", "image", "FAIL", 120),
                task("install", "other", "SUCCESS", 30),
                task("publish", "other", "SKIPPED", 0));
        BuildRecord r = record(false, List.of(), List.of(), tasks);
        String md = JkResultsMarkdown.render(r);
        assertThat(md).contains("## Deliverables");
        assertThat(md).contains("`native-image`");
        assertThat(md).contains("`write-image`");
        assertThat(md).contains("`install`");
        assertThat(md).contains("`publish`");
        assertThat(md).contains("| FAIL |");
        assertThat(md).contains("| SKIPPED |");
        assertThat(md).doesNotContain("## Failed steps");
    }

    @Test
    void warnings_and_cancelled_show_up() {
        BuildRecord.Diag warn = new BuildRecord.Diag("warning", "", "compile-java", "javac", "deprecated API", "", "");
        BuildRecord r = new BuildRecord(
                "id",
                1,
                BuildRecord.SCHEMA,
                "build",
                "/proj",
                "g:a",
                "pid",
                1,
                2,
                100,
                false,
                true,
                130,
                "9.9",
                null,
                List.of(),
                List.of(),
                List.of(warn),
                "cli",
                null,
                "abc123",
                null,
                false,
                null,
                7L,
                null);
        String md = JkResultsMarkdown.render(r);
        assertThat(md).startsWith("# jk results — CANCELLED");
        assertThat(md).contains("**exit 130**");
        assertThat(md).contains("- cancelled");
        assertThat(md).contains("## Warnings");
        assertThat(md).contains("deprecated API");
        assertThat(md).contains("jid 7");
        assertThat(md).contains("commit: abc123");
        assertThat(md).doesNotContain("## Failed steps");
    }

    /** A javadoc warning carries its locus in the message; the Warnings section renders it as file:line. */
    @Test
    void a_javadoc_warning_renders_with_its_file_and_line() {
        BuildRecord.Diag warn = BuildAccumulator.diagFromPlan(
                "warning",
                "/proj",
                "/proj",
                new BuildPlanResult.Diagnostic(
                        "package-javadoc",
                        "javadoc",
                        "/proj/src/com/example/One.java:6: warning: unknown tag. Unregistered custom tag?"));
        String md = JkResultsMarkdown.render(record(true, List.of(), List.of(warn), List.of()));
        assertThat(md).contains("## Warnings");
        assertThat(md).contains("`package-javadoc` `").contains("One.java:6`");
        assertThat(md).contains("unknown tag");
        assertThat(md).contains("Diagnostics: 1 warning");
    }

    @Test
    void extra_errors_are_capped() {
        List<BuildRecord.Diag> diags = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            diags.add(new BuildRecord.Diag("error", "", "compile-java", "javac", "e" + i, "", ""));
        }
        String md = JkResultsMarkdown.render(record(false, List.of(), diags, List.of()));
        assertThat(md).contains("more errors");
        assertThat(md.split("### compile-java", -1).length - 1).isEqualTo(1);
    }

    @Test
    void write_puts_report_in_run_dir_and_target(@TempDir Path dir) throws Exception {
        Path run = dir.resolve("runs").resolve("4");
        Path latest = dir.resolve("target").resolve("jk-results.md");
        JkResultsMarkdown.write(record(true, List.of(), List.of(), List.of()), run, latest);
        assertThat(Files.readString(run.resolve("jk-results.md"))).contains("# jk results — OK");
        assertThat(Files.readString(latest)).contains("# jk results — OK");
        assertThat(Files.readString(latest))
                .contains(run.resolve("details.jsonl").toString().replace('\\', '/'));
    }

    @Test
    void a_single_failure_in_a_large_suite_is_not_a_hundred_percent() {
        List<MarkdownTestReport.Entry> entries = new ArrayList<>();
        entries.add(new MarkdownTestReport.Entry("com.acme.FooTest", "boom()", 1, "nope", "stack", null));
        for (int i = 0; i < 200; i++) {
            entries.add(new MarkdownTestReport.Entry("com.acme.FooTest", "ok" + i + "()", 1, null, null, null));
        }
        var run = new MarkdownTestReport.ModuleRun("/ws/core", "g:core", entries);
        BuildRecord r = record(false, List.of(), List.of(), List.of(task("run-tests", "test", "FAIL", 400)));
        String md = JkResultsMarkdown.render(r, null, null, List.of(run));
        assertThat(md).contains("**1 failed**");
        assertThat(md).doesNotContain("**100%** pass");
        assertThat(md).contains("**99%** pass");
    }

    @Test
    void run_tests_crash_is_not_a_clean_hundred_percent() {
        MarkdownTestReport.Entry pass = new MarkdownTestReport.Entry("com.acme.FooTest", "ok()", 4, null, null, null);
        var run = new MarkdownTestReport.ModuleRun("/ws/web", "g:web", List.of(pass));
        BuildRecord.Module web = new BuildRecord.Module(
                "g:web", "/ws/web", false, 1, 80, List.of(task("run-tests", "test", "FAIL", 44)));
        BuildRecord.Diag err = new BuildRecord.Diag(
                "error",
                "/ws/web",
                "run-tests",
                "exception",
                "Illegal char <:> at index 24: C:\\a\\bin C:\\b\\bin",
                "",
                "");
        BuildRecord r = record(false, List.of(web), List.of(err), List.of(), new BuildRecord.Tests(1, 1, 0, 0));
        String md = JkResultsMarkdown.render(r, null, null, List.of(run));
        assertThat(md).startsWith("# jk results — FAIL");
        assertThat(md).contains("**exit 1**");
        assertThat(md).contains("- `web` `run-tests`: Illegal char <:> at index 24: C:\\a\\bin C:\\b\\bin");
        assertThat(md).doesNotContain("**100%** pass");
        assertThat(md).doesNotContain("No failures for");
        assertThat(md).contains("**run-tests failed**");
        assertThat(md).contains("## Failures");
        assertThat(md).contains("Illegal char");
        assertThat(md).contains("## Failed steps");
        assertThat(md).contains("`run-tests`");
    }

    @Test
    void workspace_failed_module_is_listed() {
        BuildRecord.Module bad = new BuildRecord.Module(
                "g:core", "/ws/core", false, 1, 80, List.of(task("compile-java", "compile", "FAIL", 80)));
        BuildRecord.Module ok = new BuildRecord.Module(
                "g:app", "/ws/app", true, 0, 20, List.of(task("compile-java", "compile", "SUCCESS", 20)));
        BuildRecord r = record(false, List.of(bad, ok), List.of(), List.of());
        String md = JkResultsMarkdown.render(r);
        assertThat(md).contains("**exit 1**");
        assertThat(md).contains("- `g:core` `compile-java` failed");
        assertThat(md).contains("Modules: 2 (**1 failed**)");
        assertThat(md).contains("## Modules");
        assertThat(md).contains("g:core");
        assertThat(md).contains("| FAIL |");
        assertThat(md).contains("## Failed steps");
    }

    @Test
    void a_failed_run_with_no_steps_still_names_the_exit() {
        String md = JkResultsMarkdown.render(record(false, List.of(), List.of(), List.of()));
        assertThat(md).startsWith("# jk results — FAIL");
        assertThat(md).contains("**exit 1**");
        assertThat(md).contains("- failed (exit 1)");
        assertThat(md).doesNotContain("**100%**");
        assertThat(md).doesNotContain("## Failed steps");
    }

    @Test
    void a_compile_crash_with_no_junit_is_a_failed_run() {
        BuildRecord.Module auditor = new BuildRecord.Module(
                "g:auditor", "/ws/auditor", false, 1, 80, List.of(task("compile-test", "compile", "FAIL", 80)));
        BuildRecord.Diag err = new BuildRecord.Diag(
                "error",
                "/ws/auditor",
                "compile-test",
                "exception",
                "zinc worker exited with status 1",
                "",
                "",
                "g:auditor",
                "",
                "",
                "",
                "");
        BuildRecord r = record(false, List.of(auditor), List.of(err), List.of());
        String md = JkResultsMarkdown.render(r);
        assertThat(md).startsWith("# jk results — FAIL");
        assertThat(md).contains("**exit 1**");
        assertThat(md).contains("- `g:auditor` `compile-test`: zinc worker exited with status 1");
        assertThat(md).contains("## Failed steps");
        assertThat(md).contains("`compile-test`");
        assertThat(md).doesNotContain("**100%**");
        assertThat(md).doesNotContain("## Tests");
    }

    @Test
    void skipped_cache_hits_do_not_bury_a_failed_step() {
        List<BuildRecord.Task> steps = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            steps.add(task("compile-java", "compile", "SKIPPED", 0));
        }
        steps.add(task("run-tests", "test", "FAIL", 40));
        String md = JkResultsMarkdown.render(record(false, List.of(), List.of(), steps));
        assertThat(md).contains("## Failed steps");
        assertThat(md).contains("`run-tests`");
        assertThat(md).contains("| FAIL |");
        assertThat(md).doesNotContain("| SKIPPED |");
        assertThat(md).doesNotContain("`compile-java`");
        assertThat(md).contains("45 tasks skipped (cache).");
        assertThat(md).contains("- `run-tests` failed");
    }

    @Test
    void passing_junit_does_not_green_a_failed_compile() {
        MarkdownTestReport.Entry pass = new MarkdownTestReport.Entry("com.acme.FooTest", "ok()", 4, null, null, null);
        var run = new MarkdownTestReport.ModuleRun("/ws/core", "g:core", List.of(pass));
        BuildRecord.Module core = new BuildRecord.Module(
                "g:core", "/ws/core", false, 1, 80, List.of(task("compile-java", "compile", "FAIL", 80)));
        BuildRecord r = record(false, List.of(core), List.of(), List.of());
        String md = JkResultsMarkdown.render(r, null, null, List.of(run));
        assertThat(md).contains("**exit 1**");
        assertThat(md).contains("- `g:core` `compile-java` failed");
        assertThat(md).doesNotContain("**100%** pass");
        assertThat(md).doesNotContain("No failures for");
        assertThat(md).contains("Recorded tests passed");
        assertThat(md).contains("Tests: 1 passed (1 total)");
    }

    private static BuildRecord.Task task(String name, String stage, String status, long ms) {
        return new BuildRecord.Task(name, stage, status, ms, 0L);
    }

    @Test
    void a_publish_run_reports_its_target_deployment_and_every_validation_error() {
        BuildRecord base = record(false, List.of(), List.of(), List.of(task("publish", "publish", "FAIL", 900)));
        BuildRecord r = new BuildRecord(
                base.id(),
                base.buildNumber(),
                base.schema(),
                "publish",
                base.dir(),
                base.coord(),
                base.projectId(),
                base.startedAt(),
                base.finishedAt(),
                base.millis(),
                base.success(),
                base.cancelled(),
                base.exitCode(),
                base.jkVersion(),
                base.tests(),
                base.modules(),
                base.steps(),
                base.diagnostics(),
                base.trigger(),
                base.session(),
                base.commit(),
                base.benefit(),
                base.running(),
                base.io(),
                base.requestId(),
                new BuildRecord.Publish(
                        "Central Portal (user-managed)",
                        16,
                        false,
                        "28570f16-da32-4c14-bd2e-c1acc0782365",
                        "FAILED",
                        List.of("Missing signature for file: widget-1.0.0.pom", "Javadocs must be provided"),
                        List.of(
                                "com/example/widget/1.0.0/widget-1.0.0.jar",
                                "com/example/widget/1.0.0/widget-1.0.0.jar.asc")));
        String md = JkResultsMarkdown.render(r);
        assertThat(md)
                .contains("## Publish\n\n- destination: Central Portal (user-managed)\n- files: 16\n"
                        + "- deployment: `28570f16-da32-4c14-bd2e-c1acc0782365` · **FAILED**\n"
                        + "- validation errors:\n  - Missing signature for file: widget-1.0.0.pom\n  - Javadocs must be provided\n"
                        + "- bundle (2 entries):\n  - `com/example/widget/1.0.0/widget-1.0.0.jar`\n");
        assertThat(JkResultsMarkdown.render(base)).doesNotContain("## Publish");
    }

    private static BuildRecord record(
            boolean success,
            List<BuildRecord.Module> modules,
            List<BuildRecord.Diag> diags,
            List<BuildRecord.Task> steps) {
        return record(success, modules, diags, steps, null);
    }

    private static BuildRecord record(
            boolean success,
            List<BuildRecord.Module> modules,
            List<BuildRecord.Diag> diags,
            List<BuildRecord.Task> steps,
            BuildRecord.@Nullable Tests tests) {
        return new BuildRecord(
                "id",
                3,
                BuildRecord.SCHEMA,
                "build",
                "/ws",
                "g:a",
                "pid",
                1_000,
                1_100,
                100,
                success,
                false,
                success ? 0 : 1,
                "9.9",
                tests,
                modules,
                steps,
                diags,
                "cli",
                null,
                null,
                null,
                false,
                null,
                0L,
                null);
    }
}
