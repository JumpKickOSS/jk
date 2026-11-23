// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

/** The GC log's largest before-occupancy, not region counts. */
class WorkerGcTest {

    @Test
    void the_largest_before_occupancy_wins_and_region_counts_do_not() {
        String log = """
                [0.145s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 14M->2M(256M) 3.456ms
                [0.200s][info][gc,heap] GC(0) Eden regions: 24->0(24)
                [1.002s][info][gc] GC(1) Pause Young (Normal) (G1 Evacuation Pause) 128M->40M(256M) 8.1ms
                [0.032s][info][gc] GC(2) Garbage Collection (Warmup) 16M(2%)->8M(1%) 1.2ms
                [2.000s][info][gc] GC(3) Pause Young (Allocation Failure) 512K->128K(256M) 1.0ms
                """;
        assertThat(WorkerGc.peak(log)).isEqualTo(128L << 20);
    }

    @Test
    void gigabyte_sizes_and_an_empty_log_are_read() {
        assertThat(WorkerGc.peak("[gc] GC(0) Pause Full (System.gc()) 1.5G->800M(2G) 20ms"))
                .isEqualTo((long) (1.5 * (1L << 30)));
        assertThat(WorkerGc.peak("")).isZero();
        assertThat(WorkerGc.peak((String) null)).isZero();
    }

    /** The JVM's default rotation moved the empty log aside as {@code <log>.0}, which nothing deleted. */
    @Test
    void the_log_flag_turns_rotation_off() {
        Path log = Path.of("/t/gc-1.log");
        assertThat(WorkerGc.flag(log)).endsWith(log.toAbsolutePath() + "::filecount=0");
    }

    @Test
    void the_sweep_drops_day_old_logs_and_keeps_fresh_ones(@TempDir Path dir) throws Exception {
        Path old = Files.createFile(dir.resolve("gc-1.log.0"));
        Path fresh = Files.createFile(dir.resolve("gc-2.log"));
        Path other = Files.createFile(dir.resolve("keep.txt"));
        Instant now = Instant.now();
        FileTime twoDaysAgo = FileTime.from(now.minus(Duration.ofDays(2)));
        Files.setLastModifiedTime(old, twoDaysAgo);
        Files.setLastModifiedTime(other, twoDaysAgo);
        WorkerGc.sweep(dir, now.minus(WorkerGc.STALE));
        assertThat(old).doesNotExist();
        assertThat(fresh).exists();
        assertThat(other).exists();
    }

    /**
     * A worker's own group counts what its children held. The resident peak it teaches is that
     * group's peak less its page cache, not the worker's own high-water mark alone.
     */
    @Test
    @EnabledOnOs(OS.LINUX)
    @ResourceLock("jk-worker-containment")
    void a_worker_teaches_the_anonymous_peak_of_its_own_group(@TempDir Path dir) throws Exception {
        long mib = 1024L * 1024L;
        Path workers = Files.createDirectories(dir.resolve("workers"));
        LearnedHeaps heaps = new LearnedHeaps(dir.resolve("heaps"));
        HeapScope.Key key = new HeapScope.Key(dir.resolve("ws"), "g:app", HeapScope.TEST, 25);
        List<String> command = List.of("/opt/jdk/bin/java", "-cp", "app.jar", "app.Main");
        WorkerGc.Watch watch =
                HeapScope.call(key, () -> WorkerGc.watch(command, JvmOptions.HeapChoice.inspect(command), heaps));
        Process worker = new ProcessBuilder("sleep", "30").start();
        Path own = workers.resolve(WorkerContainment.CHILD_PREFIX + worker.pid());
        try {
            Files.createDirectories(own);
            Files.writeString(own.resolve("memory.max"), "max\n");
            Files.writeString(own.resolve("cgroup.procs"), "");
            WorkerContainment.armForTest(workers, 8192 * mib, 0, true);
            WorkerContainment.contain(worker, 4096 * mib, "test JVM");
            watch.observe(worker);
            worker.destroyForcibly();
            worker.waitFor(5, TimeUnit.SECONDS);
            Files.writeString(own.resolve("memory.peak"), (1024 * mib) + "\n");
            Files.writeString(own.resolve("memory.stat"), "anon 0\nfile " + (256 * mib) + "\n");
            watch.finish();
        } finally {
            worker.destroyForcibly();
            WorkerContainment.resetForTest();
        }
        assertThat(heaps.price(key))
                .as("768 MiB of anonymous peak, leased at 1.25x")
                .isEqualTo(960 * mib);
    }

    /**
     * A JVM that drives engines holds those engines' forks, which no engine of the host leases: the
     * resident peak it teaches counts its process tree, filed under its own module and not its
     * kind's host-wide row, while its heap is learned from its own process alone. A worker that
     * drives none teaches only its own resident set.
     */
    @Test
    @EnabledOnOs(OS.LINUX)
    @ResourceLock("jk-worker-containment")
    void a_jvm_that_drives_engines_teaches_the_peak_of_its_process_tree(@TempDir Path dir) throws Exception {
        long mib = 1024L * 1024L;
        Path sleeper = Files.writeString(dir.resolve("Sleep.java"), """
                class Sleep {
                    public static void main(String[] args) throws Exception {
                        Thread.sleep(60_000);
                    }
                }
                """);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String child = java + " -Xms256m -Xmx256m -XX:+AlwaysPreTouch " + sleeper + " & wait";
        LearnedHeaps driving = new LearnedHeaps(dir.resolve("driving"));
        LearnedHeaps plain = new LearnedHeaps(dir.resolve("plain"));
        HeapScope.Key key = new HeapScope.Key(dir.resolve("ws"), "g:app", HeapScope.TEST, 25);
        HeapScope.Key other = new HeapScope.Key(dir.resolve("ws"), "g:other", HeapScope.TEST, 25);
        List<String> command = List.of("/opt/jdk/bin/java", "-cp", "app.jar", "app.Main");
        JvmOptions.HeapChoice choice = JvmOptions.HeapChoice.inspect(command);
        WorkerGc.Watch drives = HeapScope.call(key, () -> WorkerGc.watch(command, choice, driving, true));
        WorkerGc.Watch alone = HeapScope.call(key, () -> WorkerGc.watch(command, choice, plain, false));
        Process root = new ProcessBuilder("sh", "-c", child).start();
        try {
            awaitResidentChild(root, 256 * mib);
            drives.observe(root);
            alone.observe(root);
            Thread.sleep(500);
        } finally {
            root.descendants().forEach(ProcessHandle::destroyForcibly);
            root.destroyForcibly();
            root.waitFor(5, TimeUnit.SECONDS);
        }
        drives.finish();
        alone.finish();

        assertThat(driving.price(key)).as("its tree, leased at 1.25x").isGreaterThanOrEqualTo(320 * mib);
        assertThat(driving.peak(key)).as("no heap learned from its children").isZero();
        assertThat(driving.price(other))
                .as("its kind's host-wide row left as it was: the prior")
                .isEqualTo(640 * mib);
        assertThat(plain.price(key)).as("the shell alone, at the floor").isEqualTo(160 * mib);
    }

    /** Wait until a descendant of {@code root} holds at least {@code bytes}. */
    private static void awaitResidentChild(Process root, long bytes) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            boolean filled =
                    root.descendants().anyMatch(h -> ProcessMemory.of(h.pid()).residentBytes() >= bytes);
            if (filled) return;
            Thread.sleep(50);
        }
        throw new AssertionError("no child of the shell filled " + bytes + " bytes");
    }
}
