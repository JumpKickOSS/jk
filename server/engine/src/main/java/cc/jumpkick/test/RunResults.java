// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.run.ContextPropagator;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * The test runs and coverage the modules of one request publish, keyed by module path, drained
 * into that request's record at journal-write. One instance lives on the request's {@code
 * BuildAccumulator}; the job envelope binds it as the ambient sink on the runner thread, and the
 * {@link ContextPropagator} hop carries it onto the shared pools, so a {@code run-tests} step
 * publishing from a worker thread reaches the request that scheduled it and no other. A publish
 * with no request open — a one-shot test, a probe — has no sink and is dropped.
 */
public final class RunResults {

    private static final InheritableThreadLocal<RunResults> AMBIENT = new InheritableThreadLocal<>();

    static {
        // Propagated, not inherited: a pool worker created under one request would otherwise hold
        // that request's sink for the engine's life (see SessionContext's ledger note).
        ContextPropagator.add(new ContextPropagator.Propagator() {
            @Override
            public Runnable wrapRunnable(Runnable r) {
                RunResults captured = AMBIENT.get();
                return () -> {
                    RunResults previous = AMBIENT.get();
                    open(captured);
                    try {
                        r.run();
                    } finally {
                        open(previous);
                    }
                };
            }

            @Override
            public <T> Callable<T> wrapCallable(Callable<T> c) {
                RunResults captured = AMBIENT.get();
                return () -> {
                    RunResults previous = AMBIENT.get();
                    open(captured);
                    try {
                        return c.call();
                    } finally {
                        open(previous);
                    }
                };
            }
        });
    }

    private final ConcurrentHashMap<String, MarkdownTestReport.ModuleRun> tests = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CoverageResults.Module> coverage = new ConcurrentHashMap<>();

    /** Bind {@code sink} as this thread's ambient sink; {@code null} clears it. */
    public static void open(@Nullable RunResults sink) {
        if (sink == null) AMBIENT.remove();
        else AMBIENT.set(sink);
    }

    /** Drop this thread's ambient binding. */
    public static void close() {
        AMBIENT.remove();
    }

    /** The request's sink on this thread, or {@code null} when no request is open. */
    public static @Nullable RunResults ambient() {
        return AMBIENT.get();
    }

    /** Fold {@code entries} under {@code scopeKey}; a second run of the same module merges into the first. */
    void publishTests(String scopeKey, String label, List<MarkdownTestReport.Entry> entries) {
        MarkdownTestReport.ModuleRun add = new MarkdownTestReport.ModuleRun(scopeKey, label, entries);
        tests.merge(scopeKey, add, (a, b) -> {
            List<MarkdownTestReport.Entry> merged =
                    new ArrayList<>(a.entries().size() + b.entries().size());
            merged.addAll(a.entries());
            merged.addAll(b.entries());
            String keep = !a.label().isBlank() ? a.label() : b.label();
            return new MarkdownTestReport.ModuleRun(scopeKey, keep, merged);
        });
    }

    /** Record {@code moduleDir}'s coverage; a second publish for the same module replaces the first. */
    void publishCoverage(String moduleDir, CoverageResults.Module module) {
        coverage.put(moduleDir, module);
    }

    /** Every published run, in module-path order; the sink is empty afterwards. */
    public List<MarkdownTestReport.ModuleRun> takeTests() {
        List<MarkdownTestReport.ModuleRun> out = new ArrayList<>();
        for (String key : sortedKeys(tests)) {
            MarkdownTestReport.ModuleRun run = tests.remove(key);
            if (run != null && !run.entries().isEmpty()) out.add(run);
        }
        return out;
    }

    /** Every module's coverage, in module-path order; the sink is empty afterwards. */
    public List<CoverageResults.Module> takeCoverage() {
        List<CoverageResults.Module> out = new ArrayList<>();
        for (String key : sortedKeys(coverage)) {
            CoverageResults.Module m = coverage.remove(key);
            if (m != null) out.add(m);
        }
        return out;
    }

    private static List<String> sortedKeys(ConcurrentHashMap<String, ?> map) {
        List<String> keys = new ArrayList<>(map.keySet());
        keys.sort(null);
        return keys;
    }
}
