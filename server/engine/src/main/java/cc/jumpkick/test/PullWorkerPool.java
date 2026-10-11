// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.plugin.HeapScope;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.engine.plugin.PluginLoader;
import cc.jumpkick.engine.plugin.PluginProcess;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.run.SessionCancel;
import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.run.TestSummary;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * The pull-mode shard pool of one {@link JUnitLauncher} run: N worker JVMs draining one class queue.
 * Each worker is driven from its own virtual thread — the thread blocks on the child's stdout for
 * the worker's whole life — started under the launching request's session so the JVM it forks is
 * sized and flagged with that request's tuning and stops on its cancel token. Report accumulation
 * (XML, Markdown) stays with the launcher; this class only forks, dispatches and merges.
 *
 * <p>A worker is complete only when it exited 0 after the parent told it {@code DONE}: a JVM that
 * ends earlier, on any exit, left its in-flight class unreported and is a failure row, and classes
 * still queued once every worker has ended are a shortfall row. Neither is ever scored green.
 */
final class PullWorkerPool {

    private final JUnitLauncher launcher;
    private final Path javaHome;
    private final String classpath;
    private final Path testClassesDir;
    private final TestProgressListener listener;
    private final String moduleLabel;

    PullWorkerPool(
            JUnitLauncher launcher,
            Path javaHome,
            String classpath,
            Path testClassesDir,
            TestProgressListener listener) {
        this.launcher = launcher;
        this.javaHome = javaHome;
        this.classpath = classpath;
        this.testClassesDir = testClassesDir;
        this.listener = listener;
        this.moduleLabel = launcher.moduleLabel();
    }

