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
 * With more than one worker the launcher lists the suite's classes in a discovery JVM of its own.
 * A discovery JVM that dies before it names a class — a bad flag, a corrupt runner jar, a boot OOM
 * — has said nothing about the tests, and nothing is not a green suite: the run fails as a launcher
 * failure, the way a crashed pool worker fails it, or the build stores a green stamp for tests that
 * never ran.
 */
@Tag("integration")
class JUnitLauncherDiscoveryCrashTest {

    @Test
    void a_discovery_jvm_that_exits_non_zero_is_a_failed_run_not_an_empty_green_one(@TempDir Path dir)
            throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "POSIX shell required");
        Path javaHome = dir.resolve("jdk");
        Path java = javaHome.resolve("bin").resolve("java");
        Files.createDirectories(java.getParent());
        Files.writeString(java, "#!/bin/sh\necho 'Error: could not create the Java Virtual Machine'\nexit 3\n");
        assertThat(java.toFile().setExecutable(true)).isTrue();
        Path classes = Files.createDirectories(dir.resolve("classes"));
        Path cache = Files.createDirectories(dir.resolve("cache"));

        JUnitLauncher launcher = new JUnitLauncher().withModuleLabel("ex:m");

        assertThatThrownBy(() ->
                        launcher.run(javaHome, classes, List.of(), cache, 2, Map.of(), TestProgressListener.noop()))
                .as("a JVM that named no class is a launcher failure, not a passing suite")
                .isInstanceOfSatisfying(TestLauncherFailure.class, f -> {
                    assertThat(f.moduleLabel()).isEqualTo("ex:m");
                    assertThat(f.phase()).isEqualTo("test discovery");
                    assertThat(f.exit()).isEqualTo(3);
                    assertThat(f.getMessage()).contains("test discovery exited 3 before any test ran");
                    assertThat(f.output()).contains("could not create the Java Virtual Machine");
                });
    }
}
