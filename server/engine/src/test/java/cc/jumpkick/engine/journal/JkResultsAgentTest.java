// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.test.MarkdownTestReport;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The agent report: one line when the run is OK, one problem per line when it is not. */
class JkResultsAgentTest {

    @Test
    void an_ok_test_run_is_one_line() {
        BuildRecord r = record("test", true, false, 500, new BuildRecord.Tests(2, 2, 0, 0), List.of(), List.of());
        assertThat(JkResultsAgent.render(r)).isEqualTo("OK test rest-service · 2 tests · 500ms\n");
    }

    @Test
    void a_compile_error_names_the_line_and_quotes_it() {
        BuildRecord.Diag err = diag(
                "compile-java",
                "javac",
                "';' expected",
                "src/main/java/com/example/restservice/RestServiceApplication.java",
                3,
                50,
                3,
                List.of("public class RestServiceApplication {"),
                "");
        BuildRecord r = record("build", false, false, 700, null, List.of(err), List.of());
        assertThat(JkResultsAgent.render(r)).isEqualTo("""
                        FAIL build rest-service · 1 error · 700ms
                        E src/main/java/com/example/restservice/RestServiceApplication.java:3:50 ';' expected
                          3| public class RestServiceApplication {
                        """);
    }

    @Test
    void a_test_failure_names_the_assertion_and_the_project_frame() {
        String stack = """
                java.lang.AssertionError: expected: "Hello, World!" but was: "Hello, Wrld!"
                \tat org.junit.jupiter.api.AssertionUtils.fail(AssertionUtils.java:55)
                \tat com.example.restservice.GreetingControllerTests.noParamGreetingShouldReturnDefaultMessage(GreetingControllerTests.java:44)
                """;
        BuildRecord.Diag err = new BuildRecord.Diag(
                "error",
                "/ws/rest-service",
                "run-tests",
                "test-failure",
                "expected: \"Hello, World!\" but was: \"Hello, Wrld!\"",
                null,
                "java.lang.AssertionError",
                null,
                "junit",
                "com.example.restservice.GreetingControllerTests",
                "noParamGreetingShouldReturnDefaultMessage",
                stack,
                "src/test/java/com/example/restservice/GreetingControllerTests.java",
                44,
                0,
                0,
                List.of(),
                0,
                "");
        BuildRecord r = record("test", false, false, 1_200, new BuildRecord.Tests(2, 1, 1, 0), List.of(err), List.of());
        assertThat(JkResultsAgent.render(r)).isEqualTo("""
                        FAIL test rest-service · 1 of 2 failed · 1.2s
                        T com.example.restservice.GreetingControllerTests#noParamGreetingShouldReturnDefaultMessage
                          expected: "Hello, World!" but was: "Hello, Wrld!"
                          at GreetingControllerTests.java:44
                        """);
    }

    @Test
    void identical_missing_package_errors_collapse_and_name_the_add() {
        String message = """
                package org.springframework.web.bind.annotation does not exist
                provided by: org.springframework.boot:spring-boot-starter-web (lock)
                """;
        String file = "src/main/java/com/example/restservice/GreetingController.java";
        List<BuildRecord.Diag> diags = List.of(
                diag("compile-java", "javac", message, file, 3, 8, 0, List.of(), "compiler.err.doesnt.exist"),
                diag("compile-java", "javac", message, file, 4, 8, 0, List.of(), "compiler.err.doesnt.exist"),
                diag("compile-java", "javac", message, file, 5, 8, 0, List.of(), "compiler.err.doesnt.exist"),
                diag("compile-java", "javac", message, file, 6, 8, 0, List.of(), "compiler.err.doesnt.exist"));
        BuildRecord r = record("build", false, false, 900, null, diags, List.of());
        assertThat(JkResultsAgent.render(r)).isEqualTo("""
                        FAIL build rest-service · 4 errors · 900ms
                        E src/main/java/com/example/restservice/GreetingController.java:3:8 package org.springframework.web.bind.annotation does not exist
                          +3 more in GreetingController.java
                        FIX deps(add, org.springframework.boot:spring-boot-starter-web)
                        """);
    }

    @Test
    void a_lock_failure_is_the_step_and_its_cause() {
        BuildRecord.Diag err = new BuildRecord.Diag("error", "/ws/rest-service", "lock", "verbatim", """
                Cannot resolve dependencies:
                com.example.absent:nowhere:1.0.0 was not found
                  searched the repositories declared in jk.toml
                """, null, null);
        BuildRecord.Task step = new BuildRecord.Task("lock", "resolve", "FAIL", 400, 0);
        BuildRecord r = record("lock", false, false, 400, null, List.of(err), List.of(step));
        assertThat(JkResultsAgent.render(r)).isEqualTo("""
                        FAIL lock rest-service · 1 error · 400ms
                        E lock: com.example.absent:nowhere:1.0.0 was not found
                          searched the repositories declared in jk.toml
                        """);
    }

