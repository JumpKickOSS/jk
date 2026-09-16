// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.plugin.PluginLoader;
import cc.jumpkick.engine.plugin.PluginProcess;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.run.SessionCancel;
import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.run.TestSummary;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;
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
 */
final class PullWorkerPool {

    private final JUnitLauncher launcher;
    private final Path javaBinary;
    private final String classpath;
    private final Path testClassesDir;
    private final TestProgressListener listener;
    private final String moduleLabel;

    PullWorkerPool(
            JUnitLauncher launcher,
            Path javaBinary,
            String classpath,
            Path testClassesDir,
            TestProgressListener listener) {
        this.launcher = launcher;
        this.javaBinary = javaBinary;
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
        int[] exits = new int[actualWorkers];
        var captures = new ArrayList<CaptureBuffer>();
        var lastClasses = new ArrayList<AtomicReference<String>>();
        var handlerFailures = new ArrayList<AtomicReference<@Nullable RuntimeException>>();

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
            final var last = new AtomicReference<String>("");
            lastClasses.add(last);
            final var handlerFailure = new AtomicReference<@Nullable RuntimeException>();
            handlerFailures.add(handlerFailure);
            final int totalWorkers = actualWorkers;
            // Virtual: the thread blocks on the child's stdout for the worker's whole life —
            // exactly the shape VT is for.
            Thread t = SessionContext.startVirtual(
                    "jk-test-worker-" + workerId,
                    () -> exits[idx] =
                            driveWorker(workerId, totalWorkers, args, queue, agg, crash, last, handlerFailure));
            workerThreads.add(t);
        }
        // Each worker thread owns its process (via PluginProcess.converse) and
        // returns its exit code once stdout is fully drained and the process
        // has exited. Join them and take the worst exit.
        for (Thread t : workerThreads) t.join();
        int worstExit = 0;
        for (int e : exits) {
            if (e != 0) worstExit = e;
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
        if (total == 0 && worstExit != 0 && !handlerFailed) {
            // No test events but a worker died — the launcher failed before any test ran; what
            // the crashed worker(s) printed (the dropped stderr) is the evidence.
            StringBuilder crash = new StringBuilder();
            for (int i = 0; i < actualWorkers; i++) {
                if (exits[i] != 0 && !captures.get(i).isEmpty()) {
                    if (crash.length() > 0) crash.append('\n');
                    crash.append(captures.get(i).text());
                }
            }
            throw TestLauncherFailure.runner(moduleLabel, worstExit, crash.toString());
        }
        // A worker that dies mid-suite while its siblings keep going must not vanish silently:
        // its in-flight class is neither run nor reported, and the suite would go green with a
        // shortfall. Surface every abnormal exit as a failure naming the worker's last class
        // (idle-watchdog kills land here too), and a conversation the parent's own handler ended
        // as the parent-side bug it is. Skipped on user cancel: those exits are the kill we
        // asked for.
        if (worstExit != 0 && !SessionCancel.cancelled()) {
            for (int i = 0; i < actualWorkers; i++) {
                if (exits[i] == 0) continue;
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
        String cancelledWhy = CancelledShortfall.of(SessionCancel.cancelled(), worstExit, queue.size());
        if (cancelledWhy != null) {
            total += 1;
            failed += 1;
            allFailures.add(new TestFailureInfo(moduleLabel, "", "", "(test run)", "", cancelledWhy, "", 0));
        }
        return new TestSummary(total, succeeded, failed, skipped, classCount, allFailures, walls, actualWorkers);
    }

    /**
     * The pull protocol's parent side: each {@code ready} pulls the next class from the shared
     * queue onto the child's stdin ({@code DONE} once the queue is empty); every other event is
     * the aggregator's. A throw from here ends the conversation as a {@link
     * PluginProcess.HandlerFailure}.
     */
    static BiConsumer<String, PluginProcess.Conversation> pullHandler(
            ConcurrentLinkedDeque<String> queue, ResultAggregator aggregator, AtomicReference<String> lastClass) {
        return (json, convo) -> {
            String event = Jsonl.str(json, "event");
            if ("ready".equals(event)) {
                String next = queue.pollFirst();
                if (next != null) {
                    lastClass.set(next);
                    convo.send("RUN " + next);
                } else {
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
     * {@code -1} and leaves its exception in {@code handlerFailure} for the summary to name.
     */
    private int driveWorker(
            int workerId,
            int totalWorkers,
            List<String> args,
            ConcurrentLinkedDeque<String> queue,
            ResultAggregator aggregator,
            CaptureBuffer crash,
            AtomicReference<String> lastClass,
            AtomicReference<@Nullable RuntimeException> handlerFailure) {
        BiConsumer<String, PluginProcess.Conversation> handler = pullHandler(queue, aggregator, lastClass);
        Consumer<String> passthrough = line -> {
            crash.add(line);
            aggregator.userOutput(line);
        };

        try {
            WorkerEnv testEnv = launcher.testEnv();
            Path tmp = TestTmpDir.forWorker(launcher.testTmpDir(), workerId, totalWorkers);
            WorkerEnv env = totalWorkers > 1 && tmp != null ? TestWorkerEnv.forWorker(testEnv, workerId, tmp) : testEnv;
            List<String> flags = launcher.jvmFlags(JvmRole.PULL_WORKER, totalWorkers, tmp);
            return PluginLoader.converse(
                    javaBinary,
                    classpath,
                    // N test JVMs run at once → divide the heap cap by N so they fit.
                    flags,
                    JUnitLauncher.PROTOCOL_PREFIX,
                    args,
                    env,
                    launcher.workDir(),
                    handler,
                    passthrough,
                    TestWorkerEnv.idleTimeoutMs());
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
        }
    }
}
