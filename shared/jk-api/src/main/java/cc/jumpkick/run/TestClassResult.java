// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import java.util.List;

/**
 * One test class as it finished: its tests' outcomes and the class's wall. Sent as the class
 * completes, so a run cut short still records every class that got that far.
 *
 * @param tests every test of the class that finished or was skipped
 * @param failed the tests that failed, or 1 for a class whose setup failed before any test ran
 * @param skipped the tests that were skipped or aborted
 * @param durationMs the class's wall, setup and teardown included
 * @param tier the tier the run executed ({@link SlowestTests#FAST_TIER}, a profile name, or a tag
 *     list), empty when unknown
 * @param slow the class's methods at or over {@link SlowestTests#SLOW_MS}, longest first, at most
 *     {@link SlowestTests#TOP}
 * @param replayedFrom 0 for a class that ran in this run; for one whose last outcome was replayed
 *     instead, when that outcome was recorded, in epoch millis ({@link #REPLAYED_UNDATED} when the
 *     record does not say)
 */
public record TestClassResult(
        String module,
        String className,
        int tests,
        int failed,
        int skipped,
        long durationMs,
        String tier,
        List<SlowMethod> slow,
        long replayedFrom) {

    /** {@link #replayedFrom} of a replayed class whose record does not say when it ran. */
    public static final long REPLAYED_UNDATED = -1;

    public TestClassResult {
        slow = List.copyOf(slow);
    }

    /** A class that ran in this run. */
    public TestClassResult(
            String module,
            String className,
            int tests,
            int failed,
            int skipped,
            long durationMs,
            String tier,
            List<SlowMethod> slow) {
        this(module, className, tests, failed, skipped, durationMs, tier, slow, 0);
    }

    /** Whether this class's outcome was replayed rather than run. */
    public boolean replayed() {
        return replayedFrom != 0;
    }

    /** One test method and its wall in milliseconds. */
    public record SlowMethod(String name, long millis) {}
}
