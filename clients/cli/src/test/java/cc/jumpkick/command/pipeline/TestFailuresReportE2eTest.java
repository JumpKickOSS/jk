// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.util.MarkdownReports;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code [test] failures = "report"}: a failing test is run, recorded and reported, and the run
 * still succeeds; {@code --test-failures=fail} puts the failure back in charge of the exit code.
 */
// Network: JUnit from Central; forked test JVMs.
@Tag("integration")
class TestFailuresReportE2eTest {

    @Test
    void a_reported_failure_is_listed_and_the_run_succeeds(@TempDir Path tmp) throws Exception {
        Path project = project(tmp);
        String cache = tmp.resolve("cache").toString();
        assertThat(run("lock", "-C", project.toString(), "--cache-dir", cache)).isEqualTo(0);

        assertThat(run("test", "-C", project.toString(), "--cache-dir", cache))
                .as("a failing test fails the run by default")
                .isEqualTo(4);

        Files.writeString(
                project.resolve("jk.toml"),
                Files.readString(project.resolve("jk.toml")) + "\n[test]\nfailures = \"report\"\n");
        assertThat(run("test", "-C", project.toString(), "--cache-dir", cache)).isEqualTo(0);
        String md = MarkdownReports.strip(Files.readString(project.resolve("target/jk-results.md")));
        System.out.println(md);
        assertThat(md)
                .contains("**1 failed**")
                .contains("reported, not failing: [test] failures = \"report\"")
                .contains("CalcTest")
                .contains("broken");
        try (Stream<Path> xml = Files.list(project.resolve("target/surefire-reports"))) {
            Path report = xml.filter(p -> p.getFileName().toString().startsWith("TEST-"))
                    .findFirst()
                    .orElseThrow();
            assertThat(Files.readString(report)).contains("<failure");
        }

        assertThat(run("build", "-C", project.toString(), "--cache-dir", cache))
                .as("a build runs the same tests under the same mode")
                .isEqualTo(0);

        assertThat(run("test", "--test-failures=fail", "-C", project.toString(), "--cache-dir", cache))
                .as("the flag wins over the manifest")
                .isEqualTo(4);
    }

    /** One class under test, one passing and one failing test; JUnit from Central. */
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

                    @Test
                    void broken() {
                        assertEquals(5, Calc.add(1, 2));
                    }
                }
                """);
        return project;
    }
}
