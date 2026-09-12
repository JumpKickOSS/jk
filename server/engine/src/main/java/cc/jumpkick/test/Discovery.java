// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.run.TestSummary;
import java.util.List;

/**
 * What one list-only test-discovery fork produced: the classes it named, its exit code, and what
 * it printed outside the protocol (the crash text when it died).
 */
record Discovery(List<String> classes, int exit, String output) {

    /**
     * The fork died before naming a class. A non-zero exit after a full class list is a shutdown
     * blemish the list survives; a non-zero exit with nothing named says nothing about the suite,
     * and nothing is not an empty green run.
     */
    boolean crashed() {
        return exit != 0 && classes.isEmpty();
    }

    /**
     * The verdict for a fork that {@link #crashed()}: the same {@code (test run)} failure a crashed
     * pool worker gets, carrying the exit code and what the JVM printed, so the summary explains
     * the crash instead of counting zero tests as passed.
     */
    TestSummary failure(String moduleLabel) {
        return new TestSummary(
                1,
                0,
                1,
                0,
                List.of(new TestFailureInfo(
                        moduleLabel, "", "", "(test run)", "", "test discovery exited " + exit, output)));
    }
}
