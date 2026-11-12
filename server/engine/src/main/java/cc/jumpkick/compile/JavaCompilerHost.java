// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.config.PluginTuning;
import cc.jumpkick.config.Request;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.plugin.JobWorkers;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.engine.plugin.KeepWarm;
import cc.jumpkick.engine.plugin.MemoryProbe;
import cc.jumpkick.engine.plugin.PluginLoader;
import cc.jumpkick.engine.plugin.PluginProcess;
import cc.jumpkick.engine.plugin.WarmLinger;
import cc.jumpkick.engine.plugin.WorkerAotCache;
import cc.jumpkick.engine.plugin.WorkerContainment;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.engine.plugin.WorkerFate;
import cc.jumpkick.engine.plugin.WorkerLeases;
import cc.jumpkick.engine.plugin.WorkerScope;
import cc.jumpkick.host.Hashing;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * Where a Java compile runs: on the resident {@code jk-java-compiler} worker of its {@link
 * CompilerPool}, alongside every other module's compile, one per worker thread. A plain Java build has
 * one worker per JDK, kept warm between builds for the {@linkplain #lingerMillis linger}; a mixed
 * Scala compile, or any compile while the linger is {@code 0}, goes on a pool of its job's, retired
 * with the job. Compile and {@code jk explain} PLAN share the pools.
 *
 * <p>A compile needs a {@link WorkerScope}; outside one ({@code jk explain}, a forecast,
 * a test) {@link #scoped} binds an ephemeral scope for it, which ends with the call.
 */
public final class JavaCompilerHost {

    private static final AtomicLong EPHEMERAL = new AtomicLong(-1L);

    private JavaCompilerHost() {}

    /**
     * Run {@code body} under a compile scope: the bound request's when it has one (a build), else an
     * ephemeral one ({@code jk explain}, a forecast) whose compiles and workers end with the call.
     */
    public static <T extends @Nullable Object, X extends Throwable> T scoped(Request.Body<T, X> body) throws X {
        if (WorkerScope.current() != null) return body.call();
        WorkerScope scope = new WorkerScope(EPHEMERAL.getAndDecrement());
        try {
            return scope.call(body);
        } finally {
            end(scope.id());
            scope.shutdown(0L);
        }
    }

    /**
     * {@code requestId} has ended: its compiles still queued fail, those in flight are cancelled on
     * their workers, and its job-scoped pools are retired. Every other job's compiles go on.
     */
    public static void end(long requestId) {
        CompilerPool.endJob(requestId);
    }

    static ForkedJavac.Result compile(ForkedJavac.Request req) {
        WorkerScope scope = WorkerScope.current();
        if (scope == null) return scoped(() -> compile(req));
        long id = scope.id();
        CompileWork w = CompileWork.compile(req).forJob(id, SessionContext.current());
        return poolFor(id, req, w).submit(w);
    }

    static ForkedJavac.Plan plan(ForkedJavac.Request req) {
        WorkerScope scope = WorkerScope.current();
        if (scope == null) return scoped(() -> plan(req));
        long id = scope.id();
        CompileWork w = CompileWork.plan(req).forJob(id, SessionContext.current());
        return poolFor(id, req, w).submitPlan(w);
    }

    /**
     * As {@link #plan} when a resident worker that can take {@code req} is already running, else
     * null: a forecast is not worth starting a compiler JVM, which costs seconds on a cold engine.
     */
    static ForkedJavac.@Nullable Plan planIfWarm(ForkedJavac.Request req) {
        if (!resident(req)) return null;
        if (CompilerPool.warm(residentKey(req)) == null) return null;
        return plan(req);
    }

    /** How long a resident worker waits after its last compile before it exits; {@code JK_WORKER_LINGER_MIN} overrides. */
    static long lingerMillis() {
        return WarmLinger.millis();
    }

    /** Whether {@code req} goes on a resident pool rather than one of its job's. */
    static boolean resident(ForkedJavac.Request req) {
        String scala = req.scalaVersion();
        return lingerMillis() > 0
                && (scala == null || scala.isBlank())
                && req.compilerClasspath().isEmpty();
    }

    /** The pool {@code item} goes on, made with a worker that forks the JVM {@code req} needs. */
    private static CompilerPool poolFor(long job, ForkedJavac.Request req, CompileWork item) {
        boolean resident = resident(req);
        String key = resident ? residentKey(req) : jobKey(job, req);
        Path home = ForkedJavac.workerJavaHome(req);
        Long owner = resident ? null : job;
        CompilerPool pool = CompilerPool.forKey(
                key,
                owner,
                () -> {
                    // Composed here, on the request's thread: the worker forks on a thread of its
                    // own, which carries no request.
                    WorkerEnv env =
                            WorkerEnv.strict().with(req.env().withJavaHome(home).compilerEnvironment());
                    PluginTuning tuning = JvmOptions.currentTuning();
                    return new CompilerPool(
                            key,
                            owner,
                            () -> worker -> converse(worker, req, home, env, tuning, owner),
                            ForkedJavac::writeSpec);
                },
                item);
        if (resident) {
            Path classes = req.classOutput().toAbsolutePath().normalize();
            pool.served(KeepWarm.sourceRoot(req.sources(), List.of(classes.getParent(), req.sourceOutput())));
        }
        return pool;
    }

    /**
     * What a resident worker for {@code req} cannot be given once it runs: its JDK, its jar, the
     * whole environment it starts with — a resident worker sees every later request's variables,
     * not one request's fingerprint — and the module's own JVM flags.
     */
    static String residentKey(ForkedJavac.Request req) {
        String env = Hashing.sha256Hex(new TreeMap<>(req.env().environment()).toString());
        return "resident|" + ForkedJavac.workerJavaHome(req) + "|" + req.workerJar() + "|" + env + "|"
                + String.join(" ", req.jvmArgs());
    }

    /** A job-scoped pool's key: the job first, then as {@link #residentKey} plus the compiler classpath. */
    static String jobKey(long job, ForkedJavac.Request req) {
        return job + "|" + ForkedJavac.workerJavaHome(req) + "|" + req.workerJar() + "|"
                + req.env().fingerprint() + "|" + String.join(" ", req.jvmArgs()) + "|" + req.compilerClasspath();
    }

    /** What the lease ledger and the containment logs call the worker. */
    static final String WORKER_NAME = "java compiler worker";

    /**
     * Fork the worker JVM on {@code home} under {@code env} and {@code tuning}, captured from the
     * request that made the pool, and serve it until it exits. Its heap ceiling and cgroup cap come
     * from {@link CompilerBudget}; its lease starts at {@link CompilerWorker#FIRST_LEASE_BYTES} and
     * follows the heap it reports, charged to {@code owner}'s job or, for a resident worker, the
     * engine.
     */
    private static void converse(
            CompilerWorker worker,
            ForkedJavac.Request template,
            Path home,
            WorkerEnv env,
            PluginTuning tuning,
            @Nullable Long owner)
            throws Exception {
        String workerCp = ForkedJavac.workerClasspath(template);
        WorkerContainment.Report containment = WorkerContainment.report();
        boolean contained = containment.mode() == WorkerContainment.Mode.CGROUP && containment.perWorker();
        MemoryProbe.Memory memory = MemoryProbe.current();
        CompilerBudget.Heap heap = CompilerBudget.reserve(
                worker, memory.totalBytes(), WorkerLeases.engine().capacityBytes(), contained, memory.availableBytes());
        List<String> jvmFlags = ForkedJavac.workerJvmFlags(
                WorkerAotCache.flags(
                        "java-compiler",
                        home,
                        workerCp,
                        ForkedJavac.aotKeyFlags(template, tuning),
                        (aotOutput, scratch) ->
                                ForkedJavac.trainerCommand(template, tuning, workerCp, home, aotOutput, scratch)),
                template.jvmArgs(),
                tuning,
                heap.xmxBytes());
        List<String> command = PluginLoader.command(home, workerCp, jvmFlags, List.of("--pull"));
        int exit = PluginProcess.converseOnChannel(
                command,
                env,
                (pb, choice) -> {
                    Process p = JobWorkers.startMeasured(
                            pb,
                            CompilerWorker.FIRST_LEASE_BYTES,
                            heap.capBytes(),
                            owner,
                            WORKER_NAME,
                            true,
                            worker::leased);
                    worker.process(p);
                    return p;
                },
                worker::attach,
                worker::onFrame,
                worker::output);
        // A worker told to finish exits on its own; a kill we asked for says nothing about it.
        if (exit != 0 && !(worker.retiring() && worker.inflight.isEmpty())) {
            WorkerFate.Cause cause = WorkerFate.classify(exit, worker.transcript.render());
            if (cause == WorkerFate.Cause.HEAP_EXHAUSTED) worker.ranOutOfHeap();
            throw new IOException("zinc worker " + WorkerFate.phrase(cause, "exited with status " + exit));
        }
    }

    /** Surface a worker failure to the caller as the cause it actually was, not as a wrapper. */
    static RuntimeException unwrap(Exception e) {
        Throwable c = e.getCause() == null ? e : e.getCause();
        if (c instanceof RuntimeException re) return re;
        if (c instanceof IOException io) return new UncheckedIOException(io);
        return new RuntimeException(c);
    }
}
