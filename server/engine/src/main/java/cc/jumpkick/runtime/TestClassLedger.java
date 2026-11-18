// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.ActionRecordText;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import cc.jumpkick.model.SuiteSettings;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TestClassResult;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.test.JUnitLauncher;
import cc.jumpkick.test.SuiteEnvReads;
import cc.jumpkick.test.TestProgressListener;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Each test class's last outcome under one run-tests stamp, so a suite that went red, or was cut
 * short, runs only what it has not passed yet when its inputs are unchanged. The suite marker
 * says whether the whole suite passed; this says which classes did. Under one stamp the inputs are
 * identical, so a class that passed still stands: a re-run of a red suite runs the classes that
 * failed or never finished, and a run stopped part way keeps the classes it got through.
 *
 * <p>Keyed by the stamp and stored in the action cache beside the suite marker, under its own task
 * id, so the run-tests task pointer the forecast reads still names the marker. Each outcome is a
 * class's in one launch ({@link SuiteResources.Launcher}): a suite with settings of its own runs in
 * a launch of its own, so one class can run its untagged methods in one launch and its tagged ones
 * in another, and passing in one says nothing about the other. A launch leaves out only the
 * classes that passed in that launch. A top-level class
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

    /** Each outcome by {@link #entryKey}: its launch and its class. */
    private final Map<String, Entry> previous;

    private boolean whole;

    /** Whether the key this ledger is stored under keys on this host alone ({@link #keptHere}). */
    private volatile boolean keptHere;

    private volatile String launch = SuiteResources.ONE_LAUNCH;
    /** The settled classes the launches this run started left out, by {@link #entryKey}. */
    private final Set<String> leftOut = ConcurrentHashMap.newKeySet();

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
     * The ledger under {@code stampKey}; a run that runs its {@code whole} selection (forced,
     * debug, coverage, affected) leaves nothing out. A run that may leave classes out says so with
     * {@link #NOT_RERUN}.
     */
    static TestClassLedger begin(
            TaskContext ctx, ActionCache cache, @Nullable String stampKey, String module, String tier, boolean whole) {
        TestClassLedger ledger = load(cache, stampKey, module, tier);
        ledger.whole = whole;
        int settled = whole ? 0 : ledger.settledInAnyLaunch();
        if (settled > 0) ctx.warn(NOT_RERUN, notRerun(settled));
        return ledger;
    }

    /**
     * {@code launch} starts: the classes it records from here on are its own, and the answer is the
     * classes it leaves out, those that passed in it under this stamp ({@link #settledClasses}).
     */
    List<String> enter(String launch) {
        this.launch = launch;
        List<String> settled = whole ? List.of() : settledClasses(launch);
        for (String cls : settled) leftOut.add(entryKey(launch, cls));
        return settled;
    }

    /**
     * {@code launcher}, handed for each launch as it starts the classes that launch leaves out
     * ({@link #enter}), so each launch records its classes as its own.
     */
    SuiteResources.Launcher launching(BiFunction<List<String>, SuiteSettings, JUnitLauncher> launcher) {
        return (launch, settings) -> launcher.apply(enter(launch), settings);
    }

    /**
     * After a run that finished: store the ledger under the settled {@code stampKey} unless the run
     * was red for a reason no class owns, warn {@link #PASSED_ON_RERUN} for a class that went from
     * red to green with nothing changed, and answer {@code ran} as the whole suite, the classes left
     * out counted in. A red run settled under another key than {@code startedKey}, the one it
     * started with, is red under that key too, whatever settling decided: it leaves a red marker
     * there and clears the ledger, so neither a green marker nor a class an earlier run passed under
     * it answers the next build for this one.
     */
    TestSummary finish(
            TaskContext ctx,
            ActionCache cache,
            String testTaskId,
            @Nullable String startedKey,
            @Nullable String stampKey,
            TestSummary ran)
            throws IOException {
        storeUnlessSuiteRed(cache, testTaskId, stampKey, ran);
        if (!ran.allPassed() && startedKey != null && !startedKey.equals(stampKey)) {
            TestLaunch.recordRed(cache, testTaskId, startedKey, ran);
            clear(cache, testTaskId, startedKey);
        }
        List<String> flaky = passedOnRerun();
        if (!flaky.isEmpty()) {
            ctx.warn(
                    PASSED_ON_RERUN,
                    "passed on a re-run with no input changed, so flaky or dependent on what ran before: "
                            + String.join(", ", flaky));
        }
        return withSettled(ran);
    }

    /**
     * The counts of the tests a narrower run would have run, out of what this ledger recorded: the
     * classes {@code classRuns} admits, in the launches {@code suiteRuns} admits one suite of (a
     * run in one launch names none, and counts whole). Null when the ledger recorded nothing.
     */
    @Nullable
    TestSummary covered(Predicate<String> classRuns, Predicate<String> suiteRuns) {
        if (previous.isEmpty()) return null;
        long tests = 0;
        long skipped = 0;
        for (Map.Entry<String, Entry> e : previous.entrySet()) {
            String[] key = splitKey(e.getKey());
            if (!launchRuns(key[0], suiteRuns) || !classRuns.test(key[1])) continue;
            tests += e.getValue().tests();
            skipped += e.getValue().skipped();
        }
        return new TestSummary(tests, tests - skipped, 0, skipped, List.of());
    }

    /** Whether launch {@code name} ({@code <group>:<suite>,<suite>}, or none for one launch) ran a suite {@code suiteRuns} admits. */
    private static boolean launchRuns(String name, Predicate<String> suiteRuns) {
        int at = name.indexOf(':');
        if (name.isEmpty() || at < 0) return true;
        for (String suite : name.substring(at + 1).split(",")) {
            if (suiteRuns.test(suite)) return true;
        }
        return false;
    }

    /**
     * Note that the suite settled under the key this ledger is stored under keys on this host alone
     * ({@link SuiteEnvReads#localOnly}): no other host's stamp equals it, so the ledger stays here as
     * the marker does ({@link ActionRecordText#HOST_KEYED_INPUT}).
     */
    void keptHere() {
        keptHere = true;
    }

    /** The inputs this ledger's record names: the mark that keeps it on this host, or none. */
    private Map<String, String> ledgerInputs() {
        return keptHere ? Map.of(ActionRecordText.HOST_KEYED_INPUT, ActionRecordText.HOST_KEYED) : Map.of();
    }

    /** The action key the ledger of {@code stampKey} is stored under. */
    static String key(String stampKey) {
        return Hashing.sha256Hex("test-class-ledger\0" + stampKey);
    }

    /**
     * The top-level classes that passed in {@code launch} under this stamp with every class nested
     * in them: what that launch need not run again under the same stamp. Sorted, for a stable launch.
     */
    List<String> settledClasses(String launch) {
        Map<String, Boolean> green = new TreeMap<>();
        Set<String> own = new HashSet<>();
        for (Map.Entry<String, Entry> e : previous.entrySet()) {
            String[] key = splitKey(e.getKey());
            if (!key[0].equals(launch)) continue;
            own.add(key[1]);
            green.merge(topLevel(key[1]), e.getValue().green(), Boolean::logicalAnd);
        }
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Boolean> e : green.entrySet()) {
            // A nested class alone says nothing about its outer class's own tests.
            if (e.getValue() && own.contains(e.getKey())) out.add(e.getKey());
        }
        return out;
    }

    /** How many classes some launch may leave out, each counted once. */
    private int settledInAnyLaunch() {
        Set<String> launches = new TreeSet<>();
        for (String key : previous.keySet()) launches.add(splitKey(key)[0]);
        Set<String> classes = new TreeSet<>();
        for (String l : launches) classes.addAll(settledClasses(l));
        return classes.size();
    }

    /** The ledger's key of {@code className}'s outcome in {@code launch}. */
    static String entryKey(String launch, String className) {
        return launch + KEY_SEPARATOR + className;
    }

    /** {@link #entryKey}'s launch and class. */
    private static String[] splitKey(String key) {
        int at = key.indexOf(KEY_SEPARATOR);
        return at < 0 ? new String[] {"", key} : new String[] {key.substring(0, at), key.substring(at + 1)};
    }

    /** Between a launch's name and a class name: neither a suite name nor a binary class name holds it. */
    private static final char KEY_SEPARATOR = '|';

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
            public void onWorkers(int workers) {
                delegate.onWorkers(workers);
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
        recorded.put(entryKey(launch, r.className()), new Entry(r.tests(), r.failed(), r.skipped()));
    }

    /**
     * {@code ran}, the run of every class but those its launches left out ({@link #enter}), as the
     * whole suite: the left-out classes' recorded tests added in as passed and skipped as they were.
     */
    TestSummary withSettled(TestSummary ran) {
        if (leftOut.isEmpty()) return ran;
        long tests = 0;
        long skipped = 0;
        for (Map.Entry<String, Entry> e : previous.entrySet()) {
            String[] key = splitKey(e.getKey());
            if (!leftOut.contains(entryKey(key[0], topLevel(key[1])))) continue;
            tests += e.getValue().tests();
            skipped += e.getValue().skipped();
        }
        return new TestSummary(
                ran.total() + tests,
                ran.succeeded() + (tests - skipped),
                ran.failed(),
                ran.skipped() + skipped,
                ran.classes() > 0 ? ran.classes() + leftOut.size() : 0,
                ran.failures(),
                ran.classWallMs(),
                ran.workers());
    }

    /** Classes recorded red under this stamp that passed this time: with nothing changed, flaky or order-dependent. */
    List<String> passedOnRerun() {
        Set<String> out = new TreeSet<>();
        for (Map.Entry<String, Entry> e : recorded.entrySet()) {
            Entry before = previous.get(e.getKey());
            if (before != null && !before.green() && e.getValue().green()) out.add(splitKey(e.getKey())[1]);
        }
        return List.copyOf(out);
    }

    /**
     * {@link #store} when every failure in {@code ran} belongs to a class this run recorded red.
     * A failure no class owns (a worker that died mid-class or after its classes finished, tests
     * discovery announced that were never reported) clears the ledger instead: the classes it would
     * record green, or that an earlier run recorded green, may be the ones that lost tests, and
     * settling them would turn the suite green with those tests never run.
     */
    void storeUnlessSuiteRed(ActionCache cache, String testTaskId, @Nullable String stampKey, TestSummary ran) {
        long owned = 0;
        for (Entry e : recorded.values()) owned += e.failed();
        if (ran.failed() > owned) {
            Log.debug("test run red beyond its classes; class ledger cleared");
            clear(cache, testTaskId, stampKey);
            return;
        }
        store(cache, testTaskId, stampKey);
    }

    /** Leave no class settled under {@code stampKey}: the next run under it runs every class. */
    static void clear(ActionCache cache, String testTaskId, @Nullable String stampKey) {
        if (stampKey == null) return;
        boolean interrupted = Thread.interrupted();
        try {
            cache.storeMarkers(
                    ActionRecordText.Writes.CLASS_LEDGER,
                    testTaskId + TASK_SUFFIX,
                    key(stampKey),
                    Map.of(),
                    Map.of(),
                    List.of());
        } catch (IOException | RuntimeException e) {
            Log.debug("test class ledger not cleared", e);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /**
     * Store the ledger under {@code stampKey}: what this run recorded over what was recorded before,
     * the newer outcome of a class in a launch winning. Nothing when the stamp failed open or nothing finished. Safe to
     * call on an interrupted thread: a cancelled run's classes are what this keeps.
     */
    void store(ActionCache cache, String testTaskId, @Nullable String stampKey) {
        if (stampKey == null || recorded.isEmpty()) return;
        Map<String, String> outputs = new LinkedHashMap<>();
        previous.forEach((cls, e) -> outputs.put(cls, e.encode()));
        recorded.forEach((cls, e) -> outputs.put(cls, e.encode()));
        boolean interrupted = Thread.interrupted();
        try {
            cache.storeMarkers(
                    ActionRecordText.Writes.CLASS_LEDGER,
                    testTaskId + TASK_SUFFIX,
                    key(stampKey),
                    ledgerInputs(),
                    outputs,
                    List.of());
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
