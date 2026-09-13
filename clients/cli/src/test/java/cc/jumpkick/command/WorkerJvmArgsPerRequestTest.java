// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code JK_JVM_ARGS} is the shell's spelling of {@code --jvm-arg}, and the engine that forks the
 * worker JVMs is a daemon shared by every terminal. The variable therefore travels with each
 * request: read from the engine's own environment it was whichever shell started the daemon, so a
 * second terminal exporting a different value silently got the first one's until {@code jk engine
 * stop}.
 */
@Tag("integration")
class WorkerJvmArgsPerRequestTest {

    /** The client reads {@code JK_JVM_ARGS} through {@code JkDirs.env}, which this property overrides. */
    private static final String ENV_SEAM = "jk.env.JK_JVM_ARGS";

    @AfterEach
    void clearSeam() {
        System.clearProperty(ENV_SEAM);
    }

    @Test
    void two_builds_with_different_jvm_args_against_one_engine_fork_workers_with_their_own(@TempDir Path tempDir)
            throws Exception {
        assertThat(run("new", "--name", "probe", tempDir.toString())).isZero();
        Path calcTest = scaffoldedTest(tempDir);
        String pkg = Files.readString(calcTest)
                .lines()
                .filter(l -> l.startsWith("package "))
                .findFirst()
                .orElseThrow();
        // A test that reports what -D the forked test JVM was started with.
        Files.writeString(calcTest.resolveSibling("ProbeTest.java"), pkg + """

                import java.nio.file.Files;
                import java.nio.file.Path;
                import org.junit.jupiter.api.Test;

                class ProbeTest {
                    @Test
                    void records_the_probe_property_of_this_jvm() throws Exception {
                        Files.writeString(
                                Path.of(System.getProperty("jk.probe.out")),
                                String.valueOf(System.getProperty("jk.probe")));
                    }
                }
                """);
        Path cache = tempDir.resolve("cache");

        Path first = tempDir.resolve("first.txt");
        System.setProperty(ENV_SEAM, "-Djk.probe=first -Djk.probe.out=" + first);
        assertThat(run("build", "-r", "-C", tempDir.toString(), "--cache-dir", cache.toString()))
                .isZero();
        assertThat(first).hasContent("first");

        // The same resident engine, a second shell: it gets its own value, not the daemon's first.
        Path second = tempDir.resolve("second.txt");
        System.setProperty(ENV_SEAM, "-Djk.probe=second -Djk.probe.out=" + second);
        assertThat(run("build", "-r", "-C", tempDir.toString(), "--cache-dir", cache.toString()))
                .isZero();
        assertThat(second).hasContent("second");
    }

    /** The sample {@code CalcTest} {@code jk new} writes, wherever the layout put it. */
    private static Path scaffoldedTest(Path project) throws IOException {
        try (Stream<Path> files = Files.walk(project)) {
            return files.filter(p -> p.getFileName().toString().equals("CalcTest.java"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("jk new scaffolds a CalcTest"));
        }
    }
}
