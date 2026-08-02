// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-lock-dir monitor serializing every engine-side {@code jk-lock.toml} resolution — CLI lock
 * cascade, HTTP lock job, workspace pre-build freshen ({@code LockFlow}) and single-module
 * auto-lock ({@code AutoLock}) all synchronize on {@link #monitorFor} so concurrent re-locks of one
 * checkout single-flight instead of racing the same file (JK-1356). Conservative freshens re-check
 * staleness after acquiring the monitor and skip when a concurrent job already freshened.
 */
public final class LockGate {

    private static final ConcurrentHashMap<String, Object> MONITORS = new ConcurrentHashMap<>();

    private LockGate() {}

    /** The monitor object for one lock owner dir (normalized); never {@code null}. */
    public static Object monitorFor(Path lockDir) {
        String key = lockDir.toAbsolutePath().normalize().toString();
        return MONITORS.computeIfAbsent(key, k -> new Object());
    }
}
