// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.Optional;

/** Non-live session when {@code /dev/tty} / {@code CONIN$} cannot be opened. */
final class DeadTerminal implements TerminalSession {
    static final DeadTerminal INSTANCE = new DeadTerminal();

    private final PrintWriter out = new PrintWriter(new StringWriter());

    private DeadTerminal() {}

    @Override
    public boolean isLive() {
        return false;
    }

    @Override
    public InputMode mode() {
        return InputMode.COOKED;
    }

    @Override
    public ModeGuard enter(InputMode mode) {
        return ModeGuard.noop();
    }

    @Override
    public Optional<Key> readKey(Duration timeout) {
        requireTimeout(timeout);
        return Optional.empty();
    }

    @Override
    public void drain(Duration max) {
        requireTimeout(max);
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
        // nothing — never owned fds
    }

    static void requireTimeout(Duration timeout) {
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be >= 0");
        }
    }
}
