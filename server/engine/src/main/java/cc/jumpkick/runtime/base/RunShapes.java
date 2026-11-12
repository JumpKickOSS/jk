// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.run.TaskNames;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * What shaped each request's build, for the step ledger its run is recorded into: the test profile
 * (the integration tier is not the default tier), whether it was forced (a from-scratch compile or
 * link is not an incremental one), and whether a test filter narrowed its suites (a filtered suite
 * is not the suite). The build's session knows these and the journal, which records the finished
 * run, does not; this hands them across by request id.
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

    /** What a request's estimate was priced under, and what its run measured, by request id. */
    private record Priced(
            Set<String> frontier,
            Map<String, int[]> curves,
            Map<String, int[]> measured,
            Map<String, long[]> workers) {}

    private static final Map<Long, Priced> PRICED = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Priced> eldest) {
            return size() > MAX;
        }
    });

    private static Priced priced(long requestId) {
        return PRICED.computeIfAbsent(
                requestId,
                k -> new Priced(
                        Set.of(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>()));
    }

    /**
     * {@code requestId}'s estimate was priced under the work set {@code frontier}, its suites'
     * class shares read as time through {@code curves} (by module dir, {@link SuiteCurve}): its run
     * is recorded under the same set, so the next run the forecast sees the same way finds it.
     */
    public static void markPriced(@Nullable Long requestId, Set<String> frontier, Map<String, int[]> curves) {
        if (requestId == null || requestId <= 0 || frontier == null) return;
        synchronized (PRICED) {
            Priced was = priced(requestId);
            Map<String, int[]> read = new ConcurrentHashMap<>(curves == null ? Map.of() : curves);
            PRICED.put(requestId, new Priced(Set.copyOf(frontier), read, was.measured(), was.workers()));
        }
    }

    /** The work set {@code requestId}'s estimate was priced under, or {@code null} when none was. */
    public static @Nullable Set<String> frontierOf(long requestId) {
        Priced p = PRICED.get(requestId);
        return p == null || p.frontier().isEmpty() && p.curves().isEmpty() ? null : p.frontier();
    }

    /** The curve {@code requestId}'s estimate reads the suite of {@code dir} through, or {@code null}. */
    public static int @Nullable [] pricedCurve(long requestId, String dir) {
        Priced p = PRICED.get(requestId);
        return p == null ? null : p.curves().get(dir);
    }

    /** {@code requestId}'s suite in {@code dir} finished, its clock following {@code curve}. */
    public static void markCurve(long requestId, String dir, int[] curve) {
        if (requestId <= 0 || dir == null || curve == null) return;
        synchronized (PRICED) {
            priced(requestId).measured().put(dir, curve.clone());
        }
    }

    /** The curves {@code requestId}'s finished suites measured, by module dir. */
    public static Map<String, int[]> curvesOf(long requestId) {
        Priced p = PRICED.get(requestId);
        return p == null ? Map.of() : Map.copyOf(p.measured());
    }

    /**
     * {@code requestId}'s suite in {@code dir} runs on {@code workers} test JVMs, its classes'
     * recorded walls adding up to {@code classWallsMs}, the longest {@code longestClassMs}.
     */
    public static void markWorkers(long requestId, String dir, int workers, long classWallsMs, long longestClassMs) {
        if (requestId <= 0 || dir == null || workers <= 0) return;
        synchronized (PRICED) {
            priced(requestId).workers().put(dir, new long[] {workers, classWallsMs, longestClassMs});
        }
    }

    /**
     * The test JVMs {@code requestId}'s suites ran on, by module dir, each {@code {workers,
     * classWallsMs, longestClassMs}}.
     */
    public static Map<String, long[]> workersOf(long requestId) {
        Priced p = PRICED.get(requestId);
        return p == null ? Map.of() : Map.copyOf(p.workers());
    }

    /** {@code requestId}'s shape, or the default shape when it was never marked. */
    public static Shape of(long requestId) {
        Shape s = SHAPES.get(requestId);
        return s == null ? Shape.DEFAULT : s;
    }
}
