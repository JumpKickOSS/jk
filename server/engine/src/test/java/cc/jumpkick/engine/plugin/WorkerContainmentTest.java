// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Os;
import cc.jumpkick.test.TestLauncherFailure;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

/** Mode detection, the host-budget limits, and a real fork's oom score. */
@ResourceLock("jk-worker-containment")
class WorkerContainmentTest {

    private static final long GIB = 1024L * 1024L * 1024L;

    private static final long SELF = 4242L;

    @TempDir
    Path root;

    @Test
    void budget_reserves_the_larger_of_two_gib_and_ten_percent() {
        long sixteen = 16 * GIB;
        WorkerContainment.Limits wide = Objects.requireNonNull(WorkerContainment.budget(sixteen, -1L));
        assertThat(wide.maxBytes()).isEqualTo(sixteen - 2 * GIB);

        WorkerContainment.Limits capped = Objects.requireNonNull(WorkerContainment.budget(sixteen, 4 * GIB));
        assertThat(capped.maxBytes()).isEqualTo(4 * GIB - 2 * GIB);

        long thirty = 30 * GIB;
        long thirtyMax = thirty - thirty / 10;
        assertThat(Objects.requireNonNull(WorkerContainment.budget(thirty, -1L)).maxBytes())
                .isEqualTo(thirtyMax);
        assertThat(Objects.requireNonNull(WorkerContainment.budget(thirty, Long.MAX_VALUE))
                        .maxBytes())
                .isEqualTo(thirtyMax);

        assertThat(WorkerContainment.budget(GIB, -1L)).isNull();
        assertThat(WorkerContainment.budget(-1L, -1L)).isNull();
    }

    @Test
    void non_linux_is_none_even_when_the_tree_looks_delegated() throws Exception {
        delegated(16 * GIB, "max");
        WorkerContainment.Plan plan = WorkerContainment.plan(root, false, SELF);
        assertThat(plan.mode()).isEqualTo(WorkerContainment.Mode.NONE);
        assertThat(plan.reason()).isEmpty();
    }

    @Test
    void missing_cgroup_v2_mount_is_score_only() throws Exception {
        Files.createDirectories(root.resolve("proc"));
        Files.writeString(root.resolve("proc/meminfo"), meminfo(16 * GIB));
        WorkerContainment.Plan plan = WorkerContainment.plan(root, true, SELF);
        assertThat(plan.mode()).isEqualTo(WorkerContainment.Mode.SCORE_ONLY);
        assertThat(plan.reason()).isEqualTo("cgroup v2 is not mounted");
    }

    @Test
    void a_delegated_private_cgroup_plans_a_workers_group() throws Exception {
        delegated(16 * GIB, "max");
        WorkerContainment.Plan plan = WorkerContainment.plan(root, true, SELF);
        assertThat(plan.mode()).isEqualTo(WorkerContainment.Mode.CGROUP);
        assertThat(plan.moveEngine()).isTrue();
        assertThat(plan.enableMemory()).isTrue();
        assertThat(plan.workers()).isEqualTo(root.resolve("sys/fs/cgroup/jk/workers"));
        WorkerContainment.Limits limits = Objects.requireNonNull(plan.limits());
        assertThat(limits.maxBytes()).isEqualTo(16 * GIB - 2 * GIB);
    }

    @Test
    void an_enclosing_cap_below_mem_total_wins() throws Exception {
        delegated(16 * GIB, Long.toString(4 * GIB));
        WorkerContainment.Plan plan = WorkerContainment.plan(root, true, SELF);
        assertThat(plan.mode()).isEqualTo(WorkerContainment.Mode.CGROUP);
        assertThat(Objects.requireNonNull(plan.limits()).maxBytes()).isEqualTo(2 * GIB);
    }

