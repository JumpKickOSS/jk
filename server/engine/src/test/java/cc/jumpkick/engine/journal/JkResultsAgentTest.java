// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.test.MarkdownTestReport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The agent report: one line when the run is OK, one problem per line when it is not. */
class JkResultsAgentTest {

    @Test
    void an_ok_run_names_a_memory_wait_once_per_step() {
        BuildRecord.Diag wait = new BuildRecord.Diag(
                "warning", "/ws/rest-service", "run-tests", "memory-wait", "waited 12s for memory", null, null);
        BuildRecord.Diag again = new BuildRecord.Diag(
                "warning", "/ws/rest-service", "run-tests", "memory-wait", "waited 1s for memory", null, null);
        BuildRecord.Diag other = new BuildRecord.Diag(
                "warning",
                "/ws/rest-service",
                "compile-java",
                "memory-wait",
                "waited 2s for memory (JK_WORKER_BUDGET_MB)",
                null,
                null);
        BuildRecord r = record(
                "test", true, false, 500, new BuildRecord.Tests(1, 1, 0, 0), List.of(wait, again, other), List.of());
        assertThat(JkResultsAgent.render(r)).isEqualTo("""
                OK test rest-service · 1 test · 500ms
                waited 12s for memory
                waited 2s for memory (JK_WORKER_BUDGET_MB)
                """);
    }

    @Test
    void an_ok_run_still_names_a_heap_retry() {
        BuildRecord.Diag retry = new BuildRecord.Diag(
                "warning",
                "/ws/rest-service",
                "run-tests",
                "heap-retry",
                "retried with 1.0 GiB heap after running out of 512 MiB",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "",
                0,
                0,
                0,
                List.of(),
                0,
                "");
        BuildRecord r = record("test", true, false, 500, new BuildRecord.Tests(1, 1, 0, 0), List.of(retry), List.of());
        assertThat(JkResultsAgent.render(r)).isEqualTo("""
                OK test rest-service · 1 test · 500ms
                retried with 1.0 GiB heap after running out of 512 MiB
                """);
    }

    @Test
    void an_ok_run_still_names_a_worker_far_over_its_lease() {
        BuildRecord.Diag over = new BuildRecord.Diag(
                "warning",
                "/ws/rest-service",
                "run-tests",
                "memory-over-lease",
                "test JVM using 12.2 GiB, leased 6.7 GiB",
                null,
                null);
        BuildRecord r = record("test", true, false, 500, new BuildRecord.Tests(1, 1, 0, 0), List.of(over), List.of());
        assertThat(JkResultsAgent.render(r)).isEqualTo("""
                OK test rest-service · 1 test · 500ms
                test JVM using 12.2 GiB, leased 6.7 GiB
                """);
    }