    /** One pull-queue pool over {@code classes}; report accumulation stays with the caller. */
    TestSummary run(
            int workers, List<String> classes, @Nullable XmlTestReport xml, MarkdownTestReport md, int workerIdBase)
            throws IOException, InterruptedException {
        // Don't waste workers on small suites — N workers > N classes leaves
        // some idle waiting for a class that'll never come.
        int actualWorkers = Math.min(workers, classes.size());
        actualWorkers = TestWorkers.clampByHeap(actualWorkers);

        var queue = new ConcurrentLinkedDeque<>(classes);
        var aggregators = new ArrayList<ResultAggregator>();
        var workerThreads = new ArrayList<Thread>();
        // A worker whose thread never stored an exit — its body threw — is a death, not a clean 0.
        int[] exits = new int[actualWorkers];
        Arrays.fill(exits, -1);
        var dones = new ArrayList<AtomicBoolean>();
        var captures = new ArrayList<CaptureBuffer>();
        var lastClasses = new ArrayList<AtomicReference<String>>();
        var handlerFailures = new ArrayList<AtomicReference<@Nullable RuntimeException>>();
        var commands = new ArrayList<AtomicReference<List<String>>>();

        for (int w = 0; w < actualWorkers; w++) {
            // workerIdBase keeps ids unique across the sharded and serial-tag pools, so the
            // per-worker temp/state suffixes and failure attributions never collide.
            final int workerId = workerIdBase + w + 1;
            final int idx = w;
            List<String> args = launcher.pullWorkerArgs(workerId, testClassesDir);
            var agg = new ResultAggregator(listener, workerId, xml, md, moduleLabel);
            aggregators.add(agg);
            final var crash = new CaptureBuffer();
            captures.add(crash);
            final var done = new AtomicBoolean();
            dones.add(done);
            final var last = new AtomicReference<String>("");
            lastClasses.add(last);
            final var handlerFailure = new AtomicReference<@Nullable RuntimeException>();
            handlerFailures.add(handlerFailure);
            final var command = new AtomicReference<List<String>>(List.of());
            commands.add(command);
            final int totalWorkers = actualWorkers;
            // Virtual: the thread blocks on the child's stdout for the worker's whole life —
            // exactly the shape VT is for.
            Thread t = SessionContext.startVirtual(
                    "jk-test-worker-" + workerId,
                    () -> exits[idx] = driveWorker(
                            workerId, totalWorkers, args, queue, agg, crash, done, last, handlerFailure, command));
            workerThreads.add(t);
        }
        // Each worker thread owns its process (via PluginProcess.converse) and
        // returns its exit code once stdout is fully drained and the process
        // has exited. Join them and take the worst exit.
        for (Thread t : workerThreads) t.join();
        int worstExit = 0;
        boolean[] incomplete = new boolean[actualWorkers];
        boolean anyIncomplete = false;
        for (int i = 0; i < actualWorkers; i++) {
            if (exits[i] != 0) worstExit = exits[i];
            incomplete[i] = exits[i] != 0 || !dones.get(i).get();
            anyIncomplete |= incomplete[i];
        }
        // Merge per-worker aggregators into one TestSummary.
        long total = 0, succeeded = 0, failed = 0, skipped = 0, classCount = 0;
        var allFailures = new ArrayList<TestFailureInfo>();
        var walls = new LinkedHashMap<String, Long>();
        for (var agg : aggregators) {
            var r = agg.snapshot();
            total += r.total();
            succeeded += r.succeeded();
            failed += r.failed();
            skipped += r.skipped();
            classCount += r.classes();
            allFailures.addAll(r.failures());
            r.classWallMs().forEach((k, v) -> walls.merge(k, v, Long::sum));
        }
        boolean handlerFailed = handlerFailures.stream().anyMatch(f -> f.get() != null);
        if (total == 0 && anyIncomplete && !handlerFailed) {
            // No test events but a worker died — the launcher failed before any test ran; what
            // the crashed worker(s) printed (the dropped stderr) is the evidence, and how the
            // first of them was started is the evidence when they printed nothing.
            StringBuilder crash = new StringBuilder();
            List<String> command = List.of();
            for (int i = 0; i < actualWorkers; i++) {
                if (!incomplete[i]) continue;
                if (command.isEmpty()) command = commands.get(i).get();
                if (!captures.get(i).isEmpty()) {
                    if (crash.length() > 0) crash.append('\n');
                    crash.append(captures.get(i).text());
                }
            }
            throw TestLauncherFailure.runner(moduleLabel, worstExit, crash.toString(), command);
        }
        // A worker that dies mid-suite while its siblings keep going must not vanish silently:
        // its in-flight class is neither run nor reported, and the suite would go green with a
        // shortfall. Surface every worker that ended before DONE as a failure naming its last
        // class (idle-watchdog kills and a clean exit 0 land here too), and a conversation the
        // parent's own handler ended as the parent-side bug it is. Skipped on user cancel: those
        // exits are the kill we asked for, and the shortfall row below says so.
        if (anyIncomplete && !SessionCancel.cancelled()) {
            for (int i = 0; i < actualWorkers; i++) {
                if (!incomplete[i]) continue;
                total += 1;
                failed += 1;
                allFailures.add(WorkerFailureRow.of(
                        moduleLabel,
                        workerIdBase + i + 1,
                        exits[i],
                        lastClasses.get(i).get(),
                        captures.get(i).text(),
                        handlerFailures.get(i).get()));
            }
        }
        TestFailureInfo shortfall =
                RunShortfall.row(moduleLabel, SessionCancel.cancelled(), anyIncomplete, queue.size());
        if (shortfall != null) {
            total += 1;
            failed += 1;
            allFailures.add(shortfall);
        }
        return new TestSummary(total, succeeded, failed, skipped, classCount, allFailures, walls, actualWorkers);
    }

