// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.run.TestSummary;
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
 * — has said nothing about the tests, and nothing is not a green suite: the run must fail the same
 * way a crashed pool worker fails it, or the build stores a green stamp for tests that never ran.
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

        TestSummary result = new JUnitLauncher()
                .withModuleLabel("ex:m")
                .run(javaHome, classes, List.of(), cache, 2, Map.of(), TestProgressListener.noop());

        assertThat(result.allPassed())
                .as("a JVM that named no class is not a passing suite")
                .isFalse();
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.failures()).singleElement().satisfies(f -> {
            assertThat(f.module()).isEqualTo("ex:m");
            assertThat(f.method()).isEqualTo("(test run)");
            assertThat(f.message()).contains("exited 3");
            assertThat(f.stack()).contains("could not create the Java Virtual Machine");
        });
    }
}
