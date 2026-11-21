// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.Processes;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class FileLocksTest {

    @Test
    void a_hold_refuses_a_second_taker_until_it_closes(@TempDir Path dir) throws Exception {
        Path lock = dir.resolve("state/build.lock");
        FileLocks.Hold first = (FileLocks.Hold) FileLocks.tryHold(lock);
        first.write("pid=1\nbuild=7\n");
        assertThat(FileLocks.tryHold(lock)).as("held").isInstanceOf(FileLocks.Held.class);
        assertThat(FileLocks.describeHolder(lock)).contains("build=7");
        first.close();
        assertThat(FileLocks.describeHolder(lock))
                .as("the description leaves with the hold")
                .isEmpty();
        FileLocks.Probe second = FileLocks.tryHold(lock);
        assertThat(second).isInstanceOf(FileLocks.Hold.class);
        ((FileLocks.Hold) second).close();
        assertThat(lock).as("the lock file stays for the next taker").exists();
    }

    @Test
    void files_nobody_holds_are_forgotten_and_a_held_one_is_not(@TempDir Path dir) throws Exception {
        Path kept = dir.resolve("kept.lock");
        FileLocks.Hold hold = (FileLocks.Hold) FileLocks.tryHold(kept);
        for (int i = 0; i < 2 * FileLocks.ENTRIES_KEPT; i++) {
            FileLocks.withLock(dir.resolve("ledger-" + i + ".lock"), () -> {});
        }

        assertThat(FileLocks.entries())
                .as("the entries stay bounded however many files were locked")
                .isLessThanOrEqualTo(FileLocks.ENTRIES_KEPT + 2);
        assertThat(FileLocks.tryHold(kept))
                .as("the held file kept its entry: a second taker is refused")
                .isInstanceOf(FileLocks.Held.class);
        hold.close();
    }

    @Test
    void a_file_stays_exclusive_while_entries_are_dropped_around_it(@TempDir Path dir) throws Exception {
        Path hot = dir.resolve("hot.lock");
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();
        List<Thread> takers = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            takers.add(Thread.ofPlatform().start(() -> {
                for (int i = 0; i < 300; i++) {
                    try {
                        FileLocks.withLock(hot, () -> {
                            most.accumulateAndGet(inside.incrementAndGet(), Math::max);
                            Thread.yield();
                            inside.decrementAndGet();
                        });
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                }
            }));
        }
        for (int i = 0; i < 3 * FileLocks.ENTRIES_KEPT; i++) {
            FileLocks.withLock(dir.resolve("churn-" + i + ".lock"), () -> {});
        }
        for (Thread t : takers) assertThat(t.join(Duration.ofSeconds(60))).isTrue();

        assertThat(most).as("never two bodies on one file at once").hasValue(1);
    }

    @Test
    void with_lock_runs_the_body_and_returns_its_value(@TempDir Path dir) throws Exception {
        Path lock = dir.resolve("ledger.toml.lock");
        int value = FileLocks.withLock(lock, () -> 42);
        assertThat(value).isEqualTo(42);
    }

    /**
     * A probe from inside this JVM — a second taker, a re-entered body — must not open and close a
     * channel of its own: on POSIX that would release the lock the first taker holds. The forked
     * process is the judge; it is refused while the hold is open and again inside the re-entered
     * body.
     */
    @Test
    void a_same_process_probe_keeps_the_os_lock(@TempDir Path dir) throws Exception {
        Path lock = dir.resolve("build.lock");
        FileLocks.Hold hold = (FileLocks.Hold) FileLocks.tryHold(lock);
        try {
            assertThat(FileLocks.tryHold(lock)).isInstanceOf(FileLocks.Held.class);
            assertThat(FileLocks.tryHold(lock)).isInstanceOf(FileLocks.Held.class);
            assertThat(probeFromAnotherProcess(lock)).isEqualTo("held");
        } finally {
            hold.close();
        }
        assertThat(probeFromAnotherProcess(lock)).isEqualTo("free");

        String seen = FileLocks.withLock(lock, () -> FileLocks.withLock(lock, () -> probeFromAnotherProcess(lock)));
        assertThat(seen).as("re-entry keeps the OS lock").isEqualTo("held");
        assertThat(probeFromAnotherProcess(lock)).isEqualTo("free");
    }

    /** Describing the holder must not open a second channel: that close drops the POSIX lock. */
    @Test
    void describing_the_holder_keeps_the_os_lock(@TempDir Path dir) throws Exception {
        Path lock = dir.resolve("build.lock");
        FileLocks.Hold hold = (FileLocks.Hold) FileLocks.tryHold(lock);
        hold.write("pid=1\nbuild=7\n");
        try {
            assertThat(FileLocks.describeHolder(lock)).contains("build=7");
            assertThat(probeFromAnotherProcess(lock)).isEqualTo("held");
        } finally {
            hold.close();
        }
        assertThat(probeFromAnotherProcess(lock)).isEqualTo("free");
    }

    /**
     * A thread describing the holder while another takes the lock must not drop that thread's POSIX
     * lock by closing a descriptor of its own. The take is made while the describer is inside its read
     * of the file, widened by a large file: a describer that opened the file with no JVM lock held
     * closed it after the take and released the new hold. A forked judge probes after each take.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void describing_the_holder_while_another_thread_takes_the_lock_keeps_the_os_lock(@TempDir Path dir)
            throws Exception {
        Path lock = Files.createFile(dir.resolve("build.lock"));
        byte[] large = new byte[LARGE_DESCRIPTION];
        Arrays.fill(large, (byte) 'x');
        Process judge = start(javaCommand(ProbeLoopMain.class, lock.toString()));
        try (BufferedReader answers =
                        new BufferedReader(new InputStreamReader(judge.getInputStream(), StandardCharsets.UTF_8));
                Writer probes = new OutputStreamWriter(judge.getOutputStream(), StandardCharsets.UTF_8)) {
            int caught = 0;
            for (int attempt = 0; caught < 5; attempt++) {
                assertThat(attempt)
                        .as("takes made while the describer was reading")
                        .isLessThan(100);
                Files.write(lock, large); // no hold is open, so this descriptor's close drops nothing
                Thread describer = Thread.ofPlatform().daemon().start(() -> FileLocks.describeHolder(lock));
                if (!seenReadingTheFile(describer)) continue; // finished unseen; no take was made
                FileLocks.Hold hold = takeWhileDescribed(lock);
                try {
                    describer.join(Duration.ofSeconds(10));
                    probes.write("probe\n");
                    probes.flush();
                    assertThat(answers.readLine())
                            .as("judge's view after take " + caught)
                            .isEqualTo("held");
                } finally {
                    hold.close();
                }
                caught++;
            }
        } finally {
            judge.destroy();
            judge.waitFor();
        }
    }

    /** Bytes in the lock file, so that {@link #seenReadingTheFile} catches the describer reading. */
    private static final int LARGE_DESCRIPTION = 32 << 20;

    /**
     * Whether {@code describer} was seen inside {@link Files#readString} before it finished: a take
     * made after that comes before the read's close.
     */
    private static boolean seenReadingTheFile(Thread describer) {
        while (describer.isAlive()) {
            for (StackTraceElement frame : describer.getStackTrace()) {
                if (frame.getClassName().equals(Files.class.getName())
                        && frame.getMethodName().equals("readString")) {
                    return true;
                }
            }
            Thread.onSpinWait();
        }
        return false;
    }

    /**
     * An interrupted thread taking, describing, writing and closing a hold must not close the
     * channel: on every platform that releases the lock while the holder still believes it holds it.
     * The forked process is the judge, and the caller's interrupt survives each call.
     */
    @Test
    void an_interrupted_holder_keeps_the_os_lock(@TempDir Path dir) throws Exception {
        Path lock = dir.resolve("build.lock");
        FileLocks.Hold hold;
        Thread.currentThread().interrupt();
        try {
            hold = (FileLocks.Hold) FileLocks.tryHold(lock);
            hold.write("pid=1\nbuild=7\n");
            FileLocks.describeHolder(lock);
            assertThat(Thread.currentThread().isInterrupted())
                    .as("the interrupt is kept")
                    .isTrue();
        } finally {
            Thread.interrupted();
        }
        try {
            assertThat(probeFromAnotherProcess(lock)).isEqualTo("held");
            assertThat(FileLocks.describeHolder(lock)).contains("build=7");
        } catch (Throwable failed) {
            hold.close();
            throw failed;
        }
        Thread.currentThread().interrupt();
        try {
            hold.close();
        } finally {
            Thread.interrupted();
        }
        assertThat(FileLocks.describeHolder(lock))
                .as("an interrupted close still empties the description")
                .isEmpty();
        assertThat(probeFromAnotherProcess(lock)).isEqualTo("free");
    }

    /**
     * Describers interrupted at any point of their read never close the channel an open hold lives
     * on. The forked process is the judge.
     */
    @Test
    void interrupting_a_describer_keeps_a_hold(@TempDir Path dir) throws Exception {
        Path lock = dir.resolve("build.lock");
        FileLocks.Hold hold = (FileLocks.Hold) FileLocks.tryHold(lock);
        try {
            hold.write("pid=1\nbuild=7\n" + "x".repeat(32 * 1024));
            interruptDescribers(lock, Duration.ofSeconds(1));
            assertThat(probeFromAnotherProcess(lock)).isEqualTo("held");
            hold.write("pid=1\nbuild=8\n");
        } finally {
            hold.close();
        }
    }

    /** As {@link #interrupting_a_describer_keeps_a_hold}, while a body runs under the lock. */
    @Test
    void interrupting_a_describer_keeps_the_lock_a_body_runs_under(@TempDir Path dir) throws Exception {
        Path lock = Files.writeString(dir.resolve("build.lock"), "x".repeat(32 * 1024));
        String seen = FileLocks.withLock(lock, () -> {
            interruptDescribers(lock, Duration.ofSeconds(1));
            return probeFromAnotherProcess(lock);
        });
        assertThat(seen).isEqualTo("held");
    }

    /**
     * Four threads describe the holder for {@code duration} while a fifth interrupts each one whenever
     * it runs rather than waits, which is when it is reading through the lock's channel.
     */
    private static void interruptDescribers(Path lock, Duration duration) {
        AtomicBoolean stop = new AtomicBoolean();
        AtomicBoolean described = new AtomicBoolean();
        List<Thread> describers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            describers.add(Thread.ofPlatform().daemon().start(() -> {
                while (!stop.get()) {
                    Thread.interrupted();
                    FileLocks.describeHolder(lock);
                }
            }));
        }
        Thread interrupter = Thread.ofPlatform().daemon().start(() -> {
            while (!described.get()) {
                for (Thread describer : describers) {
                    if (describer.getState() == Thread.State.RUNNABLE) describer.interrupt();
                }
                Thread.onSpinWait();
            }
        });
        try {
            Thread.sleep(duration);
            stop.set(true);
            for (Thread describer : describers) describer.join(Duration.ofSeconds(10));
            described.set(true);
            interrupter.join(Duration.ofSeconds(10));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    /** {@link FileLocks#tryHold} until it holds; the describer's brief turns under the JVM lock refuse it. */
    private static FileLocks.Hold takeWhileDescribed(Path lock) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (FileLocks.tryHold(lock) instanceof FileLocks.Hold hold) return hold;
            Thread.onSpinWait();
        }
        throw new AssertionError("never took " + lock);
    }

    /** A hold and a fold on one file from one JVM share the channel: the fold runs under the hold. */
    @Test
    void with_lock_runs_under_this_jvms_own_hold(@TempDir Path dir) throws Exception {
        Path lock = dir.resolve("build.lock");
        FileLocks.Hold hold = (FileLocks.Hold) FileLocks.tryHold(lock);
        try {
            assertThat(FileLocks.withLock(lock, () -> probeFromAnotherProcess(lock)))
                    .isEqualTo("held");
        } finally {
            hold.close();
        }
        assertThat(probeFromAnotherProcess(lock)).isEqualTo("free");
    }

    /**
     * Two processes folding one counter under the lock never lose an increment: the sum of what
     * each wrote is what the file holds at the end.
     */
    @Test
    void with_lock_serialises_read_fold_write_across_processes(@TempDir Path dir) throws Exception {
        Path counter = Files.writeString(dir.resolve("counter"), "0");
        int processes = 3;
        int perProcess = 40;
        List<Process> running = new ArrayList<>();
        for (int i = 0; i < processes; i++) {
            running.add(fork(FoldMain.class, counter.toString(), Integer.toString(perProcess)));
        }
        for (Process p : running) {
            Processes.Captured run = Processes.capture(p, Duration.ofMinutes(2));
            assertThat(run.exit())
                    .as("subprocess exit; output: " + run.output() + run.error())
                    .isZero();
        }
        assertThat(Files.readString(counter).trim()).isEqualTo(Integer.toString(processes * perProcess));
    }

    /** A probe refused by another process's hold leaves no descriptor of its own open on the file. */
    @Test
    @EnabledOnOs(OS.LINUX)
    void a_probe_refused_by_another_process_closes_its_channel(@TempDir Path dir) throws Exception {
        Path lock = dir.resolve("build.lock");
        Process holder = fork(HoldMain.class, lock.toString());
        try (BufferedReader out =
                new BufferedReader(new InputStreamReader(holder.getInputStream(), StandardCharsets.UTF_8))) {
            assertThat(out.readLine()).isEqualTo("holding");
            assertThat(FileLocks.tryHold(lock)).isInstanceOf(FileLocks.Held.class);
            assertThat(descriptorsOn(lock))
                    .as("descriptors this JVM has open on the lock file")
                    .isZero();
        } finally {
            holder.destroy();
            holder.waitFor();
        }
    }

    /** How many of this process's descriptors are open on {@code file}, read from /proc. */
    private static long descriptorsOn(Path file) throws IOException {
        Path real = file.toRealPath();
        long[] n = {0};
        PathUtil.forEachChild(Path.of("/proc/self/fd"), (fd, attrs) -> {
            try {
                if (Files.readSymbolicLink(fd).equals(real)) n[0]++;
            } catch (IOException closedMeanwhile) {
                // the listing's own descriptor, gone by now
            }
            return true;
        });
        return n[0];
    }

    /** The one-shot probe {@link ProbeMain} answers: {@code held} or {@code free}. */
    private static String probeFromAnotherProcess(Path lock) throws IOException {
        Processes.Captured run = Processes.capture(fork(ProbeMain.class, lock.toString()), Duration.ofMinutes(1));
        String out = run.output().trim();
        assertThat(run.exit()).as(out + run.error()).isZero();
        return out;
    }

    private static Process fork(Class<?> main, String... args) throws IOException {
        Process p = start(javaCommand(main, args));
        try (OutputStream in = p.getOutputStream()) {
            // no stdin
        }
        return p;
    }

    private static Process start(List<String> cmd) throws IOException {
        return new ProcessBuilder(cmd).redirectErrorStream(true).start();
    }

    private static List<String> javaCommand(Class<?> main, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(main.getName());
        cmd.addAll(List.of(args));
        return cmd;
    }

    /** Subprocess body for {@link #with_lock_serialises_read_fold_write_across_processes}. */
    public static final class FoldMain {
        public static void main(String[] args) throws Exception {
            Path counter = Path.of(args[0]);
            int n = Integer.parseInt(args[1]);
            for (int i = 0; i < n; i++) {
                FileLocks.withLock(counter.resolveSibling("counter.lock"), () -> {
                    int current = Integer.parseInt(Files.readString(counter).trim());
                    AtomicWrites.replace(counter, Integer.toString(current + 1));
                });
            }
        }
    }

    /** Subprocess body: take the lock, say so, and keep it until killed. */
    public static final class HoldMain {
        public static void main(String[] args) throws Exception {
            FileLocks.Probe probe = FileLocks.tryHold(Path.of(args[0]));
            System.out.println(probe instanceof FileLocks.Hold ? "holding" : "not holding: " + probe);
            System.out.flush();
            Thread.sleep(Long.MAX_VALUE);
        }
    }

    /** Subprocess body: probe the lock once and say what was found. */
    public static final class ProbeMain {
        public static void main(String[] args) throws Exception {
            switch (FileLocks.tryHold(Path.of(args[0]))) {
                case FileLocks.Hold hold -> {
                    hold.close();
                    System.out.println("free");
                }
                case FileLocks.Held held -> System.out.println("held");
                case FileLocks.Unavailable unavailable -> System.out.println("unavailable: " + unavailable.reason());
            }
        }
    }

    /** Subprocess body: probe the lock once per line on stdin and answer each as {@link ProbeMain} does. */
    public static final class ProbeLoopMain {
        public static void main(String[] args) throws Exception {
            Path lock = Path.of(args[0]);
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            while (in.readLine() != null) {
                switch (FileLocks.tryHold(lock)) {
                    case FileLocks.Hold hold -> {
                        hold.close();
                        System.out.println("free");
                    }
                    case FileLocks.Held held -> System.out.println("held");
                    case FileLocks.Unavailable unavailable ->
                        System.out.println("unavailable: " + unavailable.reason());
                }
                System.out.flush();
            }
        }
    }
}
