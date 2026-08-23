// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal;

/**
 * Pops a pushed {@link InputMode}. After session {@code close()}/{@code shutdown()} this is a
 * no-op — the stack is already empty.
 */
public final class ModeGuard implements AutoCloseable {
    private final Runnable pop;
    private boolean closed;

    ModeGuard(Runnable pop) {
        this.pop = pop;
    }

    static ModeGuard noop() {
        return new ModeGuard(() -> {});
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        pop.run();
    }
}
