// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.engine.EngineSpawn.EngineArtifact;
import cc.jumpkick.cli.engine.EngineSpawn.EngineTarget;
import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.host.EngineJvmFlags;
import cc.jumpkick.wire.EnginePaths;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The installed engine's JVM spawn line: the shared flag list, the heap cap, and the OOM heap dump. */
class EngineSpawnLineTest {

    private static EngineTarget jarTarget() {
        return new EngineTarget(
                new EngineArtifact(EngineArtifact.Kind.JAR, "/lib/jk-engine-1.jar", "lib"), Path.of("/opt/jdk"));
    }

    @Test
    void the_jar_line_exits_on_out_of_memory_and_dumps_the_heap_beside_the_engine_log(@TempDir Path state) {
        EnginePaths.Paths paths = EnginePaths.resolve(state);

        List<String> cmd = EngineSpawn.jarCommand(paths, jarTarget(), new JkEngineConfig(64));

        assertThat(cmd).containsAll(EngineJvmFlags.AOT_SENSITIVE);
        assertThat(cmd).contains("-XX:+ExitOnOutOfMemoryError", "-XX:+HeapDumpOnOutOfMemoryError");
        assertThat(cmd)
                .as("a directory, so every exit writes its own java_pid<pid>.hprof")
                .contains("-XX:HeapDumpPath=" + state.resolve("engine"));
        assertThat(state.resolve("engine")).isEqualTo(paths.log().getParent());
        assertThat(cmd).contains("-Xmx64m");
        assertThat(cmd).containsSubsequence("-cp", "/lib/jk-engine-1.jar", "cc.jumpkick.engine.EngineMain");
    }

    /** The engine JVM starts from its jar and nothing else: no cache flag of any kind. */
    @Test
    void the_jar_line_carries_no_aot_flag(@TempDir Path state) {
        EnginePaths.Paths paths = EnginePaths.resolve(state);
        assertThat(EngineSpawn.jarCommand(paths, jarTarget(), new JkEngineConfig(0)))
                .noneMatch(a -> a.startsWith("-XX:AOT") || a.startsWith("-Djk.aot.train.output="))
                .noneMatch(a -> a.startsWith("-Xmx"));
    }
}
