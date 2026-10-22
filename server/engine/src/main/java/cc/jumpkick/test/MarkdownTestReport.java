// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.jsonl.Jsonl;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
public final class MarkdownTestReport {

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

    /** One module's tests, published after that module's workers join. */
    public record ModuleRun(String scopeKey, String label, List<Entry> entries) {
        public ModuleRun {
            entries = entries == null ? List.of() : List.copyOf(entries);
            if (scopeKey == null) scopeKey = "";
            if (label == null) label = "";
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    private final FailureTexts failureTexts = new FailureTexts();

    /**
     * Record a finished test (passed, failed, or aborted). {@code throwableJson} is the raw nested
     * JSON object from the protocol event's {@code throwable} field — {@code null} for a passing
     * test.
     */
    public synchronized void recordFinished(
            String uniqueId, String display, long durationMs, @Nullable String throwableJson) {
        String className = XmlTestReport.classNameFrom(uniqueId);
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
     * Record a skipped test. {@code reason} is the skip reason from the protocol event, may be null.
     */
    public synchronized void recordSkipped(String uniqueId, String display, @Nullable String reason) {
        String className = XmlTestReport.classNameFrom(uniqueId);
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
    public record Held(Collection<String> classNames, List<Entry> entries) {}

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
        return new Held(List.copyOf(classNames), held);
    }

    /** Put back what {@link #forget} took, in place of anything recorded for its classes since. */
    public synchronized void restore(Held held) {
        forget(held.classNames());
        entries.addAll(held.entries());
    }

    /**
     * Fold this launch's entries into the request's sink under {@code scopeKey} (module path).
     * No-op when nothing was recorded. Concurrent launches of the same key merge.
     */
    public synchronized void publish(String scopeKey, String label) {
        publish(scopeKey, label, entries);
    }

    /**
     * Fold {@code entries} recorded elsewhere (a surefire report) into the request's sink under
     * {@code scopeKey}. Dropped when no request is open on this thread.
     */
    public static void publish(String scopeKey, String label, List<Entry> entries) {
        if (entries.isEmpty()) return;
        RunResults sink = RunResults.ambient();
        if (sink == null) return;
        String k = scopeKey == null || scopeKey.isBlank() ? "_" : scopeKey;
        sink.publishTests(k, label == null ? "" : label, List.copyOf(entries));
    }
}
