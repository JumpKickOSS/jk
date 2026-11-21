// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.watch;

import cc.jumpkick.cli.tui.Interactivity;
import cc.jumpkick.terminal.Ansi;
import cc.jumpkick.terminal.InputMode;
import cc.jumpkick.terminal.Key;
import cc.jumpkick.terminal.ModeGuard;
import cc.jumpkick.terminal.TerminalSession;
import cc.jumpkick.terminal.Terminals;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.jspecify.annotations.Nullable;

/**
 * The keys {@code jk dev} answers on a terminal: {@code r} rebuilds and restarts, {@code c} clears
 * the screen, {@code q} stops the session. Read in {@link InputMode#PLAN_KEYS}, so Ctrl-C still
 * raises SIGINT. While they are read the terminal is jk's: the app's stdin is not the terminal, and
 * a build's own key listener reads it while {@link #pause} holds these back.
 */
final class DevKeys implements AutoCloseable {

    /** What a key asks the loop to do. */
    enum Action {
        RESTART,
        CLEAR,
        QUIT
    }

    /** The line that tells the keys, under the loop's prefix. */
    static final String HINT = "press r to rebuild and restart, c to clear, q to quit";

    private final BlockingQueue<Action> actions = new LinkedBlockingQueue<>();
    private final Object lock = new Object();
    private final TerminalSession terminal;
    private @Nullable ModeGuard mode;
    private @Nullable Thread reader;
    private volatile boolean stopped;

    /** Guards {@link #paused} and {@link #reading}; the reader waits on it while paused. */
    private final Object gate = new Object();

    private boolean paused;
    private boolean reading;

    private DevKeys(TerminalSession terminal) {
        this.terminal = terminal;
    }

    /** The keys on the controlling terminal; null under JSON output or with no terminal to read. */
    static @Nullable DevKeys attach(boolean json) {
        if (json || !Interactivity.canPrompt()) return null;
        return attach(Terminals.controlling());
    }

    /** {@link #attach(boolean)} on an explicit terminal; null when it is not live. */
    static @Nullable DevKeys attach(TerminalSession t) {
        DevKeys keys = new DevKeys(t);
        synchronized (keys.lock) {
            try {
                if (!t.isLive()) return null;
                keys.mode = t.enter(InputMode.PLAN_KEYS);
            } catch (RuntimeException e) {
                return null;
            }
            Thread thread = new Thread(keys::read, "jk-dev-keys");
            thread.setDaemon(true);
            keys.reader = thread;
            thread.start();
        }
        return keys;
    }

    /** The oldest key pressed and not yet taken; null when none is waiting. */
    @Nullable
    Action next() {
        return actions.poll();
    }

    /**
     * Hold the keys back until {@link #resume}, so a build's live view reads the terminal meanwhile.
     * Returns once a read already under way has ended: no key typed after it is taken here.
     */
    void pause() throws InterruptedException {
        synchronized (gate) {
            paused = true;
            while (reading) gate.wait();
        }
    }

    void resume() {
        synchronized (gate) {
            paused = false;
            gate.notifyAll();
        }
    }

    /** Clear the screen and its scrollback, the cursor home. */
    void clearScreen() {
        PrintWriter out = terminal.ttyOut();
        out.print(Ansi.CSI + "H" + Ansi.CSI + "2J" + Ansi.CSI + "3J");
        out.flush();
    }

    /** Stop reading and give the terminal its mode back. Idempotent. */
    @Override
    public void close() {
        stopped = true;
        Thread thread;
        ModeGuard guard;
        synchronized (lock) {
            thread = reader;
            reader = null;
            guard = mode;
            mode = null;
        }
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (guard != null) {
            try {
                guard.close();
            } catch (RuntimeException ignored) {
                // the session is ending either way
            }
        }
    }

    private void read() {
        // Keys typed before the session started are not commands.
        terminal.drain(Duration.ofMillis(40));
        while (!stopped) {
            synchronized (gate) {
                try {
                    while (paused && !stopped) gate.wait();
                } catch (InterruptedException e) {
                    return;
                }
                reading = true;
            }
            Optional<Key> key;
            try {
                key = terminal.readKey(Duration.ofMillis(100));
            } finally {
                synchronized (gate) {
                    reading = false;
                    gate.notifyAll();
                }
            }
            if (key.isEmpty()) {
                if (!terminal.isLive()) return;
                continue;
            }
            Action action = actionOf(key.get());
            if (action != null) actions.add(action);
        }
    }

    /** The action {@code key} asks for, either case; null for any other key. */
    static @Nullable Action actionOf(Key key) {
        if (!(key instanceof Key.Char(char c))) return null;
        return switch (Character.toLowerCase(c)) {
            case 'r' -> Action.RESTART;
            case 'c' -> Action.CLEAR;
            case 'q' -> Action.QUIT;
            default -> null;
        };
    }
}
