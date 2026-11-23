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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
 * <p>One class at a time, as its record is rewritten ({@link TestClassRecords#merge}): its cone is
 * walked, its key over this run's reads goes into its new row with why it would have run, and only
 * counts and a few names stay for the run's line in the module's shadow log.
 */
final class ClassShadow {

    /** The warning of a run in which per-class selection would have replayed a class whose outcome changed. */
    static final String UNSOUND = "per-class-unsound";

    /** The shadow log's name, beside the module's class records. */
    static final String LOG = "per-class-shadow.ndjson";

    /** How many class names one shadow line lists of each kind; the rest are counted. */
    static final int NAMES_LOGGED = 32;

    /** Where the module at {@code moduleDir} keeps its shadow log. */
    static Path log(Path moduleDir) {
        return TestClassRecords.file(moduleDir).resolveSibling(LOG);
    }

    private final ClassCones.Index index;
    private final String module;
    private final String suitePart;
    private final Path workspace;
    private final ClassKeys.Observed pooledBefore;
    private final ClassKeys.Observed pooledNow;
    private final Map<String, String> programs;
    private final ClassKeys.SuiteReadSet readsBefore;
    private final ClassKeys.SuiteReadSet readsAfter;
    private final long codeSince;
    private final boolean codePastBound;

    private int ran;
    private int wouldRun;
    private final List<String> wouldRunNames = new ArrayList<>();
    private final List<String> changed = new ArrayList<>();
    private final List<String> unsound = new ArrayList<>();
    private int changedCount;
    private final Map<String, Integer> reasons = new TreeMap<>();

    /**
     * Each workspace directory's listing, taken once for the pass: the run that left these reads is
     * over, and a directory beside a suite's data can hold tens of thousands of files.
     */
    private final Map<Path, String> listings = new HashMap<>();

    /**
     * The shadow of one run: cones from {@code index}, keys under {@code suitePart}; {@code
     * pooledBefore} and {@code readsBefore} are what the records were keyed over before this run (the
     * pool of the record file's header and the suite's discovered inputs), {@code
     * pooledNow} and {@code readsAfter} what this run leaves; {@code programs} the identity of each
     * program the suite's stamp keys, by name ({@link ClassKeys#programs}); {@code codeSince} is since when the code
     * files the record file's header lists have held still ({@link SuiteCodeFiles#since}) and {@code
     * codePastBound} whether this run read more than such a list holds; {@code stampKey}, the
     * suite's, keys every class when the index could not hold the workspace.
     */
    ClassShadow(
            ClassCones.Index index,
            String suitePart,
            Path workspace,
            ClassKeys.Observed pooledBefore,
            ClassKeys.Observed pooledNow,
            Map<String, String> programs,
            ClassKeys.SuiteReadSet readsBefore,
            ClassKeys.SuiteReadSet readsAfter,
            long codeSince,
            boolean codePastBound,
            @Nullable String stampKey) {
        this.index = index;
        // An index past its bound names only part of the workspace: every class is keyed by the
        // suite's stamp, which names all of it.
        this.module = index.full() ? "stamp:" + stampKey : index.digest();
        this.suitePart = suitePart;
        this.workspace = workspace;
        this.pooledBefore = pooledBefore;
        this.pooledNow = pooledNow;
        this.programs = programs;
        this.readsBefore = readsBefore;
        this.readsAfter = readsAfter;
        this.codeSince = codeSince;
        this.codePastBound = codePastBound;
    }

    /** What the shadow says of the class {@code o} names, given its row before this run: a new key and why it would have run. */
    TestClassRecords.Shadowed of(
            TestClassRecords.Outcome o, ClassReads.@Nullable Observation obs, @Nullable String previous) {
        ClassKeys.Observed now = obs == null ? ClassKeys.Observed.NONE : observed(obs, workspace);
        boolean loadedMore = obs != null && obs.loadedMore();
        List<String> reflective =
                obs == null || loadedMore ? List.of() : ClassKeys.reflective(index, o.testClass(), obs.loaded());
        ClassCones.Cone cone = index.cone(o.testClass(), reflective == null ? List.of() : reflective);
        String fallback = ClassKeys.fallback(cone, now, loadedMore, reflective == null, codePastBound);
        if (fallback == null) fallback = ClassKeys.pooledFallback(pooledNow);
        if (index.full()) fallback = "index past its bound";
        String used = fallback == null ? used(cone, obs, o.testClass()) : null;
        ClassKeys.Key key = ClassKeys.key(
                o.launch(),
                cone,
                used,
                suitePart,
                ClassKeys.identified(now, programs),
                programs,
                pooledNow,
                module,
                readsAfter,
                workspace,
                fallback,
                listings);
        String why = whyRun(previous, o.launch(), o.testClass());
        ran++;
        if (why != null) {
            wouldRun++;
            if (wouldRunNames.size() < NAMES_LOGGED) wouldRunNames.add(o.testClass());
            reasons.merge(why.split(" ", 2)[0], 1, Integer::sum);
        }
        if (changed(previous, o)) {
            changedCount++;
            if (changed.size() < NAMES_LOGGED) changed.add(o.testClass());
            if (why == null && unsound.size() < NAMES_LOGGED) unsound.add(o.testClass());
        }
        return new TestClassRecords.Shadowed(
                key.key(),
                used,
                fallback,
                cone.digest() + " " + cone.size(),
                why,
                reflective == null ? List.of() : reflective);
    }

    /**
     * Which of the cone's classes ran in the window of {@code testClass}, or null when that is not
     * known: no observation, a use left unnamed, or the class's own code not among its uses, which
     * says its JVM ran unmarked.
     */
    static @Nullable String used(ClassCones.Cone cone, ClassReads.@Nullable Observation obs, String testClass) {
        if (obs == null || obs.usedMore() || !obs.used(testClass)) return null;
        return cone.used(obs::used);
    }

    /**
     * Why per-class selection runs the class whose record was {@code prev}, or null to replay it: the
     * one answer both this shadow and the selection ({@link TestClassRecording#unmoved}) give. A class
     * whose record kept its cone beside a pool that started a program is keyed by its module now
     * ({@link ClassKeys#pooledFallback}), so it runs once more. A class
     * that read more code files than its record names, or ran beside a pool that did, runs when one
     * of its suite's code files changed after it finished. Its cone is {@code testClass}'s as it is
     * now, with the classes its record says it loaded outside it ({@code reflective}).
     */
    @Nullable
    String whyRun(@Nullable String prev, String launch, String testClass) {
        if (prev == null) return "no-record";
        String prevKey = Jsonl.str(prev, "classKey");
        if (prevKey == null) return "no-key";
        if (!"passed".equals(Jsonl.str(prev, "outcome"))) return "red";
        ClassCones.Cone cone = index.cone(testClass, Jsonl.strArray(prev, "reflective"));
        String fallback = Jsonl.str(prev, "fallback");
        if (fallback == null) fallback = ClassKeys.pooledFallback(pooledBefore);
        ClassKeys.Key now = ClassKeys.key(
                launch,
                cone,
                Jsonl.str(prev, "coneUsed"),
                suitePart,
                ClassKeys.identified(fromRow(prev), programs),
                programs,
                pooledBefore,
                module,
                readsBefore,
                workspace,
                fallback,
                listings);
        if (!now.key().equals(prevKey)) return fallback != null ? "module " + fallback : "key";
        boolean pastOwnCode = Jsonl.bool(prev, "codeMore", false) || pooledBefore.codeMore();
        if (fallback == null && pastOwnCode && codeSince >= Jsonl.longValue(prev, "ranAt", 0)) return "code moved";
        return null;
    }

    /** Whether the class's outcome moved since {@code prev}: its verdict or its counts. */
    static boolean changed(@Nullable String prev, TestClassRecords.Outcome now) {
        if (prev == null) return true;
        return now.failedNow() != !"passed".equals(Jsonl.str(prev, "outcome"))
                || Jsonl.longValue(prev, "tests", -1) != now.tests()
                || Jsonl.longValue(prev, "failed", -1) != now.failed();
    }

    /** What a record row says its class read. */
    static ClassKeys.Observed fromRow(String row) {
        return new ClassKeys.Observed(
                Jsonl.bool(row, "attributed", false),
                Jsonl.strArray(row, "reads"),
                Jsonl.bool(row, "readsMore", false),
                Jsonl.strArray(row, "code"),
                Jsonl.bool(row, "codeMore", false),
                Jsonl.strArray(row, "processes"),
                Jsonl.strArray(row, "tools"),
                Jsonl.strArray(row, "readRoots"),
                Jsonl.strArray(row, "readNames"));
    }

    /** What the record file's header says no window held; nothing when there is none. */
    static ClassKeys.Observed pooledOf(@Nullable String header) {
        if (header == null) return ClassKeys.Observed.NONE;
        String pooled = Jsonl.nested(header, "pooled");
        if (pooled == null) return ClassKeys.Observed.NONE;
        return new ClassKeys.Observed(
                true,
                Jsonl.strArray(pooled, "reads"),
                Jsonl.bool(pooled, "readsMore", false),
                Jsonl.strArray(pooled, "code"),
                Jsonl.bool(pooled, "codeMore", false),
                Jsonl.strArray(pooled, "processes"));
    }

    static ClassKeys.Observed observed(ClassReads.Observation o, Path workspace) {
        return new ClassKeys.Observed(
                true,
                TestClassRecords.spelled(o.reads(), workspace),
                o.readsMore(),
                TestClassRecords.spelled(o.code(), workspace),
                o.codeMore(),
                List.copyOf(o.processes()),
                List.copyOf(o.tools()),
                o.readsMore() ? TestClassRecords.roots(o.readRoots(), workspace) : List.of(),
                o.readsMore() ? List.copyOf(o.readNames()) : List.of());
    }

    /** The classes it would have replayed whose outcome changed: an under-run, when any. */
    List<String> unsound() {
        return unsound;
    }

    int wouldRun() {
        return wouldRun;
    }

    /** Append this run's line to the shadow log of {@code moduleDir}: counts and a few names, bounded. */
    void log(Path moduleDir, @Nullable String stampKey, long ms) {
        Map<String, String> counts = new TreeMap<>();
        reasons.forEach((k, v) -> counts.put(k, String.valueOf(v)));
        String line = JsonFields.object()
                .number("at", Clock.SYSTEM.millis())
                .optionalString("suiteKey", stampKey)
                .number("ran", ran)
                .number("wouldRun", wouldRun)
                .array("wouldRunClasses", wouldRunNames)
                .number("changedCount", changedCount)
                .array("changed", changed)
                .array("unsound", unsound)
                .map("reasons", counts)
                .number("indexed", index.size())
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
