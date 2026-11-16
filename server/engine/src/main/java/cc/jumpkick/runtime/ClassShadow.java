// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.host.Log;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.test.ClassCones;
import cc.jumpkick.test.ClassReads;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Per-class selection in shadow: selection is unchanged, the suite runs as its stamp says, and each
 * class that ran is asked what per-class selection would have done. Its key is taken over its cone
 * as it is now and what its last record says it read; a class whose key matches its last green
 * record would have been replayed. Then the run's outcome is held to that: a class whose outcome
 * changed since its record (passed to failed, failed to passed, or a different count of tests) must
 * be among the classes that would have run, or per-class selection would have under-run, and the
 * run says so ({@link #UNSOUND}).
 *
 * <p>Each run appends one line to the module's shadow log beside its class records: what ran, what
 * would have run and why, what changed and what would have been missed. The key each class takes
 * over this run's reads is what its record keeps for the next run to compare.
 */
final class ClassShadow {

    private ClassShadow() {}

    /** The warning of a run in which per-class selection would have replayed a class whose outcome changed. */
    static final String UNSOUND = "per-class-unsound";

    /** The shadow log's name, beside the module's class records. */
    static final String LOG = "per-class-shadow.ndjson";

    /** Where the module at {@code moduleDir} keeps its shadow log. */
    static Path log(Path moduleDir) {
        return TestClassRecords.file(moduleDir).resolveSibling(LOG);
    }

    /**
     * What one run's shadow found.
     *
     * @param keys each class's key over this run's reads, by {@code launch|class}, for its record
     * @param wouldRun the classes per-class selection would have run, with why
     * @param changed the classes whose outcome changed since their record
     * @param unsound the changed classes it would have replayed
     */
    record Result(
            Map<String, ClassKeys.Key> keys,
            Map<String, String> wouldRun,
            Set<String> changed,
            Set<String> unsound,
            Map<String, String> cones) {}

    /**
     * The shadow of a run whose class ledger recorded {@code recorded}: cones from {@code roots},
     * keys under {@code suitePart} and {@code stampKey}, the module's records as they stood before
     * this run against what this run saw.
     */
    static Result run(
            Path moduleDir,
            Map<String, TestClassLedger.Entry> recorded,
            ClassReads.Attributed seen,
            List<Path> roots,
            String suitePart,
            @Nullable String stampKey,
            Path workspace) {
        long started = Clock.SYSTEM.nanos();
        Path file = TestClassRecords.file(moduleDir);
        Map<String, String> previous = TestClassRecords.previousRows(file);
        ClassKeys.Observed pooledBefore = pooledOf(TestClassRecords.header(file));
        ClassKeys.Observed pooledNow = observed(seen.pooled(), seen.complete(), workspace);
        long indexStarted = Clock.SYSTEM.nanos();
        ClassCones.Index index = ClassCones.index(roots);
        String module = index.digest();
        long indexMs = (Clock.SYSTEM.nanos() - indexStarted) / 1_000_000;
        Map<String, ClassKeys.Key> keys = new TreeMap<>();
        Map<String, String> wouldRun = new TreeMap<>();
        Map<String, String> cones = new TreeMap<>();
        Set<String> changed = new TreeSet<>();
        Set<String> unsound = new TreeSet<>();
        Map<String, Integer> reasons = new TreeMap<>();
        for (Map.Entry<String, long[]> e : outcomes(recorded).entrySet()) {
            String rowKey = e.getKey();
            int bar = rowKey.indexOf('|');
            String launch = rowKey.substring(0, bar);
            String cls = rowKey.substring(bar + 1);
            long[] now = e.getValue();
            ClassCones.Cone cone = index.cone(cls);
            cones.put(rowKey, cone.digest() + " " + cone.classes().size());
            ClassReads.Observation obs = seen.complete() ? seen.classes().get(cls) : null;
            ClassKeys.Observed observedNow = obs == null ? ClassKeys.Observed.NONE : observed(obs, true, workspace);
            keys.put(
                    rowKey,
                    ClassKeys.key(
                            cls, launch, cone, suitePart, observedNow, pooledNow, module, index::contains, workspace));

            String prev = previous.get(rowKey);
            String why = whyRun(prev, launch, cone, suitePart, pooledBefore, module, index::contains, workspace);
            if (why != null) {
                wouldRun.put(cls, why);
                reasons.merge(why.split(" ", 2)[0], 1, Integer::sum);
            }
            if (changed(prev, now)) {
                changed.add(cls);
                if (why == null) unsound.add(cls);
            }
        }
        long ms = (Clock.SYSTEM.nanos() - started) / 1_000_000;
        append(moduleDir, stampKey, keys.size(), index.size(), wouldRun, changed, unsound, reasons, indexMs, ms);
        return new Result(keys, wouldRun, changed, unsound, cones);
    }

    /** Why per-class selection would run the class whose record was {@code prev}, or null to replay it. */
    static @Nullable String whyRun(
            @Nullable String prev,
            String launch,
            ClassCones.Cone cone,
            String suitePart,
            ClassKeys.Observed pooledBefore,
            String module,
            Predicate<String> indexed,
            Path workspace) {
        if (prev == null) return "no-record";
        String prevKey = Jsonl.str(prev, "classKey");
        if (prevKey == null) return "no-key";
        if (!"passed".equals(Jsonl.str(prev, "outcome"))) return "red";
        ClassKeys.Key now = ClassKeys.key(
                launch,
                cone,
                suitePart,
                fromRow(prev),
                pooledBefore,
                module,
                indexed,
                workspace,
                Jsonl.str(prev, "fallback"));
        if (now.key().equals(prevKey)) return null;
        return now.fallback() != null ? "module " + now.fallback() : "key";
    }

    /** Whether the class's outcome moved since {@code prev}: its verdict or its counts. */
    static boolean changed(@Nullable String prev, long[] now) {
        if (prev == null) return true;
        boolean failedNow = now[1] > 0;
        return failedNow != !"passed".equals(Jsonl.str(prev, "outcome"))
                || Jsonl.longValue(prev, "tests", -1) != now[0]
                || Jsonl.longValue(prev, "failed", -1) != now[1];
    }

    /** {@code recorded} by {@code launch|top-level class}: tests, failed. */
    private static Map<String, long[]> outcomes(Map<String, TestClassLedger.Entry> recorded) {
        Map<String, long[]> out = new LinkedHashMap<>();
        recorded.forEach((key, e) -> {
            int at = key.indexOf('|');
            String launch = at < 0 ? "" : key.substring(0, at);
            String cls = TestClassLedger.topLevel(at < 0 ? key : key.substring(at + 1));
            long[] sum = out.computeIfAbsent(launch + '|' + cls, k -> new long[2]);
            sum[0] += e.tests();
            sum[1] += e.failed();
        });
        return out;
    }

    /** What a record row says its class read. */
    static ClassKeys.Observed fromRow(String row) {
        String jvm = Jsonl.nested(row, "jvm");
        return new ClassKeys.Observed(
                Jsonl.bool(row, "attributed", false),
                Jsonl.strArray(row, "reads"),
                Jsonl.strArray(row, "code"),
                Jsonl.strArray(row, "processes"),
                new TreeSet<>(jvm == null ? List.of() : Jsonl.strArray(jvm, "loaded")));
    }

    /** What the record file's header says no window held; nothing when there is none. */
    private static ClassKeys.Observed pooledOf(@Nullable String header) {
        if (header == null) return ClassKeys.Observed.NONE;
        String pooled = Jsonl.nested(header, "pooled");
        if (pooled == null) return ClassKeys.Observed.NONE;
        return new ClassKeys.Observed(
                true, Jsonl.strArray(pooled, "reads"), Jsonl.strArray(pooled, "code"), List.of(), Set.of());
    }

    private static ClassKeys.Observed observed(ClassReads.Observation o, boolean attributed, Path workspace) {
        return new ClassKeys.Observed(
                attributed,
                TestClassRecords.spelled(o.reads(), workspace),
                TestClassRecords.spelled(o.code(), workspace),
                List.copyOf(o.processes()),
                o.loaded());
    }

    private static void append(
            Path moduleDir,
            @Nullable String stampKey,
            int ran,
            int indexed,
            Map<String, String> wouldRun,
            Set<String> changed,
            Set<String> unsound,
            Map<String, Integer> reasons,
            long indexMs,
            long ms) {
        Map<String, String> counts = new TreeMap<>();
        reasons.forEach((k, v) -> counts.put(k, String.valueOf(v)));
        String line = JsonFields.object()
                .number("at", Clock.SYSTEM.millis())
                .optionalString("suiteKey", stampKey)
                .number("ran", ran)
                .number("wouldRun", wouldRun.size())
                .array("changed", new ArrayList<>(changed))
                .array("unsound", new ArrayList<>(unsound))
                .map("reasons", counts)
                .map("why", wouldRun)
                .number("indexed", indexed)
                .number("indexMs", indexMs)
                .number("ms", ms)
                .finish();
        try {
            Path log = log(moduleDir);
            Files.createDirectories(log.getParent());
            Files.writeString(
                    log, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException unwritable) {
            Log.debug("per-class shadow: not logged", unwritable);
        }
    }
}
