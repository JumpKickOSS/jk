// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.terminal.InputMode;
import cc.jumpkick.terminal.Key;
import cc.jumpkick.terminal.ModeGuard;
import cc.jumpkick.terminal.TerminalSession;
import cc.jumpkick.terminal.Terminals;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The Ctrl-O key half of the process-output peek, and the only thing in {@code tui/} that puts the
 * controlling TTY into a non-default input mode while a plan is live. Companion to {@link
 * OutputWindow}, which owns the ring buffer half.
 *
 * <p>Invariant: the terminal attributes this borrows are always given back. {@link #attach} either
 * returns a listener that owns a live {@link ModeGuard}, or returns {@code null} having closed
 * whatever it opened; {@link #close} is idempotent and restores the attributes exactly once, so a
 * Ctrl-C settle racing the plan-starting thread cannot leave the shell in {@code PLAN_KEYS} mode.
 * That is why the session, the guard and the reader thread are one object and not three fields on
 * the live region.
 *
 * <p>ISIG stays on, so Ctrl-C still raises SIGINT for {@link GlobalCancel}. Best-effort: when the
 * terminal cannot be opened, peek is keyboard-unavailable for this plan and the plan runs on.
 */
@NullMarked
final class PeekKeys implements AutoCloseable {

    /** Serializes start/stop so the take-restore-null sequence is atomic. */
    private final Object lock = new Object();

    private final Runnable onCtrlO;
    private final BooleanSupplier finished;

    private @Nullable TerminalSession terminal;
    private @Nullable ModeGuard mode;
    private @Nullable Thread thread;

    /** Volatile: unblocks {@link #readKeys} before {@link #close} takes {@link #lock}. */
    private volatile boolean stopped;

    private PeekKeys(Runnable onCtrlO, BooleanSupplier finished) {
        this.onCtrlO = onCtrlO;
        this.finished = finished;
    }

    /**
     * Start a non-blocking Ctrl-O listener on the controlling TTY. {@code finished} is polled by the
     * reader thread and ends it when the live region settles. Returns {@code null} when there is no
     * interactive controlling terminal — the caller keeps peek available through its other trigger
     * (a tool crash force-opens it).
     */
    static @Nullable PeekKeys attach(Runnable onCtrlO, BooleanSupplier finished) {
        if (!Interactivity.canPrompt()) return null;
        PeekKeys keys = new PeekKeys(onCtrlO, finished);
        return keys.start() ? keys : null;
    }

    private boolean start() {
        synchronized (lock) {
            ModeGuard opened = null;
            try {
                TerminalSession t = Terminals.controlling();
                if (!t.isLive()) return false;
                opened = t.enter(InputMode.PLAN_KEYS);
                t.drain(Duration.ofMillis(40));
                terminal = t;
                mode = opened;
                thread = new Thread(this::readKeys, "jk-output-keys");
                thread.setDaemon(true);
                thread.start();
                return true;
            } catch (Exception ignored) {
                closeQuietly(opened);
                terminal = null;
                mode = null;
                return false;
            }
        }
    }

    /** Stop the reader and restore the terminal attributes. Idempotent. */
    @Override
    public void close() {
        stopped = true;
        Thread reader;
        ModeGuard guard;
        synchronized (lock) {
            reader = thread;
            thread = null;
            terminal = null;
            guard = mode;
            mode = null;
        }
        if (reader != null) {
            reader.interrupt();
            try {
                reader.join(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        closeQuietly(guard);
    }

    private static void closeQuietly(@Nullable ModeGuard guard) {
        if (guard == null) return;
        try {
            guard.close();
        } catch (RuntimeException ignored) {
            // best-effort: the region is going away either way
        }
    }

    private void readKeys() {
        TerminalSession t;
        synchronized (lock) {
            t = terminal;
        }
        if (t == null) return;
        while (!stopped && !finished.getAsBoolean()) {
            var key = t.readKey(Duration.ofMillis(100));
            if (key.isEmpty()) {
                if (!t.isLive()) return;
                continue;
            }
            if (key.get() instanceof Key.CtrlO) {
                onCtrlO.run();
            }
        }
    }
}
