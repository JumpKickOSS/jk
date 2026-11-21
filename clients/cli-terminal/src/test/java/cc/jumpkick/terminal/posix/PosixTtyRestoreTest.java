// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal.posix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.host.Os;
import cc.jumpkick.terminal.InputMode;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import org.junit.jupiter.api.Test;

/**
 * Closing the terminal gives the shell back the modes it had: over a real pseudo-terminal, whose
 * modes a second descriptor on the same side reads after the first is closed.
 */
class PosixTtyRestoreTest {

    @Test
    void close_restores_the_original_modes() throws Throwable {
        assumeTrue(Os.isLinux() || Os.isDarwin());
        boolean darwin = Os.isDarwin();
        int master = openpt(darwin);
        assumeTrue(master >= 0, "no pseudo-terminal");
        int watcher = -1;
        try {
            String slave = slaveName(master);
            int fd = openSlave(slave, darwin);
            watcher = openSlave(slave, darwin);
            byte[] original = termios(watcher, darwin);
            byte[] originalModes = withoutPendin(original, darwin);

            PosixTty tty = new PosixTty(fd, original, darwin);
            tty.apply(InputMode.PROMPT);
            assertThat(withoutPendin(termios(watcher, darwin), darwin))
                    .as("the prompt mode took")
                    .isNotEqualTo(originalModes);

            tty.close();
            assertThat(withoutPendin(termios(watcher, darwin), darwin))
                    .as("the modes after close")
                    .isEqualTo(originalModes);
        } finally {
            if (watcher >= 0) close(watcher);
            close(master);
        }
    }

    /**
     * {@code termios} with {@code PENDIN} cleared: the kernel sets it on its own when a terminal
     * goes back to canonical input, so it is no mode anyone set.
     */
    private static byte[] withoutPendin(byte[] termios, boolean darwin) {
        byte[] out = termios.clone();
        MemorySegment t = MemorySegment.ofArray(out);
        if (darwin) {
            t.set(ValueLayout.JAVA_LONG_UNALIGNED, 24, t.get(ValueLayout.JAVA_LONG_UNALIGNED, 24) & ~0x20000000L);
        } else {
            t.set(ValueLayout.JAVA_INT_UNALIGNED, 12, t.get(ValueLayout.JAVA_INT_UNALIGNED, 12) & ~0040000);
        }
        return out;
    }

    private static MethodHandle bind(String name, FunctionDescriptor desc) {
        Linker linker = Linker.nativeLinker();
        return linker.downcallHandle(linker.defaultLookup().findOrThrow(name), desc);
    }

    private static int noCtty(boolean darwin) {
        return darwin ? 0x20000 : 0400;
    }

    private static int openpt(boolean darwin) throws Throwable {
        MethodHandle openpt = bind("posix_openpt", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        MethodHandle grantpt = bind("grantpt", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        MethodHandle unlockpt = bind("unlockpt", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        int master = (int) openpt.invokeExact(TermiosDarwin.O_RDWR | noCtty(darwin));
        if (master < 0) return -1;
        if ((int) grantpt.invokeExact(master) != 0 || (int) unlockpt.invokeExact(master) != 0) {
            close(master);
            return -1;
        }
        return master;
    }

    @SuppressWarnings("restricted")
    private static String slaveName(int master) throws Throwable {
        MethodHandle ptsname = bind("ptsname", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        MemorySegment name = (MemorySegment) ptsname.invokeExact(master);
        assertThat(name.address()).as("ptsname(3)").isNotZero();
        return name.reinterpret(256).getString(0);
    }

    private static int openSlave(String path, boolean darwin) throws Throwable {
        MethodHandle open =
                bind("open", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        try (Arena arena = Arena.ofConfined()) {
            int fd = (int) open.invokeExact(arena.allocateFrom(path), TermiosDarwin.O_RDWR | noCtty(darwin));
            assertThat(fd).as("open(%s)", path).isGreaterThan(0);
            return fd;
        }
    }

    private static byte[] termios(int fd, boolean darwin) throws Throwable {
        MethodHandle tcgetattr = bind(
                "tcgetattr", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment term = arena.allocate(darwin ? TermiosDarwin.SIZE : TermiosLinux.SIZE);
            int rc = (int) tcgetattr.invokeExact(fd, term);
            assertThat(rc).as("tcgetattr(3)").isZero();
            return term.toArray(ValueLayout.JAVA_BYTE);
        }
    }

    private static void close(int fd) throws Throwable {
        MethodHandle close = bind("close", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        int ignored = (int) close.invokeExact(fd);
    }
}
