// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TestClassResult;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.test.TestProgressListener;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Each test class's last outcome under one run-tests stamp, so a suite that went red, or was cut
 * short, runs only what it has not passed yet when its inputs are unchanged. The suite marker
 * says whether the whole suite passed; this says which classes did. Under one stamp the inputs are
 * identical, so a class that passed still stands: a re-run of a red suite runs the classes that
 * failed or never finished, and a run stopped part way keeps the classes it got through.
 *
 * <p>Keyed by the stamp and stored in the action cache beside the suite marker, under its own task
 * id, so the run-tests task pointer the forecast reads still names the marker. A top-level class
 * counts as passed only when it and every class nested in it passed: excluding a class from a run
 * excludes its nested classes too. A class recorded red that passes on the re-run is reported, since
 * with nothing changed it is flaky or depends on what ran before it.
 */
final class TestClassLedger {

    /** The warning a run that leaves passed classes out carries, so a fast red re-run says why it was fast. */
    static final String NOT_RERUN = "tests-not-rerun";

    /** {@link #NOT_RERUN}'s text for {@code n} settled classes. */
    static String notRerun(int n) {
        return n + " test class" + (n == 1 ? "" : "es")
                + " passed earlier under these same inputs and are not run again; running the rest (--redo runs every class)";
    }

    /** The warning a red class that passed on an unchanged re-run carries. */
    static final String PASSED_ON_RERUN = "test-passed-on-rerun";

    /** Appended to the run-tests task id: the ledger's own task, so it moves no pointer the marker owns. */
    static final String TASK_SUFFIX = "#classes";

    /** One class's counts as last recorded. */
    record Entry(long tests, long failed, long skipped) {
        boolean green() {
            return failed == 0;
        }

        String encode() {
            return tests + "," + failed + "," + skipped;
        }

        static @Nullable Entry decode(String value) {
            String[] parts = value.split(",");
            if (parts.length != 3) return null;
            try {
                return new Entry(Long.parseLong(parts[0]), Long.parseLong(parts[1]), Long.parseLong(parts[2]));
            } catch (NumberFormatException torn) {
                return null;
            }
        }
    }

    private final Map<String, Entry> previous;
    private List<String> settled = List.of();
    private final Map<String, Entry> recorded = new ConcurrentHashMap<>();
    private final TestClassTally tally;

    private TestClassLedger(Map<String, Entry> previous, String module, String tier) {
        this.previous = previous;
        this.tally = new TestClassTally(module, tier);
    }

    /** The ledger stored under {@code stampKey}; empty when there is none or the stamp failed open. */
    static TestClassLedger load(ActionCache cache, @Nullable String stampKey, String module, String tier) {
        Map<String, Entry> previous = new TreeMap<>();
        if (stampKey != null) {
            try {
                cache.lookup(key(stampKey)).ifPresent(record -> record.outputs().forEach((cls, value) -> {
                    Entry e = Entry.decode(value);
                    if (e != null) previous.put(cls, e);
                }));
            } catch (IOException | RuntimeException e) {
                Log.debug("test class ledger unreadable; running every class", e);
            }
        }
        return new TestClassLedger(previous, module, tier);
    }

    /**
     * The ledger under {@code stampKey}, with the classes this run may leave out: none for a run
     * that runs its {@code whole} selection (forced, debug, coverage, affected). A run that leaves
     * some out says so with {@link #NOT_RERUN}.
     */
    static TestClassLedger begin(
            TaskContext ctx, ActionCache cache, @Nullable String stampKey, String module, String tier, boolean whole) {
        TestClassLedger ledger = load(cache, stampKey, module, tier);
        ledger.settled = whole ? List.of() : ledger.settledClasses();
        if (!ledger.settled.isEmpty()) ctx.warn(NOT_RERUN, notRerun(ledger.settled.size()));
        return ledger;
    }

    /** The classes this run leaves out ({@link #begin}). */
    List<String> settled() {
        return settled;
    }

    /**
     * After a run that finished: store the ledger under the settled {@code stampKey}, warn {@link
     * #PASSED_ON_RERUN} for a class that went from red to green with nothing changed, and answer
     * {@code ran} as the whole suite, the classes left out counted in.
     */
    TestSummary finish(
            TaskContext ctx, ActionCache cache, String testTaskId, @Nullable String stampKey, TestSummary ran) {
        store(cache, testTaskId, stampKey);
        List<String> flaky = passedOnRerun();
        if (!flaky.isEmpty()) {
            ctx.warn(
                    PASSED_ON_RERUN,
                    "passed on a re-run with no input changed, so flaky or dependent on what ran before: "
                            + String.join(", ", flaky));
        }
        return withSettled(ran, settled);
    }

    /** The action key the ledger of {@code stampKey} is stored under. */
    static String key(String stampKey) {
        return Hashing.sha256Hex("test-class-ledger\0" + stampKey);
    }

