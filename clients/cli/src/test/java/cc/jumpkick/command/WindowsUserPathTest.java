// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Os;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WindowsUserPathTest {

    @Test
    void recognizes_only_successful_exact_status_output(@TempDir Path tmp) {
        assertThat(run(tmp, "present", Duration.ofSeconds(2))).isEqualTo(WindowsUserPath.Result.ALREADY_PRESENT);
        assertThat(run(tmp, "added", Duration.ofSeconds(2))).isEqualTo(WindowsUserPath.Result.ADDED);
        assertThat(run(tmp, "malformed", Duration.ofSeconds(2))).isEqualTo(WindowsUserPath.Result.FAILED);
        assertThat(run(tmp, "failure", Duration.ofSeconds(2))).isEqualTo(WindowsUserPath.Result.FAILED);
    }

    @Test
    void timeout_kills_a_child_that_never_exits(@TempDir Path tmp) {
        long started = System.nanoTime();
        assertThat(run(tmp, "hang", Duration.ofMillis(150))).isEqualTo(WindowsUserPath.Result.FAILED);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void concurrent_bounded_drains_handle_large_stdout_and_stderr(@TempDir Path tmp) {
        long started = System.nanoTime();
        assertThat(run(tmp, "large", Duration.ofSeconds(5))).isEqualTo(WindowsUserPath.Result.FAILED);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    }

    private static WindowsUserPath.Result run(Path bin, String mode, Duration timeout) {
        return WindowsUserPath.ensure(bin, timeout, ignored -> new ProcessBuilder(
                        javaExecutable().toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        Child.class.getName(),
                        mode)
                .start());
    }

    private static Path javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", Os.isWindows() ? "java.exe" : "java");
    }

    public static final class Child {
        public static void main(String[] args) throws Exception {
            switch (args[0]) {
                case "present" -> System.out.println("PRESENT");
                case "added" -> System.out.println("ADDED");
                case "malformed" -> System.out.println("ADDED something else");
                case "failure" -> {
                    System.err.println("denied");
                    System.exit(1);
                }
                case "hang" -> Thread.sleep(Duration.ofMinutes(1));
                case "large" -> {
                    byte[] block = new byte[256 * 1024];
                    System.out.write(block);
                    System.err.write(block);
                }
                default -> throw new IllegalArgumentException(args[0]);
            }
        }
    }
}
