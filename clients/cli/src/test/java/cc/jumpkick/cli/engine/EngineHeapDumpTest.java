// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.wire.EnginePaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
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
    void the_message_names_the_newest_dump_when_one_exists_and_the_rotated_log_when_none_does() throws IOException {
        Path log = tmp.resolve("k.log");
        Path dump = tmp.resolve("java_pid4242.hprof");
        Files.writeString(log, OOM_TAIL);

        Optional<String> withoutDump = EngineHeapDump.exitMessage(log, tmp);
        assertThat(withoutDump).isPresent();
        assertThat(withoutDump.get())
                .startsWith("the build engine exited on OutOfMemoryError; no heap dump was written (see "
                        + tmp.resolve("k.log.1") + ")")
                .endsWith(EngineHeapDump.REMEDY);

        Files.writeString(dump, "HPROF");
        assertThat(EngineHeapDump.exitMessage(log, tmp))
                .contains("the build engine exited on OutOfMemoryError; heap dump at " + dump + "; "
                        + EngineHeapDump.REMEDY);
    }

    @Test
    void find_reports_the_newest_dump_in_the_engine_directory_only_while_one_exists() throws IOException {
        EnginePaths.Paths paths = EnginePaths.resolve(tmp);
        assertThat(EngineHeapDump.find(paths)).isEmpty();
        Files.createDirectories(paths.dir());
        Path older = Files.writeString(paths.dir().resolve("java_pid100.hprof"), "HPROF");
        Path newer = Files.writeString(paths.dir().resolve("java_pid200.hprof"), "HPROF");
        Files.setLastModifiedTime(older, FileTime.fromMillis(1_000_000L));
        Files.setLastModifiedTime(newer, FileTime.fromMillis(2_000_000L));
        Files.writeString(paths.dir().resolve("notes.hprof.txt"), "not a dump");
        assertThat(EngineHeapDump.find(paths)).contains(newer);
        Files.delete(newer);
        assertThat(EngineHeapDump.find(paths)).contains(older);
    }

    @Test
    void the_engine_s_own_exit_flag_in_the_log_is_not_an_exit(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("k.log");
        Files.writeString(
                log,
                "Picked up JAVA_TOOL_OPTIONS: -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError\n"
                        + "engine: listening\n"
                        + "engine: stopped on request\n");
        assertThat(EngineHeapDump.exitedOnOutOfMemory(log)).isFalse();
    }
}
