// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal.posix;

import cc.jumpkick.terminal.InputMode;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * glibc LP64 {@code struct termios}: c_iflag/oflag/cflag/lflag, c_line, c_cc[32], speeds.
 * sizeof 60.
 */
public final class TermiosLinux {
    public static final int SIZE = 60;
    public static final int NCCS = 32;
    public static final int VMIN = 6;
    public static final int VTIME = 5;

    public static final int ECHO = 0000010;
    public static final int ICANON = 0000002;
    public static final int ISIG = 0000001;
    public static final int IEXTEN = 0100000;
    public static final int IXON = 0002000;
    public static final int IXOFF = 0001000;
    public static final int ICRNL = 0000400;
    public static final int INLCR = 0000100;

    public static final int O_RDWR = 2;
    public static final int O_CLOEXEC = 0x80000;
    public static final int O_NONBLOCK = 0x800;
    public static final int F_GETFL = 3;
    public static final int F_SETFL = 4;
    public static final int TCSANOW = 0;
    public static final int POLLIN = 0x0001;
    public static final int POLLOUT = 0x0004;
    public static final int POLLERR = 0x0008;
    public static final int POLLHUP = 0x0010;
    public static final int POLLNVAL = 0x0020;
    public static final int CLOCK_MONOTONIC = 1;
    public static final int EINTR = 4;
    public static final int EAGAIN = 11;
    public static final int EBADF = 9;

    private static final int IFLAG = 0;
    private static final int LFLAG = 12;
    private static final int CC = 17;

    private TermiosLinux() {}

    public static int iflag(MemorySegment t) {
        return t.get(ValueLayout.JAVA_INT, IFLAG);
    }

    public static int lflag(MemorySegment t) {
        return t.get(ValueLayout.JAVA_INT, LFLAG);
    }

    public static int cc(MemorySegment t, int index) {
        return Byte.toUnsignedInt(t.get(ValueLayout.JAVA_BYTE, CC + index));
    }

    public static void apply(MemorySegment t, InputMode mode) {
        if (mode == InputMode.COOKED || mode == InputMode.INHERIT_CHILD) {
            return;
        }
        int l = lflag(t);
        l &= ~(ECHO | ICANON | IEXTEN);
        if (mode == InputMode.PROMPT) {
            l &= ~ISIG;
        } else {
            l |= ISIG;
        }
        t.set(ValueLayout.JAVA_INT, LFLAG, l);
        int i = iflag(t);
        i &= ~(IXON | IXOFF | ICRNL | INLCR);
        t.set(ValueLayout.JAVA_INT, IFLAG, i);
        t.set(ValueLayout.JAVA_BYTE, CC + VMIN, (byte) 1);
        t.set(ValueLayout.JAVA_BYTE, CC + VTIME, (byte) 0);
    }

    public static boolean ixonOff(MemorySegment t) {
        return (iflag(t) & IXON) == 0;
    }

    public static boolean iextenOff(MemorySegment t) {
        return (lflag(t) & IEXTEN) == 0;
    }

    public static boolean isigOn(MemorySegment t) {
        return (lflag(t) & ISIG) != 0;
    }
}