    @Test
    void memory_already_enabled_skips_the_move() throws Exception {
        delegated(16 * GIB, "max");
        Files.writeString(root.resolve("sys/fs/cgroup/jk/cgroup.subtree_control"), "cpu memory\n");
        Files.writeString(root.resolve("sys/fs/cgroup/jk/cgroup.procs"), "1\n2\n");
        WorkerContainment.Plan plan = WorkerContainment.plan(root, true, SELF);
        assertThat(plan.mode()).isEqualTo(WorkerContainment.Mode.CGROUP);
        assertThat(plan.moveEngine()).isFalse();
        assertThat(plan.enableMemory()).isFalse();
    }

    @Test
    void shared_cgroup_other_missing_controller_and_a_small_host_degrade() throws Exception {
        delegated(16 * GIB, "max");
        Files.writeString(root.resolve("sys/fs/cgroup/jk/cgroup.procs"), SELF + "\n7\n");
        assertThat(WorkerContainment.plan(root, true, SELF).reason()).isEqualTo("other processes share this cgroup");

        Files.writeString(root.resolve("sys/fs/cgroup/jk/cgroup.controllers"), "cpu\n");
        assertThat(WorkerContainment.plan(root, true, SELF).reason())
                .isEqualTo("the memory controller is not delegated");

        Files.writeString(root.resolve("sys/fs/cgroup/jk/cgroup.controllers"), "memory\n");
        Files.writeString(root.resolve("sys/fs/cgroup/jk/cgroup.procs"), "7\n");
        assertThat(WorkerContainment.plan(root, true, SELF).reason())
                .isEqualTo("this process is not a member of its cgroup");

        Files.writeString(root.resolve("proc/meminfo"), meminfo(GIB));
        Files.writeString(root.resolve("sys/fs/cgroup/jk/cgroup.procs"), SELF + "\n");
        assertThat(WorkerContainment.plan(root, true, SELF).reason()).isEqualTo("host memory is below the reserve");

        Files.delete(root.resolve("proc/meminfo"));
        Files.delete(root.resolve("sys/fs/cgroup/jk/memory.max"));
        assertThat(WorkerContainment.plan(root, true, SELF).reason()).isEqualTo("host memory is unknown");

        Files.writeString(root.resolve("proc/self/cgroup"), "0::/../etc\n");
        assertThat(WorkerContainment.plan(root, true, SELF).reason()).isEqualTo("this process has no cgroup");
    }

    @Test
    void an_unwritable_cgroup_cannot_take_a_child() throws Exception {
        Assumptions.assumeFalse(Os.isWindows());
        delegated(16 * GIB, "max");
        Path own = root.resolve("sys/fs/cgroup/jk");
        Files.setPosixFilePermissions(
                own, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
        try {
            assertThat(WorkerContainment.plan(root, true, SELF).reason()).isEqualTo("cannot create a child cgroup");
        } finally {
            Files.setPosixFilePermissions(
                    own,
                    EnumSet.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE));
        }
    }

    @Test
    void sigkill_is_killed_for_memory_when_oom_kill_rose_or_the_group_is_at_max() throws Exception {
        Path workers = root.resolve("workers");
        Files.createDirectories(workers);
        Files.writeString(workers.resolve("memory.events"), "low 0\nhigh 0\nmax 0\noom 0\noom_kill 0\n");
        Files.writeString(workers.resolve("memory.current"), "10\n");
        Files.writeString(workers.resolve("memory.max"), "100\n");
        try {
            assertThat(WorkerContainment.failure(137, "exited 137")).isEqualTo("exited 137");
            WorkerContainment.armForTest(workers, 100, 0);
            assertThat(WorkerContainment.failure(1, "exited 1")).isEqualTo("exited 1");
            assertThat(WorkerContainment.failure(137, "exited 137")).isEqualTo("exited 137");
            Files.writeString(workers.resolve("memory.events"), "oom_kill 2\n");
            assertThat(WorkerContainment.failure(137, "exited 137")).isEqualTo("killed for memory");
            assertThat(WorkerContainment.failure(137, "exited 137")).isEqualTo("exited 137");
            Files.writeString(workers.resolve("memory.current"), "100\n");
            assertThat(WorkerContainment.killedForMemory(137)).isTrue();
            TestLauncherFailure failure = TestLauncherFailure.runner("app", 137, "");
            assertThat(failure.getMessage()).contains("killed for memory").doesNotContain("exited 137");
        } finally {
            WorkerContainment.resetForTest();
        }
    }

