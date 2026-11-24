// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.Await;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A run whose turn on a guard report another process holds says what it waits for until it has the
 * turn, so a step blocked on another engine is not a silent one.
 */
// Out of the unit tier: a second JVM holds the lock.
@Tag("integration")
class GuardSuiteRunnerLockWaitTest {

    private static final String HOLDER = """
            import java.nio.channels.FileChannel;
            import java.nio.file.Path;
            import java.nio.file.StandardOpenOption;

            public class Holder {
                public static void main(String[] args) throws Exception {
                    try (FileChannel c = FileChannel.open(
                                    Path.of(args[0]), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                            var held = c.lock()) {
                        System.out.println("held");
                        System.out.flush();
                        System.in.read();
                    }
                }
            }
            """;

    @Test
    void a_turn_another_process_holds_is_waited_for_out_loud(@TempDir Path tmp) throws Exception {
        Path guardDir = Files.createDirectories(tmp.resolve("guard"));
        Path report = guardDir.resolve("report.json");
        Path holder = Files.writeString(tmp.resolve("Holder.java"), HOLDER);
        String java = ProcessHandle.current().info().command().orElse("java");
        Process other = new ProcessBuilder(
                        java,
                        holder.toString(),
                        guardDir.resolve(GuardSuiteRunner.LOCK_FILE).toString())
                .redirectErrorStream(true)
                .start();
        try {
            var out = new BufferedReader(new InputStreamReader(other.getInputStream(), StandardCharsets.UTF_8));
            assertThat(out.readLine()).isEqualTo("held");

            List<@Nullable String> heard = new CopyOnWriteArrayList<>();
            CompletableFuture<String> turn = CompletableFuture.supplyAsync(() -> {
                try {
                    return GuardSuiteRunner.takingTurn(report, heard::add, () -> "mine");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            Await.until(Duration.ofSeconds(20), () -> heard.contains(GuardSuiteRunner.OTHER_ENGINE));
            assertThat(turn).isNotDone();

            other.getOutputStream().close();

            assertThat(turn.get(20, TimeUnit.SECONDS)).isEqualTo("mine");
            assertThat(heard).containsExactly(GuardSuiteRunner.OTHER_ENGINE, null);
        } finally {
            other.destroyForcibly();
            other.waitFor(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void a_free_turn_is_taken_without_a_word(@TempDir Path tmp) throws Exception {
        List<@Nullable String> heard = new CopyOnWriteArrayList<>();

        String got = GuardSuiteRunner.takingTurn(tmp.resolve("guard/report.json"), heard::add, () -> "mine");

        assertThat(got).isEqualTo("mine");
        assertThat(heard).isEmpty();
    }
}
