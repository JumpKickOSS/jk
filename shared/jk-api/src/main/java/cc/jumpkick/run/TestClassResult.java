// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

/**
 * One test class as it finished: its tests' outcomes and the class's wall. Sent as the class
 * completes, so a run cut short still records every class that got that far.
 *
 * @param tests every test of the class that finished or was skipped
 * @param failed the tests that failed, or 1 for a class whose setup failed before any test ran
 * @param skipped the tests that were skipped or aborted
 * @param durationMs the class's wall, setup and teardown included
 */
public record TestClassResult(String module, String className, int tests, int failed, int skipped, long durationMs) {}
