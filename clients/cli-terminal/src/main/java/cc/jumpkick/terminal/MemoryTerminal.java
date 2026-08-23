// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.locks.LockSupport;

/**
 * In-memory backend for unit tests. {@code enter} is a no-op on attributes. Owned with
 * try-with-resources.
 */
public final class MemoryTerminal implements TerminalSession {
    private static final long SLICE_NANOS = 50_000_000L;

    private final InputStream in;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final PrintWriter out;
    private final Deque<InputMode> stack = new ArrayDeque<>();
    private volatile boolean live = true;
    private int pushback = -1;

    MemoryTerminal(InputStream in, OutputStream ignored) {
        this.in = in;
        this.out = new PrintWriter(new OutputStreamWriter(bytes, StandardCharsets.UTF_8), true);
    }

    public byte[] written() {
        out.flush();
        return bytes.toByteArray();
    }

    @Override
    public boolean isLive() {
        return live;
    }

    @Override
    public InputMode mode() {
        InputMode top = stack.peek();
        return top == null ? InputMode.COOKED : top;
    }

    @Override
    public ModeGuard enter(InputMode mode) {
        if (!live) {
            return ModeGuard.noop();
        }
        stack.push(mode);
        return new ModeGuard(() -> {
            if (!stack.isEmpty()) {
                stack.pop();
            }
        });
    }

    @Override
    public Optional<Key> readKey(Duration timeout) {
        DeadTerminal.requireTimeout(timeout);
        if (!live) {
            return Optional.empty();
        }
        boolean forever = timeout.isZero();
        long deadline = forever ? Long.MAX_VALUE : System.nanoTime() + timeout.toNanos();
        while (live) {
            if (!forever && System.nanoTime() >= deadline) {
                return Optional.empty();
            }
            int b = readNow();
            if (b == -2) {
                return Optional.empty();
            }
            if (b >= 0) {
                return Optional.of(Keys.dispatch(b, feed));
            }
            long remaining = forever ? SLICE_NANOS : Math.min(SLICE_NANOS, Math.max(0, deadline - System.nanoTime()));
            if (remaining == 0) {
                return Optional.empty();
            }
            LockSupport.parkNanos(remaining);
        }
        return Optional.empty();
    }

    @Override
    public void drain(Duration max) {
        DeadTerminal.requireTimeout(max);
        long deadline = System.nanoTime() + (max.isZero() ? 0 : max.toNanos());
        try {
            while (live && in.available() > 0 && System.nanoTime() <= deadline) {
                if (in.read() < 0) {
                    break;
                }
            }
        } catch (IOException ignored) {
            live = false;
        }
    }

    @Override
    public PrintWriter ttyOut() {
        return out;
    }

    @Override
    public Size.Window size() {
        return Size.Window.DEFAULT;
    }

    @Override
    public void close() {
        live = false;
        stack.clear();
        try {
            in.close();
        } catch (IOException ignored) {
            // test backend
        }
        out.close();
    }

    private final Keys.ByteFeed feed = new Keys.ByteFeed() {
        @Override
        public int read(Duration timeout) {
            return readRaw(timeout);
        }

        @Override
        public void unread(int b) {
            pushback = b;
        }
    };

    private int readNow() {
        return readRaw(Duration.ofNanos(1));
    }

    private int readRaw(Duration timeout) {
        if (pushback >= 0) {
            int b = pushback;
            pushback = -1;
            return b;
        }
        if (!live) {
            return -2;
        }
        try {
            if (in.available() > 0) {
                int b = in.read();
                if (b < 0) {
                    live = false;
                    return -2;
                }
                return b;
            }
        } catch (IOException e) {
            live = false;
            return -2;
        }
        long nanos = timeout.toNanos();
        if (nanos <= 1) {
            return -1;
        }
        long deadline = System.nanoTime() + nanos;
        while (live && System.nanoTime() < deadline) {
            try {
                if (in.available() > 0) {
                    int b = in.read();
                    return b < 0 ? -2 : b;
                }
            } catch (IOException e) {
                live = false;
                return -2;
            }
            LockSupport.parkNanos(Math.min(1_000_000L, deadline - System.nanoTime()));
        }
        return -1;
    }
}
