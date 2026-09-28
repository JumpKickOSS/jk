// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.PluginTuning;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.plugin.HeapNotes;
import cc.jumpkick.engine.plugin.HeapScope;
import cc.jumpkick.engine.plugin.LearnedHeaps;
import cc.jumpkick.run.TestSummary;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A suite whose first planned heap is too small passes on one retry, and the next run uses the
 * peak that retry recorded. A pinned heap is not retried.
 */
class LearnedHeapRetryTest {

    @AfterEach
    void reset() {
        HeapNotes.clear();
        SessionContext.reset();
    }

    @Test
    void a_suite_that_outgrows_its_first_heap_passes_on_one_retry_and_the_next_run_needs_none(@TempDir Path dir)
            throws Exception {
        Path home = fakeJdk(dir);
        Path classes = Files.createDirectories(dir.resolve("classes"));
        Path cache = Files.createDirectories(dir.resolve("cache"));
        Path state = dir.resolve("state");
        SessionContext.install(SessionContext.current().withWorkingDir(dir).withJvm(PluginTuning.NONE));
        LearnedHeaps forced = new LearnedHeaps(state, 128L << 20);
        JUnitLauncher launcher = new JUnitLauncher().withModuleLabel("g:app").withHeaps(forced);
        TestSummary first = launcher.run(home, classes, List.of(), cache, 1, Map.of(), TestProgressListener.noop());
        assertThat(first.failed()).isZero();
        assertThat(first.succeeded()).isEqualTo(1);
        assertThat(HeapNotes.drain()).containsExactly("retried with 256 MiB heap after running out of 128 MiB");
        String heaps = Files.readString(home.resolve("heaps"));
        assertThat(heaps).contains("-Xmx128m", "-Xmx256m");
        long peak = awaitPeak(forced, dir, 180L << 20);
        assertThat(peak).isEqualTo(180L << 20);

        Files.writeString(home.resolve("heaps"), "");
        launcher.withHeaps(new LearnedHeaps(state));
        TestSummary second = launcher.run(home, classes, List.of(), cache, 1, Map.of(), TestProgressListener.noop());
        assertThat(second.failed()).isZero();
        assertThat(second.succeeded()).isEqualTo(1);
        assertThat(HeapNotes.drain()).isEmpty();
        assertThat(Files.readString(home.resolve("heaps"))).contains("-Xmx256m").doesNotContain("-Xmx128m");
    }

    @Test
    void a_pinned_heap_is_not_retried_and_the_message_names_it(@TempDir Path dir) throws Exception {
        Path home = fakeJdk(dir);
        Path classes = Files.createDirectories(dir.resolve("classes"));
        Path cache = Files.createDirectories(dir.resolve("cache"));
        LearnedHeaps heaps = new LearnedHeaps(dir.resolve("state"));
        SessionContext.install(SessionContext.current().withWorkingDir(dir).withJvm(PluginTuning.NONE));
        JUnitLauncher launcher = new JUnitLauncher()
                .withModuleLabel("g:app")
                .withJvmArgs(List.of("-Xmx64m"))
                .withHeaps(heaps);
        assertThatThrownBy(
                        () -> launcher.run(home, classes, List.of(), cache, 1, Map.of(), TestProgressListener.noop()))
                .isInstanceOfSatisfying(TestLauncherFailure.class, failure -> assertThat(failure.getMessage())
                        .contains("pinned heap -Xmx64m")
                        .contains("[test] jvm-args"));
        assertThat(HeapNotes.drain()).isEmpty();
        assertThat(Files.readString(home.resolve("heaps"))).contains("-Xmx64m").doesNotContain("-Xmx128m");
        assertThat(heaps.peak(dir, "g:app", HeapScope.TEST, Runtime.version().feature()))
                .isZero();
    }

