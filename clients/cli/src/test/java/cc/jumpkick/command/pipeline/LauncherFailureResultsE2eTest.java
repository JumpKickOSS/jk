// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The results file names a launcher failure: the launcher pinned to the Platform 1 line beside
 * Jupiter 6 fails {@code run-tests} as a step whose entry carries the engine, the exception, both
 * versions of the {@code org.junit.platform} line, the pin, and the {@code jk why} repair — with no
 * {@code Tests:} line counting a test that never ran.
 */
// Network: JUnit from Central; one forked test JVM.
@Tag("integration")
class LauncherFailureResultsE2eTest {

    @Test
    void a_platform_line_conflict_renders_as_a_failed_step_with_the_repair(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("pinned"));
        Files.writeString(project.resolve("jk.toml"), """
                name    = "pinned"
                group   = "com.example"
                version = "1.0.0"
                java    = 25

                [test-dependencies]
                junit-jupiter           = { group = "org.junit.jupiter", name = "junit-jupiter", version = "6.1.3" }
                junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "=1.13.4" }  # off the train: Platform 1 beside Jupiter 6

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Files.createDirectories(project.resolve("test/src/com/example"));
        Files.writeString(project.resolve("test/src/com/example/PinnedTest.java"), """
                package com.example;

                import org.junit.jupiter.api.Test;

                class PinnedTest {
                    @Test
                    void passes() {}
                }
                """);
        String cache = tmp.resolve("cache").toString();
        assertThat(run("lock", "-C", project.toString(), "--cache-dir", cache)).isEqualTo(0);

        int exit = run("test", "-C", project.toString(), "--cache-dir", cache);
        String md = Files.readString(project.resolve("target/jk-results.md"));
        System.out.println("=== results ===\n" + md);

        assertThat(exit).isNotEqualTo(0);
        assertThat(md)
                .contains("# jk results — FAIL")
                .contains("`run-tests`: test discovery exited 70 before any test ran")
                .contains("TestEngine with ID 'junit-jupiter' failed to discover tests")
                .contains("### run-tests")
                .contains("Two versions of the org.junit.platform line on the test classpath:")
                .contains("org.junit.platform:junit-platform-launcher (declared =1.13.4 in [test-dependencies])")
                .contains("`jk why org.junit.platform:junit-platform-launcher`")
                .contains("jk-test-runner: test discovery failed")
                .contains("## Failed steps")
                .doesNotContain("Tests:")
                .doesNotContain("(test run)");
    }
}
