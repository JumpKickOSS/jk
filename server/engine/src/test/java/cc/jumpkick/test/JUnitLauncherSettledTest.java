// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.config.PluginTuning;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.plugin.FakeWorkerShell;
import cc.jumpkick.engine.plugin.MemoryProbe;
import cc.jumpkick.orchestrator.TestProgressListener;
import cc.jumpkick.run.TestSummary;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A launch whose classes all passed earlier under the same stamp lists the selection in its
 * discovery JVM, the settled classes left out, and starts no suite JVM when that list holds no
 * class and no test besides. A list with tests under no class (features, scripts) still runs, and
 * so does an empty list when nothing was settled: the runner judges an empty plan. The fake {@code
 * java} notes each JVM it is started as in {@code forks}.
 */
@Tag("integration")
class JUnitLauncherSettledTest {

    private static final long GIB = 1L << 30;

    @BeforeEach
    void bash() {
        assumeTrue(Files.isExecutable(Path.of("/bin/bash")), "bash required");
    }

    @AfterEach
    void reset() {
        MemoryProbe.forTests(null);
        SessionContext.reset();
    }

    @Test
    void a_selection_whose_classes_all_passed_earlier_starts_only_its_discovery_jvm(@TempDir Path dir)
            throws Exception {
        TestSummary summary = run(dir, launcher(dir, List.of("demo.A")).withAutoShare(true), 0, 1);

        assertThat(forks(dir)).as("no suite JVM for an empty plan").containsExactly("list");
        assertThat(summary.total()).isZero();
        assertThat(summary.failures()).isEmpty();
        assertThat(summary.workers()).isEqualTo(1);
    }

    @Test
    void an_explicit_pool_whose_classes_all_passed_earlier_starts_only_its_discovery_jvm(@TempDir Path dir)
            throws Exception {
        MemoryProbe.forTests(new MemoryProbe.Memory(32 * GIB, 16 * GIB));
        assumeTrue(TestWorkers.effectiveJobs() >= 2, "the jobs budget allows two test workers");

        TestSummary summary = run(dir, launcher(dir, List.of("demo.A")), 0, 2);

        assertThat(forks(dir)).containsExactly("list");
        assertThat(summary.total()).isZero();
    }

    @Test
    void tests_under_no_class_still_run_when_every_class_passed_earlier(@TempDir Path dir) throws Exception {
        run(dir, launcher(dir, List.of("demo.A")).withAutoShare(true), 3, 1);

        assertThat(forks(dir)).containsExactly("list", "suite");
    }

    @Test
    void an_empty_list_with_nothing_settled_still_reaches_the_runner(@TempDir Path dir) throws Exception {
        run(dir, launcher(dir, List.of()).withAutoShare(true), 0, 1);

        assertThat(forks(dir)).containsExactly("list", "suite");
    }

    private static JUnitLauncher launcher(Path dir, List<String> settled) {
        SessionContext.install(SessionContext.current().withWorkingDir(dir).withJvm(PluginTuning.NONE));
        return new JUnitLauncher().withModuleLabel("g:app").withSettled(settled);
    }

    /**
     * Runs {@code launcher} with {@code workers} under a {@code java} whose discovery names no class
     * and counts {@code tests} tests, and whose suite JVM runs nothing.
     */
    private static TestSummary run(Path dir, JUnitLauncher launcher, int tests, int workers) throws Exception {
        Path home = dir.resolve("jdk");
        Path java = Files.createDirectories(home.resolve("bin")).resolve("java");
        String script = FakeWorkerShell.SHEBANG + "\n" + """
                forks="$(dirname "$0")/../forks"
                e() { printf '%s\\n' "##JKT:$1"; }
                case " $* " in
                  *" --list-only "*)
                    echo list >> "$forks"
                    e '{"event":"discovery_total","classes":0,"tests":@TESTS@}'
                    exit 0 ;;
                esac
                echo suite >> "$forks"
                e '{"event":"discovery_total","classes":0,"tests":0}'
                e '{"event":"plan_finished"}'
                exit 0
                """.replace("@TESTS@", Integer.toString(tests));
        Files.writeString(java, script);
        assertThat(java.toFile().setExecutable(true)).isTrue();
        Path classes = Files.createDirectories(dir.resolve("classes"));
        Path cache = Files.createDirectories(dir.resolve("cache"));
        return launcher.run(home, classes, List.of(), cache, workers, Map.of(), TestProgressListener.noop());
    }

    private static List<String> forks(Path dir) throws Exception {
        return Files.readAllLines(dir.resolve("jdk").resolve("forks"));
    }
}
