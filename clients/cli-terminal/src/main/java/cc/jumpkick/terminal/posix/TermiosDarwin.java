// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal.posix;

import cc.jumpkick.terminal.InputMode;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Darwin LP64 {@code struct termios}: four unsigned long flags, c_cc[20], speeds. No c_line.
 * sizeof 72.
 */
public final class TermiosDarwin {
    public static final int SIZE = 72;
    public static final int NCCS = 20;
    public static final int VMIN = 16;
    public static final int VTIME = 17;

    public static final int ECHO = 0x00000008;
    public static final int ICANON = 0x00000100;
    public static final int ISIG = 0x00000080;
    public static final int IEXTEN = 0x00000400;
    public static final int IXON = 0x00000200;
    public static final int IXOFF = 0x00000400;
    public static final int ICRNL = 0x00000100;
    public static final int INLCR = 0x00000040;

    public static final int O_RDWR = 2;
    public static final int O_CLOEXEC = 0x01000000;
    public static final int O_NONBLOCK = 0x0004;
    public static final int F_GETFL = 3;
    public static final int F_SETFL = 4;
    public static final int TCSANOW = 0;
    public static final int POLLIN = 0x0001;
    public static final int POLLOUT = 0x0004;
    public static final int CLOCK_MONOTONIC = 6;
    public static final int EINTR = 4;
    public static final int EAGAIN = 35;
    public static final int EBADF = 9;

    private static final int IFLAG = 0;
    private static final int LFLAG = 24;
    private static final int CC = 32;

    private TermiosDarwin() {}

    public static long iflag(MemorySegment t) {
        return t.get(ValueLayout.JAVA_LONG, IFLAG);
    }

    public static long lflag(MemorySegment t) {
        return t.get(ValueLayout.JAVA_LONG, LFLAG);
    }

    public static int cc(MemorySegment t, int index) {
        return Byte.toUnsignedInt(t.get(ValueLayout.JAVA_BYTE, CC + index));
    }

    public static void apply(MemorySegment t, InputMode mode) {
        if (mode == InputMode.COOKED || mode == InputMode.INHERIT_CHILD) {
            return;
        }
        long l = lflag(t);
        l &= ~(ECHO | ICANON | IEXTEN);
        if (mode == InputMode.PROMPT) {
            l &= ~ISIG;
        } else {
            l |= ISIG;
        }
        t.set(ValueLayout.JAVA_LONG, LFLAG, l);
        long i = iflag(t);
        i &= ~(IXON | IXOFF | ICRNL | INLCR);
        t.set(ValueLayout.JAVA_LONG, IFLAG, i);
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
