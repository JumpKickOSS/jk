// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.plugin.HeapFlags;
import cc.jumpkick.engine.plugin.HeapScope;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.engine.plugin.PluginLoader;
import cc.jumpkick.engine.plugin.PluginProcess;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.engine.plugin.WorkerExit;
import cc.jumpkick.engine.plugin.WorkerLeases;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.orchestrator.ResultAggregator;
import cc.jumpkick.orchestrator.RunShortfall;
import cc.jumpkick.orchestrator.SuiteReport;
import cc.jumpkick.orchestrator.TestProgressListener;
import cc.jumpkick.run.SessionCancel;
import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.run.TestSummary;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
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
    /** Whether the suite's JVM count was told to the listener: by its first pool only. */
    private boolean announced;

    private final String moduleLabel;

    /** The classes the last {@link #run} left queued: every worker had ended before they were dispatched. */
    private List<String> undispatched = List.of();

    /** Workers the last {@link #run} started. */
    private int started;

    /** The largest {@code -Xmx} a worker of the last {@link #run} was started with; {@code -1} when none was. */
    private final AtomicLong heap = new AtomicLong(-1);

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

    /** How often a pool that may widen re-reads its share. */
    static final long WIDEN_EVERY_MS = 2_000;

    /**
     * One pull-queue pool over {@code classes}; report accumulation stays with the caller. A {@code
     * widen} of non-null is the count the suite would start at now: while classes are still queued
     * the pool re-reads it every {@link #WIDEN_EVERY_MS} and starts more workers up to it, one at a
     * time and only while the lease ledger has a free JVM slot and the memory for one more
     * ({@link #roomFor}). It never stops a worker it started.
     */
    TestSummary run(
            int workers,
            List<String> classes,
            @Nullable XmlTestReport xml,
            MarkdownTestReport md,
            int workerIdBase,
            @Nullable IntSupplier widen)
            throws IOException, InterruptedException {
        // Don't waste workers on small suites — N workers > N classes leaves
        // some idle waiting for a class that'll never come.
        int actualWorkers = Math.min(workers, classes.size());
        actualWorkers = TestWorkers.clampByHeap(actualWorkers);
        // The suite's first pool is the JVMs its classes run side by side on: the sharded pool's, or
        // the serial-tag pool's one when every class is serial-tagged (the CLI's integration suite
        // was handed 8 and ran its 119 classes one after the other).
        boolean first = !announced;
        if (first) {
            announced = true;
            listener.onWorkers(actualWorkers);
        }

        undispatched = List.of();
        heap.set(-1);
        var queue = new ConcurrentLinkedDeque<>(classes);
        var shards = new ArrayList<Shard>();
        // A pool that may grow isolates its first worker too: a later sibling must not share its
        // temp root or home.
        boolean isolate = actualWorkers > 1 || widen != null;
        for (int w = 0; w < actualWorkers; w++) start(shards, queue, workerIdBase, actualWorkers, isolate, xml, md);
        if (widen != null) widen(shards, queue, workerIdBase, classes.size(), widen, first, xml, md);
        // Each worker thread owns its process (via PluginProcess.converse) and
        // returns its exit code once stdout is fully drained and the process
        // has exited. Join them and take the worst exit.
        for (Shard shard : shards) Objects.requireNonNull(shard.thread).join();
        started = shards.size();
        WorkerExit worstExit = WorkerExit.noWorker(0);
        boolean anyIncomplete = false;
        for (Shard shard : shards) {
            if (shard.exit.code() != 0) worstExit = shard.exit;
            anyIncomplete |= shard.incomplete();
        }
        // Merge per-worker aggregators into one TestSummary.
        long total = 0, succeeded = 0, failed = 0, skipped = 0, classCount = 0;
        var allFailures = new ArrayList<TestFailureInfo>();
        var walls = new LinkedHashMap<String, Long>();
        for (Shard shard : shards) {
            var r = shard.aggregator.snapshot();
            total += r.total();
            succeeded += r.succeeded();
            failed += r.failed();
            skipped += r.skipped();
            classCount += r.classes();
            allFailures.addAll(r.failures());
            r.classWallMs().forEach((k, v) -> walls.merge(k, v, Long::sum));
        }
        undispatched = List.copyOf(queue);
        boolean handlerFailed = shards.stream().anyMatch(s -> s.handlerFailure.get() != null);
        if (total == 0 && anyIncomplete && !handlerFailed) {
            // No worker ran because none got its lease: the lease's own refusal is the whole story.
            String unstarted = neverStarted(shards);
            if (unstarted != null) throw new IOException(unstarted);
            // No test events but a worker died — the launcher failed before any test ran; what
            // the crashed worker(s) printed (the dropped stderr) is the evidence, and how the
            // first of them was started is the evidence when they printed nothing.
            StringBuilder crash = new StringBuilder();
            List<String> command = List.of();
            for (Shard shard : shards) {
                if (!shard.incomplete()) continue;
                if (command.isEmpty()) command = shard.command.get();
                if (!shard.crash.isEmpty()) {
                    if (crash.length() > 0) crash.append('\n');
                    crash.append(shard.crash.text());
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
            for (Shard shard : shards) {
                if (!shard.incomplete()) continue;
                total += 1;
                failed += 1;
                allFailures.add(WorkerFailureRow.of(
                        moduleLabel,
                        shard.workerId,
                        shard.exit,
                        shard.lastClass.get(),
                        shard.unstarted.get(),
                        shard.crash.text(),
                        shard.handlerFailure.get()));
            }
        }
        TestFailureInfo shortfall =
                RunShortfall.row(moduleLabel, SessionCancel.cancelled(), anyIncomplete, queue.size());
        if (shortfall != null) {
            total += 1;
            failed += 1;
            allFailures.add(shortfall);
        }
        return new TestSummary(total, succeeded, failed, skipped, classCount, allFailures, walls, shards.size());
    }

    /** Why the pool's workers never started, when every one that ended early was refused its lease; else null. */
    private static @Nullable String neverStarted(List<Shard> shards) {
        String why = null;
        for (Shard shard : shards) {
            if (!shard.incomplete()) continue;
            if (shard.unstarted.get().isEmpty()) return null;
            if (why == null) why = shard.unstarted.get();
        }
        return why;
    }

    /**
     * Start more workers while classes are queued and {@code widen} asks for more than are running,
     * until every worker has ended, the queue is empty or the run is cancelled.
     */
    private void widen(
            List<Shard> shards,
            ConcurrentLinkedDeque<String> queue,
            int workerIdBase,
            int classCount,
            IntSupplier widen,
            boolean announce,
            @Nullable XmlTestReport xml,
            MarkdownTestReport md)
            throws InterruptedException {
        while (true) {
            Thread alive = null;
            for (Shard shard : shards) {
                Thread t = Objects.requireNonNull(shard.thread);
                if (t.isAlive()) {
                    alive = t;
                    break;
                }
            }
            if (alive == null) return;
            alive.join(WIDEN_EVERY_MS);
            if (queue.isEmpty() || SessionCancel.cancelled()) return;
            int want = Math.min(Math.min(widen.getAsInt(), classCount), shards.size() + queue.size());
            if (want <= shards.size()) continue;
            long lease = leaseOf(shards);
            if (lease < 0 || !roomFor(WorkerLeases.engine().snapshot(), lease)) continue;
            start(shards, queue, workerIdBase, want, true, xml, md);
            if (announce) listener.onWorkers(shards.size());
        }
    }

    /**
     * Whether the ledger can take one more test JVM leased at {@code leaseBytes} without anyone
     * waiting: nothing is queued for a lease, a JVM slot is free and the bytes fit the budget.
     */
    static boolean roomFor(WorkerLeases.Snapshot ledger, long leaseBytes) {
        if (ledger.queued() > 0 || ledger.runningJvms() >= ledger.cpuCap()) return false;
        return ledger.budgetBytes() - ledger.leasedBytes() >= leaseBytes;
    }

    /**
     * The lease the pool's first worker's heap asks for, what one more worker would take; {@code -1}
     * before that worker has built its command.
     */
    private static long leaseOf(List<Shard> shards) {
        List<String> command = shards.getFirst().command.get();
        if (command.isEmpty()) return -1;
        return WorkerLeases.jvmLease(
                Math.max(0, JvmOptions.HeapChoice.inspect(command).xmxBytes()));
    }

    /** Start worker {@code shards.size() + 1} of the pool, its JVM sized for {@code concurrency}. */
    private void start(
            List<Shard> shards,
            ConcurrentLinkedDeque<String> queue,
            int workerIdBase,
            int concurrency,
            boolean isolate,
            @Nullable XmlTestReport xml,
            MarkdownTestReport md) {
        // workerIdBase keeps ids unique across the sharded and serial-tag pools, so the
        // per-worker temp/state suffixes and failure attributions never collide.
        int workerId = workerIdBase + shards.size() + 1;
        var shard = new Shard(
                workerId,
                new ResultAggregator(listener, workerId, SuiteReport.all(xml, md), moduleLabel, launcher.secrets()));
        List<String> args = launcher.pullWorkerArgs(workerId, testClassesDir);
        // Virtual: the thread blocks on the child's stdout for the worker's whole life —
        // exactly the shape VT is for.
        shard.thread = SessionContext.startVirtual(
                "jk-test-worker-" + workerId, () -> shard.exit = driveWorker(shard, concurrency, isolate, args, queue));
        shards.add(shard);
    }

    /** How many workers the last {@link #run} started, the ones it widened to included. */
    int started() {
        return started;
    }

    /**
     * The largest {@code -Xmx} a worker of the last {@link #run} was started with, {@code -1} when it
     * started none: the heap a retry climbs from. Planning it again after the run would read the
     * peaks its workers recorded as they exited, not the heap they had.
     */
    long heap() {
        return heap.get();
    }

    /** The classes the last {@link #run} never dispatched; a heap retry runs them with the worker's own class. */
    List<String> undispatched() {
        return undispatched;
    }

    /**
     * The pull protocol's parent side: each {@code ready} pulls the next class from the shared
     * queue onto the worker's command channel ({@code DONE} once the queue is empty, which also sets {@code
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
     * writing one line to the worker's command channel. Non-protocol lines are user test output — passed through
     * to the parent's stdout, tagged with the worker id. A handler that throws ends the worker with
     * {@code -1} and leaves its exception in {@code handlerFailure} for the summary to name; any
     * other parent-side throw ends it with {@code -1} and its stack in the worker's output.
     */
    private WorkerExit driveWorker(
            Shard shard, int concurrency, boolean isolate, List<String> args, ConcurrentLinkedDeque<String> queue) {
        int workerId = shard.workerId;
        CaptureBuffer crash = shard.crash;
        ResultAggregator aggregator = shard.aggregator;
        BiConsumer<String, PluginProcess.Conversation> handler =
                pullHandler(queue, aggregator, shard.done, shard.lastClass);
        Consumer<String> passthrough = line -> {
            crash.add(aggregator.userOutput(line));
        };

        try {
            WorkerEnv testEnv = launcher.testEnv();
            Path tmp = TestTmpDir.forWorker(launcher.testTmpDir(), workerId, isolate);
            WorkerEnv env = isolate && tmp != null
                    ? TestWorkerEnv.forWorker(testEnv, workerId, tmp, launcher.workerHomes())
                    : testEnv;
            List<String> flags = launcher.jvmFlags(JvmRole.PULL_WORKER, concurrency, tmp);
            heap.accumulateAndGet(HeapFlags.parseXmx(flags), Math::max);
            shard.command.set(PluginLoader.command(javaHome, classpath, flags, args));
            PluginProcess.Starter starter = launcher.poolStarter();
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
                        TestWorkerEnv.poolIdleTimeoutMs(launcher.idleTimeoutMs()),
                        starter);
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
                                TestWorkerEnv.poolIdleTimeoutMs(launcher.idleTimeoutMs()),
                                starter));
            } catch (IOException | InterruptedException | RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException(e);
            }
        } catch (PluginProcess.HandlerFailure e) {
            shard.handlerFailure.set(e.handler());
            listener.onUserOutput(workerId, Objects.requireNonNull(e.getMessage()));
            return WorkerExit.noWorker(-1);
        } catch (IOException e) {
            String why = Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName());
            if (leaseRefused(e)) {
                shard.unstarted.set(why);
                listener.onUserOutput(workerId, why);
            } else {
                crash.add("reader error: " + why);
                listener.onUserOutput(workerId, "reader error: " + why);
            }
            return WorkerExit.noWorker(-1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return WorkerExit.noWorker(-1);
        } catch (RuntimeException e) {
            StringWriter stack = new StringWriter();
            e.printStackTrace(new PrintWriter(stack, true));
            String text = "test worker driver failed: " + stack;
            crash.add(text);
            listener.onUserOutput(workerId, text);
            return WorkerExit.noWorker(-1);
        }
    }

    /** Whether {@code e} is the starter's word that the worker's lease gave up, so no process ever started. */
    static boolean leaseRefused(IOException e) {
        return e.getCause() instanceof WorkerLeases.LeaseRefused;
    }

    /** One worker JVM of a pool and what its driver thread leaves behind. */
    private static final class Shard {
        final int workerId;
        final ResultAggregator aggregator;
        final CaptureBuffer crash = new CaptureBuffer();
        /** Set once the parent told the worker {@code DONE}: it owes nothing more. */
        final AtomicBoolean done = new AtomicBoolean();

        final AtomicReference<String> lastClass = new AtomicReference<>("");
        /** Why its process never started: the lease it waited for gave up; empty once it started. */
        final AtomicReference<String> unstarted = new AtomicReference<>("");

        final AtomicReference<@Nullable RuntimeException> handlerFailure = new AtomicReference<>();
        final AtomicReference<List<String>> command = new AtomicReference<>(List.of());
        /** A worker whose thread never stored an exit (its body threw) is a death, not a clean 0. */
        volatile WorkerExit exit = WorkerExit.noWorker(-1);

        @Nullable
        Thread thread;

        Shard(int workerId, ResultAggregator aggregator) {
            this.workerId = workerId;
            this.aggregator = aggregator;
        }

        boolean incomplete() {
            return exit.code() != 0 || !done.get();
        }
    }
}
