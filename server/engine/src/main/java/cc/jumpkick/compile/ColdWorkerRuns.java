// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The requests whose build started a compiler worker: it paid the worker's warm-up. Their compile walls carry the
 * worker's JVM and JIT warm-up — the early modules of a Helidon chain compile 3–7x slower — and the
 * ETA prices modules from compile walls averaged over runs, so one such run inflated every later
 * warm estimate. Their compile walls are kept out of the run's metrics; the warm-up is priced once,
 * as the cold-worker shape's learned correction.
 */
public final class ColdWorkerRuns {

    private ColdWorkerRuns() {}

    /** Requests remembered at most; a run's metrics are written within seconds of its end. */
    private static final int MAX = 256;

    /** Oldest first out once full, so a run still in flight keeps its mark. */
    private static final Set<Long> COLD = Collections.synchronizedSet(Collections.newSetFromMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
            return size() > MAX;
        }
    }));

    /** A compiler worker was started for a compile of {@code requestId}. */
    public static void mark(@Nullable Long requestId) {
        if (requestId == null || requestId <= 0) return;
        COLD.add(requestId);
    }

    /** Record {@code requestId}'s compile walls after all: there is no warm history to protect. */
    public static void keepWalls(@Nullable Long requestId) {
        if (requestId != null) COLD.remove(requestId);
    }

    /** Whether {@code requestId}'s build started cold. */
    public static boolean cold(long requestId) {
        return COLD.contains(requestId);
    }
}
