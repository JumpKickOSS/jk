// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.EngineJvmFlags;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The sidecar AOT trainer's crash-safe publish: the recording assembles at a temp
 * sibling and only a clean, non-empty result is atomically promoted — a killed trainer (the
 * watchdog uses {@code Runtime.halt}) can never leave a partial file at the final path.
 */
class EngineMainAotTrainerTest {

    @TempDir
    Path tmp;

    @Test
    void trainer_command_assembles_at_the_temp_sibling_never_the_final_path() {
        Path finalPath = tmp.resolve("engine-1.0.0-0123456789abcdef.aot");
        Path tmpOut = EngineMain.trainerTmpPath(finalPath);

        assertThat(tmpOut.getParent()).isEqualTo(finalPath.getParent()); // same fs → atomic move
        assertThat(tmpOut.getFileName().toString())
                .startsWith(finalPath.getFileName().toString() + ".tmp-");

        List<String> serving = new ArrayList<>(EngineJvmFlags.AOT_SENSITIVE);
        serving.addAll(List.of(
                EngineJvmFlags.heapDumpPath(Path.of("/home/x/.jk/state/engine/0123456789abcdef.hprof")),
                "-XX:MaxMetaspaceSize=256m",
                "-Xss512k",
                "-Djk.aot.train.output=" + finalPath,
                "-Xms32m",
                "-Xmx256m",
                "-Djk.home=/home/x/.jk"));
        List<String> cmd = EngineMain.aotTrainerCommand("/opt/jdk/bin/java", serving, "engine.jar", tmpOut);
        assertThat(cmd).contains("-XX:AOTCacheOutput=" + tmpOut);
        assertThat(cmd).doesNotContain("-XX:AOTCacheOutput=" + finalPath);
        // The cache is mapped only under the flag set it was recorded under, heap included, so the
        // trainer runs the serving JVM's own flags — all of them — with only the AOT ones swapped.
        assertThat(cmd).containsAll(EngineJvmFlags.AOT_SENSITIVE);
        // Including the OOM exit + dump pair: a trainer that survived an OutOfMemoryError would
        // publish a cache recorded under a different flag set, or hang the sidecar.
        assertThat(cmd)
                .contains(
                        "-XX:+ExitOnOutOfMemoryError",
                        "-XX:+HeapDumpOnOutOfMemoryError",
                        "-XX:HeapDumpPath=/home/x/.jk/state/engine/0123456789abcdef.hprof");
        assertThat(cmd)
                .contains("-Xms32m", "-Xmx256m", "-XX:MaxMetaspaceSize=256m", "-Xss512k", "-Djk.home=/home/x/.jk");
        assertThat(cmd).noneMatch(a -> a.startsWith("-Djk.aot.train.output="));
        assertThat(cmd).containsSubsequence("-cp", "engine.jar");
        assertThat(cmd.getLast()).isEqualTo("--aot-training");
    }

    @Test
    void the_trainer_drops_every_aot_flag_of_the_serving_line() {
        List<String> args = EngineMain.trainerJvmArgs(List.of(
                "-XX:+UseSerialGC",
                "-XX:AOTCache=/x/e.aot",
                "-XX:AOTMode=auto",
                "-XX:AOTCacheOutput=/x/o.aot",
                "-Xmx256m"));
        assertThat(args).containsExactly("-XX:+UseSerialGC", "-Xmx256m");
    }

    @Test
    void clean_exit_with_a_non_empty_assembly_is_promoted_atomically() throws IOException {
        Path finalPath = tmp.resolve("engine-1.0.0-0123456789abcdef.aot");
        Path tmpOut = EngineMain.trainerTmpPath(finalPath);
        Files.writeString(tmpOut, "assembled-cache");

        EngineMain.promoteTrainedCache(tmpOut, finalPath, 0);

        assertThat(finalPath).exists();
        assertThat(Files.readString(finalPath)).isEqualTo("assembled-cache");
        assertThat(tmpOut).doesNotExist();
    }

    @Test
    void nonzero_exit_discards_the_assembly_and_leaves_no_final_file() throws IOException {
        Path finalPath = tmp.resolve("engine-1.0.0-0123456789abcdef.aot");
        Path tmpOut = EngineMain.trainerTmpPath(finalPath);
        Files.writeString(tmpOut, "partial");
        Files.writeString(tmpOut.resolveSibling(tmpOut.getFileName() + ".config"), "recording");

        EngineMain.promoteTrainedCache(tmpOut, finalPath, 2); // the watchdog halts with 2

        assertThat(finalPath).doesNotExist();
        assertThat(tmpOut).doesNotExist();
        assertThat(tmpOut.resolveSibling(tmpOut.getFileName() + ".config")).doesNotExist();
    }

    @Test
    void empty_or_missing_assembly_is_never_promoted() throws IOException {
        Path finalPath = tmp.resolve("engine-1.0.0-0123456789abcdef.aot");
        Path tmpOut = EngineMain.trainerTmpPath(finalPath);

        EngineMain.promoteTrainedCache(tmpOut, finalPath, 0); // exited clean but assembled nothing
        assertThat(finalPath).doesNotExist();

        Files.createFile(tmpOut); // zero-byte assembly (disk full / torn)
        EngineMain.promoteTrainedCache(tmpOut, finalPath, 0);
        assertThat(finalPath).doesNotExist();
        assertThat(tmpOut).doesNotExist();
    }
}