    @Test
    void a_workspace_names_the_module_on_a_heap_retry_and_a_memory_wait() {
        List<BuildRecord.Module> modules = List.of(
                new BuildRecord.Module("cc.jumpkick:jk-engine", "/ws/server/engine", true, 0, 10, List.of()),
                new BuildRecord.Module("cc.jumpkick:jk-cli", "/ws/clients/cli", true, 0, 10, List.of()));
        BuildRecord.Diag engineWait = new BuildRecord.Diag(
                "warning", "/ws/server/engine", "run-tests", "memory-wait", "waited 12s for memory", null, null);
        BuildRecord.Diag engineAgain = new BuildRecord.Diag(
                "warning", "/ws/server/engine", "run-tests", "memory-wait", "waited 1s for memory", null, null);
        BuildRecord.Diag cliWait = new BuildRecord.Diag(
                "warning", "/ws/clients/cli", "run-tests", "memory-wait", "waited 3s for memory", null, null);
        BuildRecord.Diag retry = new BuildRecord.Diag(
                "warning",
                "/ws/server/engine",
                "run-tests",
                "heap-retry",
                "retried with 256 MiB heap after running out of 128 MiB",
                null,
                null);
        BuildRecord r = workspace(modules, List.of(engineWait, engineAgain, cliWait, retry));
        assertThat(JkResultsAgent.render(r)).isEqualTo("""
                OK test rest-service · 1 test · 500ms
                W jk-engine run-tests: waited 12s for memory
                W jk-cli run-tests: waited 3s for memory
                W jk-engine run-tests: retried with 256 MiB heap after running out of 128 MiB
                """);
    }

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
                          3|\tpublic class RestServiceApplication {
                        """);
    }

    @Test
    void the_quoted_line_keeps_its_indentation_and_inner_whitespace() {
        BuildRecord.Diag err = diag(
                "compile-java",
                "javac",
                "';' expected",
                "src/main/java/app/Calc.java",
                16,
                21,
                15,
                List.of("    int add(int a, int b) {", "        return a  +\tb", "    }"),
                "");
        BuildRecord r = record("build", false, false, 700, null, List.of(err), List.of());
        assertThat(JkResultsAgent.render(r))
                .contains("E src/main/java/app/Calc.java:16:21 ';' expected\n  16|\t        return a  +\tb\n")
                .doesNotContain("15|");
    }

    @Test
    void a_line_that_is_not_unique_in_its_file_is_quoted_with_its_neighbors(@TempDir Path dir) throws Exception {
        String source = """
                class A {
                    int one() {
                        return 1
                    }

                    int two() {
                        return 1
                    }
                }
                """;
        Path file = dir.resolve("src/main/java/A.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        BuildRecord.Diag err =
                diag("compile-java", "javac", "';' expected", "src/main/java/A.java", 7, 17, 0, List.of(), "");
        BuildRecord r = record(dir.toString(), "build", false, false, 700, null, List.of(err), List.of());
        assertThat(JkResultsAgent.render(r)).contains("""
                E src/main/java/A.java:7:17 ';' expected
                  6|\t    int two() {
                  7|\t        return 1
                  8|\t    }
                """);
    }

    @Test
    void a_unique_line_on_disk_is_quoted_alone(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("src/main/java/A.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class A {\r\n    int x = 1\r\n}\r\n");
        BuildRecord.Diag err =
                diag("compile-java", "javac", "';' expected", "src/main/java/A.java", 2, 14, 0, List.of(), "");
        BuildRecord r = record(dir.toString(), "build", false, false, 700, null, List.of(err), List.of());
        assertThat(JkResultsAgent.render(r)).endsWith("""
                E src/main/java/A.java:2:14 ';' expected
                  2|\t    int x = 1
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
                43,
                List.of(
                        "@@source line=44 start=43 lang=java path=src/test/java/com/example/restservice/GreetingControllerTests.java",
                        "@@src 43|\t\tvar body = get(\"/greeting\");",
                        "@@src 44*|\t\tassertThat(body).isEqualTo(\"Hello, World!\");",
                        "@@src-end"),
                0,
                "");
        BuildRecord r = record("test", false, false, 1_200, new BuildRecord.Tests(2, 1, 1, 0), List.of(err), List.of());
        assertThat(JkResultsAgent.render(r)).isEqualTo("""
                        FAIL test rest-service · 1 of 2 failed · 1.2s
                        T com.example.restservice.GreetingControllerTests#noParamGreetingShouldReturnDefaultMessage
                          expected: "Hello, World!" but was: "Hello, Wrld!"
                          at src/test/java/com/example/restservice/GreetingControllerTests.java:44
                          44|\t\t\tassertThat(body).isEqualTo("Hello, World!");
                        """);
    }

    @Test
    void a_module_test_failure_is_located_from_the_project_root() {
        BuildRecord.Diag err = new BuildRecord.Diag(
                "error",
                "/ws/rest-service/app",
                "run-tests",
                "test-failure",
                "expected: 3 but was: 4",
                null,
                "org.opentest4j.AssertionFailedError",
                null,
                "junit",
                "app.CalcTest",
                "adds",
                "\tat app.CalcTest.adds(CalcTest.java:9)\n",
                "src/test/java/app/CalcTest.java",
                9,
                0,
                9,
                List.of("        assertEquals(3, calc.add(1, 2));"),
                0,
                "");
        BuildRecord r = record("test", false, false, 100, new BuildRecord.Tests(1, 0, 1, 0), List.of(err), List.of());
        assertThat(JkResultsAgent.render(r)).endsWith("""
                          at app/src/test/java/app/CalcTest.java:9
                          9|\t        assertEquals(3, calc.add(1, 2));
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
    void a_kotlin_unresolved_import_with_a_provider_names_the_add() {
        String message = """
                file:///ws/rest-service/src/test/kotlin/HttpControllersTests.kt:3:12 Unresolved reference 'ninjasquad'.
                  provided by: com.ninja-squad:springmockk (in the local artifact store)
                """;
        BuildRecord.Diag unresolved = diag(
                "compile-test", "kotlinc", message, "src/test/kotlin/HttpControllersTests.kt", 3, 12, 0, List.of(), "");
        BuildRecord r = record("build", false, false, 900, null, List.of(unresolved), List.of());
        assertThat(JkResultsAgent.render(r)).contains("FIX deps(add, com.ninja-squad:springmockk)\n");
    }

    @Test
    void a_removed_starter_is_the_add_and_a_symbol_error_names_no_coordinate() {
        String message = """
                package org.springframework.web.bind.annotation does not exist
                provided by: org.springframework.boot:spring-boot-starter-webmvc (removed from this module's dependencies)
                """;
        BuildRecord.Diag missing = diag(
                "compile-java",
                "javac",
                message,
                "src/main/java/com/example/restservice/GreetingController.java",
                3,
                8,
                0,
                List.of(),
                "compiler.err.doesnt.exist");
        BuildRecord.Diag symbol = diag(
                "compile-java",
                "javac",
                """
                        cannot find symbol
                          symbol:   class RestController
                          location: class com.example.restservice.GreetingController""",
                "src/main/java/com/example/restservice/GreetingController.java",
                9,
                2,
                0,
                List.of(),
                "compiler.err.cant.resolve.location");
        BuildRecord r = record("build", false, false, 900, null, List.of(missing, symbol), List.of());
        assertThat(JkResultsAgent.render(r)).isEqualTo("""
                        FAIL build rest-service · 2 errors · 900ms
                        E src/main/java/com/example/restservice/GreetingController.java:3:8 package org.springframework.web.bind.annotation does not exist
                        FIX deps(add, org.springframework.boot:spring-boot-starter-webmvc)
                        E src/main/java/com/example/restservice/GreetingController.java:9:2 cannot find symbol
                        """);
    }

    @Test
    void a_pinned_resolve_conflict_names_the_version_the_graph_requires() {
        BuildRecord.Diag err =
                new BuildRecord.Diag("error", "/ws/rest-service", "parse-build", "verbatim", """
                ‼ Cannot resolve dependencies:
                  │ org.springframework.boot:spring-boot 4.0.8 depends on org.springframework:spring-core [7.0.9,+∞)
                  │ The project depends on org.springframework.boot:spring-boot-starter-webmvc 4.0.8
                  │ The project depends on org.springframework:spring-core 6.0.0
                """, null, null);
        BuildRecord.Task step = new BuildRecord.Task("parse-build", "resolve", "FAIL", 400, 0);
        BuildRecord r = record("test", false, false, 400, null, List.of(err), List.of(step));
        assertThat(JkResultsAgent.render(r)).isEqualTo("""
                        FAIL test rest-service · 1 error · 400ms
                        E parse-build: │ org.springframework.boot:spring-boot 4.0.8 depends on org.springframework:spring-core [7.0.9,+∞)
                          │ The project depends on org.springframework.boot:spring-boot-starter-webmvc 4.0.8
                        FIX deps(pin, org.springframework:spring-core:7.0.9)
                        """);
    }

    @Test
    void a_junit_engine_that_failed_to_start_names_the_pin_to_drop_forward() {
        BuildRecord.Diag err = new BuildRecord.Diag(
                "error", "/ws/junit-starter-gradle", "run-tests", "test-launcher", """
                        test discovery exited 70 before any test ran — TestEngine with ID 'junit-jupiter' failed to discover tests
                        engine: junit-jupiter

                        Two versions of the org.junit.jupiter line on the test classpath:
                          5.0.0: org.junit.jupiter:junit-jupiter-api
                          6.1.3: org.junit.jupiter:junit-jupiter-engine, org.junit.jupiter:junit-jupiter
                        """, null, null);
        BuildRecord r = record("test", false, false, 986, null, List.of(err), List.of());
        assertThat(JkResultsAgent.render(r)).isEqualTo("""
                        FAIL test rest-service · 1 error · 986ms
                        E run-tests: test discovery exited 70 before any test ran — TestEngine with ID 'junit-jupiter' failed to discover tests
                          engine: junit-jupiter
                        FIX deps(pin, org.junit.jupiter:junit-jupiter-api:6.1.3)
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
    void the_sixth_problem_names_the_shell_command_and_the_tool() {
        List<BuildRecord.Diag> diags = List.of(
                diag("compile-java", "javac", "a", "src/A.java", 1, 1, 0, List.of(), ""),
                diag("compile-java", "javac", "b", "src/B.java", 2, 1, 0, List.of(), ""),
                diag("compile-java", "javac", "c", "src/C.java", 3, 1, 0, List.of(), ""),
                diag("compile-java", "javac", "d", "src/D.java", 4, 1, 0, List.of(), ""),
                diag("compile-java", "javac", "e", "src/E.java", 5, 1, 0, List.of(), ""),
                diag("compile-java", "javac", "f", "src/F.java", 6, 1, 0, List.of(), ""),
                diag("compile-java", "javac", "g", "src/G.java", 7, 1, 0, List.of(), ""));
        String text = JkResultsAgent.render(record("build", false, false, 100, null, diags, List.of()));
        assertThat(text).contains("+2 more: jk results --all | diagnostics(file=src/F.java)\n");
        assertThat(text).doesNotContain("src/G.java:");
    }

    @Test
    void the_all_report_is_every_problem_with_source_lines_and_no_headline() {
        List<BuildRecord.Diag> diags = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            diags.add(diag("compile-java", "javac", "e" + i, "src/F" + i + ".java", i, 1, i, List.of("line " + i), ""));
        }
        String text = JkResultsAgent.renderAll(record("build", false, false, 100, null, diags, List.of()), null);
        assertThat(text).doesNotContain("FAIL build", "more:");
        assertThat(text)
                .contains("E src/F1.java:1:1 e1")
                .contains("E src/F7.java:7:1 e7")
                .contains("line 7");
    }

    @Test
    void the_all_report_of_a_green_run_says_so() {
        BuildRecord ok = record("build", true, false, 100, null, List.of(), List.of());
        assertThat(JkResultsAgent.renderAll(ok, null)).isEqualTo("0 diagnostics\n");
    }

    @Test
    void the_all_report_includes_test_failures_only_the_module_runs_carry() {
        MarkdownTestReport.Entry fail = new MarkdownTestReport.Entry(
                "com.example.AppTests",
                "adds()",
                10,
                "expected: 3 but was: 4",
                "\tat com.example.AppTests.adds(AppTests.java:9)\n",
                null);
        var run = new MarkdownTestReport.ModuleRun("/ws", "app", List.of(fail));
        BuildRecord r = record("test", false, false, 100, new BuildRecord.Tests(1, 0, 1, 0), List.of(), List.of());
        assertThat(JkResultsAgent.renderAll(r, List.of(run)))
                .contains("T com.example.AppTests#adds")
                .contains("expected: 3 but was: 4");
    }

    @Test
    void details_for_one_file_include_the_snippet_window() {
        BuildRecord.Diag err = diag(
                "compile-java", "javac", "';' expected", "src/A.java", 2, 1, 1, List.of("class A {", "int x", "}"), "");
        BuildRecord r = record("build", false, false, 100, null, List.of(err), List.of());
        String text = JkResultsAgent.renderDetails(r, "src/A.java", 20, true);
        assertThat(text).contains("  1|\tclass A {\n  2|\tint x\n  3|\t}\n").doesNotContain("FAIL build");
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

    private static BuildRecord workspace(List<BuildRecord.Module> modules, List<BuildRecord.Diag> diags) {
        return new BuildRecord(
                "id",
                1,
                BuildRecord.SCHEMA,
                "test",
                "/ws/rest-service",
                "com.example:rest-service",
                "pid",
                1_000,
                1_500,
                500,
                true,
                false,
                0,
                "0.14.0",
                new BuildRecord.Tests(1, 1, 0, 0),
                modules,
                List.of(),
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

    private static BuildRecord record(
            String kind,
            boolean success,
            boolean cancelled,
            long millis,
            BuildRecord.@Nullable Tests tests,
            List<BuildRecord.Diag> diags,
            List<BuildRecord.Task> steps) {
        return record("/ws/rest-service", kind, success, cancelled, millis, tests, diags, steps);
    }

    private static BuildRecord record(
            String dir,
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
                dir,
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
