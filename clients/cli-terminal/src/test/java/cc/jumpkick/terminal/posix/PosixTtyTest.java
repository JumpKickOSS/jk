// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal.posix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.host.Os;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The poll/read loop over a pipe: the same descriptor states a pty produces — readable, idle, and
 * hung up — without needing a terminal. A pipe whose writer has closed is what a closed pty
 * master looks like to {@code poll} (POLLHUP) and to {@code read} (0 bytes).
 */
class PosixTtyTest {

    @Test
    void a_descriptor_whose_writer_is_gone_reports_dead_instead_of_spinning() throws Throwable {
        assumeTrue(Os.isLinux() || Os.isDarwin());
        int[] fds = pipe();
        close(fds[1]);
        try (PosixTty tty = over(fds[0])) {
            int got = assertTimeoutPreemptively(Duration.ofSeconds(3), () -> tty.readByte(Duration.ZERO, () -> true));
            assertThat(got).as("dead, not a byte and not a timeout").isEqualTo(-2);
        }
    }

    @Test
    void bytes_still_buffered_arrive_before_the_hang_up_is_reported() throws Throwable {
        assumeTrue(Os.isLinux() || Os.isDarwin());
        int[] fds = pipe();
        write(fds[1], (byte) 'q');
        close(fds[1]);
        try (PosixTty tty = over(fds[0])) {
            int first = assertTimeoutPreemptively(
                    Duration.ofSeconds(3), () -> tty.readByte(Duration.ofSeconds(1), () -> true));
            assertThat(first).isEqualTo('q');
            int then = assertTimeoutPreemptively(
                    Duration.ofSeconds(3), () -> tty.readByte(Duration.ofSeconds(1), () -> true));
            assertThat(then)
                    .as("drained and hung up is dead at once, not a one-second timeout")
                    .isEqualTo(-2);
        }
    }

    @Test
    void an_idle_descriptor_with_a_live_writer_times_out_rather_than_dying() throws Throwable {
        assumeTrue(Os.isLinux() || Os.isDarwin());
        int[] fds = pipe();
        try (PosixTty tty = over(fds[0])) {
            int got = assertTimeoutPreemptively(
                    Duration.ofSeconds(3), () -> tty.readByte(Duration.ofMillis(60), () -> true));
            assertThat(got).isEqualTo(-1);
        } finally {
            close(fds[1]);
        }
    }

    @Test
    void select_waits_out_an_idle_descriptor_instead_of_returning_at_once() throws Throwable {
        assumeTrue(Os.isLinux() || Os.isDarwin());
        int[] fds = pipe();
        try (PosixTty tty = over(fds[0])) {
            tty.waitWithSelect();
            long start = System.nanoTime();
            int got = assertTimeoutPreemptively(
                    Duration.ofSeconds(3), () -> tty.readByte(Duration.ofMillis(60), () -> true));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            assertThat(got).isEqualTo(-1);
            assertThat(elapsedMs).isGreaterThanOrEqualTo(40);
        } finally {
            close(fds[1]);
        }
    }

    @Test
    void select_delivers_a_buffered_byte_and_then_the_hang_up() throws Throwable {
        assumeTrue(Os.isLinux() || Os.isDarwin());
        int[] fds = pipe();
        write(fds[1], (byte) 'y');
        close(fds[1]);
        try (PosixTty tty = over(fds[0])) {
            tty.waitWithSelect();
            int first = assertTimeoutPreemptively(
                    Duration.ofSeconds(3), () -> tty.readByte(Duration.ofMillis(200), () -> true));
            assertThat(first).isEqualTo('y');
            int then = assertTimeoutPreemptively(
                    Duration.ofSeconds(3), () -> tty.readByte(Duration.ofMillis(200), () -> true));
            assertThat(then).isEqualTo(-2);
        }
    }

    @Test
    void a_controlling_tty_idle_read_times_out_instead_of_dying() {
        assumeTrue(Os.isLinux() || Os.isDarwin());
        PosixTty tty = PosixTty.openControlling();
        if (tty == null) {
            assumeTrue(false, "no controlling terminal");
            return;
        }
        try {
            long start = System.nanoTime();
            int got = assertTimeoutPreemptively(
                    Duration.ofSeconds(3), () -> tty.readByte(Duration.ofMillis(80), () -> true));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            assertThat(got).as("timeout, not a dead terminal").isEqualTo(-1);
            assertThat(elapsedMs)
                    .as("waited for the terminal instead of returning at once")
                    .isGreaterThanOrEqualTo(50);
        } finally {
            tty.close();
        }
    }

    /** A {@link PosixTty} over a plain descriptor: no termios to restore, the host's layout. */
    private static PosixTty over(int fd) {
        boolean darwin = Os.isDarwin();
        return new PosixTty(fd, new byte[darwin ? TermiosDarwin.SIZE : TermiosLinux.SIZE], darwin);
    }

    // The test binds its own pipe/close/write: the class under test keeps its downcalls private.

    private static MethodHandle bind(String name, FunctionDescriptor desc) {
        Linker linker = Linker.nativeLinker();
        return linker.downcallHandle(linker.defaultLookup().findOrThrow(name), desc);
    }

    private static int[] pipe() throws Throwable {
        MethodHandle pipe = bind("pipe", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment fds = arena.allocate(8);
            int rc = (int) pipe.invokeExact(fds);
            assertThat(rc).as("pipe(2)").isZero();
            return new int[] {fds.get(ValueLayout.JAVA_INT, 0), fds.get(ValueLayout.JAVA_INT, 4)};
        }
    }

    private static void close(int fd) throws Throwable {
        MethodHandle close = bind("close", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        int ignored = (int) close.invokeExact(fd);
    }

    private static void write(int fd, byte b) throws Throwable {
        MethodHandle write = bind(
                "write",
                FunctionDescriptor.of(
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(1);
            buf.set(ValueLayout.JAVA_BYTE, 0, b);
            long n = (long) write.invokeExact(fd, buf, 1L);
            assertThat(n).as("write(2)").isEqualTo(1L);
        }
    }
}
