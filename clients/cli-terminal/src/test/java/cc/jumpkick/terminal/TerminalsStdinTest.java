// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Os;
import cc.jumpkick.host.Processes;
import java.io.Console;
import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Whether stdin is a terminal asks about the process's own stdin, never the console or TTY the
 * process could open: on Windows a console is attached under Git Bash and PowerShell even with stdin
 * redirected from a file.
 */
class TerminalsStdinTest {

    /** A Windows host whose console is attached but whose stdin is a file or a pipe. */
    private static final class RedirectedOnWindows implements Terminals.Probe {
        final List<Integer> asked = new ArrayList<>();

        @Override
        public boolean windowsIsConsole(int stdHandle) {
            asked.add(stdHandle);
            // Only the console's own handles answer GetConsoleMode; the redirected stdin does not.
            return stdHandle != Terminals.STD_INPUT_HANDLE;
        }

        @Override
        public boolean posixIsatty(int fd) {
            throw new AssertionError("a Windows host asks the console API");
        }
    }

    @Test
    void on_windows_a_redirected_stdin_is_not_a_terminal_though_a_console_is_attached() {
        RedirectedOnWindows probe = new RedirectedOnWindows();

        assertThat(Terminals.stdinIsTty(true, probe)).isFalse();
        assertThat(probe.asked).containsExactly(Terminals.STD_INPUT_HANDLE);
    }

    @Test
    void on_windows_a_console_stdin_is_a_terminal() {
        Terminals.Probe console = new Terminals.Probe() {
            @Override
            public boolean windowsIsConsole(int stdHandle) {
                return true;
            }

            @Override
            public boolean posixIsatty(int fd) {
                throw new AssertionError("a Windows host asks the console API");
            }
        };

        assertThat(Terminals.stdinIsTty(true, console)).isTrue();
    }

    @Test
    void on_windows_a_console_nobody_types_into_is_no_controlling_terminal() {
        // An ssh session or `jk update < NUL`: the console is live, stdin is not it.
        assertThat(Terminals.controllingIsTty(true, () -> true, () -> false)).isFalse();
        assertThat(Terminals.controllingIsTty(true, () -> true, () -> true)).isTrue();
        assertThat(Terminals.controllingIsTty(true, () -> false, () -> true)).isFalse();
    }

    @Test
    void on_posix_the_controlling_terminal_answers_even_with_stdin_redirected() {
        assertThat(Terminals.controllingIsTty(false, () -> true, () -> false)).isTrue();
        assertThat(Terminals.controllingIsTty(false, () -> false, () -> true)).isFalse();
    }

    @Test
    void on_posix_stdin_is_file_descriptor_zero() {
        List<Integer> asked = new ArrayList<>();
        Terminals.Probe posix = new Terminals.Probe() {
            @Override
            public boolean windowsIsConsole(int stdHandle) {
                throw new AssertionError("a POSIX host asks isatty");
            }

            @Override
            public boolean posixIsatty(int fd) {
                asked.add(fd);
                return false;
            }
        };

        assertThat(Terminals.stdinIsTty(false, posix)).isFalse();
        assertThat(asked).containsExactly(0);
    }

    @Test
    void without_the_native_check_only_a_console_that_is_a_terminal_counts() {
        Terminals.Probe unavailable = new Terminals.Probe() {
            @Override
            public boolean windowsIsConsole(int stdHandle) {
                throw new UnsatisfiedLinkError("kernel32");
            }

            @Override
            public boolean posixIsatty(int fd) {
                throw new UnsatisfiedLinkError("isatty");
            }
        };
        Console console = System.console();
        boolean terminal = console != null && console.isTerminal();

        assertThat(Terminals.stdinIsTty(true, unavailable)).isEqualTo(terminal);
        assertThat(Terminals.stdinIsTty(false, unavailable)).isEqualTo(terminal);
    }

    @Test
    void a_process_whose_stdin_is_redirected_from_the_null_device_reads_it_as_no_terminal() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", Os.isWindows() ? "java.exe" : "java")
                .toString();
        ProcessBuilder child = new ProcessBuilder(
                        java,
                        "--enable-native-access=ALL-UNNAMED",
                        "-cp",
                        System.getProperty("java.class.path"),
                        PrintStdinIsTty.class.getName())
                .redirectInput(ProcessBuilder.Redirect.from(new File(Os.isWindows() ? "NUL" : "/dev/null")))
                .redirectErrorStream(true);
        Processes.Captured run = Processes.capture(child, Duration.ofSeconds(60));
        String out = run.output();

        assertThat(run.exit()).as(out).isZero();
        assertThat(out.strip().lines().toList()).as(out).endsWith("stdin-is-tty=false");
    }

    /** Prints {@link Terminals#stdinIsTty()} as the child process sees it. */
    public static final class PrintStdinIsTty {
        public static void main(String[] args) {
            System.out.println("stdin-is-tty=" + Terminals.stdinIsTty());
        }
    }
}
