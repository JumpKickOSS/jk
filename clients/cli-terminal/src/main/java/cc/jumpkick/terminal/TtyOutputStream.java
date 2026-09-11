// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal;

import cc.jumpkick.terminal.posix.PosixTty;
import cc.jumpkick.terminal.windows.WindowsConsole;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;

final class TtyOutputStream extends OutputStream {
    private final @Nullable PosixTty posix;
    private final @Nullable WindowsConsole windows;
    private final BooleanSupplier live;

    TtyOutputStream(@Nullable PosixTty posix, @Nullable WindowsConsole windows, BooleanSupplier live) {
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