    @Test
    void status_phrases() {
        assertThat(new WorkerContainment.Report(WorkerContainment.Mode.NONE, "", -1).text())
                .isEqualTo("none");
        assertThat(new WorkerContainment.Report(WorkerContainment.Mode.SCORE_ONLY, "cannot create a child cgroup", -1)
                        .text())
                .isEqualTo("score-only (cannot create a child cgroup)");
        assertThat(new WorkerContainment.Report(WorkerContainment.Mode.CGROUP, "", 14 * GIB).text())
                .isEqualTo("cgroup (max 14.0 GiB)");
    }

    @Test
    void a_scope_note_is_appended_only_to_score_only() {
        assertThat(WorkerContainment.withScopeNote(
                        WorkerContainment.Mode.SCORE_ONLY, "other processes share this cgroup", "JK_ENGINE_SCOPE=0"))
                .isEqualTo("other processes share this cgroup; JK_ENGINE_SCOPE=0");
        assertThat(WorkerContainment.withScopeNote(
                        WorkerContainment.Mode.SCORE_ONLY, "cannot create a child cgroup", "  "))
                .isEqualTo("cannot create a child cgroup");
        assertThat(WorkerContainment.withScopeNote(WorkerContainment.Mode.CGROUP, "", "stale"))
                .isEmpty();
        assertThat(WorkerContainment.withScopeNote(WorkerContainment.Mode.NONE, "", "JK_ENGINE_SCOPE=0"))
                .isEmpty();
        assertThat(WorkerContainment.withScopeNote(WorkerContainment.Mode.SCORE_ONLY, "", "n".repeat(300)))
                .hasSize(160)
                .endsWith("...");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void a_forked_child_gets_the_raised_oom_score_and_this_process_does_not() throws Exception {
        int before = Integer.parseInt(
                readProc(ProcessHandle.current().pid(), "oom_score_adj").trim());
        Process child = JobWorkers.start(new ProcessBuilder("sleep", "30"));
        try {
            assertThat(Integer.parseInt(readProc(child.pid(), "oom_score_adj").trim()))
                    .isEqualTo(WorkerContainment.OOM_SCORE_ADJ);
            assertThat(Integer.parseInt(readProc(ProcessHandle.current().pid(), "oom_score_adj")
                            .trim()))
                    .isEqualTo(before);
        } finally {
            child.destroyForcibly();
            child.waitFor(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void a_forked_child_lands_in_the_workers_group_when_this_host_delegates() throws Exception {
        WorkerContainment.install();
        WorkerContainment.Report report = WorkerContainment.report();
        Assumptions.assumeTrue(report.mode() == WorkerContainment.Mode.CGROUP, report.text());
        Process child = JobWorkers.start(new ProcessBuilder("sleep", "30"));
        try {
            String cg = readProc(child.pid(), "cgroup");
            String path = "";
            for (String line : cg.split("\n")) {
                if (line.startsWith("0::"))
                    path = line.substring("0::".length()).trim();
            }
            assertThat(path).endsWith("/workers");
            Path group = Path.of("/sys/fs/cgroup" + path);
            assertThat(Long.parseLong(readFile(group.resolve("memory.max")).trim()))
                    .isEqualTo(report.maxBytes());
            assertThat(readFile(group.resolve("memory.high")).trim()).isEqualTo("max");
            Path swap = group.resolve("memory.swap.max");
            if (Files.exists(swap)) assertThat(readFile(swap).trim()).isEqualTo("0");
        } finally {
            child.destroyForcibly();
            child.waitFor(5, TimeUnit.SECONDS);
        }
    }

    /**
     * A child over a small {@code memory.max}, with swap pinned at 0, is SIGKILL'd. This process
     * stays up, and the death is reported as killed for memory. Skips when no memory-delegated
     * cgroup can be created — a shared session cgroup, for example.
     */
    @Test
    @EnabledOnOs(OS.LINUX)
    @Timeout(90)
    void a_memory_hog_in_a_delegated_group_is_killed_for_memory() throws Exception {
        HogBench bench = HogBench.open();
        Assumptions.assumeTrue(bench != null, "no delegated memory cgroup");
        Path err = Files.createTempFile("jk-hog-", ".log");
        Process hog = null;
        try {
            long seen = oomKills(bench.dir);
            WorkerContainment.armForTest(bench.dir, bench.maxBytes, seen);
            hog = JobWorkers.start(hogCommand(err));
            boolean placed = awaitPlacement(bench, hog);
            if (hog.isAlive() && !placed) {
                hog.destroyForcibly();
                hog.waitFor(5, TimeUnit.SECONDS);
                assertThat(placed).as(groupState(bench, hog, err)).isTrue();
            }
            assertThat(awaitKill(bench, hog, seen))
                    .as(groupState(bench, hog, err))
                    .isTrue();
            int exit = hog.exitValue();
            String how = awaitKilledForMemory(exit);
            assertThat(exit).as(groupState(bench, hog, err)).isEqualTo(WorkerContainment.SIGKILL_EXIT);
            assertThat(how).as(groupState(bench, hog, err)).isEqualTo(WorkerContainment.KILLED_FOR_MEMORY);
            assertThat(ProcessHandle.current().isAlive()).isTrue();
            assertThat(selfCgroup()).isNotEqualTo(bench.dir);
        } finally {
            if (hog != null) {
                hog.destroyForcibly();
                hog.waitFor(5, TimeUnit.SECONDS);
            }
            bench.close();
            Files.deleteIfExists(err);
            WorkerContainment.resetForTest();
            WorkerContainment.install();
        }
    }

    /** A writable cgroup that contains only {@link #SELF}, with {@code memory} available to enable. */
    private void delegated(long memTotalBytes, String memoryMax) throws Exception {
        Path own = root.resolve("sys/fs/cgroup/jk");
        Files.createDirectories(own);
        Files.createDirectories(root.resolve("proc/self"));
        Files.writeString(root.resolve("sys/fs/cgroup/cgroup.controllers"), "cpuset cpu io memory\n");
        Files.writeString(root.resolve("proc/self/cgroup"), "0::/jk\n");
        Files.writeString(root.resolve("proc/meminfo"), meminfo(memTotalBytes));
        Files.writeString(own.resolve("cgroup.controllers"), "cpu memory pids\n");
        Files.writeString(own.resolve("cgroup.subtree_control"), "");
        Files.writeString(own.resolve("cgroup.procs"), SELF + "\n");
        Files.writeString(own.resolve("memory.max"), memoryMax + "\n");
    }

    private static String meminfo(long bytes) {
        return "MemTotal:       " + (bytes / 1024) + " kB\n";
    }

    private static String readProc(long pid, String name) throws Exception {
        return readFile(Path.of("/proc", Long.toString(pid), name));
    }

    private static String readFile(Path file) throws Exception {
        try (InputStream in = Files.newInputStream(file)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static ProcessBuilder hogCommand(Path err) {
        return new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-Xms32m",
                        "-Xmx1g",
                        "-cp",
                        hogClasspath(),
                        MemoryHog.class.getName())
                .redirectOutput(err.toFile())
                .redirectError(err.toFile());
    }

    /** The move is synchronous, so this is short. An uncontained hog is stopped by the caller. */
    private static boolean awaitPlacement(HogBench bench, Process hog) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (hog.isAlive() && System.nanoTime() < deadline) {
            if (inGroup(hog.pid(), bench.dir)) return true;
            Thread.sleep(20);
        }
        return !hog.isAlive() || inGroup(hog.pid(), bench.dir);
    }

    /**
     * Polls {@code memory.events} {@code oom_kill} and process exit until one lands. A risen counter
     * is given a few seconds to reap; the whole wait is one minute.
     */
    private static boolean awaitKill(HogBench bench, Process hog, long seen) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            if (!hog.isAlive()) return true;
            if (oomKills(bench.dir) > seen && hog.waitFor(5, TimeUnit.SECONDS)) return true;
            Thread.sleep(50);
        }
        return !hog.isAlive();
    }

    /** The counter can land just after the exit status. */
    private static String awaitKilledForMemory(int exit) throws Exception {
        String how = "exited " + exit;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!WorkerContainment.KILLED_FOR_MEMORY.equals(how) && System.nanoTime() < deadline) {
            how = WorkerContainment.failure(exit, "exited " + exit);
            if (!WorkerContainment.KILLED_FOR_MEMORY.equals(how)) Thread.sleep(50);
        }
        return how;
    }

    /**
     * The directory that contains {@link MemoryHog}, not the whole test classpath. A fat classpath
     * makes the hog JVM larger than the cgroup cap before it allocates.
     */
    private static String hogClasspath() {
        var url = MemoryHog.class.getResource("MemoryHog.class");
        if (url != null && "file".equals(url.getProtocol())) {
            try {
                Path classes = Path.of(url.toURI());
                for (int i = 0; i < 5 && classes != null; i++) classes = classes.getParent();
                if (classes != null) return classes.toString();
            } catch (Exception ignored) {
                // the full classpath still names the class
            }
        }
        return System.getProperty("java.class.path");
    }

    private static String groupState(HogBench bench, Process hog, Path err) {
        StringBuilder sb = new StringBuilder();
        sb.append("hog pid ").append(hog.pid());
        sb.append(" alive=").append(hog.isAlive());
        try {
            sb.append(" cgroup=").append(readProc(hog.pid(), "cgroup").trim());
        } catch (Exception e) {
            sb.append(" cgroup unread");
        }
        for (String name : List.of("memory.max", "memory.high", "memory.current", "memory.swap.max", "memory.events")) {
            String text = readQuiet(bench.dir.resolve(name));
            sb.append('\n').append(name).append('=').append(text == null ? "absent" : text.trim());
        }
        sb.append("\nlog=").append(hogLog(err));
        return sb.toString();
    }

    private static String hogLog(Path err) {
        try {
            String text = Files.readString(err).trim();
            return text.isEmpty() ? "hog produced no output" : text;
        } catch (IOException e) {
            return e.getMessage() == null ? "hog log unreadable" : e.getMessage();
        }
    }

    private static long oomKills(Path dir) throws Exception {
        String text = readQuiet(dir.resolve("memory.events"));
        if (text == null) return 0L;
        for (String line : text.split("\n")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 2 && "oom_kill".equals(parts[0])) return Long.parseLong(parts[1]);
        }
        return 0L;
    }

