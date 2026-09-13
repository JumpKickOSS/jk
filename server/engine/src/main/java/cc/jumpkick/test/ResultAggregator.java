// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static cc.jumpkick.test.TestEventFields.classNameOf;
import static cc.jumpkick.test.TestEventFields.engineOf;
import static cc.jumpkick.test.TestEventFields.identityKey;
import static cc.jumpkick.test.TestEventFields.methodOf;
import static cc.jumpkick.test.TestEventFields.progressLabel;
import static cc.jumpkick.test.TestEventFields.xmlName;

import cc.jumpkick.jsonl.Jsonl;
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
 * <p>Counting per-event (not summing per-worker plan totals) sidesteps a {@link
 * org.junit.platform.launcher.listeners.SummaryGeneratingListener} quirk: it resets its
 * accumulator on every {@code testPlanExecutionStarted} — which fires per {@code
 * Launcher.execute} call — so in pull mode a worker's final summary reflects only its last
 * class.
 */
final class ResultAggregator {

    private final TestProgressListener listener;
    private final int workerId;
    private final @Nullable XmlTestReport xmlReport;
    private final @Nullable MarkdownTestReport mdReport;
    private final String moduleLabel;
    private long succeeded;
    private long failed;
    private long skipped;
    private final List<TestFailureInfo> failures = new ArrayList<>();
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

    /** Test-friendly ctor: no listener, no worker id, no reports. */
    ResultAggregator() {
        this(TestProgressListener.noop(), 0, null, null, "");
    }

    /** For tests of the XML naming. */
    ResultAggregator(XmlTestReport xml) {
        this(TestProgressListener.noop(), 0, xml, null, "");
    }

    ResultAggregator(TestProgressListener listener, int workerId) {
        this(listener, workerId, null, null, "");
    }

    ResultAggregator(
            TestProgressListener listener,
            int workerId,
            @Nullable XmlTestReport xmlReport,
            @Nullable MarkdownTestReport mdReport) {
        this(listener, workerId, xmlReport, mdReport, "");
    }

    ResultAggregator(
            TestProgressListener listener,
            int workerId,
            @Nullable XmlTestReport xmlReport,
            @Nullable MarkdownTestReport mdReport,
            @Nullable String moduleLabel) {
        this.listener = listener;
        this.workerId = workerId;
        this.xmlReport = xmlReport;
        this.mdReport = mdReport;
        this.moduleLabel = moduleLabel == null ? "" : moduleLabel;
    }

    synchronized void accept(String json) {
        // A non-protocol line shows up here only in tests that call accept
        // directly. In production the caller already stripped the prefix, so
        // any line that doesn't look like a JSON object is user output.
        if (json == null || !json.startsWith("{")) {
            listener.onUserOutput(workerId, json);
            return;
        }
        acceptJson(json);
    }

