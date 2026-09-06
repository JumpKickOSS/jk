// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Os;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class WindowsExecutionPolicyTest {

    @Test
    void recognizes_only_successful_exact_status_output() {
        assertThat(run("ok", Duration.ofSeconds(2))).isEqualTo(WindowsExecutionPolicy.Result.ALREADY_OK);
        assertThat(run("set", Duration.ofSeconds(2))).isEqualTo(WindowsExecutionPolicy.Result.SET);
        assertThat(run("blocked", Duration.ofSeconds(2))).isEqualTo(WindowsExecutionPolicy.Result.BLOCKED);
        assertThat(run("malformed", Duration.ofSeconds(2))).isEqualTo(WindowsExecutionPolicy.Result.FAILED);
        assertThat(run("failure", Duration.ofSeconds(2))).isEqualTo(WindowsExecutionPolicy.Result.FAILED);
    }

    @Test
    void timeout_kills_a_child_that_never_exits() {
        long started = System.nanoTime();
        assertThat(run("hang", Duration.ofMillis(150))).isEqualTo(WindowsExecutionPolicy.Result.FAILED);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void non_windows_short_circuits() {
        if (Os.isWindows()) {
            return;
        }
        assertThat(WindowsExecutionPolicy.ensure()).isEqualTo(WindowsExecutionPolicy.Result.NOT_WINDOWS);
    }

    private static WindowsExecutionPolicy.Result run(String mode, Duration timeout) {
        return WindowsExecutionPolicy.ensure(timeout, () -> new ProcessBuilder(
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
                case "ok" -> System.out.println("OK");
                case "set" -> System.out.println("SET");
                case "blocked" -> System.out.println("BLOCKED");
                case "malformed" -> System.out.println("SET something else");
                case "failure" -> {
                    System.err.println("denied");
                    System.exit(1);
                }
                case "hang" -> Thread.sleep(Duration.ofMinutes(1));
                default -> throw new IllegalArgumentException(args[0]);
            }
        }
    }
}
