// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.host.Log;
import cc.jumpkick.host.time.Clock;
import org.jspecify.annotations.Nullable;

/**
 * Wall-clock probes for build-latency work, written to the log at debug with a leading {@code
 * perf} marker: {@code perf <label> ms=<n>}, or {@code perf <label> key=value …} for an
 * observation beside the timings. Costs one level check when the log is above debug. Keep call
 * sites coarse (one per plan stage), never per-file.
 */
public final class Perf {

    private Perf() {}

    /** True when probes are written: the log threshold is debug. */
    public static boolean enabled() {
        return Log.debugEnabled();
    }

    public static long start() {
        return enabled() ? System.nanoTime() : 0;
    }

    public static void end(String label, long startNanos) {
        if (!enabled()) return;
        long ms = (System.nanoTime() - startNanos) / 1_000_000;
        Log.debug("perf " + label, "ms", ms);
    }

    /** As {@link #end(String, long)} with an observation beside the timing: {@code perf <label> ms=<n> key=value …}. */
    public static void end(String label, long startNanos, @Nullable Object... detail) {
        if (!enabled()) return;
        long ms = (Clock.SYSTEM.nanos() - startNanos) / 1_000_000;
        Object[] all = new Object[detail.length + 2];
        all[0] = "ms";
        all[1] = ms;
        System.arraycopy(detail, 0, all, 2, detail.length);
        Log.debug("perf " + label, all);
    }

    /** An observation beside the timings: {@code perf <label> key=value …}. */
    public static void note(String label, @Nullable Object... detail) {
        if (!enabled()) return;
        Log.debug("perf " + label, detail);
    }
}
