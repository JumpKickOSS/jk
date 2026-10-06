// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.config.PluginTuning;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.plugin.HeapNotes;
import cc.jumpkick.engine.plugin.HeapScope;
import cc.jumpkick.engine.plugin.LearnedHeaps;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.Task;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A format worker that runs out of its heap climbs the heap ladder and finishes, each file counted
 * once across the attempts, and the next run starts at a heap that fits. A pinned heap is not
 * retried.
 */
class FormatHeapRetryTest {

    @AfterEach
    void reset() {
        HeapNotes.clear();
        SessionContext.reset();
    }

    @Test
    void a_worker_that_outgrows_its_first_heap_finishes_on_one_retry_and_the_next_run_starts_there(@TempDir Path dir)
            throws Exception {
        Path java = fakeJava(dir, "die", 4);
        Path state = dir.resolve("state");
        SessionContext.install(SessionContext.current().withWorkingDir(dir).withJvm(PluginTuning.NONE));
        HeapScope.Key key = FormatWorker.heapKey(dir);
        LearnedHeaps forced = new LearnedHeaps(state, 128L << 20);

        Run first = run(java, forced, key, 4);
        assertThat(first.result().success()).as("%s", first.result().errors()).isTrue();
        assertThat(first.plan().get(FormatWorker.CLEAN))
                .as("the file the dead attempt reported is counted once")
                .contains(4);
        assertThat(first.observed()).hasSize(4);
        assertThat(HeapNotes.drain()).containsExactly("retried with 256 MiB heap after running out of 128 MiB");
        assertThat(Files.readString(dir.resolve("heaps"))).contains("-Xmx128m", "-Xmx256m");

        LearnedHeaps learned = new LearnedHeaps(state);
        assertThat(learned.good(key)).isEqualTo(256L << 20);
        assertThat(learned.choose(key.project(), key.module(), key.kind(), key.jdk(), 128L << 20))
                .isGreaterThanOrEqualTo(256L << 20);

        Files.writeString(dir.resolve("heaps"), "");
        Run second = run(java, learned, key, 4);
        assertThat(second.result().success()).isTrue();
        assertThat(HeapNotes.drain()).isEmpty();
        assertThat(Files.readString(dir.resolve("heaps"))).doesNotContain("-Xmx128m");
    }

    @Test
    void a_heap_that_never_suffices_fails_the_step_naming_every_heap(@TempDir Path dir) throws Exception {
        Path java = fakeJava(dir, "always", 3);
        SessionContext.install(SessionContext.current().withWorkingDir(dir).withJvm(PluginTuning.NONE));
        HeapScope.Key key = FormatWorker.heapKey(dir);

        Run r = run(java, new LearnedHeaps(dir.resolve("state"), 128L << 20), key, 3);
        assertThat(r.result().success()).isFalse();
        assertThat(r.result().errors()).hasSize(1);
        assertThat(r.result().errors().get(0).message())
                .startsWith("format worker ran out of heap at 128 MiB, 256 MiB")
                .contains("[jvm] args");
    }

    @Test
    void a_pinned_heap_is_neither_sized_nor_retried(@TempDir Path dir) throws Exception {
        Path java = fakeJava(dir, "die", 2);
        SessionContext.install(SessionContext.current()
                .withWorkingDir(dir)
                .withJvm(new PluginTuning(null, null, null, List.of("-Xmx128m"))));
        LearnedHeaps heaps = new LearnedHeaps(dir.resolve("state"));
        HeapScope.Key key = FormatWorker.heapKey(dir);
        assertThat(FormatWorker.startHeap(heaps, key)).isNull();

        Run r = run(java, heaps, key, 2);
        assertThat(r.result().success()).isFalse();
        assertThat(HeapNotes.drain()).isEmpty();
        assertThat(Files.readString(dir.resolve("heaps"))).isEqualTo("-Xmx128m\n");
    }

    private record Run(BuildPlan plan, BuildPlanResult result, List<String> observed) {}

    /** One format step over {@code total} files, forking {@code java} at the heap {@code heaps} chooses. */
    private static Run run(Path java, LearnedHeaps heaps, HeapScope.Key key, int total) {
        List<String> observed = new ArrayList<>();
        Long heap = FormatWorker.startHeap(heaps, key);
        List<String> base = heap == null
                ? List.of(java.toString(), "-Xmx128m", "Worker")
                : List.of(java.toString(), "-Xmx64m", "Worker");
        Task format = Task.builder("format")
                .ticks(total)
                .execute(ctx -> FormatWorker.runWorker(
                        ctx,
                        h -> FormatWorker.atHeap(base, h),
                        heap,
                        key,
                        heaps,
                        0,
                        total,
                        false,
                        null,
                        (path, status, msg, index, tot) -> observed.add(path)))
                .build();
        BuildPlan plan = BuildPlan.builder("format")
                .stateKeys(
                        FormatWorker.CHANGED,
                        FormatWorker.CLEAN,
                        FormatWorker.ERRORS,
                        FormatWorker.TOTAL,
                        FormatWorker.WORKER_EXIT)
                .addTask(format)
                .build();
        return new Run(plan, plan.run(), observed);
    }

    /**
     * A {@code java} that reports {@code files} clean files. Below 256 MiB it reports the first and
     * exits 3 as {@code -XX:+ExitOnOutOfMemoryError} does; {@code always} does that at every heap.
     * A heap that suffices logs a 180 MiB peak to the GC log jk asked for.
     */
    private static Path fakeJava(Path dir, String mode, int files) throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "POSIX shell required");
        Path bin = Files.createDirectories(dir.resolve("jdk/bin"));
        Path java = bin.resolve("java");
        String script = """
                #!/bin/sh
                mx=""
                gc=""
                for a in "$@"; do
                  case "$a" in
                    -Xmx*) mx="$a" ;;
                    -Xlog:gc:file=*) gc="${a#-Xlog:gc:file=}" ;;
                  esac
                done
                echo "$mx" >> "DIR/heaps"
                small=0
                case "$mx" in
                  -Xmx128m|-Xmx64m) small=1 ;;
                esac
                if [ "MODE" = "always" ] || [ "$small" = 1 ]; then
                  printf '%s\\n' '##JKFMT:{"t":"file","path":"F0.java","status":"clean"}'
                  echo "Terminating due to java.lang.OutOfMemoryError: Java heap space"
                  exit 3
                fi
                if [ -n "$gc" ]; then
                  printf '%s\\n' '[0.2s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 180M->20M(256M) 1.0ms' > "$gc"
                fi
                i=0
                while [ "$i" -lt FILES ]; do
                  printf '##JKFMT:{"t":"file","path":"F%s.java","status":"clean"}\\n' "$i"
                  i=$((i + 1))
                done
                exit 0
                """;
        Files.writeString(
                java,
                script.replace("DIR", dir.toString()).replace("MODE", mode).replace("FILES", String.valueOf(files)));
        Files.setPosixFilePermissions(
                java,
                Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
        return java;
    }
}
