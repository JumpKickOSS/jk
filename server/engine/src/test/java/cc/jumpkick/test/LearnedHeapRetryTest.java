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
 * A suite whose planned heap is too small climbs the heap ladder until it passes, and the next run
 * starts at the heap that passed. A pinned heap is not retried.
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
        LearnedHeaps learned = new LearnedHeaps(state);
        long next =
                learned.choose(dir, "g:app", HeapScope.TEST, Runtime.version().feature(), 128L << 20);
        assertThat(next).as("at least the heap that passed").isGreaterThanOrEqualTo(256L << 20);
        launcher.withHeaps(learned);
        TestSummary second = launcher.run(home, classes, List.of(), cache, 1, Map.of(), TestProgressListener.noop());
        assertThat(second.failed()).isZero();
        assertThat(second.succeeded()).isEqualTo(1);
        assertThat(HeapNotes.drain()).isEmpty();
        assertThat(Files.readString(home.resolve("heaps")))
                .contains("-Xmx" + (next >> 20) + "m")
                .doesNotContain("-Xmx128m");
        awaitPeaks(state, 3);
    }

    @Test
    void a_suite_that_needs_two_doublings_climbs_the_ladder_and_starts_there_next_time(@TempDir Path dir)
            throws Exception {
        Path home = fakeJdk(dir, "big");
        Path classes = Files.createDirectories(dir.resolve("classes"));
        Path cache = Files.createDirectories(dir.resolve("cache"));
        Path state = dir.resolve("state");
        SessionContext.install(SessionContext.current().withWorkingDir(dir).withJvm(PluginTuning.NONE));
        JUnitLauncher launcher =
                new JUnitLauncher().withModuleLabel("g:app").withHeaps(new LearnedHeaps(state, 128L << 20));
        TestSummary first = launcher.run(home, classes, List.of(), cache, 1, Map.of(), TestProgressListener.noop());
        assertThat(first.failed()).isZero();
        assertThat(first.succeeded()).isEqualTo(1);
        assertThat(HeapNotes.drain())
                .containsExactly(
                        "retried with 256 MiB heap after running out of 128 MiB",
                        "retried with 512 MiB heap after running out of 256 MiB");
        assertThat(Files.readString(home.resolve("heaps"))).contains("-Xmx128m", "-Xmx256m", "-Xmx512m");
        LearnedHeaps learned = new LearnedHeaps(state);
        assertThat(learned.good(new HeapScope.Key(
                        dir, "g:app", HeapScope.TEST, Runtime.version().feature())))
                .isEqualTo(512L << 20);

        Files.writeString(home.resolve("heaps"), "");
        launcher.withHeaps(learned);
        TestSummary second = launcher.run(home, classes, List.of(), cache, 1, Map.of(), TestProgressListener.noop());
        assertThat(second.failed()).isZero();
        assertThat(HeapNotes.drain()).isEmpty();
        assertThat(Files.readString(home.resolve("heaps"))).contains("-Xmx512m").doesNotContain("-Xmx256m");
        awaitPeaks(state, 4);
    }

    @Test
    void a_test_whose_heap_error_escapes_is_rerun_on_the_next_heap(@TempDir Path dir) throws Exception {
        Path home = fakeJdk(dir, "escaped");
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
        awaitPeaks(dir.resolve("state"), 2);
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
        awaitPeaks(dir.resolve("state"), 2);
    }

    @Test
    void a_heap_that_never_suffices_climbs_three_rungs_and_names_every_heap(@TempDir Path dir) throws Exception {
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
                        .contains("ran out of heap at 128 MiB, 256 MiB, 512 MiB and 1.0 GiB;")
                        .contains("[test] jvm-args"));
        assertThat(HeapNotes.drain())
                .containsExactly(
                        "retried with 256 MiB heap after running out of 128 MiB",
                        "retried with 512 MiB heap after running out of 256 MiB",
                        "retried with 1.0 GiB heap after running out of 512 MiB");
    }

    /**
     * Wait until {@code g:app}'s test row holds {@code count} peaks, so a GC log folded in after
     * {@code run} returned does not write into a temp directory that is being deleted.
     */
    private static void awaitPeaks(Path state, int count) throws Exception {
        for (int i = 0; i < 100; i++) {
            if (Files.isDirectory(state)) {
                try (var files = Files.list(state)) {
                    for (Path file : files.toList()) {
                        for (String line : Files.readAllLines(file)) {
                            String[] fields = line.split("\t", -1);
                            if (fields.length > 3
                                    && fields[0].equals("g:app")
                                    && fields[1].equals(HeapScope.TEST)
                                    && fields[3].split(",").length >= count) {
                                return;
                            }
                        }
                    }
                }
            }
            Thread.sleep(50);
        }
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
     * {@code always} does that at every heap. {@code big} dies as {@code die} does below 512 MiB.
     * {@code escaped} reports the test failed with {@code java.lang.OutOfMemoryError: Java heap
     * space} and exits 1, as a test JVM without exit-on-OOM does. A heap of 256 MiB or more passes,
     * except {@code always} and {@code big}.
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
                  -Xmx256m) [ "$mode" = "big" ] && small=1 ;;
                esac
                if [ "$mode" = "escaped" ] && [ "$small" = 1 ]; then
                  printf '%s\\n' '##JKT:{"event":"finished","type":"TEST","status":"FAILED","uniqueId":"[engine:junit-jupiter]/[class:demo.Big]/[method:alloc()]","testClass":"demo.Big","testMethod":"alloc()","duration_ms":1,"throwable":{"class":"java.lang.OutOfMemoryError","message":"Java heap space","stack":""}}'
                  exit 1
                fi
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
