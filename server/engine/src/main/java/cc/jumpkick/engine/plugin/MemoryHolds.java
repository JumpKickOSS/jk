// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.time.Clock;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Which builds are on hold for memory. A build is on hold only when it is fully stalled: one of its
 * forks has waited {@link #HOLD_AFTER_NANOS} or more for memory, is still waiting, and none of its
 * forks is running. A wait for a JVM slot, or a memory wait while other forks work, is ordinary
 * scheduling. On hold the surfaces show the hold, the countdown stands still, and the time held is
 * kept out of every learned wall.
 *
 * <p>A hold that ends and starts again within {@link #MERGE_NANOS} is the same hold: its end is
 * reported only once the build has stayed out of the stall that long.
 */
public final class MemoryHolds {

    /** A fork counts toward a hold only after waiting for memory this long without a break. */
    public static final long HOLD_AFTER_NANOS = TimeUnit.SECONDS.toNanos(5);

    /** A stall that comes back within this long continues the hold it ended. */
    static final long MERGE_NANOS = TimeUnit.SECONDS.toNanos(3);

    /** {@link #HOLD_AFTER_NANOS}, or what a test set in its place. */
    private static volatile long holdAfterNanos = HOLD_AFTER_NANOS;

    /** How long a fork waits for memory before it counts toward a hold. */
    static long holdAfterNanos() {
        return holdAfterNanos;
    }

    /** Test seam: count a fork toward a hold after {@code nanos}; {@code 0} restores the default. */
    static void holdAfter(long nanos) {
        holdAfterNanos = nanos > 0 ? nanos : HOLD_AFTER_NANOS;
    }

    /** What the engine does when a build goes on hold or resumes. */
    public interface Listener {
        /** {@code requestId} went on hold: a fork needs {@code needBytes}, {@code freeBytes} are free. */
        void held(long requestId, long needBytes, long freeBytes);

        /** {@code requestId} resumed after {@code heldMillis} on hold. */
        void resumed(long requestId, long heldMillis);
    }

    private static final Listener NONE = new Listener() {
        @Override
        public void held(long requestId, long needBytes, long freeBytes) {}

        @Override
        public void resumed(long requestId, long heldMillis) {}
    };

    private static volatile Listener listener = NONE;

    /**
     * One build's forks: {@code waiting} counts those past the memory threshold, {@code running}
     * those holding a lease. {@code since} is when its hold started and {@code endedAt} when its
     * last stall ended, both {@code 0} when there is none.
     */
    private static final class Build {
        int waiting;
        int running;
        long since;
        long endedAt;
        long needBytes;
        long freeBytes;

        boolean idle() {
            return waiting == 0 && running == 0 && since == 0;
        }
    }

    private static final Map<Long, Build> BUILDS = new HashMap<>();

    /** Builds remembered at most for their total time held; a run reads its total within seconds of its end. */
    private static final int MAX_TOTALS = 256;

    private static final Map<Long, Long> TOTAL_NANOS = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Long> eldest) {
            return size() > MAX_TOTALS;
        }
    });

    private static Clock clock = Clock.SYSTEM;

    /** Runs {@link #settle} once {@link #MERGE_NANOS} has passed. */
    private static final Runnable SETTLE_LATER = () -> SessionContext.startVirtual("jk-hold-settle", () -> {
        try {
            Thread.sleep(TimeUnit.NANOSECONDS.toMillis(MERGE_NANOS) + 50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        settle();
    });

    private static Runnable settleLater = SETTLE_LATER;

    private MemoryHolds() {}

    /** The engine's listener; {@code null} detaches it. */
    public static void listen(@Nullable Listener l) {
        listener = l == null ? NONE : l;
    }

    /** Test seam: the clock holds are timed on. */
    static synchronized void useClock(Clock c) {
        clock = Objects.requireNonNull(c, "clock");
    }

    /** Test seam: what runs when a hold's end needs settling later; {@code null} restores the timer. */
    static synchronized void settleWith(@Nullable Runnable r) {
        settleLater = r == null ? SETTLE_LATER : r;
    }

    /** A fork of {@code requestId} has waited for memory past the threshold and is still waiting. */
    static void waiting(@Nullable Long requestId, long needBytes, long freeBytes) {
        if (requestId == null || requestId <= 0) return;
        change(requestId, b -> {
            b.waiting++;
            if (b.since == 0) {
                b.needBytes = needBytes;
                b.freeBytes = freeBytes;
            }
        });
    }

    /** A fork of {@code requestId} that {@link #waiting} counted is no longer waiting for memory. */
    static void done(@Nullable Long requestId) {
        if (requestId == null || requestId <= 0) return;
        change(requestId, b -> b.waiting = Math.max(0, b.waiting - 1));
    }

    /** A fork of {@code requestId} was granted its lease and runs. */
    static void started(@Nullable Long requestId) {
        if (requestId == null || requestId <= 0) return;
        change(requestId, b -> b.running++);
    }

    /** A running fork of {@code requestId} released its lease. */
    static void ended(@Nullable Long requestId) {
        if (requestId == null || requestId <= 0) return;
        change(requestId, b -> b.running = Math.max(0, b.running - 1));
    }

    private static void change(long requestId, Consumer<Build> edit) {
        boolean started = false;
        boolean schedule = false;
        long need = 0;
        long free = 0;
        Runnable later;
        synchronized (MemoryHolds.class) {
            Build b = BUILDS.computeIfAbsent(requestId, id -> new Build());
            edit.accept(b);
            boolean stalled = b.waiting > 0 && b.running == 0;
            long now = clock.nanos();
            if (stalled) {
                if (b.since == 0) {
                    b.since = now;
                    started = true;
                    need = b.needBytes;
                    free = b.freeBytes;
                }
                b.endedAt = 0;
            } else if (b.since != 0 && b.endedAt == 0) {
                b.endedAt = now;
                schedule = true;
            }
            if (b.idle()) BUILDS.remove(requestId);
            later = settleLater;
        }
        if (started) listener.held(requestId, need, free);
        if (schedule) later.run();
    }

    /** End every hold whose last stall ended {@link #MERGE_NANOS} or more ago. */
    static void settle() {
        endHolds(false, null);
    }

    /** Test seam: end {@code requestId}'s hold now, without waiting out the merge window. */
    static void endNow(long requestId) {
        endHolds(true, requestId);
    }

    private static void endHolds(boolean now, @Nullable Long only) {
        Map<Long, Long> ended = new LinkedHashMap<>();
        synchronized (MemoryHolds.class) {
            long t = clock.nanos();
            var it = BUILDS.entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                Build b = e.getValue();
                if (only != null && !only.equals(e.getKey())) continue;
                if (b.since == 0) continue;
                if (now) {
                    if (b.endedAt == 0) b.endedAt = t;
                    b.waiting = 0;
                } else if (b.endedAt == 0 || t - b.endedAt < MERGE_NANOS) {
                    continue;
                }
                long heldNanos = Math.max(0, b.endedAt - b.since);
                TOTAL_NANOS.merge(e.getKey(), heldNanos, Long::sum);
                ended.put(e.getKey(), heldNanos);
                b.since = 0;
                b.endedAt = 0;
                if (b.idle()) it.remove();
            }
        }
        ended.forEach((rid, nanos) -> listener.resumed(rid, nanos / 1_000_000L));
    }

    /** Whether {@code requestId} is on hold now. */
    public static synchronized boolean onHold(long requestId) {
        Build b = BUILDS.get(requestId);
        return b != null && b.since != 0;
    }

    /** Milliseconds {@code requestId}'s current hold has lasted; 0 when it is not on hold. */
    public static synchronized long heldMillis(long requestId) {
        Build b = BUILDS.get(requestId);
        if (b == null || b.since == 0) return 0;
        long until = b.endedAt != 0 ? b.endedAt : clock.nanos();
        return Math.max(0, until - b.since) / 1_000_000L;
    }

    /** The memory the current hold waits for, {@code {need, free}} bytes; null when not on hold. */
    public static synchronized long @Nullable [] holdNeed(long requestId) {
        Build b = BUILDS.get(requestId);
        return b == null || b.since == 0 ? null : new long[] {b.needBytes, b.freeBytes};
    }

    /** Milliseconds {@code requestId} has spent on hold in all, the current hold included. */
    public static long totalHeldMillis(long requestId) {
        Long done = TOTAL_NANOS.get(requestId);
        return (done == null ? 0 : done / 1_000_000L) + heldMillis(requestId);
    }
}
