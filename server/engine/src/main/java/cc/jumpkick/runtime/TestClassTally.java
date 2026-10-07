// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.plugin.protocol.JUnitUniqueIds;
import cc.jumpkick.run.TestClassResult;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Each test class's outcomes as its tests finish, closed into a {@link TestClassResult} when the
 * class itself finishes. Safe across the workers of one run.
 */
final class TestClassTally {

    private final String module;

    /** Per class: tests, failed, skipped so far. */
    private final Map<String, int[]> counts = new ConcurrentHashMap<>();

    TestClassTally(String module) {
        this.module = module;
    }

    /** A test finished with the runner's {@code status}; {@code ABORTED} counts as skipped. */
    void finished(String id, String status) {
        count(id, "FAILED".equals(status) ? 1 : 0, "ABORTED".equals(status) ? 1 : 0);
    }

    void skipped(String id) {
        count(id, 0, 1);
    }

    /**
     * A container finished: the class's result when it is a class (not a parameterized template
     * inside one) that ran a test or failed its setup. A class whose setup failed before any test
     * ran counts as one failure.
     */
    Optional<TestClassResult> containerFinished(String id, String status, long durationMs) {
        String cls = JUnitUniqueIds.classOf(id);
        if (cls.isEmpty() || !JUnitUniqueIds.methodOf(id).isEmpty()) return Optional.empty();
        int[] c = counts.remove(cls);
        boolean setupFailed = "FAILED".equals(status);
        if (c == null) {
            return setupFailed ? Optional.of(new TestClassResult(module, cls, 0, 1, 0, durationMs)) : Optional.empty();
        }
        synchronized (c) {
            return Optional.of(
                    new TestClassResult(module, cls, c[0], Math.max(c[1], setupFailed ? 1 : 0), c[2], durationMs));
        }
    }

    private void count(String id, int failed, int skipped) {
        String cls = JUnitUniqueIds.classOf(id);
        if (cls.isEmpty()) return;
        int[] c = counts.computeIfAbsent(cls, k -> new int[3]);
        synchronized (c) {
            c[0]++;
            c[1] += failed;
            c[2] += skipped;
        }
    }
}
