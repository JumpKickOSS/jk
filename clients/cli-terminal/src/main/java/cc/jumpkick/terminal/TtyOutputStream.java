// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal;

import cc.jumpkick.terminal.posix.PosixTty;
import cc.jumpkick.terminal.windows.WindowsConsole;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

final class TtyOutputStream extends OutputStream {
    private final PosixTty posix;
    private final WindowsConsole windows;
    private final BooleanSupplier live;

    TtyOutputStream(PosixTty posix, WindowsConsole windows, BooleanSupplier live) {
        this.posix = posix;
        this.windows = windows;
        this.live = live;
    }

    @Override
    public void write(int b) {
        write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) {
        byte[] slice = off == 0 && len == b.length ? b : Arrays.copyOfRange(b, off, off + len);
        if (posix != null) {
            posix.writeFully(slice, live);
        } else if (windows != null) {
            windows.writeFully(slice, live);
        }
    }
}
