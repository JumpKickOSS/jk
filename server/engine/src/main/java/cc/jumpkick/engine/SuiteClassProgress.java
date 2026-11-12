// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.runtime.base.BuildMetrics;
import java.util.HashMap;
import java.util.Map;

/**
 * A running suite's progress as the share of its class walls done: each class at its recorded wall
 * ({@link BuildMetrics#classWallsForSession}), a class with no record at the wall it just took. The
 * suite's ticks count classes, which differ in length by orders of magnitude; their walls do not
 * lie about which part of the suite is left.
 */
final class SuiteClassProgress {

    private final Map<String, Long> expected;
    private double total;
    private double done;
    private final Map<String, Boolean> finished = new HashMap<>();

    private SuiteClassProgress(Map<String, Long> expected) {
        this.expected = expected;
        for (long ms : expected.values()) total += Math.max(0, ms);
    }

    static SuiteClassProgress of(String moduleDir, String step) {
        return new SuiteClassProgress(BuildMetrics.classWallsForSession().forModule(moduleDir, step));
    }

    /** {@code className} finished in {@code millis}; the suite's share done (0..1). */
    synchronized double finished(String className, long millis) {
        if (finished.put(className, Boolean.TRUE) != null) return share();
        Long known = expected.get(className);
        if (known != null && known > 0) done += known;
        else {
            done += Math.max(0, millis);
            total += Math.max(0, millis);
        }
        return share();
    }

    private double share() {
        return total <= 0 ? 0 : Math.min(1.0, done / total);
    }
}
