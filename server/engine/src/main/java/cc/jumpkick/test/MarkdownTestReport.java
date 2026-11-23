// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.orchestrator.FailureClip;
import cc.jumpkick.orchestrator.FailureTexts;
import cc.jumpkick.orchestrator.SuiteReport;
import cc.jumpkick.orchestrator.TestEventFields;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Thread-safe per-launch accumulator of JUnit method results. After workers join, {@link
 * #publish} folds the entries into the {@link RunResults} sink of the request that ran the launch,
 * keyed by module path; the journal drains that sink into {@code jk-results.md} when the request's
 * record is written. Companion to {@link XmlTestReport} (JUnit XML under {@code
 * target/surefire-reports/}).
 *
 * <p>A failure's message and stack are clipped by {@link FailureClip}, and a repeated failure
 * shares one copy ({@link FailureTexts}), so a suite whose every failure carries the same
 * forty-kilobyte trace costs kilobytes, not megabytes. A publish with no request
 * open is dropped: nothing would drain it.
 */
public final class MarkdownTestReport implements SuiteReport {

    public record Entry(
            String className,
            String displayName,
            long durationMs,
            @Nullable String failureMessage,
            @Nullable String failureStack,
            @Nullable String skipReason) {
        public boolean isFail() {
            return failureMessage != null || failureStack != null;
        }

        public boolean isSkip() {
            return skipReason != null;
        }

        public boolean isPass() {
            return !isFail() && !isSkip();
        }
    }

    /**
     * What one test class printed: every line counted, the last {@link #KEPT_LINES} kept when the
     * run keeps output ({@code -v}), and {@code report}, the XML file holding all of it (empty when
     * none is written).
     */
    public record Printed(int lines, List<String> tail, String report) {
        public Printed {
            tail = tail == null ? List.of() : List.copyOf(tail);
            if (report == null) report = "";
        }
    }

    /**
     * One module's tests, published after that module's workers join. {@code printed} is keyed by
     * class name, in the order the classes first printed; {@code snapshots} holds each failed
     * test's {@link FailureSnapshotLines}, keyed by {@link #testKey}.
     */
    public record ModuleRun(
            String scopeKey,
            String label,
            List<Entry> entries,
            Map<String, Printed> printed,
            Map<String, List<String>> snapshots) {
        public ModuleRun {
            entries = entries == null ? List.of() : List.copyOf(entries);
            printed = printed == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(printed));
            snapshots = snapshots == null ? Map.of() : Map.copyOf(snapshots);
            if (scopeKey == null) scopeKey = "";
            if (label == null) label = "";
        }

        public ModuleRun(String scopeKey, String label, List<Entry> entries) {
            this(scopeKey, label, entries, Map.of(), Map.of());
        }
    }

    /** {@code Class#method}, the {@code ()} of a method dropped; either part alone when the other is blank. */
    public static String testKey(@Nullable String className, @Nullable String method) {
        String cls = className == null ? "" : className.strip();
        String m = method == null ? "" : method.strip();
        if (m.endsWith("()")) m = m.substring(0, m.length() - 2);
        if (cls.isEmpty()) return m;
        if (m.isEmpty()) return cls;
        return cls + "#" + m;
    }

    /** The lines of each class's output a run that keeps output holds: the last ones printed. */
    public static final int KEPT_LINES = 40;

    /** A kept line longer than this is clipped. */
    static final int KEPT_LINE_CHARS = 400;

    private final List<Entry> entries = new ArrayList<>();

    private final FailureTexts failureTexts = new FailureTexts();

    private final boolean keepOutput;
    private final Map<String, Integer> printedLines = new LinkedHashMap<>();
    private final Map<String, ArrayDeque<String>> printedTail = new LinkedHashMap<>();

    /** Each failed test's snapshot lines by {@link #testKey}, with the class that recorded it. */
    private final Map<String, Snapshot> snapshots = new LinkedHashMap<>();

    /** One failed test's snapshot lines and the class they belong to. */
    record Snapshot(String className, List<String> lines) {}

    /** A report that counts what each class printed and keeps none of it. */
    public MarkdownTestReport() {
        this(false);
    }

    /** {@code keepOutput}: also keep the last {@link #KEPT_LINES} lines each class printed. */
    public MarkdownTestReport(boolean keepOutput) {
        this.keepOutput = keepOutput;
    }

    @Override
    public void output(String className, String line) {
        recordOutput(className, line);
    }

    @Override
    public void finished(
            String uniqueId, String xmlName, String label, long durationMs, @Nullable String throwableJson) {
        recordFinished(uniqueId, label, durationMs, throwableJson);
    }

    @Override
    public void skipped(String uniqueId, String xmlName, String label, @Nullable String reason) {
        recordSkipped(uniqueId, label, reason);
    }

    @Override
    public void failed(String className, List<String> names, String throwableJson) {
        recordSnapshot(className, names, FailureSnapshotLines.of(throwableJson));
    }

    /** One line the test JVM printed while {@code className} ran. */
    public synchronized void recordOutput(String className, String line) {
        printedLines.merge(className, 1, Integer::sum);
        if (!keepOutput) return;
        ArrayDeque<String> tail = printedTail.computeIfAbsent(className, k -> new ArrayDeque<>());
        if (tail.size() == KEPT_LINES) tail.removeFirst();
        tail.addLast(line.length() > KEPT_LINE_CHARS ? line.substring(0, KEPT_LINE_CHARS) + "…" : line);
    }

    /**
     * Record a finished test (passed, failed, or aborted). {@code throwableJson} is the raw nested
     * JSON object from the protocol event's {@code throwable} field — {@code null} for a passing
     * test.
     */
    public synchronized void recordFinished(
            String uniqueId, String display, long durationMs, @Nullable String throwableJson) {
        String className = TestEventFields.classNameFrom(uniqueId);
        String failureMessage = null, failureStack = null;
        if (throwableJson != null) {
            failureMessage = Jsonl.str(throwableJson, "message");
            failureStack = Jsonl.str(throwableJson, "stack");
            if ((failureMessage == null || failureMessage.isBlank()) && failureStack == null) {
                failureMessage = Jsonl.str(throwableJson, "class");
            }
        }
        entries.add(new Entry(
                className,
                display,
                durationMs,
                failureMessage == null ? null : failureTexts.held(FailureClip.message(failureMessage)),
                failureStack == null ? null : failureTexts.held(FailureClip.stack(failureStack)),
                null));
    }

    /**
     * Record the snapshot of a failed test of {@code className}, under each of the {@code names} it
     * is known by (its method, its display name). Nothing for no lines.
     */
    public synchronized void recordSnapshot(String className, Collection<String> names, List<String> lines) {
        if (lines.isEmpty()) return;
        Snapshot s = new Snapshot(className, List.copyOf(lines));
        for (String name : names) snapshots.put(testKey(className, name), s);
    }

    /**
     * Record a skipped test. {@code reason} is the skip reason from the protocol event, may be null.
     */
    public synchronized void recordSkipped(String uniqueId, String display, @Nullable String reason) {
        String className = TestEventFields.classNameFrom(uniqueId);
        entries.add(new Entry(className, display, 0, null, null, reason != null ? reason : ""));
    }

    /** What a launch recorded for some classes that its {@link cc.jumpkick.run.TestSummary} carries only as totals. */
    public record Tally(long passed, long skipped, long classes) {}

    /** The passes, skips and distinct classes recorded for {@code classNames}, every class when it is empty. */
    public synchronized Tally tally(Collection<String> classNames) {
        long passed = 0, skipped = 0;
        Set<String> classes = new HashSet<>();
        for (Entry e : entries) {
            if (!classNames.isEmpty() && !classNames.contains(e.className())) continue;
            if (e.isPass()) passed++;
            else if (e.isSkip()) skipped++;
            classes.add(e.className());
        }
        return new Tally(passed, skipped, classes.size());
    }

    /** What {@link #forget} took for some classes, for {@link #restore}. */
    public record Held(
            Collection<String> classNames,
            List<Entry> entries,
            Map<String, Integer> printedLines,
            Map<String, ArrayDeque<String>> printedTail,
            Map<String, Snapshot> snapshots) {}

    /**
     * Drop what was recorded for {@code classNames}, every class when it is empty, so a run that
     * repeats them reports only the repeat. Returns what was dropped.
     */
    public synchronized Held forget(Collection<String> classNames) {
        List<Entry> held = new ArrayList<>();
        for (var it = entries.iterator(); it.hasNext(); ) {
            Entry e = it.next();
            if (classNames.isEmpty() || classNames.contains(e.className())) {
                held.add(e);
                it.remove();
            }
        }
        Map<String, Integer> lines = new LinkedHashMap<>();
        Map<String, ArrayDeque<String>> tails = new LinkedHashMap<>();
        printedLines.keySet().removeIf(cls -> {
            if (!classNames.isEmpty() && !classNames.contains(cls)) return false;
            lines.put(cls, printedLines.get(cls));
            ArrayDeque<String> tail = printedTail.remove(cls);
            if (tail != null) tails.put(cls, tail);
            return true;
        });
        Map<String, Snapshot> took = new LinkedHashMap<>();
        snapshots.entrySet().removeIf(e -> {
            if (!classNames.isEmpty() && !classNames.contains(e.getValue().className())) return false;
            took.put(e.getKey(), e.getValue());
            return true;
        });
        return new Held(List.copyOf(classNames), held, lines, tails, took);
    }

    /** Put back what {@link #forget} took, in place of anything recorded for its classes since. */
    public synchronized void restore(Held held) {
        forget(held.classNames());
        entries.addAll(held.entries());
        printedLines.putAll(held.printedLines());
        printedTail.putAll(held.printedTail());
        snapshots.putAll(held.snapshots());
    }

    /**
     * Fold this launch's entries into the request's sink under {@code scopeKey} (module path).
     * No-op when nothing was recorded. Concurrent launches of the same key merge.
     */
    public synchronized void publish(String scopeKey, String label, Function<String, String> reportOf) {
        if (entries.isEmpty()) return;
        Map<String, Printed> printed = new LinkedHashMap<>();
        printedLines.forEach((cls, n) -> {
            ArrayDeque<String> tail = printedTail.get(cls);
            printed.put(cls, new Printed(n, tail == null ? List.of() : List.copyOf(tail), reportOf.apply(cls)));
        });
        Map<String, List<String>> lines = new LinkedHashMap<>();
        snapshots.forEach((key, s) -> lines.put(key, s.lines()));
        publish(scopeKey, label, entries, printed, lines);
    }

    /**
     * Fold {@code entries} recorded elsewhere (a surefire report) into the request's sink under
     * {@code scopeKey}. Dropped when no request is open on this thread.
     */
    public static void publish(String scopeKey, String label, List<Entry> entries) {
        publish(scopeKey, label, entries, Map.of(), Map.of());
    }

    private static void publish(
            String scopeKey,
            String label,
            List<Entry> entries,
            Map<String, Printed> printed,
            Map<String, List<String>> snapshots) {
        if (entries.isEmpty()) return;
        RunResults sink = RunResults.ambient();
        if (sink == null) return;
        String k = scopeKey == null || scopeKey.isBlank() ? "_" : scopeKey;
        sink.publishTests(k, label == null ? "" : label, List.copyOf(entries), printed, snapshots);
    }
}
