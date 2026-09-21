// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
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
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(p.waitFor()).as("subprocess exit; output: " + out).isZero();
        }
        assertThat(Files.readString(counter).trim()).isEqualTo(Integer.toString(processes * perProcess));
    }

    /** The one-shot probe {@link ProbeMain} answers: {@code held} or {@code free}. */
    private static String probeFromAnotherProcess(Path lock) throws IOException {
        Process p = fork(ProbeMain.class, lock.toString());
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        try {
            assertThat(p.waitFor()).as(out).isZero();
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
        return out;
    }

    private static Process fork(Class<?> main, String... args) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(main.getName());
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        try (OutputStream in = p.getOutputStream()) {
            // no stdin
        }
        return p;
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
}
