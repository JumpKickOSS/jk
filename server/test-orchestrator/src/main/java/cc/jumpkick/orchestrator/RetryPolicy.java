// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.orchestrator;

import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.run.TestSummary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Which failures of a suite run a retry answers and how the retry's run replaces them: a row that is
 * the suite running out of its heap, or a worker killed for memory, runs again; any other failure
 * stands. The engine sizes the heap a retry runs at and launches it; a change to these rules moves
 * every run-tests stamp through this package's classes.
 *
 * @param retryableKill whether a failure's text names a memory kill a retry at the same heap may
 *     survive, as the engine's worker containment words it
 * @param heapText whether a failure's text names the JVM's own heap running out
 * @param heapExit the exit status of a JVM that ran out of heap under {@code -XX:+ExitOnOutOfMemoryError}
 */
public record RetryPolicy(Predicate<String> retryableKill, Predicate<String> heapText, int heapExit) {

    /** What the run so far recorded for the classes a retry runs, which the retry's run replaces. */
    public record Share(long passed, long skipped, long classes) {}

    /**
     * A failure row that is this suite running out of heap (an {@link OutOfMemoryError} for the heap
     * that escaped a test, or a runner that died of one) or a worker killed for memory. A heap row
     * counts only when a suite JVM of this run marked its own heap exhaustion ({@code ranOut}): a
     * test can print the words, or assert on a child's output. An error for the array size limit,
     * metaspace or direct buffers, or a kill at the worker's own cap, is an ordinary failure.
     */
    public boolean retryable(TestFailureInfo failure, boolean ranOut) {
        String text = failure.message() + "\n" + failure.stack();
        if (retryableKill.test(text)) return true;
        if (!ranOut) return false;
        if (escapedHeapError(failure)) return true;
        if (heapText.test(text)) return true;
        return text.contains("exited " + heapExit);
    }

    /** A test's own uncaught {@code java.lang.OutOfMemoryError: Java heap space} or GC-overhead error. */
    static boolean escapedHeapError(TestFailureInfo failure) {
        if (!OutOfMemoryError.class.getName().equals(failure.exceptionClass())) return false;
        String message = failure.message();
        return message.contains("Java heap space") || message.contains("GC overhead limit exceeded");
    }

    /** Every retryable failure of {@code summary} is a memory kill, not a heap exhaustion. */
    public boolean onlyKilled(TestSummary summary, boolean ranOut) {
        return summary.failures().stream().filter(f -> retryable(f, ranOut)).allMatch(this::killed);
    }

    /** A memory kill a retry at the same heap may survive, and not the heap running out. */
    public boolean killed(TestFailureInfo failure) {
        String text = failure.message() + "\n" + failure.stack();
        return retryableKill.test(text) && !heapText.test(text);
    }

    /**
     * Classes to run again. A worker row's class is the one it was dispatching; once a retry is
     * due, {@code undispatched} (the classes a pool left queued when its workers died) joins it.
     */
    public List<String> classes(TestSummary summary, List<String> undispatched, boolean ranOut) {
        Set<String> out = new LinkedHashSet<>();
        boolean due = false;
        for (TestFailureInfo failure : summary.failures()) {
            if (!retryable(failure, ranOut)) continue;
            due = true;
            if (!suiteRow(failure)) out.add(failure.className());
        }
        if (due) out.addAll(undispatched);
        return List.copyOf(out);
    }

    /**
     * A retryable failure is the suite JVM's, not one class's ({@link #suiteRow}): it died after it
     * had already reported tests, so the whole selection runs again.
     */
    public boolean wholeSuite(TestSummary summary, boolean ranOut) {
        for (TestFailureInfo failure : summary.failures()) {
            if (retryable(failure, ranOut) && suiteRow(failure)) return true;
        }
        return false;
    }

    /** Every failure is a heap exhaustion or a memory kill, not an assertion the tests made. */
    public boolean onlyHeap(TestSummary summary, boolean ranOut) {
        if (summary.failures().isEmpty()) return false;
        for (TestFailureInfo failure : summary.failures()) {
            if (!retryable(failure, ranOut)) return false;
        }
        return true;
    }

    /** Classes whose failure is running out of heap, not a memory kill. */
    public List<String> heapClasses(TestSummary summary, boolean ranOut) {
        Set<String> out = new LinkedHashSet<>();
        for (TestFailureInfo failure : summary.failures()) {
            if (!retryable(failure, ranOut) || killed(failure)) continue;
            if (!suiteRow(failure)) out.add(failure.className());
        }
        return List.copyOf(out);
    }

    /**
     * {@code second}, the retried classes' run, replaces what {@code first} holds for them: their
     * failure rows, a blank-class heap row and the shortfall row for the classes the retry ran
     * undispatched go, {@code share} (the passes, skips and classes the launch's report recorded
     * for them before the retry) comes off the counts, and their walls are the retry's.
     */
    public TestSummary merge(TestSummary first, List<String> retried, Share share, TestSummary second, boolean ranOut) {
        Set<String> drop = Set.copyOf(retried);
        List<TestFailureInfo> kept = new ArrayList<>();
        for (TestFailureInfo failure : first.failures()) {
            String cls = failure.className();
            if (drop.contains(cls) || (suiteRow(failure) && retryable(failure, ranOut)) || RunShortfall.isRow(failure))
                continue;
            kept.add(failure);
        }
        long removed = first.failures().size() - kept.size();
        kept.addAll(second.failures());
        var walls = new LinkedHashMap<>(first.classWallMs());
        walls.keySet().removeAll(drop);
        second.classWallMs().forEach((k, v) -> walls.merge(k, v, Long::sum));
        return new TestSummary(
                Math.max(0, first.total() - removed - share.passed() - share.skipped()) + second.total(),
                Math.max(0, first.succeeded() - share.passed()) + second.succeeded(),
                Math.max(0, first.failed() - removed) + second.failed(),
                Math.max(0, first.skipped() - share.skipped()) + second.skipped(),
                Math.max(0, first.classes() - share.classes()) + second.classes(),
                kept,
                walls,
                Math.max(first.workers(), second.workers()));
    }

    /**
     * A row that is the suite JVM's rather than one class's: it names no class, or it is the {@code
     * (test run)} row of a one-JVM suite that died, whose class is only where it died. The classes
     * that JVM had not reached yet never ran, so a retry runs the whole selection, not that class.
     */
    private static boolean suiteRow(TestFailureInfo failure) {
        return failure.className().isBlank() || RunShortfall.RUN.equals(failure.method());
    }
}
