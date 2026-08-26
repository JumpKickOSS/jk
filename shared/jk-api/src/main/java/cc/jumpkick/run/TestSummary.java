// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.jsonl.MiniJson;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Aggregate test outcome: counts plus failures for exit codes and UI. {@code classes} is the
 * distinct executed test-class count when the runner could derive it, else 0 (unknown).
 * {@code classWallMs} maps FQCN → wall-ms for successfully finished class containers (ETA training).
 *
 * <p>Failures are {@link TestFailureInfo} — the same record the wire, {@code details.jsonl} and
 * {@link BuildPlanResult.Diagnostic} carry. There is no summary-local failure type and therefore no
 * adapter between them to lose {@code file}/{@code line}/{@code snippet}.
 *
 * <p>This class also owns the one on-the-wire spelling of the four counts: a nested
 * {@code "tests":{"total":…,"succeeded":…,"failed":…,"skipped":…}} object. {@code plan-finish}, the
 * history verbs, the MCP history rows, the journal's {@code record.json} and the dashboard all read
 * and write that one shape — see {@link #WIRE_KEY}, {@link #countsJson} and {@link #readCounts}.
 */
public record TestSummary(
        long total,
        long succeeded,
        long failed,
        long skipped,
        long classes,
        List<TestFailureInfo> failures,
        Map<String, Long> classWallMs) {

    /**
     * The one wire field name for test counts. It is an object, not four flat scalars: a flat
     * prefix has to be spelled the same on every surface to stay one field, and jk shipped three
     * spellings of it ({@code testFailed}, {@code testsFailed}, {@code tests.failed}) before this
     * became the single owner. Absent or {@code null} means the run had no test phase — that is the
     * signal, not a {@code -1} count.
     */
    public static final String WIRE_KEY = "tests";

    public TestSummary {
        failures = List.copyOf(failures);
        classWallMs = classWallMs == null || classWallMs.isEmpty() ? Map.of() : Map.copyOf(classWallMs);
    }

    /** Classes unknown; no class walls. */
    public TestSummary(long total, long succeeded, long failed, long skipped, List<TestFailureInfo> failures) {
        this(total, succeeded, failed, skipped, 0, failures, Map.of());
    }

    /** Class count known; no class walls. */
    public TestSummary(
            long total, long succeeded, long failed, long skipped, long classes, List<TestFailureInfo> failures) {
        this(total, succeeded, failed, skipped, classes, failures, Map.of());
    }

    public boolean allPassed() {
        return failed == 0;
    }

    /**
     * The four counts as the wire object's value — {@code {"total":…,"succeeded":…,"failed":…,
     * "skipped":…}}, ready to follow {@code "tests":} in a JSONL line. Takes scalars so the journal's
     * own count record can encode through the same owner without depending on this module.
     */
    public static String countsJson(long total, long succeeded, long failed, long skipped) {
        return MiniJson.write(countsMap(total, succeeded, failed, skipped));
    }

    /** {@link #countsJson} as a map, for encoders that build JSON from maps rather than strings. */
    public static Map<String, Object> countsMap(long total, long succeeded, long failed, long skipped) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", total);
        m.put("succeeded", succeeded);
        m.put("failed", failed);
        m.put("skipped", skipped);
        return m;
    }

    /** This summary's counts as the wire object's value (failures and class walls are not counts). */
    public String countsJson() {
        return countsJson(total, succeeded, failed, skipped);
    }

    /**
     * {@link #countsMap}'s inverse for decoders that parse JSON into maps: the value found under
     * {@link #WIRE_KEY}, or {@code null} when it is not a counts object (absent or {@code null}
     * means the run had no test phase).
     */
    public static @Nullable TestSummary countsFromMap(@Nullable Object value) {
        if (!(value instanceof Map<?, ?> m)) return null;
        return new TestSummary(
                count(m, "total"), count(m, "succeeded"), count(m, "failed"), count(m, "skipped"), List.of());
    }

    private static long count(Map<?, ?> counts, String key) {
        return counts.get(key) instanceof Number n ? n.longValue() : 0;
    }

    /**
     * Read the {@link #WIRE_KEY} object out of a JSONL line. {@code null} when the line carries no
     * test counts — every producer omits the field (or writes {@code null}) for a run with no test
     * phase, so "absent" and "zero tests ran" stay distinguishable.
     */
    public static @Nullable TestSummary readCounts(@Nullable String json) {
        String counts = Jsonl.nested(json, WIRE_KEY);
        if (counts == null) return null;
        return new TestSummary(
                Jsonl.longValue(counts, "total", 0),
                Jsonl.longValue(counts, "succeeded", 0),
                Jsonl.longValue(counts, "failed", 0),
                Jsonl.longValue(counts, "skipped", 0),
                List.of());
    }
}
