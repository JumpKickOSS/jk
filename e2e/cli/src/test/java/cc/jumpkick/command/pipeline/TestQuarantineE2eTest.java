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
 * {@code jk-quarantine.toml}: a run whose only failures are in quarantined classes succeeds and says
 * so in one line; a failure outside the quarantine still fails it; {@code --quarantined} runs only
 * the quarantined classes.
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

        Files.writeString(project.resolve("jk-quarantine.toml"), """
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

        Files.writeString(project.resolve("jk-quarantine.toml"), """
                [[class]]
                name = "com.example.FlakyTest"
                module = "elsewhere"
                """);
        assertThat(run("test", "-C", dir, "--cache-dir", cache))
                .as("an entry for another module holds nothing here")
                .isEqualTo(4);
    }

    /** One class under test, a passing test class and one that always fails; JUnit from Central. */
    private static Path project(Path tmp) throws IOException {
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
        Files.writeString(project.resolve("test/src/com/example/FlakyTest.java"), """
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
        return project;
    }
}
