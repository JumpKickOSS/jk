// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.test.MarkdownTestReport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
        assertThat(md).contains("details.jsonl");
        assertThat(md).contains("target/jk-results.md");
        assertThat(md).doesNotContain("## Failures");
        assertThat(md).doesNotContain("## Warnings");
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
        assertThat(md).contains("## Failures");
        assertThat(md).contains("compile-java — g:core");
        assertThat(md).contains("`Foo.java:12:5`");
        assertThat(md).contains("cannot find symbol");
        assertThat(md).contains("missing();");
        assertThat(md).contains("## Failed / skipped tasks");
        assertThat(md).contains("compile-java");
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
        assertThat(md).doesNotContain("## Failed / skipped tasks");
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
                "abc123",
                null,
                false,
                null,
                7L);
        String md = JkResultsMarkdown.render(r);
        assertThat(md).startsWith("# jk results — CANCELLED");
        assertThat(md).contains("## Warnings");
        assertThat(md).contains("deprecated API");
        assertThat(md).contains("jid 7");
        assertThat(md).contains("commit: abc123");
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
    void workspace_failed_module_is_listed() {
        BuildRecord.Module bad = new BuildRecord.Module(
                "g:core", "/ws/core", false, 1, 80, List.of(task("compile-java", "compile", "FAIL", 80)));
        BuildRecord.Module ok = new BuildRecord.Module(
                "g:app", "/ws/app", true, 0, 20, List.of(task("compile-java", "compile", "SUCCESS", 20)));
        BuildRecord r = record(false, List.of(bad, ok), List.of(), List.of());
        String md = JkResultsMarkdown.render(r);
        assertThat(md).contains("Modules: 2 (**1 failed**)");
        assertThat(md).contains("## Modules");
        assertThat(md).contains("g:core");
        assertThat(md).contains("| FAIL |");
    }

    private static BuildRecord.Task task(String name, String stage, String status, long ms) {
        return new BuildRecord.Task(name, stage, status, ms, 0L);
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
            BuildRecord.Tests tests) {
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
                false,
                null,
                0L);
    }
}
