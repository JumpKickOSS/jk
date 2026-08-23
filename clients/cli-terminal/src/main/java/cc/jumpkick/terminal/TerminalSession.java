// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal;

import java.io.PrintWriter;
import java.time.Duration;
import java.util.Optional;

/**
 * One controlling TTY (or in-memory test backend). Production callers never
 * {@code try-with-resources} the process singleton — only {@link MemoryTerminal} is owned that way.
 */
public sealed interface TerminalSession extends AutoCloseable permits NativeTerminal, MemoryTerminal, DeadTerminal {

    boolean isLive();

    InputMode mode();

    /** Push {@code mode}, return a guard that pops. Nested enters are a stack. */
    ModeGuard enter(InputMode mode);

    /**
     * The only public timed/blocking key read.
     * {@code timeout.isZero()} waits forever (still clock-sliced on spurious EAGAIN).
     */
    Optional<Key> readKey(Duration timeout);

    /** Discard pending input for up to {@code max}. */
    void drain(Duration max);

    /** Writer to the controlling TTY. Not {@code System.out}. */
    PrintWriter ttyOut();

    Size.Window size();

    /**
     * Singleton: restore original attrs, clear mode stack, keep fds, keep {@code isLive}.
     * {@link MemoryTerminal}: close the pipes, {@code isLive=false}.
     */
    @Override
    void close();
}