    @Test
    void a_cancelled_run_names_the_cancel() {
        BuildRecord.Diag err =
                new BuildRecord.Diag("error", "/ws/rest-service", "compile-java", "cancelled", "cancelled", null, null);
        BuildRecord r = record("build", false, true, 1_200, null, List.of(err), List.of());
        assertThat(JkResultsAgent.render(r)).isEqualTo("""
                        CANCELLED build rest-service · 1 error · 1.2s
                        E compile-java: cancelled
                        """);
    }

    @Test
    void a_delta_that_changes_the_next_step_is_one_line() {
        BuildRecord.Diag err = diag("compile-java", "javac", "';' expected", "src/A.java", 1, 1, 0, List.of(), "");
        JobDelta delta = new JobDelta(
                4,
                false,
                800,
                null,
                new JobDelta.Rows(1, List.of("e")),
                new JobDelta.Rows(2, List.of("a", "b")),
                null,
                null,
                null,
                null);
        String text = JkResultsAgent.render(record("build", false, false, 700, null, List.of(err), List.of())
                .withDelta(delta));
        assertThat(text).endsWith("new 1 · fixed 2\n");
    }

    @Test
    void the_sixth_problem_points_at_diagnostics() {
        List<BuildRecord.Diag> diags = List.of(
                diag("compile-java", "javac", "a", "src/A.java", 1, 1, 0, List.of(), ""),
                diag("compile-java", "javac", "b", "src/B.java", 2, 1, 0, List.of(), ""),
                diag("compile-java", "javac", "c", "src/C.java", 3, 1, 0, List.of(), ""),
                diag("compile-java", "javac", "d", "src/D.java", 4, 1, 0, List.of(), ""),
                diag("compile-java", "javac", "e", "src/E.java", 5, 1, 0, List.of(), ""),
                diag("compile-java", "javac", "f", "src/F.java", 6, 1, 0, List.of(), ""),
                diag("compile-java", "javac", "g", "src/G.java", 7, 1, 0, List.of(), ""));
        String text = JkResultsAgent.render(record("build", false, false, 100, null, diags, List.of()));
        assertThat(text).contains("+2 more: diagnostics(file=src/F.java)\n");
        assertThat(text).doesNotContain("src/G.java:");
    }

    @Test
    void details_for_one_file_include_the_snippet_window() {
        BuildRecord.Diag err = diag(
                "compile-java", "javac", "';' expected", "src/A.java", 2, 1, 1, List.of("class A {", "int x", "}"), "");
        BuildRecord r = record("build", false, false, 100, null, List.of(err), List.of());
        String text = JkResultsAgent.renderDetails(r, "src/A.java", 20, true);
        assertThat(text).contains("  1| class A {").contains("  2| int x").doesNotContain("FAIL build");
    }

    @Test
    void module_run_failures_are_not_repeated_when_the_diagnostic_already_names_them() {
        MarkdownTestReport.Entry fail = new MarkdownTestReport.Entry(
                "com.example.restservice.GreetingControllerTests",
                "noParamGreetingShouldReturnDefaultMessage()",
                10,
                "expected: x but was: y",
                "\tat com.example.restservice.GreetingControllerTests.noParam(GreetingControllerTests.java:9)\n",
                null);
        var run = new MarkdownTestReport.ModuleRun("/ws", "rest-service", List.of(fail));
        BuildRecord.Diag err = new BuildRecord.Diag(
                "error",
                "/ws/rest-service",
                "run-tests",
                "test-failure",
                "expected: x but was: y",
                null,
                null,
                null,
                "junit",
                "com.example.restservice.GreetingControllerTests",
                "noParamGreetingShouldReturnDefaultMessage",
                "\tat com.example.restservice.GreetingControllerTests.noParam(GreetingControllerTests.java:9)\n",
                "",
                0,
                0,
                0,
                List.of(),
                0);
        String text = JkResultsAgent.render(
                record("test", false, false, 100, new BuildRecord.Tests(1, 0, 1, 0), List.of(err), List.of()),
                List.of(run));
        assertThat(text.lines().filter(l -> l.startsWith("T ")).count()).isEqualTo(1);
    }

    private static BuildRecord.Diag diag(
            String step,
            String code,
            String message,
            String file,
            int line,
            int col,
            int snippetStart,
            List<String> snippet,
            String key) {
        return new BuildRecord.Diag(
                "error",
                "/ws/rest-service",
                step,
                code,
                message,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                file,
                line,
                col,
                snippetStart,
                snippet,
                0,
                key);
    }

    private static BuildRecord record(
            String kind,
            boolean success,
            boolean cancelled,
            long millis,
            BuildRecord.@Nullable Tests tests,
            List<BuildRecord.Diag> diags,
            List<BuildRecord.Task> steps) {
        return new BuildRecord(
                "id",
                1,
                BuildRecord.SCHEMA,
                kind,
                "/ws/rest-service",
                "com.example:rest-service",
                "pid",
                1_000,
                1_000 + millis,
                millis,
                success,
                cancelled,
                success ? 0 : 1,
                "0.14.0",
                tests,
                List.of(),
                steps,
                diags,
                "cli",
                null,
                null,
                null,
                false,
                null,
                7,
                null,
                List.of());
    }
}