    /**
     * The pull protocol's parent side: each {@code ready} pulls the next class from the shared
     * queue onto the child's stdin ({@code DONE} once the queue is empty, which also sets {@code
     * done}: the worker owes nothing more); every other event is the aggregator's. A throw from
     * here ends the conversation as a {@link PluginProcess.HandlerFailure}.
     */
    static BiConsumer<String, PluginProcess.Conversation> pullHandler(
            ConcurrentLinkedDeque<String> queue,
            ResultAggregator aggregator,
            AtomicBoolean done,
            AtomicReference<String> lastClass) {
        return (json, convo) -> {
            String event = Jsonl.str(json, "event");
            if ("ready".equals(event)) {
                String next = queue.pollFirst();
                if (next != null) {
                    lastClass.set(next);
                    convo.send("RUN " + next);
                } else {
                    done.set(true);
                    convo.send("DONE");
                    convo.closeInput();
                }
            } else {
                aggregator.accept(json);
            }
        };
    }

    /**
     * Per-worker reader thread. Reads the child's stdout line-by-line. On each {@code ready} event,
     * dispatch the next class from the shared queue (or {@code DONE} when the queue is empty) by
     * writing one line to the child's stdin. Non-protocol lines are user test output — passed through
     * to the parent's stdout, tagged with the worker id. A handler that throws ends the worker with
     * {@code -1} and leaves its exception in {@code handlerFailure} for the summary to name; any
     * other parent-side throw ends it with {@code -1} and its stack in the worker's output.
     */
    private int driveWorker(
            int workerId,
            int totalWorkers,
            List<String> args,
            ConcurrentLinkedDeque<String> queue,
            ResultAggregator aggregator,
            CaptureBuffer crash,
            AtomicBoolean done,
            AtomicReference<String> lastClass,
            AtomicReference<@Nullable RuntimeException> handlerFailure,
            AtomicReference<List<String>> command) {
        BiConsumer<String, PluginProcess.Conversation> handler = pullHandler(queue, aggregator, done, lastClass);
        Consumer<String> passthrough = line -> {
            crash.add(line);
            aggregator.userOutput(line);
        };

        try {
            WorkerEnv testEnv = launcher.testEnv();
            Path tmp = TestTmpDir.forWorker(launcher.testTmpDir(), workerId, totalWorkers);
            WorkerEnv env = totalWorkers > 1 && tmp != null ? TestWorkerEnv.forWorker(testEnv, workerId, tmp) : testEnv;
            List<String> flags = launcher.jvmFlags(JvmRole.PULL_WORKER, totalWorkers, tmp);
            command.set(PluginLoader.command(javaHome, classpath, flags, args));
            HeapScope.Key key = SuiteRetry.key(
                    SuiteRetry.project(launcher.inferredModuleDir()), moduleLabel, JvmOptions.hostFeature(javaHome));
            if (key == null) {
                return PluginLoader.converse(
                        javaHome,
                        classpath,
                        // N test JVMs run at once → divide the heap cap by N so they fit.
                        flags,
                        JUnitLauncher.PROTOCOL_PREFIX,
                        args,
                        env,
                        launcher.workDir(),
                        handler,
                        passthrough,
                        TestWorkerEnv.idleTimeoutMs(),
                        launcher.heaps());
            }
            try {
                return HeapScope.call(
                        key,
                        () -> PluginLoader.converse(
                                javaHome,
                                classpath,
                                flags,
                                JUnitLauncher.PROTOCOL_PREFIX,
                                args,
                                env,
                                launcher.workDir(),
                                handler,
                                passthrough,
                                TestWorkerEnv.idleTimeoutMs(),
                                launcher.heaps()));
            } catch (IOException | InterruptedException | RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException(e);
            }
        } catch (PluginProcess.HandlerFailure e) {
            handlerFailure.set(e.handler());
            listener.onUserOutput(workerId, Objects.requireNonNull(e.getMessage()));
            return -1;
        } catch (IOException e) {
            listener.onUserOutput(workerId, "reader error: " + e.getMessage());
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } catch (RuntimeException e) {
            StringWriter stack = new StringWriter();
            e.printStackTrace(new PrintWriter(stack, true));
            String text = "test worker driver failed: " + stack;
            crash.add(text);
            listener.onUserOutput(workerId, text);
            return -1;
        }
    }
}
