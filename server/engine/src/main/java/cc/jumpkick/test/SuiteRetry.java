// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.plugin.HeapScope;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.engine.plugin.LearnedHeaps;
import cc.jumpkick.engine.plugin.WorkerContainment;
import cc.jumpkick.engine.plugin.WorkerFate;
import cc.jumpkick.engine.plugin.WorkerLeases;
import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.run.TestSummary;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * One retry of a test item whose jk-planned heap ran out, or whose worker was killed for memory.
 * A user-pinned heap is left alone.
 */
final class SuiteRetry {

    private SuiteRetry() {}

    /** True when jk chose the suite heap: no {@code --ram-percent} and no heap flag in {@code userArgs}. */
    static boolean planned(List<String> userArgs) {
        if (!JvmOptions.autoHeapEnabled()) return false;
        if (userArgs == null) return true;
        for (String arg : userArgs) {
            if (JvmOptions.pinsHeap(arg)) return false;
        }
        return true;
    }

    /** The pin a failure should name, from {@code userArgs} or the session tuning. */
    static String pin(List<String> userArgs) {
        if (userArgs != null) {
            for (String arg : userArgs) {
                if (JvmOptions.pinsHeap(arg)) return arg;
            }
        }
        return JvmOptions.userPinLabel(List.of());
    }

    /**
     * Replace the planned {@code -Xmx} with the learned size, or with {@code override} on a retry.
     * {@code flags} must not yet include the user's own heap flags.
     */
    static List<String> tune(
            List<String> flags, Path project, String module, int jdk, @Nullable Long override, LearnedHeaps heaps) {
        long current = WorkerLeases.parseXmx(flags);
        long chosen = override != null ? override : heaps.choose(project, module, HeapScope.TEST, jdk, current);
        if (chosen <= 0 || chosen == current) return flags;
        List<String> rewritten = new ArrayList<>(WorkerLeases.rewriteHeap(flags, chosen));
        JvmOptions.notePlannedCommand(rewritten);
        return rewritten;
    }

    static HeapScope.@Nullable Key key(Path project, String module, int jdk) {
        if (project == null || module == null || module.isBlank()) return null;
        return new HeapScope.Key(project, module, HeapScope.TEST, jdk);
    }

    static Path project(@Nullable Path moduleDir) {
        if (moduleDir != null) return moduleDir;
        return SessionContext.current().workingDir();
    }

    /** The launcher failure's cause, without probing the cgroup a second time. */
    static WorkerFate.Cause cause(TestLauncherFailure failure) {
        String msg = failure.getMessage() == null ? "" : failure.getMessage();
        return WorkerFate.classify(
                failure.exit(), failure.output() + "\n" + msg, msg.contains(WorkerContainment.KILLED_FOR_MEMORY));
    }

    /** A failure row that is this suite running out of heap, or a worker killed for memory. */
    static boolean retryable(TestFailureInfo failure) {
        if (failure == null) return false;
        String text = failure.message() + "\n" + failure.stack();
        if (WorkerFate.mentionsHeap(text)) return true;
        if (text.contains(WorkerContainment.KILLED_FOR_MEMORY)) return true;
        return text.contains("exited " + WorkerFate.EXIT_ON_OUT_OF_MEMORY);
    }

    static boolean killed(TestFailureInfo failure) {
        if (failure == null) return false;
        String text = failure.message() + "\n" + failure.stack();
        return text.contains(WorkerContainment.KILLED_FOR_MEMORY) && !WorkerFate.mentionsHeap(text);
    }

    /** Classes to run again. A worker row's class is the one it was dispatching. */
    static List<String> classes(TestSummary summary) {
        Set<String> out = new LinkedHashSet<>();
        if (summary == null) return List.of();
        for (TestFailureInfo failure : summary.failures()) {
            if (!retryable(failure)) continue;
            if (!failure.className().isBlank()) out.add(failure.className());
        }
        return List.copyOf(out);
    }

    /**
     * A retryable failure names no class: the suite JVM died after it had already reported tests, so
     * the whole selection runs again.
     */
    static boolean wholeSuite(TestSummary summary) {
        if (summary == null) return false;
        for (TestFailureInfo failure : summary.failures()) {
            if (retryable(failure) && failure.className().isBlank()) return true;
        }
        return false;
    }

    /** Every failure is a heap exhaustion or a memory kill, not an assertion the tests made. */
    static boolean onlyHeap(TestSummary summary) {
        if (summary == null || summary.failures().isEmpty()) return false;
        for (TestFailureInfo failure : summary.failures()) {
            if (!retryable(failure)) return false;
        }
        return true;
    }

    /** Twice {@code heap}, or {@code null} when the budget cannot grow it. */
    static @Nullable Long grown(long heap) {
        if (heap <= 0) return null;
        long bigger = LearnedHeaps.doubled(heap, WorkerLeases.engine().capacityBytes());
        return bigger > heap ? bigger : null;
    }

    static String exhausted(String who, long first, @Nullable Long second) {
        StringBuilder msg =
                new StringBuilder(who).append(" ran out of heap at ").append(WorkerLeases.format(first));
        if (second != null) msg.append(" and again at ").append(WorkerLeases.format(second));
        msg.append("; raise it with [test] jvm-args = [\"-Xmx...\"] or [jvm] args = [\"-Xmx...\"]");
        return msg.toString();
    }

    static String pinned(String who, String pin) {
        String named = pin == null || pin.isBlank() ? "heap" : pin;
        return who + " ran out of the pinned heap " + named
                + "; that setting is the worker's heap — raise it with [test] jvm-args = [\"-Xmx...\"]"
                + " or [jvm] args = [\"-Xmx...\"]";
    }

    /**
     * Drop {@code retried} classes' failures from {@code first} and fold in {@code second}. A class
     * that passed on the retry is no longer a failure.
     */
    static TestSummary merge(TestSummary first, List<String> retried, TestSummary second) {
        Set<String> drop = Set.copyOf(retried);
        List<TestFailureInfo> kept = new ArrayList<>();
        long removed = 0;
        for (TestFailureInfo failure : first.failures()) {
            if (retryable(failure) && (failure.className().isBlank() || drop.contains(failure.className()))) {
                removed++;
                continue;
            }
            kept.add(failure);
        }
        kept.addAll(second.failures());
        var walls = new LinkedHashMap<>(first.classWallMs());
        second.classWallMs().forEach((k, v) -> walls.merge(k, v, Long::sum));
        return new TestSummary(
                Math.max(0, first.total() - removed) + second.total(),
                first.succeeded() + second.succeeded(),
                Math.max(0, first.failed() - removed) + second.failed(),
                first.skipped() + second.skipped(),
                first.classes() + second.classes(),
                kept,
                walls,
                Math.max(first.workers(), second.workers()));
    }
}
