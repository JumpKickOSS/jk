// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal.posix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.host.Os;
import cc.jumpkick.host.Processes;
import cc.jumpkick.terminal.InputMode;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * A process in a background group of its terminal (under {@code timeout}, {@code nohup} or {@code
 * &}) gets no live session: setting the terminal's modes from there stops the whole process with
 * SIGTTOU, as {@code jk new} and {@code jk jdk list} were stopped after finishing their work.
 */
class PosixTtyBackgroundTest {

    @Test
    void only_the_terminals_foreground_group_owns_it() {
        assertThat(PosixTty.foreground(4242, 4242)).isTrue();
        assertThat(PosixTty.foreground(4242, 4243)).as("a background job").isFalse();
        assertThat(PosixTty.foreground(-1, 4242)).as("unknown").isFalse();
    }

    /**
     * A child on a pty ({@code script}) opens the terminal while in the foreground, then moves to a
     * group of its own, which the terminal does not run in the foreground, and opens it again.
     */
    @Test
    void a_background_process_of_a_terminal_gets_no_session_and_exits() throws Exception {
        assumeTrue(Os.isLinux(), "util-linux script");
        Path script = Path.of("/usr/bin/script");
        assumeTrue(Files.isExecutable(script), "script(1) installed");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        // "; exit": the shell stays the child's parent in the pty's session, so the child's own
        // group is not orphaned and the terminal stops it, as a shell's background job is stopped.
        String command = quote(java) + " --enable-native-access=ALL-UNNAMED -cp "
                + quote(System.getProperty("java.class.path")) + " " + quote(Background.class.getName()) + "; exit $?";
        ProcessBuilder pty =
                new ProcessBuilder(script.toString(), "-qec", command, "/dev/null").redirectErrorStream(true);
        pty.environment().put("SHELL", "/bin/sh");
        Processes.Captured run = Processes.capture(pty, Duration.ofSeconds(15));
        String out = run.output();

        assertThat(run.timedOut()).as("stopped instead of exiting: " + out).isFalse();
        assertThat(out).as(out).contains("foreground-session=true");
        assertThat(out).as(out).contains("background-session=false", "exited");
        assertThat(run.exit()).as(out).isZero();
    }

    private static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** Opens the terminal in the foreground, then from a group of its own, setting modes each time. */
    public static final class Background {
        public static void main(String[] args) throws Throwable {
            System.out.println("foreground-session=" + session());
            Linker linker = Linker.nativeLinker();
            MethodHandle setpgid = linker.downcallHandle(
                    linker.defaultLookup().findOrThrow("setpgid"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            int rc = (int) setpgid.invokeExact(0, 0);
            System.out.println("setpgid=" + rc);
            System.out.println("background-session=" + session());
            System.out.println("exited");
        }

        private static boolean session() {
            PosixTty tty = PosixTty.openControlling();
            if (tty == null) return false;
            tty.apply(InputMode.PROMPT);
            tty.restoreOriginal();
            tty.close();
            return true;
        }
    }
}