    @Test
    void a_suite_that_dies_after_a_passing_test_is_retried_once(@TempDir Path dir) throws Exception {
        Path home = fakeJdk(dir, "after");
        Path classes = Files.createDirectories(dir.resolve("classes"));
        Path cache = Files.createDirectories(dir.resolve("cache"));
        SessionContext.install(SessionContext.current().withWorkingDir(dir).withJvm(PluginTuning.NONE));
        JUnitLauncher launcher = new JUnitLauncher()
                .withModuleLabel("g:app")
                .withHeaps(new LearnedHeaps(dir.resolve("state"), 128L << 20));
        TestSummary first = launcher.run(home, classes, List.of(), cache, 1, Map.of(), TestProgressListener.noop());
        assertThat(first.failed()).isZero();
        assertThat(first.succeeded()).isEqualTo(1);
        assertThat(HeapNotes.drain()).containsExactly("retried with 256 MiB heap after running out of 128 MiB");
        assertThat(Files.readString(home.resolve("heaps"))).contains("-Xmx128m", "-Xmx256m");
    }

    @Test
    void a_second_heap_exhaustion_names_both_heaps(@TempDir Path dir) throws Exception {
        Path home = fakeJdk(dir, "always");
        Path classes = Files.createDirectories(dir.resolve("classes"));
        Path cache = Files.createDirectories(dir.resolve("cache"));
        SessionContext.install(SessionContext.current().withWorkingDir(dir).withJvm(PluginTuning.NONE));
        JUnitLauncher launcher = new JUnitLauncher()
                .withModuleLabel("g:app")
                .withHeaps(new LearnedHeaps(dir.resolve("state"), 128L << 20));
        assertThatThrownBy(
                        () -> launcher.run(home, classes, List.of(), cache, 1, Map.of(), TestProgressListener.noop()))
                .isInstanceOfSatisfying(TestLauncherFailure.class, failure -> assertThat(failure.getMessage())
                        .contains("128 MiB")
                        .contains("256 MiB")
                        .contains("[test] jvm-args"));
        assertThat(HeapNotes.drain()).containsExactly("retried with 256 MiB heap after running out of 128 MiB");
    }

    /** The GC log is folded in when the process exits, which can land just after {@code run} returns. */
    private static long awaitPeak(LearnedHeaps heaps, Path project, long atLeast) throws InterruptedException {
        long peak = 0;
        for (int i = 0; i < 50; i++) {
            peak = heaps.peak(
                    project, "g:app", HeapScope.TEST, Runtime.version().feature());
            if (peak >= atLeast) return peak;
            Thread.sleep(20);
        }
        return peak;
    }

    /** A {@code java} that exits 3 below 256 MiB and passes, logging a 180 MiB peak, at 256 MiB. */
    private static Path fakeJdk(Path dir) throws Exception {
        return fakeJdk(dir, "die");
    }

    /**
     * {@code die} exits 3 before any test. {@code after} reports one success, then exits 3.
     * {@code always} does that at every heap. A heap of 256 MiB or more passes, except {@code always}.
     */
    private static Path fakeJdk(Path dir, String mode) throws Exception {
        Path home = dir.resolve("jdk");
        Path bin = Files.createDirectories(home.resolve("bin"));
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
                echo "$mx" >> "$(dirname "$0")/../heaps"
                mode="MODE"
                small=0
                case "$mx" in
                  -Xmx128m|-Xmx64m) small=1 ;;
                esac
                if [ "$mode" = "always" ] || [ "$small" = 1 ]; then
                  if [ "$mode" = "after" ] || [ "$mode" = "always" ]; then
                    printf '%s\\n' '##JKT:{"event":"finished","type":"TEST","status":"SUCCESSFUL","id":"[engine:junit-jupiter]/[class:demo.Big]/[method:alloc()]","display":"alloc()","duration_ms":1}'
                  fi
                  echo "Terminating due to java.lang.OutOfMemoryError: Java heap space"
                  exit 3
                fi
                if [ -n "$gc" ]; then
                  printf '%s\\n' '[0.2s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 180M->20M(256M) 1.0ms' > "$gc"
                fi
                printf '%s\\n' '##JKT:{"event":"finished","type":"TEST","status":"SUCCESSFUL","id":"[engine:junit-jupiter]/[class:demo.Big]/[method:alloc()]","display":"alloc()","duration_ms":1}'
                exit 0
                """;
        Files.writeString(java, script.replace("MODE", mode));
        Files.setPosixFilePermissions(
                java,
                Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
        return home;
    }
}
