// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileLocksTest {

    @Test
    void a_hold_refuses_a_second_taker_until_it_closes(@TempDir Path dir) throws Exception {
        Path lock = dir.resolve("state/build.lock");
        FileLocks.Hold first = FileLocks.tryHold(lock).orElseThrow();
        first.write("pid=1\nbuild=7\n");
        assertThat(FileLocks.tryHold(lock)).as("held").isEmpty();
        assertThat(FileLocks.describeHolder(lock)).contains("build=7");
        first.close();
        Optional<FileLocks.Hold> second = FileLocks.tryHold(lock);
        assertThat(second).isPresent();
        second.get().close();
        assertThat(lock).as("the lock file stays for the next taker").exists();
    }

    @Test
    void with_lock_runs_the_body_and_returns_its_value(@TempDir Path dir) throws Exception {
        Path lock = dir.resolve("ledger.toml.lock");
        int value = FileLocks.withLock(lock, () -> 42);
        assertThat(value).isEqualTo(42);
        assertThat(FileLocks.withLock(lock, () -> FileLocks.withLock(lock, () -> "reentrant")))
                .isEqualTo("reentrant");
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
        String classpath = System.getProperty("java.class.path");
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        List<Process> running = new ArrayList<>();
        for (int i = 0; i < processes; i++) {
            running.add(new ProcessBuilder(
                            java.toString(),
                            "-cp",
                            classpath,
                            FoldMain.class.getName(),
                            counter.toString(),
                            Integer.toString(perProcess))
                    .redirectErrorStream(true)
                    .start());
        }
        for (Process p : running) {
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(p.waitFor()).as("subprocess exit; output: " + out).isZero();
        }
        assertThat(Files.readString(counter).trim()).isEqualTo(Integer.toString(processes * perProcess));
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
}
