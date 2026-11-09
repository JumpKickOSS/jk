// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A worker forked on an older JDK than the engine's gets only the flags that JDK takes. */
class WorkerJvmFlagsTest {

    private static final List<String> ENGINE_CHOSEN = List.of(
            "-XX:AOTCache=/s/java-compiler.aot",
            "-Xlog:aot=off",
            "-Xms64m",
            "-Xmx10108m",
            "-XX:+UseG1GC",
            "-XX:+UseCompactObjectHeaders",
            "--sun-misc-unsafe-memory-access=allow",
            "-XX:TrimNativeHeapInterval=60000");

    @Test
    void a_jdk_21_worker_drops_what_only_newer_jdks_know_and_keeps_the_order_of_the_rest() {
        assertThat(WorkerJvmFlags.forFeature(21, ENGINE_CHOSEN))
                .containsExactly("-Xms64m", "-Xmx10108m", "-XX:+UseG1GC", "-XX:TrimNativeHeapInterval=60000");
    }

    @Test
    void jep_498_arrives_at_23_and_the_aot_flags_and_compact_headers_at_25() {
        assertThat(WorkerJvmFlags.forFeature(23, ENGINE_CHOSEN))
                .contains("--sun-misc-unsafe-memory-access=allow")
                .doesNotContain("-XX:AOTCache=/s/java-compiler.aot", "-XX:+UseCompactObjectHeaders");
        assertThat(WorkerJvmFlags.forFeature(25, ENGINE_CHOSEN)).isEqualTo(ENGINE_CHOSEN);
        assertThat(WorkerJvmFlags.forFeature(27, ENGINE_CHOSEN)).isEqualTo(ENGINE_CHOSEN);
    }

    @Test
    void the_launch_command_reads_the_workers_own_jdk(@TempDir Path home) throws Exception {
        Files.writeString(home.resolve("release"), "JAVA_VERSION=\"21.0.12\"\n");
        List<String> cmd = PluginLoader.command(home, "w.jar", ENGINE_CHOSEN, List.of("--pull"));
        assertThat(cmd).doesNotContain("--sun-misc-unsafe-memory-access=allow", "-Xlog:aot=off");
        assertThat(cmd).contains("-Xmx10108m", "-cp", "w.jar", "--pull");
    }
}
