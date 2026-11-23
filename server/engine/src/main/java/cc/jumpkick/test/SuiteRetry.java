// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.plugin.HeapFlags;
import cc.jumpkick.engine.plugin.HeapLadder;
import cc.jumpkick.engine.plugin.HeapScope;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.engine.plugin.LearnedHeaps;
import cc.jumpkick.engine.plugin.WorkerContainment;
import cc.jumpkick.engine.plugin.WorkerFate;
import cc.jumpkick.orchestrator.RetryPolicy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The heap a suite JVM runs at and climbs to when it runs out, up the {@link HeapLadder}. A
 * user-pinned heap is left alone. Which failures a retry answers, and how its run replaces them, is
 * the orchestrator's ({@link RetryPolicy}); {@link #POLICY} reads a failure's text the way this
 * engine's workers word it.
 */
final class SuiteRetry {

    private SuiteRetry() {}

    /** The retry rules over this engine's words for a memory kill and a heap that ran out. */
    static final RetryPolicy POLICY =
            new RetryPolicy(WorkerFate::retryableKill, WorkerFate::mentionsHeap, WorkerFate.EXIT_ON_OUT_OF_MEMORY);

    /** True when jk chose the suite heap: no {@code -K} and no heap flag in {@code userArgs}. */
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
        return tune(flags, project, module, jdk, override, heaps, ceiling());
    }

    /** {@link #tune} under {@code ceiling}, the most a test JVM is given ({@code 0}: not known). */
    static List<String> tune(
            List<String> flags,
            Path project,
            String module,
            int jdk,
            @Nullable Long override,
            LearnedHeaps heaps,
            long ceiling) {
        long current = HeapFlags.parseXmx(flags);
        long chosen = override != null ? override : heaps.choose(project, module, HeapScope.TEST, jdk, current);
        // Past the most a test JVM is given, the heap runs at that, silently: a record is planned
        // under the ceiling already, so only a key with none, at the plan's share of the host's free
        // memory, can pass it, and that is nothing the user could forget. The flag names whole MiB,
        // so a ceiling that is not one runs at the whole MiB below it.
        if (override == null && ceiling > 0 && chosen > ceiling) chosen = ceiling;
        if (chosen <= 0 || chosen == current) return flags;
        List<String> rewritten = new ArrayList<>(HeapFlags.rewriteHeap(flags, chosen));
        JvmOptions.notePlannedCommand(rewritten);
        return rewritten;
    }

    static HeapScope.@Nullable Key key(Path project, String module, int jdk) {
        if (project == null || module == null || module.isBlank()) return null;
        return new HeapScope.Key(project, module, HeapScope.TEST, jdk);
    }

    /**
     * Run the class listing of the suite keyed {@code suite} under a row of its own, so the ledger
     * leases it at what a listing holds and the suite's learned heap stays the suite's.
     */
    static <T extends @Nullable Object> T listing(HeapScope.@Nullable Key suite, HeapScope.Fork<T> list)
            throws IOException, InterruptedException {
        if (suite == null) return list.run();
        return HeapScope.fork(listKey(suite), list);
    }

    static HeapScope.Key listKey(HeapScope.Key suite) {
        return new HeapScope.Key(suite.project(), suite.module(), HeapScope.TEST_LIST, suite.jdk());
    }

    static Path project(@Nullable Path moduleDir) {
        if (moduleDir != null) return moduleDir;
        return SessionContext.current().workingDir();
    }

    /** The launcher failure's cause, without probing the cgroup a second time. */
    static WorkerFate.Cause cause(TestLauncherFailure failure, boolean ranOut) {
        String msg = failure.getMessage() == null ? "" : failure.getMessage();
        WorkerFate.Cause cause = WorkerFate.classify(
                failure.exit(), failure.output() + "\n" + msg, msg.contains(WorkerContainment.KILLED_FOR_MEMORY));
        // A test JVM runs without -XX:+ExitOnOutOfMemoryError, so exit 3 is a test's own
        // System.exit(3) and the words are anyone's: only the JVM's own signal says its heap ran out.
        return cause == WorkerFate.Cause.HEAP_EXHAUSTED && !ranOut ? WorkerFate.Cause.OTHER : cause;
    }

    /**
     * The largest heap a test JVM is given, learned or climbed to: {@link LearnedHeaps#testCeiling}.
     */
    static long ceiling() {
        return LearnedHeaps.testCeiling();
    }

    /**
     * {@code who} ran out of every heap in {@code ranOut}, oldest first, on a ladder held to {@code
     * budget} and {@code ceiling}.
     */
    static String exhausted(String who, List<Long> ranOut, long budget, long ceiling) {
        return who + " " + HeapLadder.ranOut(ranOut, budget, ceiling) + "; raise -Xmx in [test] jvm-args or [jvm] args";
    }

    /**
     * The failure a launch with a heap jk does not plan ends in: the pinned-heap failure when the
     * runner ran out of its heap, else {@code e} as it is.
     */
    static TestLauncherFailure unplanned(TestLauncherFailure e, String moduleLabel, List<String> userArgs) {
        if (!WorkerFate.heapExhausted(e.exit(), e.output())) return e;
        return TestLauncherFailure.heap(
                moduleLabel, e.exit(), e.output(), e.command(), pinned("test runner", pin(userArgs)));
    }

    static String pinned(String who, String pin) {
        String named = pin == null || pin.isBlank() ? "heap" : pin;
        return who + " ran out of the pinned heap " + named + "; raise -Xmx in [test] jvm-args or [jvm] args";
    }
}