    private void acceptJson(String json) {
        String event = Jsonl.str(json, "event");
        if (event == null) return;
        switch (event) {
            case "discovery_total" ->
                listener.onDiscoveryTotal(Jsonl.intValue(json, "classes", 0), Jsonl.intValue(json, "tests", 0));
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
        listener.onTestStarted(id, label, isTest, eventWorker(json));
    }

    private void onFinished(String json) {
        boolean isTest = "TEST".equals(Jsonl.str(json, "type"));
        String id = identityKey(json);
        String status = Objects.requireNonNullElse(Jsonl.str(json, "status"), "");
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
            if (!cls.isEmpty() && duration > 0 && "SUCCESSFUL".equals(status)) {
                classWallMs.merge(cls, duration, Long::sum);
            }
            if ("FAILED".equals(status)) {
                // A container-level failure (class initializer / @BeforeAll / engine):
                // no per-test event follows, so without capturing it the run would
                // surface only as a bare "runner exited N". Record it with its stack.
                captureFailure(json, label.isEmpty() ? "container" : label + " (container)", true);
            }
        }
        listener.onTestFinished(id, label, status, isTest, wasStatic, duration, w);
        if (isTest) {
            String throwable = Jsonl.nested(json, "throwable");
            String xmlName = xmlName(json, label);
            if ("ABORTED".equals(status)) {
                if (xmlReport != null) xmlReport.recordSkipped(id, xmlName, "aborted");
                if (mdReport != null) mdReport.recordSkipped(id, label, "aborted");
            } else {
                if (xmlReport != null) xmlReport.recordFinished(id, xmlName, duration, throwable);
                if (mdReport != null) mdReport.recordFinished(id, label, duration, throwable);
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
        message = truncateMessage(message);
        String stack = readStack(throwableJson);
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
        listener.onFailure(identityKey(json), testName, exClass, message, stack, engine, className, method, w);
    }

    private void onSkipped(String json) {
        boolean isTest = "TEST".equals(Jsonl.str(json, "type"));
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
        if (isTest) {
            if (xmlReport != null) xmlReport.recordSkipped(id, xmlName(json, label), reason);
            if (mdReport != null) mdReport.recordSkipped(id, label, reason);
        }
    }

    /** Event {@code worker} field, else this aggregator's id. */
    private int eventWorker(String json) {
        int w = Jsonl.intValue(json, "worker", -1);
        return w > 0 ? w : workerId;
    }

    /**
     * {@code throwable.stack} as a single string. Accepts a string (preferred) or a legacy line
     * array and joins it. Truncated to {@link #MAX_STACK_CHARS}: the stack is worker-controlled
     * input that rides every downstream copy (wire, SSE, journal), and a deep-recursion failure
     * can produce megabytes of frames that no reader wants.
     */
    static String readStack(@Nullable String throwableJson) {
        if (throwableJson == null) return "";
        String s = Jsonl.str(throwableJson, "stack");
        if (s == null) {
            List<String> lines = Jsonl.strArray(throwableJson, "stack");
            if (lines.isEmpty()) return "";
            s = String.join("\n", lines);
        }
        return truncateStack(s);
    }

    /** Bound for a single failure's stack text; ~400 frames — far past any useful depth. */
    static final int MAX_STACK_CHARS = 32_768;

    static String truncateStack(String stack) {
        if (stack == null || stack.length() <= MAX_STACK_CHARS) return stack;
        int cut = stack.lastIndexOf('\n', MAX_STACK_CHARS);
        if (cut <= 0) {
            cut = MAX_STACK_CHARS;
            // Hard cut (a single >32KB line): never leave a lone high surrogate.
            if (Character.isHighSurrogate(stack.charAt(cut - 1))) cut--;
        }
        return stack.substring(0, cut) + JUnitLauncher.STACK_TRUNCATION_MARKER + (stack.length() - cut)
                + " more chars)";
    }

    /**
     * Bound for a single failure's message. Same rationale as {@link #MAX_STACK_CHARS}
     *: the message is worker-controlled input that rides every downstream copy —
     * wire, SSE, journal, web card — and an {@code assertEquals} diff of two multi-MB strings
     * otherwise puts hundreds of MB of transients through the engine for one bad suite. The
     * copy of the message inside the stack's first line was already bounded; the field itself
     * was not. {@code LauncherPath} applies the same cap worker-side so the JSONL line is
     * bounded on the wire too; this cap covers workers that predate it.
     */
    static final int MAX_MESSAGE_CHARS = 8_192;

    static String truncateMessage(String message) {
        if (message == null || message.length() <= MAX_MESSAGE_CHARS) return message;
        if (workerCapped(message)) return message;
        int cut = MAX_MESSAGE_CHARS;
        if (Character.isHighSurrogate(message.charAt(cut - 1))) cut--;
        return message.substring(0, cut) + JUnitLauncher.MESSAGE_TRUNCATION_MARKER + (message.length() - cut)
                + " more chars)";
    }

    /**
     * A worker-capped message is cap-sized content + marker + remainder count. It exceeds the
     * cap only by the marker's own tail, and re-cutting would replace the worker's accurate
     * remainder count with the marker's length — so it passes through verbatim. The marker
     * position is bounded by the cap, keeping the accepted form itself bounded.
     */
    private static boolean workerCapped(String message) {
        String tail = " more chars)";
        if (!message.endsWith(tail)) return false;
        int at = message.lastIndexOf(JUnitLauncher.MESSAGE_TRUNCATION_MARKER);
        if (at < 0 || at > MAX_MESSAGE_CHARS) return false;
        int digitsFrom = at + JUnitLauncher.MESSAGE_TRUNCATION_MARKER.length();
        int digitsTo = message.length() - tail.length();
        if (digitsTo <= digitsFrom) return false;
        for (int i = digitsFrom; i < digitsTo; i++) {
            char c = message.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    synchronized TestSummary toResult(int exitCode) {
        return toResult(exitCode, "");
    }

    /**
     * As {@link #toResult(int)}, attaching {@code crashOutput} (the worker's captured
     * stdout/stderr) to the synthetic "runner exited" failure so a hard crash with no test events
     * still explains itself.
     */
    synchronized TestSummary toResult(int exitCode, String crashOutput) {
        long total = succeeded + failed + skipped;
        if (total == 0 && exitCode != 0) {
            return new TestSummary(
                    1,
                    0,
                    1,
                    0,
                    List.of(new TestFailureInfo(
                            moduleLabel,
                            "",
                            "",
                            "(test run)",
                            "",
                            "runner exited " + exitCode,
                            crashOutput == null ? "" : crashOutput,
                            workerId)));
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

    /** Snapshot of just the counters — used by the parallel-merge path. */
    synchronized TestSummary snapshot() {
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

    /** Class walls collected this worker (for parallel merge). */
    synchronized Map<String, Long> classWallMs() {
        return Map.copyOf(classWallMs);
    }
}
