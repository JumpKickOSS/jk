// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A test JVM is started with the snapshot agent only when its JDK can be debugged and its own
 * arguments do not load an agent already; the launcher adds the flags to the JVMs that run tests
 * and never under {@code --debug-jvm}.
 */
class FailureSnapshotFlagsTest {

    private static Path jdk(Path dir, String modules) throws IOException {
        Files.writeString(dir.resolve("release"), "JAVA_VERSION=\"25\"\nMODULES=\"" + modules + "\"\n");
        return dir;
    }

    @Test
    void a_full_jdk_gets_the_agent_the_export_and_the_property(@TempDir Path dir) throws IOException {
        Path home = jdk(dir, "java.base java.management jdk.jdi jdk.jdwp.agent");
        assertThat(FailureSnapshotFlags.flags(home, List.of("-Xmx512m")))
                .containsExactly(
                        FailureSnapshotFlags.AGENT,
                        FailureSnapshotFlags.EXPORTS,
                        "-D" + FailureSnapshotFlags.PROPERTY + "=true");
        assertThat(FailureSnapshotFlags.AGENT).contains("address=127.0.0.1:0").contains("suspend=n");
    }

    @Test
    void a_runtime_without_the_debugger_modules_or_a_release_file_gets_none(@TempDir Path dir) throws IOException {
        assertThat(FailureSnapshotFlags.flags(dir, List.of())).isEmpty();
        assertThat(FailureSnapshotFlags.flags(jdk(dir, "java.base jdk.jdwp.agent"), List.of()))
                .isEmpty();
        assertThat(FailureSnapshotFlags.flags(null, List.of())).isEmpty();
    }

    @Test
    void jvm_args_that_load_their_own_agent_keep_it_alone(@TempDir Path dir) throws IOException {
        Path home = jdk(dir, "java.base jdk.jdi jdk.jdwp.agent");
        assertThat(FailureSnapshotFlags.flags(home, List.of("-agentlib:jdwp=transport=dt_socket,server=y")))
                .isEmpty();
    }
}
