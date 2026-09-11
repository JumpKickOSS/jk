// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal;

import cc.jumpkick.terminal.posix.PosixTty;
import cc.jumpkick.terminal.windows.WindowsConsole;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;

/** Thin dispatcher: mode stack, lock, isLive. FFM lives in {@link PosixTty} / {@link WindowsConsole}. */
final class NativeTerminal implements TerminalSession {
    private final ReentrantLock lock = new ReentrantLock();
    private final Deque<InputMode> stack = new ArrayDeque<>();
    private final @Nullable PosixTty posix;
    private final @Nullable WindowsConsole windows;
    private final PrintWriter out;
    private volatile boolean live = true;
    private boolean fdsClosed;
    private int pushback = -1;
    private final Keys.ByteFeed feed = new Keys.ByteFeed() {
        @Override
        public int read(Duration timeout) {
            return readDevice(timeout);
        }

        @Override
        public void unread(int b) {
            pushback = b;
        }
    };

    NativeTerminal(@Nullable PosixTty posix, @Nullable WindowsConsole windows) {
        this.posix = posix;
        this.windows = windows;
        this.out = new PrintWriter(
                new OutputStreamWriter(new TtyOutputStream(posix, windows, this::isLive), StandardCharsets.UTF_8),
                true);
    }

    @Override
    public boolean isLive() {
        return live;
    }

    @Override
    public InputMode mode() {
        lock.lock();
        try {
            InputMode top = stack.peek();
            return top == null ? InputMode.COOKED : top;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public ModeGuard enter(InputMode mode) {
        lock.lock();
        try {
            if (!live) {
                return ModeGuard.noop();
            }
            stack.push(mode);
            apply(mode);
            return new ModeGuard(this::pop);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<Key> readKey(Duration timeout) {
        DeadTerminal.requireTimeout(timeout);
        lock.lock();
        boolean ok;
        try {
            ok = live && !fdsClosed;
        } finally {
            lock.unlock();
        }
        if (!ok) {
            return Optional.empty();
        }
        int b = readDevice(timeout);
        lock.lock();
        try {
            if (!live) {
                return Optional.empty();
            }
        } finally {
            lock.unlock();
        }
        if (b == -2) {
            live = false;
            return Optional.empty();
        }
        if (b < 0) {
            return Optional.empty();
        }
        return Optional.of(Keys.dispatch(b, feed));
    }

    @Override
    public void drain(Duration max) {
        DeadTerminal.requireTimeout(max);
        long deadline = System.nanoTime() + (max.isZero() ? 0 : max.toNanos());
        while (isLive() && System.nanoTime() < deadline) {
            Optional<Key> k = readKey(Duration.ofMillis(1));
            if (k.isEmpty()) {
                return;
            }
        }
    }

    @Override
    public PrintWriter ttyOut() {
        return out;
    }

    @Override
    public Size.Window size() {
        return Size.current();
    }

    @Override
    public void close() {
        lock.lock();
        try {
            stack.clear();
            restore();
            // keep fds and isLive — only shutdown() closes fds
        } finally {
            lock.unlock();
        }
    }

    void shutdown() {
        lock.lock();
        try {
            stack.clear();
            restore();
            live = false;
            if (!fdsClosed) {
                fdsClosed = true;
                if (posix != null) {
                    posix.close();
                }
                if (windows != null) {
                    windows.close();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    void restoreForChild() {
        lock.lock();
        try {
            stack.clear();
            restore();
        } finally {
            lock.unlock();
        }
    }

    private void pop() {
        lock.lock();
        try {
            if (stack.isEmpty()) {
                return;
            }
            stack.pop();
            apply(stack.isEmpty() ? InputMode.COOKED : stack.peek());
        } finally {
            lock.unlock();
        }
    }

    private void apply(InputMode mode) {
        if (posix != null) {
            posix.apply(mode);
        }
        if (windows != null) {
            windows.apply(mode);
        }
    }

    private int readDevice(Duration timeout) {
        if (pushback >= 0) {
            int b = pushback;
            pushback = -1;
            return b;
        }
        return posix != null
                ? posix.readByte(timeout, this::isLive)
                : Objects.requireNonNull(windows, "a native terminal has a POSIX or a Windows backend")
                        .readByte(timeout, this::isLive);
    }

    private void restore() {
        if (posix != null) {
            posix.restoreOriginal();
        }
        if (windows != null) {
            windows.restoreOriginal();
        }
    }
}
