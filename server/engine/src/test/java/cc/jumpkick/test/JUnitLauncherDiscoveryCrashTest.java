// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.engine.plugin.MemoryProbe;
import cc.jumpkick.orchestrator.TestProgressListener;
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
 * Before a pool runs, the launcher lists the suite's classes in a discovery JVM of its own. A
 * discovery JVM that dies before it names a class — a bad flag, a corrupt runner jar, a boot OOM —
 * has said nothing about the tests, and nothing is not a green suite: the run fails as a launcher
 * failure, the way a crashed pool worker fails it, or the build stores a green stamp for tests that
 * never ran.
 *
 * <p>The auto share lists the classes before it sizes the pool, whatever the host's free memory.
 * An explicit pin lists them only when the heap clamp leaves it more than one JVM; with less, the
 * single runner is the only JVM started, and the failure names it. Each case pins the free memory
 * the clamp reads, so a loaded host runs the same case as an idle one.
 */
@Tag("integration")
class JUnitLauncherDiscoveryCrashTest {

    private static final long GIB = 1L << 30;
    private static final MemoryProbe.Memory PLENTY = new MemoryProbe.Memory(32 * GIB, 16 * GIB);
    private static final MemoryProbe.Memory ONE_HEAP = new MemoryProbe.Memory(32 * GIB, 768L << 20);

    @BeforeEach
    void posixShell() {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "POSIX shell required");
    }

    @AfterEach
    void measureTheHostAgain() {
        MemoryProbe.forTests(null);
    }

    @Test
    void a_discovery_jvm_that_exits_non_zero_is_a_failed_run_not_an_empty_green_one(@TempDir Path dir)
            throws Exception {
        MemoryProbe.forTests(ONE_HEAP);
        JUnitLauncher launcher = new JUnitLauncher().withModuleLabel("ex:m").withAutoShare(true);

        assertThatThrownBy(() -> run(launcher, dir))
                .as("a JVM that named no class is a launcher failure, not a passing suite")
                .isInstanceOfSatisfying(TestLauncherFailure.class, f -> assertFailed(f, "test discovery"));
    }

    @Test
    void an_explicit_pool_whose_discovery_jvm_exits_non_zero_fails_the_same_way(@TempDir Path dir) throws Exception {
        MemoryProbe.forTests(PLENTY);
        JUnitLauncher launcher = new JUnitLauncher().withModuleLabel("ex:m");

        assertThatThrownBy(() -> run(launcher, dir))
                .isInstanceOfSatisfying(TestLauncherFailure.class, f -> assertFailed(f, "test discovery"));
    }

    @Test
    void an_explicit_pool_clamped_to_one_jvm_names_the_runner_it_started(@TempDir Path dir) throws Exception {
        MemoryProbe.forTests(ONE_HEAP);
        JUnitLauncher launcher = new JUnitLauncher().withModuleLabel("ex:m");

        assertThatThrownBy(() -> run(launcher, dir))
                .as("no discovery JVM was started, so none is blamed")
                .isInstanceOfSatisfying(TestLauncherFailure.class, f -> assertFailed(f, "test runner"));
    }

    /** Runs a two-worker suite under a {@code java} that prints HotSpot's boot refusal and exits 3. */
    private static void run(JUnitLauncher launcher, Path dir) throws Exception {
        Path javaHome = dir.resolve("jdk");
        Path java = javaHome.resolve("bin").resolve("java");
        Files.createDirectories(java.getParent());
        Files.writeString(java, "#!/bin/sh\necho 'Error: could not create the Java Virtual Machine'\nexit 3\n");
        assertThat(java.toFile().setExecutable(true)).isTrue();
        Path classes = Files.createDirectories(dir.resolve("classes"));
        Path cache = Files.createDirectories(dir.resolve("cache"));
        launcher.run(javaHome, classes, List.of(), cache, 2, Map.of(), TestProgressListener.noop());
    }

    private static void assertFailed(TestLauncherFailure f, String phase) {
        assertThat(f.moduleLabel()).isEqualTo("ex:m");
        assertThat(f.phase()).isEqualTo(phase);
        assertThat(f.exit()).isEqualTo(3);
        assertThat(f.getMessage()).contains(phase + " exited 3 before any test ran");
        assertThat(f.output()).contains("could not create the Java Virtual Machine");
    }
}
