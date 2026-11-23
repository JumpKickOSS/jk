// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.orchestrator;

import static cc.jumpkick.orchestrator.TestEventFields.classNameOf;
import static cc.jumpkick.orchestrator.TestEventFields.engineOf;
import static cc.jumpkick.orchestrator.TestEventFields.identityKey;
import static cc.jumpkick.orchestrator.TestEventFields.methodOf;
import static cc.jumpkick.orchestrator.TestEventFields.progressLabel;
import static cc.jumpkick.orchestrator.TestEventFields.xmlName;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.SecretRedactor;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.plugin.protocol.JUnitUniqueIds;
import cc.jumpkick.run.SessionCancel;
import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.run.TestSummary;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Thread-safe accumulator. Multiple driveWorker threads call into {@link #accept} concurrently.
 * Counts come from FINISHED / SKIPPED events for {@code type == TEST} — we ignore CONTAINER nodes
 * (the engine root, classes themselves) so totals match {@code SummaryGeneratingListener}
 * semantics. Failure detail is pulled from FINISHED[status=FAILED] events.
 *
 * <p>A one-shot fork proves it ran its plan to the end with {@code plan_finished}; {@link
 * #toResult} refuses to stamp a run green without it, whatever the exit code, since a test (or the
 * code under it) that calls {@code System.exit(0)} ends the JVM cleanly with the rest of the plan
 * unrun.
 *
 * <p>Counting per-event (not summing per-worker plan totals) sidesteps a {@link
 * org.junit.platform.launcher.listeners.SummaryGeneratingListener} quirk: it resets its
 * accumulator on every {@code testPlanExecutionStarted} — which fires per {@code
 * Launcher.execute} call — so in pull mode a worker's final summary reflects only its last
 * class.
 */
public final class ResultAggregator {

    private final TestProgressListener listener;
    private final int workerId;
    private final SuiteReport report;
    private final String moduleLabel;

    /**
     * The module's redactor ({@link BuildEnv#secretsFor}, the one the wire uses) as it stood when
     * the suite launched. Every line the fork sends passes through it before anything reads it, so
     * the progress listener, the XML and Markdown reports, the failures the journal and the agent
     * report carry, and the terminal all see the masked text.
     */
    private final SecretRedactor secrets;

    /** {@link #secrets} for text inside a JSON string, where a secret appears escaped. */
    private final SecretRedactor jsonSecrets;

    private long succeeded;
    private long failed;
    private long skipped;
    /** Tests {@code discovery_total} announced, or {@code -1} when that event never arrived. */
    private int discoveredTests = -1;
    /** Whether {@code plan_finished} arrived: the runner ran its plan to the end. */
    private boolean planFinished;
    /**
     * A container outcome hid the tests under it from the stream — a skipped or aborted class
     * reports once for every test it holds, and a node that is both container and test counts in
     * the plan but never as a test here — so {@link #discoveredTests} no longer bounds what is
     * reported.
     */
    private boolean containerHidTests;

    private final List<TestFailureInfo> failures = new ArrayList<>();
    private final FailureTexts failureTexts = new FailureTexts();
    // Tests whose `dynamic_registered` event we observed at execute-time
    // — i.e., @ParameterizedTest / @TestFactory / @TestTemplate /
    // @RepeatedTest invocations that weren't in the static plan. Used
    // to mark their later `finished`/`skipped` events as wasStatic=false
    // so progress UIs can keep a stable static-plan denominator.
    private final Set<String> dynamicIds = new HashSet<>();
    // Distinct classes with at least one executed (finished/skipped) test — the
    // class-rate ETA prior's denominator. Workers partition by class, so
    // per-worker counts sum without overlap.
    private final Set<String> executedClasses = new HashSet<>();
    /** FQCN → wall-ms for CONTAINER finished events (class-level timing for ETA). */
    private final Map<String, Long> classWallMs = new LinkedHashMap<>();
    /**
     * The class this worker is running, named the way the XML report names its files, so what the
     * JVM prints between the class's start and its finish lands in that class's {@code system-out}.
     * Null between classes.
     */
    private @Nullable String runningClass;

    /** The {@code (test run)} row {@link #toResult} added for a fork that died; null while none did. */
    private @Nullable TestFailureInfo death;

    /** Test-friendly ctor: no listener, no worker id, no reports. */
    public ResultAggregator() {
        this(TestProgressListener.noop(), 0);
    }

    public ResultAggregator(TestProgressListener listener, int workerId) {
        this(listener, workerId, SuiteReport.NONE, "");
    }

    public ResultAggregator(TestProgressListener listener, int workerId, SuiteReport report, String moduleLabel) {
        this(listener, workerId, report, moduleLabel, BuildEnv.secretsFor(null));
    }

    public ResultAggregator(
            TestProgressListener listener,
            int workerId,
            SuiteReport report,
            String moduleLabel,
            SecretRedactor secrets) {
        this.secrets = secrets;
        this.jsonSecrets = secrets.forEscapedJson();
        this.listener = listener;
        this.workerId = workerId;
        this.report = report;
        this.moduleLabel = moduleLabel;
    }

    public synchronized void accept(String json) {
        // A non-protocol line shows up here only in tests that call accept
        // directly. In production the caller already stripped the prefix, so
        // any line that doesn't look like a JSON object is user output.
        if (json == null || !json.startsWith("{")) {
            userOutput(json == null ? "" : json);
            return;
        }
        // The runner clips a long message or stack line in the fork, which can cut a secret short.
        acceptJson(jsonSecrets.redactClipped(
                json,
                FailureClip.MESSAGE_TRUNCATION_MARKER,
                FailureClip.LINE_TRUNCATION_MARKER,
                FailureClip.VALUE_TRUNCATION_MARKER));
    }

    /**
     * A line the test JVM printed outside the protocol: shown to the listener as this worker's
     * output and, when a class is running, kept for that class's {@code system-out} in the XML
     * report — so a failing test's diagnostic dump survives the temp dir it was printed from. Its
     * secrets are masked first, in every copy; the masked line is returned for the caller's crash
     * capture.
     */
    public synchronized String userOutput(String raw) {
        String line = secrets.redact(raw);
        listener.onUserOutput(workerId, line);
        if (runningClass != null) report.output(runningClass, line);
        return line;
    }

    private void acceptJson(String json) {
        String event = Jsonl.str(json, "event");
        if (event == null) return;
        switch (event) {
            case "discovery_total" -> {
                int tests = Jsonl.intValue(json, "tests", 0);
                discoveredTests = tests;
                listener.onDiscoveryTotal(Jsonl.intValue(json, "classes", 0), tests);
            }
            case "plan_finished" -> planFinished = true;
            case "dynamic_registered" -> {
                if ("TEST".equals(Jsonl.str(json, "type"))) {
                    String uid = identityKey(json);
                    if (!uid.isEmpty()) dynamicIds.add(uid);
                }
            }
            case "warning" ->
                // Both fields are optional on arrival: a warning the runner half-filled is
                // still worth surfacing, and a decoder that throws here ends the worker's pump.
                listener.onWarning(
                        Objects.requireNonNullElse(Jsonl.str(json, "code"), "warning"),
                        Objects.requireNonNullElse(Jsonl.str(json, "message"), ""));
            case "started" -> onStarted(json);
            case "finished" -> onFinished(json);
            case "skipped" -> onSkipped(json);
            default -> {}
        }
    }

    private void onStarted(String json) {
        boolean isTest = "TEST".equals(Jsonl.str(json, "type"));
        String id = identityKey(json);
        String label = progressLabel(json);
        // Only a class-like node names a suite; the engine root's fallback name is not a class.
        String cls = TestEventFields.classNameFrom(id);
        if (!cls.isEmpty() && !cls.equals(id) && !cls.equals(JUnitUniqueIds.engineOf(id))) runningClass = cls;
        listener.onTestStarted(id, label, isTest, eventWorker(json));
    }

    private void onFinished(String json) {
        String type = Jsonl.str(json, "type");
        boolean isTest = "TEST".equals(type);
        String id = identityKey(json);
        String status = Objects.requireNonNullElse(Jsonl.str(json, "status"), "");
        if (!isTest && (!"SUCCESSFUL".equals(status) || "CONTAINER_AND_TEST".equals(type))) containerHidTests = true;
        String label = progressLabel(json);
        long duration = Jsonl.intValue(json, "duration_ms", 0);
        int w = eventWorker(json);
        boolean wasStatic = isTest && (id.isEmpty() || !dynamicIds.contains(id));
        String cls = classNameOf(json);
        if (isTest) {
            if (!cls.isEmpty()) executedClasses.add(cls);
            switch (status) {
                case "SUCCESSFUL" -> succeeded++;
                case "FAILED" -> captureFailure(json, label, false);
                case "ABORTED" -> skipped++;
                default -> {}
            }
        } else {
            // Class (or suite) container wall — free duration_ms from the runner; no method walk.
            // Only the class's own container: a @ParameterizedTest or @TestFactory is a container
            // inside it that names its method, and its wall is already in the class's. Added on
            // top, LearnedHeapRetryTest's 40 s read 60 s.
            if (!cls.isEmpty() && duration > 0 && "SUCCESSFUL".equals(status) && namesNoMethod(json)) {
                classWallMs.merge(cls, duration, Long::sum);
            }
            if ("FAILED".equals(status)) {
                // A container-level failure (class initializer / @BeforeAll / engine):
                // no per-test event follows, so without capturing it the run would
                // surface only as a bare "runner exited N". Record it with its stack.
                captureFailure(json, label.isEmpty() ? "container" : label + " (container)", true);
            }
            // The class is done: what the JVM prints next belongs to no class until one starts.
            if (runningClass != null && runningClass.equals(TestEventFields.classNameFrom(id))) runningClass = null;
        }
        listener.onTestFinished(id, label, status, isTest, wasStatic, duration, w);
        if (isTest) {
            String throwable = Jsonl.nested(json, "throwable");
            String xmlName = xmlName(json, label);
            if ("ABORTED".equals(status)) {
                // An assumption that aborted the test says why in its message; keep it, or the
                // report cannot tell a missing fixture from a busy machine.
                String why = throwable != null ? Jsonl.str(throwable, "message") : null;
                String reason = why == null || why.isBlank() ? "aborted" : why;
                report.skipped(id, xmlName, label, reason);
            } else {
                report.finished(id, xmlName, label, duration, throwable);
            }
        }
    }

    /** Record a FAILED test/container: count it and keep identity + full stack. */
    private void captureFailure(String json, String label, boolean container) {
        failed++;
        String throwableJson = Jsonl.nested(json, "throwable");
        String exClass = throwableJson != null ? Jsonl.str(throwableJson, "class") : null;
        if (exClass == null) exClass = "?";
        String message = throwableJson != null ? Jsonl.str(throwableJson, "message") : null;
        if (message == null) message = "";
        // A suite whose every test fails the same way repeats one text per failure; hold it once.
        message = failureTexts.held(FailureClip.message(message));
        String stack = failureTexts.held(readStack(throwableJson));
        String className = classNameOf(json);
        String method = methodOf(json);
        String engine = engineOf(json);
        String testName = !method.isEmpty() ? method : label;
        if (container && !testName.endsWith("(container)")) {
            testName = testName + " (container)";
        }
        int w = eventWorker(json);
        // TestFailureInfo.method is the display identity: the method when the runner named one,
        // else the container/run label — there is no second short-name component to drift from it.
        String name = method.isEmpty() ? testName : method;
        failures.add(new TestFailureInfo(moduleLabel, engine, className, name, exClass, message, stack, w));
        if (throwableJson != null) report.failed(className, List.of(name, testName), throwableJson);
        listener.onFailure(identityKey(json), testName, exClass, message, stack, engine, className, method, w);
    }

    private void onSkipped(String json) {
        boolean isTest = "TEST".equals(Jsonl.str(json, "type"));
        if (!isTest) containerHidTests = true;
        String id = identityKey(json);
        boolean wasStatic = isTest && (id.isEmpty() || !dynamicIds.contains(id));
        if (isTest) {
            skipped++;
            String cls = classNameOf(json);
            if (!cls.isEmpty()) executedClasses.add(cls);
        }
        String reason = Jsonl.str(json, "reason");
        String label = progressLabel(json);
        int w = eventWorker(json);
        listener.onTestSkipped(id, label, reason != null ? reason : "", isTest, wasStatic, w);
        if (isTest) report.skipped(id, xmlName(json, label), label, reason);
    }

    /** Event {@code worker} field, else this aggregator's id. */
    private int eventWorker(String json) {
        int w = Jsonl.intValue(json, "worker", -1);
        return w > 0 ? w : workerId;
    }

    /**
     * {@code throwable.stack} as a single string, clipped by {@link FailureClip#stack}. Accepts a
     * string (preferred) or a legacy line array and joins it.
     */
    public static String readStack(@Nullable String throwableJson) {
        if (throwableJson == null) return "";
        String s = Jsonl.str(throwableJson, "stack");
        if (s == null) {
            List<String> lines = Jsonl.strArray(throwableJson, "stack");
            if (lines.isEmpty()) return "";
            s = String.join("\n", lines);
        }
        return FailureClip.stack(s);
    }

    public synchronized TestSummary toResult(WorkerEnd exit) {
        return toResult(exit, "");
    }

    /**
     * As {@link #toResult(WorkerEnd)}. A fork that ended before running its plan to the end ({@link
     * #incomplete}) with no test event at all raises {@link WorkerEnd#nothingRan} with {@code
     * crashOutput} (the worker's captured stdout/stderr): the launcher never ran a test, so the step
     * fails on that instead of counting one red test. After tests were reported it is one {@code (test run)} failure row carrying {@code
     * crashOutput}: the passes reported before the end are not the suite, and counting them green
     * would stamp the run and skip it next build. The row names the class the fork was running when
     * it died, if any, so that class is the one that failed; no test event carried the row, so the
     * caller tells its listener of it once no retry replaced it ({@link #death}).
     */
    public synchronized TestSummary toResult(WorkerEnd exit, String crashOutput) {
        long total = succeeded + failed + skipped;
        String why = incomplete(exit, total);
        if (why != null) {
            String crash = crashOutput == null ? "" : crashOutput;
            if (total == 0) throw exit.nothingRan(moduleLabel, crash);
            failed++;
            total++;
            // The fork's own output rides as the row's detail: what it died of — its heap, say —
            // is what decides whether a retry on a bigger heap is due.
            String cls = runningClass == null ? "" : runningClass;
            death = new TestFailureInfo(moduleLabel, "", cls, RunShortfall.RUN, "", why, crash, 0);
            failures.add(death);
        }
        return new TestSummary(
                total,
                succeeded,
                failed,
                skipped,
                executedClasses.size(),
                List.copyOf(failures),
                Map.copyOf(classWallMs));
    }

    /**
     * Why the fork did not run its plan to the end, or {@code null} when it did. The runner exits
     * 1 for a failed test it already reported once its plan finished, so that exit is a death only
     * when nothing failed or the plan never finished, and any other non-zero exit is one whatever
     * failed before it (a later test's System.exit, an OOM kill); exit 0 is a death when {@code
     * plan_finished} never came, and a plan that finished short of the tests discovery announced
     * lost some on the way. A cancel that arrived after every discovered test was reported did
     * finish: that one stays green.
     */
    private @Nullable String incomplete(WorkerEnd exit, long reported) {
        if (cancelAfterEveryTest(reported)) return null;
        int exitCode = exit.code();
        if (exitCode != 0 && !(exitCode == 1 && failed > 0 && planFinished)) {
            return "worker " + exit.failure("exited " + exitCode);
        }
        if (!planFinished) {
            String reportedOf = discoveredTests < 0
                    ? reported + (reported == 1 ? " test" : " tests") + " reported"
                    : reported + " of " + discoveredTests + " discovered tests reported";
            return "worker exited 0 early: " + reportedOf + "; something called System.exit or Runtime.halt";
        }
        if (reported < discoveredTests && !containerHidTests) {
            return "worker finished with " + reported + " of " + discoveredTests + " discovered tests reported";
        }
        return null;
    }

    /** True when cancel arrived only after discovery's tests had all been reported. */
    private boolean cancelAfterEveryTest(long reported) {
        return SessionCancel.cancelled() && discoveredTests > 0 && reported >= discoveredTests;
    }

    /** The row {@link #toResult} added because the fork died before running its plan to the end, or null. */
    public synchronized @Nullable TestFailureInfo death() {
        return death;
    }

    /** Snapshot of just the counters — used by the parallel-merge path. */
    public synchronized TestSummary snapshot() {
        long total = succeeded + failed + skipped;
        return new TestSummary(
                total,
                succeeded,
                failed,
                skipped,
                executedClasses.size(),
                List.copyOf(failures),
                Map.copyOf(classWallMs));
    }

    /** Whether a container event is a class's own, not a method's (a template or factory) inside it. */
    private static boolean namesNoMethod(String json) {
        return methodOf(json).isEmpty()
                && JUnitUniqueIds.methodOf(identityKey(json)).isEmpty();
    }

    /** Class walls collected this worker (for parallel merge). */
    public synchronized Map<String, Long> classWallMs() {
        return Map.copyOf(classWallMs);
    }
}