    /**
     * The top-level classes that passed under this stamp with every class nested in them: what a
     * run under the same stamp need not run again. Sorted, for a stable launch.
     */
    List<String> settledClasses() {
        Map<String, Boolean> green = new TreeMap<>();
        for (Map.Entry<String, Entry> e : previous.entrySet()) {
            green.merge(topLevel(e.getKey()), e.getValue().green(), Boolean::logicalAnd);
        }
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Boolean> e : green.entrySet()) {
            // A nested class alone says nothing about its outer class's own tests.
            if (e.getValue() && previous.containsKey(e.getKey())) out.add(e.getKey());
        }
        return out;
    }

    /**
     * {@code delegate}, recording each class's outcome as the class finishes, so a run stopped part
     * way still leaves the classes it got through.
     */
    TestProgressListener recording(TestProgressListener delegate) {
        return new TestProgressListener() {
            @Override
            public void onDiscoveryTotal(int classes, int tests) {
                delegate.onDiscoveryTotal(classes, tests);
            }

            @Override
            public void onTestStarted(String id, String display, boolean isTest, int workerId) {
                delegate.onTestStarted(id, display, isTest, workerId);
            }

            @Override
            public void onTestFinished(
                    String id,
                    String display,
                    String status,
                    boolean isTest,
                    boolean wasStatic,
                    long durationMs,
                    int workerId) {
                if (isTest) tally.finished(id, status, durationMs);
                else tally.containerFinished(id, status, durationMs).ifPresent(TestClassLedger.this::record);
                delegate.onTestFinished(id, display, status, isTest, wasStatic, durationMs, workerId);
            }

            @Override
            public void onTestSkipped(
                    String id, String display, String reason, boolean isTest, boolean wasStatic, int workerId) {
                if (isTest) tally.skipped(id);
                delegate.onTestSkipped(id, display, reason, isTest, wasStatic, workerId);
            }

            @Override
            public void onUserOutput(int workerId, String line) {
                delegate.onUserOutput(workerId, line);
            }

            @Override
            public void onFailure(
                    String id,
                    String label,
                    String exClass,
                    String message,
                    String stack,
                    String engine,
                    String className,
                    String method,
                    int workerId) {
                delegate.onFailure(id, label, exClass, message, stack, engine, className, method, workerId);
            }

            @Override
            public void onWarning(String code, String message) {
                delegate.onWarning(code, message);
            }
        };
    }

    private void record(TestClassResult r) {
        recorded.put(r.className(), new Entry(r.tests(), r.failed(), r.skipped()));
    }

    /**
     * {@code ran}, the run of every class but {@code settled}, as the whole suite: the settled
     * classes' recorded tests added in as passed and skipped as they were.
     */
    TestSummary withSettled(TestSummary ran, List<String> settled) {
        if (settled.isEmpty()) return ran;
        long tests = 0;
        long skipped = 0;
        for (Map.Entry<String, Entry> e : previous.entrySet()) {
            if (!settled.contains(topLevel(e.getKey()))) continue;
            tests += e.getValue().tests();
            skipped += e.getValue().skipped();
        }
        return new TestSummary(
                ran.total() + tests,
                ran.succeeded() + (tests - skipped),
                ran.failed(),
                ran.skipped() + skipped,
                ran.classes() > 0 ? ran.classes() + settled.size() : 0,
                ran.failures(),
                ran.classWallMs(),
                ran.workers());
    }

    /** Classes recorded red under this stamp that passed this time: with nothing changed, flaky or order-dependent. */
    List<String> passedOnRerun() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Entry> e : new TreeMap<>(recorded).entrySet()) {
            Entry before = previous.get(e.getKey());
            if (before != null && !before.green() && e.getValue().green()) out.add(e.getKey());
        }
        return out;
    }

    /**
     * Store the ledger under {@code stampKey}: what this run recorded over what was recorded before,
     * the newer outcome winning. Nothing when the stamp failed open or nothing finished. Safe to
     * call on an interrupted thread: a cancelled run's classes are what this keeps.
     */
    void store(ActionCache cache, String testTaskId, @Nullable String stampKey) {
        if (stampKey == null || recorded.isEmpty()) return;
        Map<String, String> outputs = new LinkedHashMap<>();
        previous.forEach((cls, e) -> outputs.put(cls, e.encode()));
        recorded.forEach((cls, e) -> outputs.put(cls, e.encode()));
        boolean interrupted = Thread.interrupted();
        try {
            cache.storeWithOutputs(testTaskId + TASK_SUFFIX, key(stampKey), Map.of(), outputs);
        } catch (IOException | RuntimeException e) {
            Log.debug("test class ledger not stored", e);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** The top-level class of a class name: everything before its first {@code $}. */
    static String topLevel(String className) {
        int dollar = className.indexOf('$');
        return dollar < 0 ? className : className.substring(0, dollar);
    }
}
