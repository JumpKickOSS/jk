// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.plugin.protocol.JUnitUniqueIds;
import cc.jumpkick.run.SlowestTests;
import cc.jumpkick.run.TestClassResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Each test class's outcomes as its tests finish, closed into a {@link TestClassResult} when the
 * class itself finishes. Safe across the workers of one run.
 */
final class TestClassTally {

    private final String module;
    private final String tier;

    private final Map<String, Counts> counts = new ConcurrentHashMap<>();

    TestClassTally(String module, String tier) {
        this.module = module;
        this.tier = tier;
    }

    /** Per class: tests, failed and skipped so far, and its methods at or over the slow floor. */
    private static final class Counts {
        int tests;
        int failed;
        int skipped;
        final List<TestClassResult.SlowMethod> slow = new ArrayList<>();
    }

    /** A test finished with the runner's {@code status}; {@code ABORTED} counts as skipped. */
    void finished(String id, String status, long durationMs) {
        Counts c = count(id, "FAILED".equals(status) ? 1 : 0, "ABORTED".equals(status) ? 1 : 0);
        if (c == null || durationMs < SlowestTests.SLOW_MS) return;
        synchronized (c) {
            c.slow.add(
                    new TestClassResult.SlowMethod(SlowestTests.methodName(JUnitUniqueIds.methodOf(id)), durationMs));
            c.slow.sort(
                    Comparator.comparingLong(TestClassResult.SlowMethod::millis).reversed());
            if (c.slow.size() > SlowestTests.TOP) c.slow.removeLast();
        }
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
        Counts c = counts.remove(cls);
        boolean setupFailed = "FAILED".equals(status);
        if (c == null) {
            return setupFailed
                    ? Optional.of(new TestClassResult(module, cls, 0, 1, 0, durationMs, tier, List.of()))
                    : Optional.empty();
        }
        synchronized (c) {
            return Optional.of(new TestClassResult(
                    module,
                    cls,
                    c.tests,
                    Math.max(c.failed, setupFailed ? 1 : 0),
                    c.skipped,
                    durationMs,
                    tier,
                    c.slow));
        }
    }

    private @Nullable Counts count(String id, int failed, int skipped) {
        String cls = JUnitUniqueIds.classOf(id);
        if (cls.isEmpty()) return null;
        Counts c = counts.computeIfAbsent(cls, k -> new Counts());
        synchronized (c) {
            c.tests++;
            c.failed += failed;
            c.skipped += skipped;
        }
        return c;
    }
}
