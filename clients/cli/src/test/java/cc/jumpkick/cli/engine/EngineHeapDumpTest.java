// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.wire.EnginePaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** How an OutOfMemoryError exit is recognised from the engine log and reported. */
class EngineHeapDumpTest {

    @TempDir
    Path tmp;

    private static final String OOM_TAIL = "java.lang.OutOfMemoryError: Java heap space\n"
            + "Dumping heap to /x/engine/abc.hprof ...\n"
            + "Heap dump file created [12345678 bytes in 0.412 secs]\n"
            + "Terminating due to java.lang.OutOfMemoryError: Java heap space\n";

    @Test
    void an_oom_exit_is_read_from_the_tail_of_a_long_log() throws IOException {
        Path log = tmp.resolve("k.log");
        Files.writeString(log, "jk engine: serving\n".repeat(20_000) + OOM_TAIL);
        assertThat(EngineHeapDump.exitedOnOutOfMemory(log)).isTrue();
    }

    @Test
    void a_clean_or_missing_log_is_not_an_oom_exit() throws IOException {
        Path log = tmp.resolve("k.log");
        assertThat(EngineHeapDump.exitedOnOutOfMemory(log)).isFalse();
        Files.writeString(log, "jk engine: serving\njk engine: stopped\n");
        assertThat(EngineHeapDump.exitedOnOutOfMemory(log)).isFalse();
    }

    @Test
    void the_message_names_the_dump_when_it_exists_and_the_rotated_log_when_it_does_not() throws IOException {
        Path log = tmp.resolve("k.log");
        Path dump = tmp.resolve("k.hprof");
        Files.writeString(log, OOM_TAIL);

        Optional<String> withoutDump = EngineHeapDump.exitMessage(log, dump);
        assertThat(withoutDump).isPresent();
        assertThat(withoutDump.get())
                .startsWith("the build engine exited on OutOfMemoryError; no heap dump was written (see "
                        + tmp.resolve("k.log.1") + ")")
                .endsWith(EngineHeapDump.REMEDY);

        Files.writeString(dump, "HPROF");
        assertThat(EngineHeapDump.exitMessage(log, dump))
                .contains("the build engine exited on OutOfMemoryError; heap dump at " + dump + "; "
                        + EngineHeapDump.REMEDY);
    }

    @Test
    void find_reports_the_dump_sibling_of_the_log_only_while_it_exists() throws IOException {
        EnginePaths.Paths paths = EnginePaths.resolve(tmp);
        assertThat(EngineHeapDump.find(paths)).isEmpty();
        Files.createDirectories(paths.dir());
        Files.writeString(EnginePaths.heapDump(paths), "HPROF");
        assertThat(EngineHeapDump.find(paths)).contains(paths.dir().resolve(paths.key() + ".hprof"));
    }
}
