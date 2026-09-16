// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import java.util.concurrent.Future;
import org.jetbrains.annotations.Nullable;

/**
 * Collapses a burst of manifest changes into one action after a quiet window: a {@code jk add}
 * rewrites {@code jk.toml} and {@code jk-lock.toml} in quick succession and should cost one sync.
 * Thread-safe; the scheduler and the action are injected so the rule is testable without an IDE.
 */
final class JkSyncDebouncer {

    /** Quiet window before a re-resolve, in milliseconds. */
    static final long QUIET_MS = 2_000;

    /** A delayed executor; the returned future must support {@link Future#cancel}. */
    interface Scheduler {
        Future<?> schedule(Runnable task, long delayMs);
    }

    private final long quietMs;
    private final Scheduler scheduler;
    private final Runnable action;
    private @Nullable Future<?> pending;

    JkSyncDebouncer(long quietMs, Scheduler scheduler, Runnable action) {
        this.quietMs = quietMs;
        this.scheduler = scheduler;
        this.action = action;
    }

    /** A change happened: restart the quiet window. */
    synchronized void touch() {
        if (pending != null) pending.cancel(false);
        pending = scheduler.schedule(this::fire, quietMs);
    }

    /** Whether a run is waiting for the window to close. */
    synchronized boolean isPending() {
        return pending != null;
    }

    private void fire() {
        synchronized (this) {
            pending = null;
        }
        action.run();
    }
}
