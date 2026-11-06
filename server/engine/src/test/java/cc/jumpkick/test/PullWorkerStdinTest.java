// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.config.PluginTuning;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.run.TestSummary;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A pull worker's tests read end-of-input from stdin, and so does a child they give that stdin to:
 * neither waits on the parent's command channel. Each fixture bounds its own wait, so a regression
 * fails here rather than hanging the worker.
 */
@Tag("integration")
class PullWorkerStdinTest {

    @AfterEach
    void reset() {
        SessionContext.reset();
    }

    @Test
    void a_pull_workers_tests_and_their_children_read_end_of_input(@TempDir Path dir) throws Exception {
        assumeTrue(TestWorkers.clampByHeap(2) == 2, "host heap allows two test workers");
        Path javaHome = Path.of(System.getProperty("java.home"));
        List<Path> junit = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(e -> e.contains("junit") || e.contains("opentest4j") || e.contains("apiguardian"))
                .map(Path::of)
                .toList();
        Path classes = compile(dir, junit);
        Path cache = Files.createDirectories(dir.resolve("cache"));
        SessionContext.install(SessionContext.current().withWorkingDir(dir).withJvm(PluginTuning.NONE));

        TestSummary summary = new JUnitLauncher()
                .withModuleLabel("g:app")
                .run(javaHome, classes, junit, cache, 2, Map.of(), TestProgressListener.noop());

        assertThat(summary.workers()).as("the classes ran on pull workers").isEqualTo(2);
        assertThat(summary.failures())
                .extracting(f -> f.className() + "#" + f.method() + ": " + f.message())
                .isEmpty();
        assertThat(summary.succeeded()).isEqualTo(2);
    }

    private static Path compile(Path dir, List<Path> classpath) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/demo"));
        Files.writeString(src.resolve("ReadsStdinTest.java"), """
                package demo;

                import java.time.Duration;
                import org.junit.jupiter.api.Assertions;
                import org.junit.jupiter.api.Test;

                class ReadsStdinTest {
                    @Test
                    void stdin_is_at_end_of_input() {
                        int read = Assertions.assertTimeoutPreemptively(Duration.ofSeconds(30), () -> System.in.read());
                        Assertions.assertEquals(-1, read);
                    }
                }
                """);
        Files.writeString(src.resolve("ChildReadsStdinTest.java"), """
                package demo;

                import java.nio.file.Files;
                import java.nio.file.Path;
                import java.util.concurrent.TimeUnit;
                import org.junit.jupiter.api.Assertions;
                import org.junit.jupiter.api.Test;

                class ChildReadsStdinTest {
                    @Test
                    void a_child_on_the_inherited_stdin_reads_end_of_input() throws Exception {
                        Path source = Files.createTempFile("ReadsStdin", ".java");
                        Files.writeString(source, "class ReadsStdin { public static void main(String[] a) throws Exception {"
                                + " System.exit(System.in.read() == -1 ? 0 : 3); } }");
                        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
                        Process child = new ProcessBuilder(java.toString(), source.toString()).inheritIO().start();
                        try {
                            Assertions.assertTrue(child.waitFor(60, TimeUnit.SECONDS), "the child is waiting on stdin");
                            Assertions.assertEquals(0, child.exitValue());
                        } finally {
                            child.destroyForcibly();
                        }
                    }
                }
                """);
        Path out = Files.createDirectories(dir.resolve("classes"));
        List<String> args = new ArrayList<>(List.of(
                "-d",
                out.toString(),
                "-cp",
                String.join(
                        File.pathSeparator,
                        classpath.stream().map(Path::toString).toList())));
        args.add(src.resolve("ReadsStdinTest.java").toString());
        args.add(src.resolve("ChildReadsStdinTest.java").toString());
        int exit = ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(new String[0]));
        assertThat(exit).as("fixture compiles").isZero();
        return out;
    }
}
