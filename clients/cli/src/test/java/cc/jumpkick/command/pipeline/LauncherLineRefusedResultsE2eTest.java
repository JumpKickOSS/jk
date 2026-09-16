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
 * The results file names a refused lock: the launcher pinned to the Platform 1 line beside
 * Jupiter 6 never reaches a run — {@code jk lock} fails with the Jupiter's Platform line, the
 * launcher it resolved, and the pin that aligns them, and writes no lockfile.
 */
// Network: JUnit from Central.
@Tag("integration")
class LauncherLineRefusedResultsE2eTest {

    @Test
    void a_launcher_pin_off_the_jupiters_line_renders_as_a_refused_lock_with_the_repair(@TempDir Path tmp)
            throws Exception {
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
        String cache = tmp.resolve("cache").toString();

        int exit = run("lock", "-C", project.toString(), "--cache-dir", cache);
        String md = Files.readString(project.resolve("target/jk-results.md"));
        System.out.println("=== results ===\n" + md);

        assertThat(exit).isNotEqualTo(0);
        assertThat(project.resolve("jk-lock.toml")).doesNotExist();
        assertThat(md)
                .contains("# jk results — FAIL")
                .contains("junit-jupiter 6.1.3 runs on JUnit Platform 6.1.3")
                .contains("junit-platform-launcher resolved to 1.13.4")
                .contains("pin junit-platform-launcher to 6.1.3")
                .doesNotContain("Tests:");
    }
}
