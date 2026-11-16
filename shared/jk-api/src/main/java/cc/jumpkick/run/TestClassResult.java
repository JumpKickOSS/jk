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
 */
public record TestClassResult(
        String module,
        String className,
        int tests,
        int failed,
        int skipped,
        long durationMs,
        String tier,
        List<SlowMethod> slow) {

    public TestClassResult {
        slow = List.copyOf(slow);
    }

    /** One test method and its wall in milliseconds. */
    public record SlowMethod(String name, long millis) {}
}
