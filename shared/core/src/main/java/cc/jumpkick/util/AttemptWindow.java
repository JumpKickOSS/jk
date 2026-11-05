// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * At most one network attempt per key per window, for a best-effort fetch the engine's request path
 * reaches again and again: a catalog clone, a registry download. Without it an unreachable origin is
 * dialed by every command that wants the feed, and on a network that drops the packets each of
 * those commands waits out the full timeout.
 *
 * <p>Readings are a monotonic nanosecond clock passed in by the caller, so a test moves time by
 * hand. The state lives in this object, and the engine is the one long-lived process holding it;
 * a key is whatever names the remote (a cache key, a URL).
 */
public final class AttemptWindow {

    private final ConcurrentHashMap<String, Long> lastNanos = new ConcurrentHashMap<>();
    private final long windowNanos;

    /** A window of {@code window} after each recorded attempt. */
    public AttemptWindow(Duration window) {
        Objects.requireNonNull(window, "window");
        this.windowNanos = window.toNanos();
    }

    /** The window's length. */
    public Duration window() {
        return Duration.ofNanos(windowNanos);
    }

    /**
     * True when the caller won the attempt slot for {@code key}: no attempt recorded in the last
     * window. Atomically records this one, so of several callers at once exactly one wins. For a
     * fetch where a success is as good a reason to wait as a failure.
     */
    public boolean tryAcquire(String key, long nowNanos) {
        boolean[] won = {false};
        lastNanos.compute(key, (k, last) -> {
            if (last != null && nowNanos - last < windowNanos) return last;
            won[0] = true;
            return nowNanos;
        });
        return won[0];
    }

    /** True when no attempt is recorded for {@code key} in the window ending at {@code nowNanos}. */
    public boolean open(String key, long nowNanos) {
        Long last = lastNanos.get(key);
        return last == null || nowNanos - last >= windowNanos;
    }

    /** Record an attempt at {@code nowNanos}, closing the window for {@code key}. */
    public void record(String key, long nowNanos) {
        lastNanos.put(key, nowNanos);
    }

    /** Forget every recorded attempt. */
    public void clear() {
        lastNanos.clear();
    }
}
