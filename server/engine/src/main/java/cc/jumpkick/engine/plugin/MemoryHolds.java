// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.host.time.Clock;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Which builds are on hold for memory: a request is on hold while at least one of its forks has
 * waited {@link #HOLD_AFTER_NANOS} or more for a worker lease and is still waiting. A wait is an
 * exceptional pause, not work: the surfaces show it as a hold, the countdown stands still through
 * it, and the time held is kept out of every learned wall.
 *
 * <p>The engine registers one {@link Listener}; {@link WorkerLeases} reports each waiter that
 * crosses the threshold and each that leaves the queue (granted, cancelled or refused). Several
 * forks of one build may wait at once: the hold starts with the first and ends with the last.
 */
public final class MemoryHolds {

    /** A wait shorter than this is not a hold: a lease freed a moment later is ordinary queueing. */
    public static final long HOLD_AFTER_NANOS = 500_000_000L;

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

    /** A request on hold: forks waiting and when its hold started. */
    private record Hold(int waiting, long sinceNanos, long needBytes, long freeBytes) {}

    private static final Map<Long, Hold> HOLDS = new HashMap<>();

    /** Builds remembered at most for their total time held; a run reads its total within seconds of its end. */
    private static final int MAX_TOTALS = 256;

    private static final Map<Long, Long> TOTAL_NANOS = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Long> eldest) {
            return size() > MAX_TOTALS;
        }
    });

    private static Clock clock = Clock.SYSTEM;

    private MemoryHolds() {}

    /** The engine's listener; {@code null} detaches it. */
    public static void listen(@Nullable Listener l) {
        listener = l == null ? NONE : l;
    }

    /** Test seam: the clock holds are timed on. */
    static synchronized void useClock(Clock c) {
        clock = Objects.requireNonNull(c, "clock");
    }

    /** A fork of {@code requestId} has waited past the threshold and is still waiting. */
    static void waiting(@Nullable Long requestId, long needBytes, long freeBytes) {
        if (requestId == null || requestId <= 0) return;
        boolean started;
        synchronized (MemoryHolds.class) {
            Hold h = HOLDS.get(requestId);
            started = h == null;
            Hold next = h == null
                    ? new Hold(1, clock.nanos(), needBytes, freeBytes)
                    : new Hold(h.waiting() + 1, h.sinceNanos(), h.needBytes(), h.freeBytes());
            HOLDS.put(requestId, next);
        }
        if (started) listener.held(requestId, needBytes, freeBytes);
    }

    /** A fork of {@code requestId} that {@link #waiting} counted left the queue. */
    static void done(@Nullable Long requestId) {
        if (requestId == null || requestId <= 0) return;
        long heldNanos = -1;
        synchronized (MemoryHolds.class) {
            Hold h = HOLDS.get(requestId);
            if (h == null) return;
            if (h.waiting() > 1) {
                HOLDS.put(requestId, new Hold(h.waiting() - 1, h.sinceNanos(), h.needBytes(), h.freeBytes()));
            } else {
                HOLDS.remove(requestId);
                heldNanos = Math.max(0, clock.nanos() - h.sinceNanos());
                TOTAL_NANOS.merge(requestId, heldNanos, Long::sum);
            }
        }
        if (heldNanos >= 0) listener.resumed(requestId, heldNanos / 1_000_000L);
    }

    /** Whether {@code requestId} is on hold now. */
    public static synchronized boolean onHold(long requestId) {
        return HOLDS.containsKey(requestId);
    }

    /** Milliseconds {@code requestId}'s current hold has lasted; 0 when it is not on hold. */
    public static synchronized long heldMillis(long requestId) {
        Hold h = HOLDS.get(requestId);
        return h == null ? 0 : Math.max(0, clock.nanos() - h.sinceNanos()) / 1_000_000L;
    }

    /** The memory the current hold waits for, {@code {need, free}} bytes; null when not on hold. */
    public static synchronized long @Nullable [] holdNeed(long requestId) {
        Hold h = HOLDS.get(requestId);
        return h == null ? null : new long[] {h.needBytes(), h.freeBytes()};
    }

    /** Milliseconds {@code requestId} has spent on hold in all, the current hold included. */
    public static long totalHeldMillis(long requestId) {
        Long done = TOTAL_NANOS.get(requestId);
        return (done == null ? 0 : done / 1_000_000L) + heldMillis(requestId);
    }
}
