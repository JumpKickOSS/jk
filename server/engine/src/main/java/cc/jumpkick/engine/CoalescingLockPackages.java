// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;

/**
 * Human-paced {@code lock-package} wire events: at most one emit per cadence with the latest
 * package and a running total. The client should prefer the CLI's
 * {@code EngineRequests.LockCounts} for the final count (not message cardinality).
 *
 * <p>Same cadence as {@link CoalescingBuildPlanListener} ({@code JK_WIRE_PROGRESS_MS}, default
 * 500 ms).
 */
public final class CoalescingLockPackages implements AutoCloseable {

    @FunctionalInterface
    public interface Emitter {
        void emit(@Nullable String dir, String name, @Nullable String version, int totalSeen);
    }

    private final Emitter emitter;
    private final long cadenceMs;
    private final Object lock = new Object();

    private @Nullable String dir;
    private @Nullable String name;
    private @Nullable String version;
    private int totalSeen;
    private long lastFlushNanos;
    private @Nullable ScheduledFuture<?> scheduled;
    private final AtomicBoolean closed = new AtomicBoolean();

    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "jk-wire-lock-packages");
        t.setDaemon(true);
        return t;
    });

    public CoalescingLockPackages(Emitter emitter) {
        this(emitter, CoalescingBuildPlanListener.cadenceFromEnv());
    }

    public CoalescingLockPackages(Emitter emitter, long cadenceMs) {
        this.emitter = Objects.requireNonNull(emitter, "emitter");
        this.cadenceMs = Math.max(0L, cadenceMs);
    }

    public void onPackage(String dir, String name, String version) {
        if (cadenceMs == 0) {
            emitter.emit(dir, name, version, -1);
            return;
        }
        synchronized (lock) {
            this.dir = dir;
            this.name = name;
            this.version = version;
            this.totalSeen++;
            scheduleLocked();
        }
    }

    public void flush() {
        if (closed.get()) return;
        synchronized (lock) {
            cancelScheduledLocked();
            emitPendingLocked();
            lastFlushNanos = System.nanoTime();
        }
    }

    /** Caller holds {@link #lock}. */
    private void emitPendingLocked() {
        if (name == null) return;
        String d = dir;
        String n = name;
        String v = version;
        int total = totalSeen;
        dir = null;
        name = null;
        version = null;
        // keep totalSeen cumulative for next batch's total
        emitter.emit(d, n, v, total);
    }

    /**
     * Caller holds {@link #lock}. First pending package opens a cadence window; samples land on the
     * timer or when the window has elapsed (or on explicit {@link #flush}).
     */
    private void scheduleLocked() {
        if (closed.get()) return;
        long now = System.nanoTime();
        if (lastFlushNanos == 0L) {
            lastFlushNanos = now;
            if (scheduled == null || scheduled.isDone()) {
                scheduled = SCHEDULER.schedule(this::flushSafe, cadenceMs, TimeUnit.MILLISECONDS);
            }
            return;
        }
        long elapsedMs = (now - lastFlushNanos) / 1_000_000L;
        if (elapsedMs >= cadenceMs) {
            emitPendingLocked();
            lastFlushNanos = now;
            return;
        }
        if (scheduled != null && !scheduled.isDone()) return;
        scheduled = SCHEDULER.schedule(this::flushSafe, cadenceMs - elapsedMs, TimeUnit.MILLISECONDS);
    }

    private void cancelScheduledLocked() {
        if (scheduled != null) {
            scheduled.cancel(false);
            scheduled = null;
        }
    }

    private void flushSafe() {
        try {
            flush();
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void close() {
        // Flush BEFORE marking closed — flush no-ops once closed, so the old order
        // silently dropped whatever was still pending.
        flush();
        if (!closed.compareAndSet(false, true)) return;
        synchronized (lock) {
            cancelScheduledLocked();
        }
    }
}
