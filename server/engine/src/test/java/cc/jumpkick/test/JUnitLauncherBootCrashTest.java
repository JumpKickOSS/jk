// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.engine.plugin.OverbookSignals;
import cc.jumpkick.engine.plugin.WorkerLeases;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A suite fork that dies before its first test event is reported with the JVM's own last words and
 * its exit, not a bare exit code: the heap reservation HotSpot refused, or the signal that killed a
 * fork that printed nothing. The guard suite and every {@code run-tests} step share this path. The
 * forks lease from a ledger whose host sample reads 800 MiB free, as after a heavy concurrent run.
 */
@Tag("integration")
class JUnitLauncherBootCrashTest {

    private static final long MIB = 1L << 20;
    private static final long GIB = 1L << 30;

    private final WorkerLeases.Ledger ledger = new WorkerLeases.Ledger(
            () -> 8 * GIB, () -> 8, id -> false, () -> new OverbookSignals.Reading(true, 800 * MIB, 0, 0));

    @BeforeEach
    void littleFreeMemory() {
        WorkerLeases.engineForTests(ledger);
    }

    @AfterEach
    void processLedger() {
        WorkerLeases.engineForTests(null);
    }

    @Test
    void a_jvm_whose_heap_cannot_be_reserved_fails_with_hotspots_refusal(@TempDir Path dir) throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "POSIX shell required");
        assumeTrue(canLimitVirtualMemory(), "the shell cannot set `ulimit -v` here (macOS refuses it)");
        Path javaHome = shellJava(
                dir,
                "ulimit -v 2097152\nexec '" + Path.of(System.getProperty("java.home"), "bin", "java") + "' \"$@\"\n");
        Path classes = Files.createDirectories(dir.resolve("classes"));
        Path cache = Files.createDirectories(dir.resolve("cache"));

        JUnitLauncher launcher =
                new JUnitLauncher().withModuleLabel("guard root").withJvmArgs(List.of("-Xmx4g"));

        try (WorkerLeases.Grant others = ledger.acquireBytes(2 * GIB, true, 3L)) {
            assertThatThrownBy(() ->
                            launcher.run(javaHome, classes, List.of(), cache, 1, Map.of(), TestProgressListener.noop()))
                    .isInstanceOfSatisfying(TestLauncherFailure.class, f -> {
                        assertThat(f.phase()).isEqualTo("test runner");
                        assertThat(f.exit()).isEqualTo(1);
                        assertThat(f.jvmRefused()).isTrue();
                        assertThat(f.getMessage())
                                .startsWith(
                                        "test runner exited 1 before any test ran — Could not reserve enough space for")
                                .contains("object heap");
                        assertThat(f.output()).contains("Error occurred during initialization of VM");
                    });
        }
    }

    @Test
    void a_heap_the_budget_can_never_hold_is_refused_before_the_fork(@TempDir Path dir) throws Exception {
        Path javaHome = Path.of(System.getProperty("java.home"));
        Path classes = Files.createDirectories(dir.resolve("classes"));
        Path cache = Files.createDirectories(dir.resolve("cache"));

        JUnitLauncher launcher =
                new JUnitLauncher().withModuleLabel("guard root").withJvmArgs(List.of("-Xmx1000000g"));

        try (WorkerLeases.Grant others = ledger.acquireBytes(7 * GIB, true, 3L)) {
            assertTimeoutPreemptively(Duration.ofSeconds(20), () -> assertThatThrownBy(() ->
                            launcher.run(javaHome, classes, List.of(), cache, 1, Map.of(), TestProgressListener.noop()))
                    .hasMessageContaining("-Xmx1000000g")
                    .hasMessageContaining("8.0 GiB worker budget"));
            assertThat(ledger.queued()).isZero();
        }
    }

    @Test
    void a_fork_killed_before_it_printed_anything_names_the_signal(@TempDir Path dir) throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "POSIX shell required");
        Path javaHome = shellJava(dir, "kill -9 $$\n");
        Path classes = Files.createDirectories(dir.resolve("classes"));
        Path cache = Files.createDirectories(dir.resolve("cache"));

        JUnitLauncher launcher = new JUnitLauncher().withModuleLabel("ex:m");

        assertThatThrownBy(() ->
                        launcher.run(javaHome, classes, List.of(), cache, 1, Map.of(), TestProgressListener.noop()))
                .isInstanceOfSatisfying(TestLauncherFailure.class, f -> {
                    assertThat(f.exit()).isEqualTo(137);
                    assertThat(f.signal()).isEqualTo("SIGKILL");
                    assertThat(f.getMessage())
                            .isEqualTo(
                                    "test runner exited 137 (SIGKILL) before any test ran — the fork printed nothing");
                });
    }

    /** A {@code java} home whose {@code bin/java} is the shell {@code script}. */
    private static Path shellJava(Path dir, String script) throws Exception {
        Path javaHome = dir.resolve("jdk");
        Path java = javaHome.resolve("bin").resolve("java");
        Files.createDirectories(java.getParent());
        Files.writeString(java, "#!/bin/sh\n" + script);
        assertThat(java.toFile().setExecutable(true)).isTrue();
        return javaHome;
    }

    /** Whether {@code /bin/sh} accepts the virtual-memory limit the test starts its JVM under. */
    private static boolean canLimitVirtualMemory() throws Exception {
        Process sh = new ProcessBuilder("/bin/sh", "-c", "ulimit -v 2097152")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        return sh.waitFor() == 0;
    }
}
