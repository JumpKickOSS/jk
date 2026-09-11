// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.engine.EngineSpawn.AotMode;
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

    private static EngineTarget jarTarget(Path aot) {
        return new EngineTarget(
                new EngineArtifact(EngineArtifact.Kind.JAR, "/lib/jk-engine-1.jar", "lib"),
                Path.of("/opt/jdk"),
                true,
                aot);
    }

    @Test
    void the_jar_line_exits_on_out_of_memory_and_dumps_the_heap_beside_the_engine_log(@TempDir Path state) {
        EnginePaths.Paths paths = EnginePaths.resolve(state);

        List<String> cmd = EngineSpawn.jarCommand(paths, jarTarget(null), AotMode.NONE, new JkEngineConfig(64));

        assertThat(cmd).containsAll(EngineJvmFlags.AOT_SENSITIVE);
        assertThat(cmd).contains("-XX:+ExitOnOutOfMemoryError", "-XX:+HeapDumpOnOutOfMemoryError");
        assertThat(cmd)
                .as("a directory, so every exit writes its own java_pid<pid>.hprof")
                .contains("-XX:HeapDumpPath=" + state.resolve("engine"));
        assertThat(state.resolve("engine")).isEqualTo(paths.log().getParent());
        assertThat(cmd).contains("-Xmx64m");
        assertThat(cmd).containsSubsequence("-cp", "/lib/jk-engine-1.jar", "cc.jumpkick.engine.EngineMain");
        assertThat(cmd).noneMatch(a -> a.startsWith("-XX:AOTCache") || a.startsWith("-Djk.aot.train.output="));
    }

    @Test
    void the_aot_mode_picks_the_one_cache_flag(@TempDir Path state) {
        EnginePaths.Paths paths = EnginePaths.resolve(state);
        Path aot = state.resolve("engine-1.aot");
        JkEngineConfig uncapped = new JkEngineConfig(0);

        assertThat(EngineSpawn.jarCommand(paths, jarTarget(aot), AotMode.USE, uncapped))
                .contains("-XX:AOTCache=" + aot)
                .noneMatch(a -> a.startsWith("-Xmx"));
        assertThat(EngineSpawn.jarCommand(paths, jarTarget(aot), AotMode.TRAIN, uncapped))
                .contains("-Djk.aot.train.output=" + aot)
                .noneMatch(a -> a.startsWith("-XX:AOTCache"));
    }
}
