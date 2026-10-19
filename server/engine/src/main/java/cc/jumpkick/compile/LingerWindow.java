// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * How long a warm compiler pool waits for the next build: long enough to catch the loop the
 * builds are arriving in, no longer.
 *
 * <p>The gaps are between builds, not compiles: from the last item of one job settling to the first
 * item of the next job arriving. Items of one build come back to back and say nothing about the
 * loop. The window is twice the slower quartile of the last {@link #KEPT} gaps, never under {@link
 * #FLOOR_MS} and never over {@link #CEILING_MS}: an agent rebuilding every few seconds keeps the
 * floor, a person rebuilding every two minutes keeps the worker four, and a gap past the ceiling —
 * someone who left — forgets the loop, so the next build's worker goes after the floor again.
 * Under CI the window is {@link #CI_MS} flat: a runner's next job is the loop, and an ephemeral one
 * takes the worker with it either way.
 */
final class LingerWindow {

    static final long FLOOR_MS = 60_000L;

    static final long CEILING_MS = TimeUnit.MINUTES.toMillis(10);

    static final long CI_MS = TimeUnit.MINUTES.toMillis(10);

    static final int KEPT = 8;

    private final long[] gaps = new long[KEPT];
    private int count;
    private int next;
    private long lastJob;
    private long lastSettledNanos = -1L;
    private int inflight;

    /** An item of {@code job} arrives at {@code nanos}; the first of a new job after a quiet spell is a gap. */
    synchronized void arrived(long job, long nanos) {
        if (inflight == 0 && lastSettledNanos >= 0 && job != lastJob) {
            record(TimeUnit.NANOSECONDS.toMillis(nanos - lastSettledNanos));
        }
        inflight++;
        lastJob = job;
    }

    /** An item settled at {@code nanos}. */
    synchronized void settled(long nanos) {
        if (inflight > 0) inflight--;
        lastSettledNanos = nanos;
    }

    private void record(long gapMillis) {
        if (gapMillis > CEILING_MS) {
            count = 0;
            next = 0;
            return;
        }
        gaps[next] = Math.max(0L, gapMillis);
        next = (next + 1) % KEPT;
        count = Math.min(KEPT, count + 1);
    }

    /** The window for a host not under CI: twice the gaps' upper quartile, within floor and ceiling. */
    synchronized long millis() {
        if (count == 0) return FLOOR_MS;
        long[] sorted = Arrays.copyOf(gaps, count);
        Arrays.sort(sorted);
        long upper = sorted[Math.min(count - 1, (count * 3) / 4)];
        return Math.min(CEILING_MS, Math.max(FLOOR_MS, upper * 2));
    }
}
