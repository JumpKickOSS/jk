// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A suite fork that dies before its first test event is reported with the JVM's own last words and
 * its exit, not a bare exit code: the heap reservation HotSpot refused, or the signal that killed a
 * fork that printed nothing. The guard suite and every {@code run-tests} step share this path.
 */
@Tag("integration")
class JUnitLauncherBootCrashTest {

    @Test
    void a_jvm_whose_heap_cannot_be_reserved_fails_with_hotspots_refusal(@TempDir Path dir) throws Exception {
        Path javaHome = Path.of(System.getProperty("java.home"));
        Path classes = Files.createDirectories(dir.resolve("classes"));
        Path cache = Files.createDirectories(dir.resolve("cache"));

        JUnitLauncher launcher =
                new JUnitLauncher().withModuleLabel("guard root").withJvmArgs(List.of("-Xmx1000000g"));

        assertThatThrownBy(() ->
                        launcher.run(javaHome, classes, List.of(), cache, 1, Map.of(), TestProgressListener.noop()))
                .isInstanceOfSatisfying(TestLauncherFailure.class, f -> {
                    assertThat(f.phase()).isEqualTo("test runner");
                    assertThat(f.exit()).isEqualTo(1);
                    assertThat(f.jvmRefused()).isTrue();
                    assertThat(f.getMessage())
                            .startsWith("test runner exited 1 before any test ran — Could not reserve enough space for")
                            .contains("object heap");
                    assertThat(f.output()).contains("Error occurred during initialization of VM");
                });
    }

    @Test
    void a_fork_killed_before_it_printed_anything_names_the_signal(@TempDir Path dir) throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "POSIX shell required");
        Path javaHome = dir.resolve("jdk");
        Path java = javaHome.resolve("bin").resolve("java");
        Files.createDirectories(java.getParent());
        Files.writeString(java, "#!/bin/sh\nkill -9 $$\n");
        assertThat(java.toFile().setExecutable(true)).isTrue();
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
}
