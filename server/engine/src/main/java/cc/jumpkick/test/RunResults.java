// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.config.Request;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * The test runs and coverage the modules of one request publish, keyed by module path, drained
 * into that request's record at journal-write. One instance lives on the request's {@code
 * BuildAccumulator}; the job envelope binds it in the job's {@link Request} under {@link #SLOT}, which
 * reaches every pool task and thread the job uses, so a {@code run-tests} step publishing from a
 * worker thread reaches the request that scheduled it and no other. A publish with no request open
 * — a one-shot test, a probe — has no sink and is dropped.
 */
public final class RunResults {

    /** Where a request carries its sink. */
    public static final Request.Slot<RunResults> SLOT = new Request.Slot<>("results");

    private final ConcurrentHashMap<String, MarkdownTestReport.ModuleRun> tests = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CoverageResults.Module> coverage = new ConcurrentHashMap<>();

    /** The bound request's sink, or {@code null} when no request carries one. */
    public static @Nullable RunResults ambient() {
        Request r = Request.current();
        return r == null ? null : r.get(SLOT);
    }

    /**
     * Fold {@code entries} and what their classes {@code printed} under {@code scopeKey}; a second
     * run of the same module merges into the first, its printed output replacing the first's.
     */
    void publishTests(
            String scopeKey,
            String label,
            List<MarkdownTestReport.Entry> entries,
            Map<String, MarkdownTestReport.Printed> printed) {
        MarkdownTestReport.ModuleRun add = new MarkdownTestReport.ModuleRun(scopeKey, label, entries, printed);
        tests.merge(scopeKey, add, (a, b) -> {
            List<MarkdownTestReport.Entry> merged =
                    new ArrayList<>(a.entries().size() + b.entries().size());
            merged.addAll(a.entries());
            merged.addAll(b.entries());
            Map<String, MarkdownTestReport.Printed> out = new LinkedHashMap<>(a.printed());
            out.putAll(b.printed());
            String keep = !a.label().isBlank() ? a.label() : b.label();
            return new MarkdownTestReport.ModuleRun(scopeKey, keep, merged, out);
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
