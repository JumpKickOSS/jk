// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.run.TaskNames;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What shaped each request's build, for the step ledger its run is recorded into: the test profile
 * (the integration tier is not the default tier), whether it was forced (a from-scratch compile or
 * link is not an incremental one), and whether a test filter narrowed its suites (a filtered suite
 * is not the suite). The build's session knows these and the journal, which records the finished
 * run, does not; this hands them across by request id, like {@code ColdWorkerRuns}.
 */
public final class RunShapes {

    private RunShapes() {}

    /**
     * One request's shape; the frontier is read from the finished run itself. {@code suite} is the
     * metrics name its {@code run-tests} is kept under ({@link SuiteSelection#step}): the live
     * countdown reads the suite's class walls under it, since the integration tier's classes are
     * not the default tier's.
     */
    public record Shape(String profile, boolean forced, boolean filtered, String suite) {
        public static final Shape DEFAULT = new Shape("", false, false);

        public Shape {
            profile = profile == null ? "" : profile;
            suite = suite == null || suite.isBlank() ? TaskNames.RUN_TESTS : suite;
        }

        public Shape(String profile, boolean forced, boolean filtered) {
            this(profile, forced, filtered, TaskNames.RUN_TESTS);
        }
    }

    /** Requests remembered at most; a run is recorded within seconds of its end. */
    private static final int MAX = 256;

    private static final Map<Long, Shape> SHAPES = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Shape> eldest) {
            return size() > MAX;
        }
    });

    /** {@code requestId}'s build runs with {@code shape}. */
    public static void mark(@Nullable Long requestId, Shape shape) {
        if (requestId == null || requestId <= 0 || shape == null) return;
        SHAPES.put(requestId, shape);
    }

    /** {@code requestId}'s shape, or the default shape when it was never marked. */
    public static Shape of(long requestId) {
        Shape s = SHAPES.get(requestId);
        return s == null ? Shape.DEFAULT : s;
    }
}
