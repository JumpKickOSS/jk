// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.util.MarkdownReports;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code .jk/test/quarantine.toml}: a class that fails and then passes on a re-run under one stamp
 * as often as the default threshold says goes into it on its own; a run whose only failures are in
 * quarantined classes succeeds and says so in one line; a failure outside the quarantine still fails
 * it; {@code --quarantined} runs only the quarantined classes.
 */
// Network: JUnit from Central; forked test JVMs.
@Tag("integration")
class TestQuarantineE2eTest {

    @Test
    void a_failure_only_in_quarantine_passes_the_run_and_says_so(@TempDir Path tmp) throws Exception {
        Path project = project(tmp);
        String dir = project.toString();
        String cache = tmp.resolve("cache").toString();
        assertThat(run("lock", "-C", dir, "--cache-dir", cache)).isEqualTo(0);
        assertThat(run("test", "-C", dir, "--cache-dir", cache))
                .as("a failing test fails the run when nothing is quarantined")
                .isEqualTo(4);

        Files.writeString(quarantine(project), """
                [[class]]
                name = "com.example.FlakyTest"
                module = "."
                """);
        assertThat(run("test", "-C", dir, "--cache-dir", cache)).isEqualTo(0);
        String md = MarkdownReports.strip(Files.readString(project.resolve("target/jk-results.md")));
        assertThat(md)
                .contains("**1 failed**")
                .contains("quarantined flaky test failed, not your change")
                .doesNotContain("[test] failures = \"report\"");

        assertThat(run("test", "--quarantined", "-r", "-C", dir, "--cache-dir", cache))
                .isEqualTo(0);
        assertThat(Files.readString(project.resolve("target/jk-results.md")))
                .as("--quarantined runs the quarantined class alone")
                .contains("FlakyTest")
                .doesNotContain("CalcTest");

        Files.writeString(quarantine(project), """
                [[class]]
                name = "com.example.FlakyTest"
                module = "elsewhere"
                """);
        assertThat(run("test", "-C", dir, "--cache-dir", cache))
                .as("an entry for another module holds nothing here")
                .isEqualTo(4);
    }

    @Test
    void a_class_that_passes_on_a_rerun_twice_is_quarantined_on_its_own(@TempDir Path tmp) throws Exception {
        Path runs = tmp.resolve("flaky-runs");
        Path project = project(tmp, """
                package com.example;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import java.nio.file.Files;
                import java.nio.file.Path;
                import org.junit.jupiter.api.Test;

                class FlakyTest {
                    @Test
                    void fails_every_other_run() throws Exception {
                        Path runs = Path.of("%s");
                        int run = Files.exists(runs) ? Integer.parseInt(Files.readString(runs).strip()) + 1 : 1;
                        Files.writeString(runs, Integer.toString(run));
                        assertEquals(0, run %% 2, "run " + run);
                    }
                }
                """.formatted(runs.toString().replace("\\", "\\\\")));
        String dir = project.toString();
        String cache = tmp.resolve("cache").toString();
        Path results = project.resolve("target/jk-results.md");
        assertThat(run("lock", "-C", dir, "--cache-dir", cache)).isEqualTo(0);

        assertThat(run("test", "-C", dir, "--cache-dir", cache))
                .as("run 1 fails")
                .isEqualTo(4);
        assertThat(run("test", "-C", dir, "--cache-dir", cache))
                .as("run 2 re-runs the failed class under the same stamp, and it passes")
                .isEqualTo(0);
        assertThat(Files.readString(results))
                .contains("passed on a re-run with no input changed, so flaky or dependent on what ran before: "
                        + "com.example.FlakyTest");
        assertThat(project.resolve(".jk/test/quarantine.toml"))
                .as("1 re-run pass in 20 runs is under the threshold")
                .doesNotExist();

        moveStamp(project);
        assertThat(run("test", "-C", dir, "--cache-dir", cache))
                .as("run 3 fails")
                .isEqualTo(4);
        assertThat(run("test", "-C", dir, "--cache-dir", cache))
                .as("run 4 passes on the re-run")
                .isEqualTo(0);
        assertThat(Files.readString(runs).strip())
                .as("one FlakyTest launch per run")
                .isEqualTo("4");
        assertThat(MarkdownReports.strip(Files.readString(results)))
                .contains("quarantined com.example.FlakyTest: passed on a re-run 2 of its last 20 runs");
        assertThat(Files.readString(quarantine(project)))
                .contains("name = \"com.example.FlakyTest\"")
                .contains("in 2 of its last 20 runs")
                .doesNotContain("CalcTest");

        moveStamp(project);
        assertThat(run("test", "-C", dir, "--cache-dir", cache))
                .as("run 5 fails only in the quarantined class, so it passes")
                .isEqualTo(0);
        assertThat(MarkdownReports.strip(Files.readString(results)))
                .contains("**1 failed**")
                .contains("quarantined flaky test failed, not your change: com.example.FlakyTest");
    }

    /** A new stamp: an edit to a test source re-runs every class. */
    private static void moveStamp(Path project) throws IOException {
        Path calc = project.resolve("test/src/com/example/CalcTest.java");
        Files.writeString(calc, Files.readString(calc) + "// edited\n");
    }

    /** The project's quarantine file, its directory made. */
    private static Path quarantine(Path project) throws IOException {
        return Files.createDirectories(project.resolve(".jk/test")).resolve("quarantine.toml");
    }

    /** One class under test, a passing test class and one that always fails; JUnit from Central. */
    private static Path project(Path tmp) throws IOException {
        return project(tmp, """
                package com.example;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import org.junit.jupiter.api.Test;

                class FlakyTest {
                    @Test
                    void sometimes() {
                        assertEquals(5, Calc.add(1, 2));
                    }
                }
                """);
    }

    /** {@link #project(Path)} with {@code flakyTest} as the source of {@code com.example.FlakyTest}. */
    private static Path project(Path tmp, String flakyTest) throws IOException {
        Path project = Files.createDirectories(tmp.resolve("calc"));
        Files.writeString(project.resolve("jk.toml"), """
                group   = "com.example"
                name    = "calc"
                version = "1.0.0"
                java    = 25

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"

                [test-dependencies]
                junit-jupiter = { group = "org.junit.jupiter", name = "junit-jupiter", version = "6.1.3" }
                """);
        Files.createDirectories(project.resolve("src/com/example"));
        Files.writeString(project.resolve("src/com/example/Calc.java"), """
                package com.example;
                public final class Calc {
                    public static int add(int a, int b) { return a + b; }
                }
                """);
        Files.createDirectories(project.resolve("test/src/com/example"));
        Files.writeString(project.resolve("test/src/com/example/CalcTest.java"), """
                package com.example;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import org.junit.jupiter.api.Test;

                class CalcTest {
                    @Test
                    void adds() {
                        assertEquals(3, Calc.add(1, 2));
                    }
                }
                """);
        Files.writeString(project.resolve("test/src/com/example/FlakyTest.java"), flakyTest);
        return project;
    }
}
