// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import java.nio.file.Path;

/**
 * Per-lock-dir monitor serializing every engine-side {@code jk-lock.toml} resolution — CLI lock
 * cascade, HTTP lock job, workspace pre-build freshen ({@code LockFlow}) and single-module
 * auto-lock ({@code AutoLock}) all synchronize on {@link #monitorFor} so concurrent re-locks of one
 * checkout single-flight instead of racing the same file. Conservative freshens re-check
 * staleness after acquiring the monitor and skip when a concurrent job already freshened.
 */
public final class LockGate {

    /**
     * Striped, not keyed: a clear-on-overflow map could invalidate a monitor another thread was
     * holding, and a monitor map must never do that. Two checkouts sharing a stripe only
     * over-serialize; memory is bounded forever.
     */
    private static final Object[] MONITORS = new Object[64];

    static {
        for (int i = 0; i < MONITORS.length; i++) MONITORS[i] = new Object();
    }

    private LockGate() {}

    /** The monitor object for one lock owner dir (normalized); never {@code null}. */
    public static Object monitorFor(Path lockDir) {
        String key = lockDir.toAbsolutePath().normalize().toString();
        return MONITORS[Math.floorMod(key.hashCode(), MONITORS.length)];
    }
}
