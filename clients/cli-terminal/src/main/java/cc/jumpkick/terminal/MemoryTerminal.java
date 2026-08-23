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
            try {
                if (in.available() > 0) {
                    int b = in.read();
                    if (b < 0) {
                        live = false;
                        return Optional.empty();
                    }
                    return Optional.of(Keys.mapByte(b));
                }
            } catch (IOException e) {
                live = false;
                return Optional.empty();
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
}
