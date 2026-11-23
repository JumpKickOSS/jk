// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.host.Log;
import cc.jumpkick.host.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
        return enabled() ? Clock.SYSTEM.nanos() : 0;
    }

    public static void end(String label, long startNanos) {
        if (!enabled()) return;
        long ms = (Clock.SYSTEM.nanos() - startNanos) / 1_000_000;
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

    /** Laps of one stage started now, each timed from the one before; all no-ops when probes are off. */
    public static Laps laps() {
        return new Laps(enabled());
    }

    /**
     * The phases of one stage, written as one line: {@code perf <label> ms=<total> <phase>=<ms> …},
     * in the order they were timed, then any observation the caller adds.
     */
    public static final class Laps {
        private final boolean on;
        private final long started;
        private final List<Object> phases = new ArrayList<>();
        private long last;

        Laps(boolean on) {
            this.on = on;
            this.started = on ? Clock.SYSTEM.nanos() : 0;
            this.last = started;
        }

        /** Close the phase since the last lap (or the start) as {@code phase}. */
        public void lap(String phase) {
            if (!on) return;
            long now = Clock.SYSTEM.nanos();
            phases.add(phase);
            phases.add((now - last) / 1_000_000);
            last = now;
        }

        /** {@code value}, once the phase since the last lap that computed it is closed as {@code phase}. */
        public <T extends @Nullable Object> T lap(String phase, T value) {
            lap(phase);
            return value;
        }

        /** Close the last phase as {@code phase}, then write the line: the total, each phase, then {@code detail}. */
        public void end(String phase, String label, @Nullable Object... detail) {
            if (!on) return;
            lap(phase);
            List<Object> all = new ArrayList<>();
            all.add("ms");
            all.add((Clock.SYSTEM.nanos() - started) / 1_000_000);
            all.addAll(phases);
            all.addAll(Arrays.asList(detail));
            Log.debug("perf " + label, all.toArray());
        }

        /** The phases timed so far, alternating name and milliseconds. */
        List<Object> phases() {
            return List.copyOf(phases);
        }
    }
}