    private static boolean inGroup(long pid, Path group) {
        try {
            return group.equals(cgroupDir(readProc(pid, "cgroup")));
        } catch (Exception e) {
            return false;
        }
    }

    private static @Nullable Path selfCgroup() {
        try {
            return cgroupDir(readFile(Path.of("/proc/self/cgroup")));
        } catch (Exception e) {
            return null;
        }
    }

    private static @Nullable Path cgroupDir(String text) {
        for (String line : text.split("\n")) {
            if (!line.startsWith("0::")) continue;
            String rel = line.substring("0::".length()).trim();
            if (rel.startsWith("/")) rel = rel.substring(1);
            if (rel.isEmpty()) return Path.of("/sys/fs/cgroup");
            return Path.of("/sys/fs/cgroup").resolve(rel).normalize();
        }
        return null;
    }

    private static @Nullable String readQuiet(Path file) {
        try {
            return readFile(file);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean containsWord(@Nullable String text, String word) {
        if (text == null || text.isBlank()) return false;
        for (String w : text.trim().split("\\s+")) {
            if (word.equals(w)) return true;
        }
        return false;
    }

    private static void writeCgroup(Path file, String text) throws IOException {
        try (var out = Files.newOutputStream(file, StandardOpenOption.WRITE)) {
            out.write((text.endsWith("\n") ? text : text + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * A private memory cgroup for the hog. Created under a parent that already delegates memory, or
     * borrowed from an empty workers group this process just set up. {@link #close} puts it back.
     */
    private static final class HogBench implements AutoCloseable {
        /** Small enough that the hog's first dirty chunks cross it. Swap is pinned at 0. */
        private static final long MAX = 64L * 1024 * 1024;

        final Path dir;
        final long maxBytes = MAX;
        private final boolean created;
        private final @Nullable String savedMax;
        private final @Nullable String savedSwap;

        private HogBench(Path dir, boolean created, @Nullable String savedMax, @Nullable String savedSwap) {
            this.dir = dir;
            this.created = created;
            this.savedMax = savedMax;
            this.savedSwap = savedSwap;
        }

        static @Nullable HogBench open() throws Exception {
            HogBench created = createPrivate();
            if (created != null) return created;
            WorkerContainment.install();
            created = createPrivate();
            if (created != null) return created;
            return borrow();
        }

        @Override
        public void close() throws IOException {
            if (created) {
                for (int i = 0; i < 20; i++) {
                    try {
                        Files.deleteIfExists(dir);
                        return;
                    } catch (IOException e) {
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
                return;
            }
            if (savedMax != null) writeCgroup(dir.resolve("memory.max"), savedMax.trim());
            if (savedSwap != null) writeCgroup(dir.resolve("memory.swap.max"), savedSwap.trim());
        }

        private static @Nullable HogBench createPrivate() {
            Path self = selfCgroup();
            if (self == null) return null;
            HogBench underSelf = mkdir(self);
            if (underSelf != null) return underSelf;
            return mkdir(self.getParent());
        }

        private static @Nullable HogBench mkdir(@Nullable Path parent) {
            if (parent == null || !Files.isWritable(parent)) return null;
            if (!containsWord(readQuiet(parent.resolve("cgroup.subtree_control")), "memory")) return null;
            Path dir = parent.resolve(
                    "jk-hog-" + ProcessHandle.current().pid() + "-" + Long.toHexString(System.nanoTime()));
            try {
                Files.createDirectory(dir);
                writeCgroup(dir.resolve("memory.max"), Long.toString(MAX));
                try {
                    writeCgroup(dir.resolve("memory.swap.max"), "0");
                } catch (IOException ignored) {
                    // No swap controller: the hard cap already cannot spill.
                }
                return new HogBench(dir, true, null, null);
            } catch (IOException e) {
                try {
                    Files.deleteIfExists(dir);
                } catch (IOException ignored) {
                    // the next open uses a fresh name
                }
                return null;
            }
        }

        /** An empty workers group this process owns, with its limits saved so {@link #close} restores them. */
        private static @Nullable HogBench borrow() throws Exception {
            if (WorkerContainment.report().mode() != WorkerContainment.Mode.CGROUP) return null;
            Path workers = WorkerContainment.workersGroup();
            Path self = selfCgroup();
            if (workers == null || workers.equals(self)) return null;
            String procs = readQuiet(workers.resolve("cgroup.procs"));
            if (procs == null || !procs.isBlank()) return null;
            String max = readQuiet(workers.resolve("memory.max"));
            String swap = readQuiet(workers.resolve("memory.swap.max"));
            writeCgroup(workers.resolve("memory.max"), Long.toString(MAX));
            if (swap != null) writeCgroup(workers.resolve("memory.swap.max"), "0");
            return new HogBench(workers, false, max, swap);
        }
    }
}
