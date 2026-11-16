// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.wire.EnginePaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An engine that runs out of heap cannot speak as it dies, so memory admission keeps what it holds
 * on disk, and the next engine tells it: the jobs live and queued at the last admission or release,
 * and the heap then, but only for an engine that left a heap dump, and once.
 */
class LiveJobsFileTest {

    private static final long MIB = 1L << 20;

    private static EnginePaths.Paths paths(Path dir) {
        return new EnginePaths.Paths(
                "k1",
                dir,
                dir.resolve("s.sock"),
                dir.resolve("l"),
                dir.resolve("p"),
                dir.resolve("log"),
                dir.resolve("t"),
                dir.resolve("h"),
                dir.resolve("ht"));
    }

    /** A pid no process holds: far above any the kernel hands out here. */
    private static final long DEAD = 4_000_000_001L;

    @Test
    void admission_writes_the_jobs_it_holds_and_the_next_engine_names_them(@TempDir Path dir) throws Exception {
        EnginePaths.Paths paths = paths(dir);
        Path file = LiveJobsFile.fileOf(paths, DEAD);
        MemoryAdmissionTest.FakeHeap heap = new MemoryAdmissionTest.FakeHeap(256, 40);
        MemoryAdmission gate = new MemoryAdmission(
                        heap, (kind, d) -> 60 * MIB, () -> 64L << 30, JobEnvelopeQueueTest.PATIENT, () -> 1_000L, 5L)
                .withLiveJobs(new LiveJobsFile(file, DEAD));
        assertThat(gate.admit(7, "test", "/work/app", (a, w) -> {}, () -> false))
                .isEqualTo(MemoryAdmission.Verdict.ADMITTED);
        assertThat(gate.admit(8, "build", "/work/lib", (a, w) -> {}, () -> false))
                .isEqualTo(MemoryAdmission.Verdict.ADMITTED);
        gate.release(8);
        assertThat(Files.readString(file))
                .contains("pid " + DEAD, "live 7 test " + (60 * MIB) + " /work/app")
                .doesNotContain("/work/lib");

        assertThat(LiveJobsFile.previous(paths, 1L))
                .as("no heap dump: the engine stopped some other way, and the file goes")
                .isEmpty();
        assertThat(file).doesNotExist();

        gate.admit(9, "test", "/work/app", (a, w) -> {}, () -> false);
        Files.writeString(dir.resolve("java_pid" + DEAD + ".hprof"), "dump");
        List<String> told = LiveJobsFile.previous(paths, 1L);
        assertThat(told)
                .singleElement()
                .asString()
                .startsWith("jk engine: the previous engine (pid " + DEAD
                        + ") exited on OutOfMemoryError with these jobs live: ")
                .contains("test /work/app (jid 7, 60 MiB estimated)", "test /work/app (jid 9, 60 MiB estimated)")
                .contains("heap 40 MiB of 256 MiB at the last admission");
        assertThat(LiveJobsFile.previous(paths, 1L)).as("told once").isEmpty();
    }

    @Test
    void this_engine_s_file_and_a_running_predecessor_s_are_left_alone(@TempDir Path dir) throws Exception {
        EnginePaths.Paths paths = paths(dir);
        long self = ProcessHandle.current().pid();
        new LiveJobsFile(LiveJobsFile.fileOf(paths, self), self).write(List.of(), List.of(), 0, 0);
        Files.writeString(dir.resolve("java_pid" + self + ".hprof"), "dump");
        assertThat(LiveJobsFile.previous(paths, 1L)).as("a live process's file").isEmpty();
        assertThat(LiveJobsFile.fileOf(paths, self)).exists();
    }
}
