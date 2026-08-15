// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

        List<String> cmd = EngineMain.aotTrainerCommand("/opt/jdk/bin/java", "engine.jar", tmpOut);
        assertThat(cmd).contains("-XX:AOTCacheOutput=" + tmpOut);
        assertThat(cmd).doesNotContain("-XX:AOTCacheOutput=" + finalPath);
        assertThat(cmd).containsSubsequence("-cp", "engine.jar");
        assertThat(cmd.getLast()).isEqualTo("--aot-training");
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
