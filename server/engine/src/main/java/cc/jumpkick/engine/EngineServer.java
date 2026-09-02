// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.config.JkHistoryConfig;
import cc.jumpkick.config.JkHttpConfig;
import cc.jumpkick.config.Jobs;
import cc.jumpkick.config.Session;
import cc.jumpkick.engine.http.HttpEngineServer;
import cc.jumpkick.engine.http.HttpEvents;
import cc.jumpkick.engine.http.StatusSnapshot;
import cc.jumpkick.engine.jobs.JobEnvelope;
import cc.jumpkick.engine.jobs.JobSessions;
import cc.jumpkick.engine.jobs.JobTransport;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.journal.JournalWriter;
import cc.jumpkick.engine.plugin.HeapPlan;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.engine.plugin.PluginAot;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoLifecycle;
import cc.jumpkick.engine.verbs.HostedVerb;
import cc.jumpkick.engine.verbs.VerbRegistry;
import cc.jumpkick.engine.verbs.VerbShape;
import cc.jumpkick.jsonl.BoundedLineReader;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.layout.InputTrees;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.runtime.BuildMetrics;
import cc.jumpkick.util.JkDirs;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Resident engine: election, accept loop, drain/close, and four-arm {@link VerbShape} dispatch.
 * Verbs, journal, SSE, HTTP jobs, and listeners are collaborators — this class is the
 * composition root.
 *
 * <p><b>What deliberately stays here, and is not a peel waiting to happen.</b> {@link
 * #lifecycleLock} covers the drain decision, the accept gate and the plan-slot claim as one
 * lifecycle, and {@link #tryStartBuildPlan} <em>is</em> that invariant rather than a caller of it:
 * "not draining" and "one more plan is running" have to become true inside the same critical
 * section the deciders read — the shutdown message, the displacement watchdog, and the orphan
 * check all settle under this lock. Extracting the claim, {@link #yieldListeners}, {@link
 * #awaitDrainComplete} or the shutdown arm was proposed and withdrawn: every version of it hands
 * {@code lifecycleLock} to a second object, which spreads one invariant across two files instead
 * of making the race unrepresentable. Their length here is the cost of holding that invariant in
 * one place, and it was paid on purpose.
 *
 * <p>What did leave, and why it could: {@link EngineElection} owns the engine's identity on disk —
 * mutex, incumbent probe, generation, listener, pid file, endpoint — which is settled before the
 * accept loop starts and only read afterwards. {@link DrainReporter} owns the {@code drain-status}
 * channel — it reports the decision this class makes and makes none of its own. Neither touches
 * {@code lifecycleLock}.
 */
public final class EngineServer implements AutoCloseable {

    private final EnginePaths.Paths paths;
    private final JkEngineConfig config;

    /**
     * The {@code [http]} table when present, else {@code null} — the embedded HTTP server's enable
     * switch. The dashboard it serves is why the engine stays resident until an explicit stop.
     */
    private final JkHttpConfig httpConfig;

    private final String version;
    /** Content identity for -SNAPSHOT builds (see BuildIdentity); "" = version rule only. */
    private final String buildId;

    private final Consumer<String> log;
    private final LongSupplier clockMillis;
    private final long pid;
    private final long startedAtMillis;
    /** Process generation id ({@code version[+buildId]@startedAt}) for web UI hard-refresh. */
    private final String engineEpoch;

    private final Object lifecycleLock = new Object();
    private final AtomicInteger activeConnections = new AtomicInteger();
    /** High-water marks for concurrent load (UDS + SSE combined). */
    private final AtomicInteger peakActiveConnections = new AtomicInteger();

    /** How often the displacement watchdog re-reads the endpoint / pid file. */
    private static final long DISPLACEMENT_TICK_MS = 1_000;

    /**
     * Sidecar AOT trainer spawner/process. Spawned only after winning election; reaped on exit.
     * Clients never talk to it.
     */
    private final AotTrainer aot;

    private final IdleHousekeeping idle;
    private final EngineVitals vitals;
    private final SsePublisher sse;
    private final LiveRuns liveRuns;
    private final JournalWriter journalWriter;
    private final EngineListeners listeners;
    private final EngineHttpFront http;
    private final AtomicInteger activeBuildPlans = new AtomicInteger();
    private final AtomicInteger peakActiveBuildPlans = new AtomicInteger();

    private void noteConnectionOpened() {
        activeConnections.incrementAndGet();
        // Combined high-water mark (UDS + SSE surfaces) — same metric the SSE admission hook and
        // statusSnapshot() bump, so the reported peak means one thing.
        peakActiveConnections.accumulateAndGet(liveConnectionCount(), Math::max);
    }

    private void noteBuildPlanStarted() {
        int n = activeBuildPlans.incrementAndGet();
        peakActiveBuildPlans.accumulateAndGet(n, Math::max);
    }

    /**
     * Atomically decide "not shutting down" <em>and</em> join the active-plan count, under
     * {@link #lifecycleLock}.
     *
     * <p>Checking {@code draining} and incrementing separately is a real race: displacement and
     * {@code jk engine stop} both decide under this lock, so a job that passed the check but had
     * not yet incremented is invisible to them — they see zero plans, set {@code shuttingDown},
     * close the listener, and the JVM exits mid-build. Claiming the slot inside the same lock the
     * deciders use closes that window.
     *
     * @return false when the engine is draining or already shutting down (caller must refuse)
     */
    private boolean tryStartBuildPlan() {
        synchronized (lifecycleLock) {
            if (draining || shuttingDown) return false;
            noteBuildPlanStarted();
            return true;
        }
    }

    /**
     * Return a slot claimed by {@link #tryStartBuildPlan} when the job never actually ran (admission
     * rejected). Deliberately not {@link #noteBuildPlanFinished}: no work happened, so this must not
     * trigger the idle-housekeeping that a real plan completion does.
     */
    private void abandonBuildPlanSlot() {
        activeBuildPlans.decrementAndGet();
    }

    /** Release a plan slot after work finished — call <em>before</em> publishing {@code request-finish}. */
    private void noteBuildPlanFinished() {
        activeBuildPlans.decrementAndGet();
    }

    /** Dashboard SSE fan-out; non-null only when {@link #httpConfig} is set. */
    private final HttpEvents httpEvents;

    /** Ids for {@code request-start}/{@code request-finish} events and {@code POST /api/build} acks. */
    private final AtomicLong requestIds = new AtomicLong();

    /**
     * One row per request: progress, accumulator, emit throttle. Retired ids cannot
     * {@code computeIfAbsent} a zombie.
     */
    private final JobSessions sessions = new JobSessions(requestIds::get);

    private final JobEnvelope jobs;
    private final VerbRegistry verbs;

    private final JkHistoryConfig historyConfig = JkHistoryConfig.resolve();

    private final BuildJournal journal = BuildJournal.current();

    /** The running invocation/step aggregates every finished build/test folds into. */
    private Path metricsFile = BuildMetrics.defaultFile();

    /** Exclusive same-fingerprint slots + in-flight holds. */
    private final InFlightBuilds inFlightBuilds = new InFlightBuilds();

    /** Test seam: point the metrics store at a sandbox file instead of the user's real state dir. */
    void metricsFileForTests(Path file) {
        this.metricsFile = file;
    }

    /** Event-request id for the hosted op on this thread (set around the runner). */
    private final ThreadLocal<Long> currentEventRequestId = new ThreadLocal<>();

    /**
     * Fair RW lock: plans hold read for their run; cache maintenance holds write so sweeps
     * never delete under an in-flight plan. Cross-process safety still uses on-disk {@code
     * .prune.lock}.
     */
    private final ReentrantReadWriteLock cacheGate = new ReentrantReadWriteLock(true);

    private volatile boolean shuttingDown;
    // Graceful-drain: listeners (UDS / TCP / HTTP) are closed so the successor can bind; in-flight
    // connections keep running. New jobs are refused. Exit once every plan slot is released.
    private volatile boolean draining;

    /** Identity on disk: mutex, incumbent probe, generation, pid file, endpoint pointer. */
    private final EngineElection election;

    /** The {@code drain-status} channel to a successor, and the record of our own predecessors. */
    private final DrainReporter drain;

    private ServerSocketChannel serverChannel;
    private ExecutorService connectionExecutor;

    /** Non-null only on the loopback-TCP transport (Windows) — see {@link EngineTransport}. */
    private String expectedToken;

    /**
     * Quiet background revalidation of {@code store/libs.global.toml} and {@code store/jdks.json}
     * (every 12 h). Started only after winning the resident-engine election — never in {@code --job}
     * mode.
     */
    private StoreFeedRefresh storeFeedRefresh;

    /** One-minute chore loop (config mtime + wall-clock 12 h maintenance). */
    private EngineMaintenance engineMaintenance;

    public EngineServer(EnginePaths.Paths paths, JkEngineConfig config, String version, Consumer<String> log) {
        this(paths, config, null, version, BuildIdentity.buildId(), log);
    }

    /** As above plus the optional {@code [http]} table ({@code null} = feature off). */
    public EngineServer(
            EnginePaths.Paths paths,
            JkEngineConfig config,
            JkHttpConfig httpConfig,
            String version,
            Consumer<String> log) {
        this(paths, config, httpConfig, version, BuildIdentity.buildId(), log);
    }

    /**
     * Canonical: {@code buildId} is the engine's content identity ({@code BuildIdentity}
     * derived from its own jar; injectable for election tests). Empty means "no opinion": the
     * same-version election then falls back to the version-string rule, exactly the release
     * behavior. For -SNAPSHOT dev builds it distinguishes a REBUILT engine from a stale one.
     */
    public EngineServer(
            EnginePaths.Paths paths,
            JkEngineConfig config,
            JkHttpConfig httpConfig,
            String version,
            String buildId,
            Consumer<String> log) {
        this.paths = paths;
        this.config = config;
        this.httpConfig = httpConfig;
        this.httpEvents = httpConfig != null ? new HttpEvents() : null;
        this.version = version;
        this.buildId = buildId == null ? "" : buildId;
        this.log = log != null ? log : s -> {};
        this.clockMillis = System::currentTimeMillis;
        this.pid = ProcessHandle.current().pid();
        this.startedAtMillis = clockMillis.getAsLong();
        // Process-scoped generation id for the dashboard hard-refresh contract.
        String bid = this.buildId.isEmpty() ? "" : "+" + this.buildId;
        this.engineEpoch = version + bid + "@" + this.startedAtMillis;
        this.aot = new AotTrainer(this.log);
        this.election = new EngineElection(paths, this.version, this.buildId, this.pid, this.startedAtMillis, this.log);
        this.drain = new DrainReporter(
                this.pid,
                this.version,
                () -> EnginePaths.activeSocket(paths),
                // Reads the lifecycle decision; never takes the lock itself (see DrainReporter).
                () -> {
                    synchronized (lifecycleLock) {
                        return draining && !shuttingDown;
                    }
                },
                activeBuildPlans::get,
                DrainReporter.sockets(),
                this.log,
                DrainReporter.TICK_MS);
        this.idle = new IdleHousekeeping(
                activeBuildPlans,
                cacheGate,
                historyConfig,
                journal,
                () -> metricsFile,
                clockMillis,
                this.log,
                () -> shuttingDown,
                () -> draining,
                () -> {
                    synchronized (lifecycleLock) {
                        shuttingDown = true;
                        closeServerChannelQuietly();
                        lifecycleLock.notifyAll();
                    }
                });
        this.journalWriter = new JournalWriter(
                sessions, journal, historyConfig, () -> metricsFile, clockMillis, this.version, this.log);
        this.sse = new SsePublisher(
                sessions,
                inFlightBuilds,
                httpEvents,
                this::httpServer,
                clockMillis,
                activeBuildPlans,
                sseConnect,
                journalWriter::accStepStart);
        this.liveRuns = new LiveRuns(inFlightBuilds, sessions, httpEvents, sseConnect, clockMillis);
        this.listeners = new EngineListeners(sessions, sse, journalWriter, inFlightBuilds, this::eventRequestId);
        this.jobs = new JobEnvelope(new EngineEnvelopeHost(
                this::tryStartBuildPlan,
                this::abandonBuildPlanSlot,
                this::noteBuildPlanFinished,
                () -> draining,
                requestIds,
                clockMillis,
                sessions,
                sse,
                journalWriter,
                cacheGate,
                currentEventRequestId,
                inFlightBuilds,
                activeBuildPlans,
                idle,
                this.log,
                this.version,
                historyConfig,
                journal));
        this.verbs = VerbRegistry.standard(new EngineVerbBridge(
                this::eventRequestId,
                sessions,
                listeners,
                jobs,
                journalWriter,
                sse,
                idle,
                cacheGate,
                activeBuildPlans,
                clockMillis,
                journal,
                historyConfig,
                () -> metricsFile,
                inFlightBuilds));
        this.http = new EngineHttpFront(
                httpConfig,
                paths,
                this.version,
                httpEvents,
                journal,
                () -> metricsFile,
                this.log,
                this::statusSnapshot,
                liveRuns,
                peakActiveConnections,
                this::liveConnectionCount,
                jobs,
                verbs,
                this::cancelJob,
                this::cancelJobsForDir,
                cacheGate);
        this.vitals = new EngineVitals(
                this.version,
                this.pid,
                this.startedAtMillis,
                this.engineEpoch,
                peakActiveConnections,
                peakActiveBuildPlans,
                activeConnections,
                activeBuildPlans,
                this::httpServer,
                aot::pid);
    }

    /**
     * Try to become the engine and serve until shutdown. Returns {@code false} immediately, having
     * touched nothing but the lock file, if another engine already holds {@link
     * EnginePaths.Paths#lock} — the caller (a losing spawn-race participant) should treat that as
     * success-by-proxy, not an error. Blocks until the server stops (an explicit {@link
     * EngineProtocol#SHUTDOWN} — {@code jk engine stop} — or {@link #close}), then returns
     * {@code true}; there is no idle countdown — the engine stays resident until told to stop.
     */
    public boolean run() throws IOException {
        EngineElection.Won won = election.win();
        if (won == null) return false; // lost the spawn race / an identical engine already serves
        serverChannel = won.listener();
        expectedToken = won.token();

        connectionExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("jk-engine-conn-", 0).factory());
        planSharedWorkerMemoryOnce();

        log.accept("jk engine: listening on " + won.active().socket() + " (pid " + pid + ")");

        // Order matters: tell the predecessor to drain FIRST — that is what
        // makes it suppress training and kill its trainer sidecar. Wiping before that signal
        // leaves a window in which its in-flight trainer can atomically rename a fresh cache into
        // the directory we just swept, which is exactly the refill this was meant to prevent.
        // Our own trainer starts last, after the sweep, so it never sweeps its own output.
        election.askPredecessorToYield(won.displaced());
        // Drop other product versions' AOT (engine + workers); keep ours (named *-<version>-*).
        try {
            int wiped = EngineInstall.wipeAotDirectory(JkDirs.state().resolve("aot"), version);
            if (wiped > 0) {
                log.accept("jk engine: retired " + wiped + " AOT cache(s) from other versions");
            }
        } catch (RuntimeException ignored) {
            // best-effort
        }
        try {
            var gc = EngineInstall.current().gc();
            if (!gc.isEmpty()) {
                log.accept("jk engine: removed " + gc.size() + " displaced install file(s)");
            }
        } catch (RuntimeException ignored) {
            // a predecessor may still have the previous jar mapped — retry on the next cycle
        }
        aot.startIfConfigured();
        // HTTP binds only after the predecessor has yielded (askPredecessorToYield waits for bye,
        // which is sent after HTTP/UDS unbind). Binding earlier lost the handoff race and stuck
        // "Address already in use" in `jk engine status` for the engine's life.
        http.start();
        // leftover running=true journal rows from a killed engine cannot still be live.
        int abandoned = journal.abandonStaleRunning(version);
        if (abandoned > 0) {
            log.accept("jk engine: abandoned " + abandoned + " stale in-flight journal entries");
        }
        // Store feeds are revalidated by HostWarmup / EngineMaintenance (not a 12 h process sleep).
        storeFeedRefresh = new StoreFeedRefresh(log, null);
        // 1-minute loop: config.toml mtime reload + wall-clock 12 h maintenance (feeds, templates,
        // cache prune, AOT/cal). Laptop suspend-safe — due work runs on the next minute tick after resume.
        engineMaintenance = new EngineMaintenance(log, storeFeedRefresh, idle::enqueueScheduledCachePrune);
        engineMaintenance.start();
        // First-start self-heal: feeds → templates → AOT/cal on the idle worker (does not block accept).
        idle.scheduleHostWarmup(false);
        // Touch resolve/PubGrub classes so the first real lock does not pay classload on the critical path.
        idle.scheduleResolveClassWarmup();
        startDisplacementWatchdog();
        acceptLoop();
        awaitDrainComplete();
        cleanup();
        log.accept("jk engine: stopped");
        return true;
    }

    /**
     * Lifecycle watchdog over the endpoint pointer <em>and</em> the pid file. The filename alone is
     * not identity: deleting and recreating the state dir leaves a ghost engine whose generation
     * name matches the successor's pointer, so it would never drain. The pid file and a hello of
     * the path catch that.
     *
     * <ul>
     * <li><b>Pointer, pid file, or live hello names someone else</b> — displaced. Yield UDS/TCP and
     * HTTP immediately so the successor can bind them, drain in-flight jobs, report status to the
     * successor, exit when idle. Attached dashboard streams get no vote: the successor needs the
     * port, and a tab reconnects to it.
     * <li><b>The pointer is absent</b> — orphaned. Exit once genuinely unused — no jobs and no
     * attached streams. Keep HTTP while a browser is attached, because here there is no successor
     * to hand it to.
     * <li><b>The pointer names this engine and the pid file matches</b> — primary. Never
     * self-terminates.
     * </ul>
     */
    private void startDisplacementWatchdog() {
        Thread t = new Thread(
                () -> {
                    while (!shuttingDown) {
                        try {
                            Thread.sleep(DISPLACEMENT_TICK_MS);
                        } catch (InterruptedException e) {
                            return;
                        }
                        try {
                            if (displacementTick()) return;
                        } catch (IOException ignored) {
                            // transient read failure — check again next tick
                        }
                    }
                },
                "jk-engine-displacement-watchdog");
        t.setDaemon(true);
        t.start();
    }

    /**
     * One watchdog check: displaced → yield listeners and drain; orphaned and unused → exit.
     * Returns {@code true} when the watchdog's work is done. Package-private so a test can drive a
     * tick on the calling thread.
     */
    boolean displacementTick() throws IOException {
        if (election.displacedBySuccessor()) {
            log.accept("jk engine: displaced by a newer engine — yielding listeners and draining");
            yieldListeners(activeBuildPlans.get() == 0);
            return true;
        }
        if (election.endpointMissing() && orphanedAndUnused()) {
            log.accept("jk engine: no endpoint names this engine and it is unused — exiting");
            aot.stopQuietly();
            synchronized (lifecycleLock) {
                shuttingDown = true;
                closeServerChannelQuietly();
                lifecycleLock.notifyAll();
            }
            return true;
        }
        return false;
    }

    private void acceptLoop() {
        while (!shuttingDown) {
            SocketChannel ch;
            try {
                ch = serverChannel.accept();
            } catch (ClosedChannelException e) {
                break; // close / drain-complete / shutdown message closed the listener
            } catch (IOException e) {
                if (shuttingDown) break;
                log.accept("jk engine: accept failed: " + e.getMessage());
                continue;
            }
            synchronized (lifecycleLock) {
                if (shuttingDown || draining) {
                    closeQuietly(ch);
                    continue;
                }
                noteConnectionOpened();
            }
            connectionExecutor.execute(() -> handleConnection(ch));
        }
    }

    /** Loopback-TCP transport only: the connection's first line must be a matching {@link EngineProtocol#AUTH}. */
    private boolean authenticate(BufferedReader reader) throws IOException {
        String line = reader.readLine();
        if (line == null || !EngineProtocol.AUTH.equals(EngineProtocol.typeOf(line))) return false;
        String presented = Jsonl.str(line, "token");
        if (presented == null) return false;
        return MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
    }

    private void handleConnection(SocketChannel ch) {
        try (ch;
                BufferedReader reader = new BoundedLineReader(
                        new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8));
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8))) {
            if (expectedToken != null && !authenticate(reader)) {
                // Typed refusal (then close): a silent close is indistinguishable from a crash.
                WireWriter.sendQuiet(writer, ProtoLifecycle.error(EngineProtocol.ERR_AUTH, "engine token rejected"));
                return;
            }
            serveConnection(reader, writer, ch);
        } catch (IOException ignored) {
            // client disconnected / socket error mid-exchange — nothing to do
        } finally {
            onConnectionFinished();
        }
    }

    private void serveConnection(BufferedReader reader, BufferedWriter writer, SocketChannel ch) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            String type = EngineProtocol.typeOf(line);
            if (type == null) {
                // A garbled REQUEST gets a typed refusal, never silence — a silently-dropped
                // request wedges a streaming client that is waiting for a terminal event.
                WireWriter.sendQuiet(
                        writer,
                        ProtoLifecycle.error(
                                EngineProtocol.ERR_PROTOCOL, "unparseable request line (no \"type\" discriminator)"));
                continue;
            }
            // Lock floor: a jk older than the lock's jk-min refuses with the upgrade error —
            // newer always wins, and nothing runs an older engine to satisfy a lock.
            if (LockFloor.GUARDED.contains(type)) {
                String dir = Jsonl.str(line, "dir");
                String floor = dir == null ? null : LockFloor.requiredNewer(Path.of(dir), version);
                if (floor != null) {
                    WireWriter.send(
                            writer,
                            ProtoLifecycle.error(EngineProtocol.ERR_VERSION_SKEW, LockFloor.message(floor, version)));
                    return;
                }
            }
            HostedVerb verb = verbs.find(type);
            if (verb != null) {
                if (dispatchVerb(verb, line, reader, writer, ch)) return;
                continue;
            }
            switch (type) {
                case EngineProtocol.HELLO -> {
                    int clientProto = Jsonl.intValue(line, "proto", EngineProtocol.PROTOCOL);
                    if (clientProto > EngineProtocol.PROTOCOL) {
                        // A newer-protocol client: this engine must not serve wire semantics
                        // it postdates — the client reacts by taking over (spawn + drain).
                        WireWriter.send(
                                writer,
                                ProtoLifecycle.error(
                                        EngineProtocol.ERR_VERSION_SKEW,
                                        "client speaks protocol " + clientProto + " but this engine speaks "
                                                + EngineProtocol.PROTOCOL + " — start a matching engine"));
                        return;
                    }
                    WireWriter.send(writer, ProtoLifecycle.helloAck(version, pid, startedAtMillis, draining, buildId));
                }
                case EngineProtocol.PING -> WireWriter.send(writer, ProtoLifecycle.pong());
                case EngineProtocol.STATUS -> {
                    StatusSnapshot s = statusSnapshot();
                    HttpEngineServer hs = http.server();
                    String ack = ProtoLifecycle.statusAck(
                            s.version(),
                            s.pid(),
                            s.startedAtMillis(),
                            s.activeRequests(),
                            s.activeBuildPlans(),
                            draining,
                            s.heapUsedBytes(),
                            s.heapCommittedBytes(),
                            s.heapMaxBytes(),
                            s.rssBytes(),
                            s.aotTrainingPid(),
                            hs != null ? hs.url() : null,
                            http.error(),
                            hs != null && hs.mcpEnabled(),
                            s.peakActiveRequests(),
                            s.peakActiveBuildPlans());
                    WireWriter.send(writer, InputTrees.appendToStatusAck(ack));
                }
                case EngineProtocol.SHUTDOWN -> {
                    handleShutdown(line, writer);
                    return;
                }
                case EngineProtocol.DRAIN_STATUS ->
                    drain.predecessorDraining(Jsonl.longValue(line, "pid", -1), Jsonl.intValue(line, "plans", 0));
                case EngineProtocol.DRAIN_DONE -> drain.predecessorFinished(Jsonl.longValue(line, "pid", -1));
                case EngineProtocol.CANCEL_REQUEST -> handleCancelRequest(line, writer);
                default ->
                    WireWriter.sendQuiet(
                            writer, ProtoLifecycle.error(EngineProtocol.ERR_PROTOCOL, "unknown request type: " + type));
            }
        }
    }

    /**
     * Registry dispatch. {@code true} means the verb owns the rest of this connection
     * (async plan / cache maint).
     */
    private boolean dispatchVerb(
            HostedVerb verb, String line, BufferedReader reader, BufferedWriter writer, SocketChannel ch)
            throws IOException {
        return switch (verb.shape()) {
            case VerbShape.AsyncPlan() -> {
                jobs.submit(line, verb.toJobRequest(line), new JobTransport.SocketWatch(reader, writer, ch));
                yield true;
            }
            case VerbShape.CacheMaint() -> {
                jobs.submit(line, verb.toJobRequest(line), new JobTransport.SocketWatch(reader, writer, ch));
                yield true;
            }
            case VerbShape.SyncRead() -> {
                verb.run(line, Session.defaults().cancel(), writer);
                yield false;
            }
        };
    }

    private void handleCancelRequest(String requestLine, BufferedWriter writer) throws IOException {
        long jid = Jsonl.longValue(requestLine, "jid", -1);
        String dir = Jsonl.str(requestLine, "dir");
        if (jid >= 0) {
            boolean ok = cancelJob(jid);
            WireWriter.send(writer, ProtoLifecycle.cancelAck(jid, ok, ok ? null : "unknown or already finished jid"));
            return;
        }
        if (dir != null && !dir.isBlank()) {
            int n = jobs.cancelJobsForDir(dir);
            WireWriter.send(
                    writer,
                    ProtoLifecycle.cancelAck(
                            0, n > 0, n > 0 ? ("cancelled " + n + " job(s)") : "no running jobs for dir"));
            return;
        }
        WireWriter.send(writer, ProtoLifecycle.cancelAck(-1, false, "cancel-request requires jid or dir"));
    }

    boolean cancelJob(long jid) {
        return jobs.cancelJob(jid);
    }

    int cancelJobsForDir(String dir) {
        return jobs.cancelJobsForDir(dir);
    }

    /**
     * Orders wire-event publication against dashboard SSE connect hydration.
     * Publishers take the read side around each publish (accumulation happens strictly before,
     * in program order); a connecting dashboard takes the write side around snapshot capture →
     * {@code deliverTo} → {@code attach}. Any publish that completed before the write section
     * accumulated before the snapshot was captured (so its effect is in the snapshot); any
     * publish after it reaches the attached queue. Overlap yields duplicates, which the SPA
     * folds idempotently — gaps, which it cannot heal, are impossible.
     */
    private final ReentrantReadWriteLock sseConnect = new ReentrantReadWriteLock();

    /** The current thread's hosted-request id for dashboard events; {@code -1} outside a request. */
    private long eventRequestId() {
        Long id = currentEventRequestId.get();
        return id != null ? id : -1;
    }

    private void onConnectionFinished() {
        activeConnections.decrementAndGet();
    }

    private HttpEngineServer httpServer() {
        return http.server();
    }

    private StatusSnapshot statusSnapshot() {
        return vitals.snapshot();
    }

    private int liveConnectionCount() {
        return vitals.liveConnectionCount();
    }

    /**
     * Install the sidecar AOT-trainer factory; must be called before {@link #run}. The factory
     * is invoked once, only if this engine wins its election and starts serving; it may return
     * {@code null} (nothing to train after all — e.g. the cache appeared meanwhile).
     */
    public void aotTrainerSpawner(Supplier<Process> spawner) {
        aot.spawner(spawner);
    }

    /** Caller-facing graceful stop — same effect as receiving a {@link EngineProtocol#SHUTDOWN} message. */
    @Override
    public void close() {
        synchronized (lifecycleLock) {
            shuttingDown = true;
            closeServerChannelQuietly();
            lifecycleLock.notifyAll();
        }
    }

    /**
     * A {@link EngineProtocol#SHUTDOWN} message: with plans in flight (and no {@code force}) the
     * engine drains; otherwise it stops now. The bye line reports the plan count the decision saw.
     * Package-private so a test can drive this path without a socket.
     */
    void handleShutdown(String line, BufferedWriter writer) throws IOException {
        boolean force = Jsonl.bool(line, "force", false);
        // Takeover already repointed the endpoint before sending shutdown — kill the
        // engine AOT sidecar so it cannot re-publish engine-<old-v>-*.
        // Voluntary `jk engine stop` still names us; leave train to finish then.
        if (!election.endpointNamesThisEngine()) {
            aot.stopQuietly();
        }
        int n;
        boolean willDrain;
        synchronized (lifecycleLock) {
            n = activeBuildPlans.get();
            willDrain = !force && n > 0;
            if (!willDrain) {
                // Decision and flag settle in one critical section, so a plan about to claim its
                // slot can never slip between "zero plans observed" and "shutting down".
                shuttingDown = true;
                // Yield listeners before bye so a successor waiting on this line can bind.
                closeServerChannelQuietly();
                lifecycleLock.notifyAll();
            }
        }
        if (willDrain) enterDrain();
        else http.stopNow();
        WireWriter.send(writer, ProtoLifecycle.bye(n, willDrain));
    }

    /**
     * The one drain transition. Both entry paths — a SHUTDOWN message with plans in flight and the
     * displacement watchdog — flip {@code draining} here, yield the listeners so a successor can
     * bind, and start the {@link DrainReporter} — started nowhere else. A
     * plan that claims its slot before the flag lands is simply drained too — {@link
     * #awaitDrainComplete} watches the live count, not the count a caller saw.
     */
    private void enterDrain() {
        synchronized (lifecycleLock) {
            draining = true;
            closeServerChannelQuietly();
            lifecycleLock.notifyAll();
        }
        http.stopNow();
        drain.start();
    }

    /**
     * Stop accepting new clients and HTTP so a successor can bind. Existing connections keep
     * running. {@code exitNow} also marks the process as shutting down (idle, or force).
     */
    private void yieldListeners(boolean exitNow) {
        aot.stopQuietly();
        if (!exitNow) {
            enterDrain();
            return;
        }
        synchronized (lifecycleLock) {
            shuttingDown = true;
            closeServerChannelQuietly();
            lifecycleLock.notifyAll();
        }
        http.stopNow();
    }

    /** Test seam: whether the drain reporter has been started (drain entered). */
    boolean drainStartedForTests() {
        return drain.started();
    }

    /** After the listener is closed, wait for in-flight plans before {@link #cleanup}. */
    private void awaitDrainComplete() {
        synchronized (lifecycleLock) {
            while (draining && !shuttingDown && activeBuildPlans.get() > 0) {
                try {
                    lifecycleLock.wait(1_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Test seam: hold a plan slot so drain cannot exit until {@link #releasePlanSlotForTests}. */
    boolean claimPlanSlotForTests() {
        return tryStartBuildPlan();
    }

    /** Test seam: release a slot claimed by {@link #claimPlanSlotForTests}. */
    void releasePlanSlotForTests() {
        noteBuildPlanFinished();
        idle.maybeIdleBoundary();
    }

    private void closeServerChannelQuietly() {
        // Must be called while holding lifecycleLock: unblocks acceptLoop (accept throws
        // ClosedChannelException) without racing a connection being registered concurrently.
        try {
            if (serverChannel != null) serverChannel.close();
        } catch (IOException ignored) {
            // already closing
        }
    }

    /**
     * True when an orphaned engine has nothing left to serve: no in-flight jobs and no attached SSE
     * stream.
     *
     * <p>The stream check is what keeps this from breaking the case that matters — a developer who works
     * through the Web UI, leaves the tab open overnight and comes back to it. A browser cannot spawn an
     * engine the way the CLI can, so exiting under an attached tab would leave them with a dead SPA and no
     * indication that the fix is to run a command.
     */
    private boolean orphanedAndUnused() {
        if (activeBuildPlans.get() != 0) return false;
        return http.liveEventStreams() == 0;
    }

    private void cleanup() {
        if (engineMaintenance != null) {
            engineMaintenance.close();
            engineMaintenance = null;
        }
        if (storeFeedRefresh != null) {
            storeFeedRefresh.close();
            storeFeedRefresh = null;
        }
        http.close();
        try {
            Files.deleteIfExists(paths.http()); // the live bound-URL file — stale once we stop
        } catch (IOException ignored) {
            // best-effort; the next start overwrites it
        }
        // The http token is deliberately NOT deleted: it persists across restarts so an open
        // dashboard tab survives an upgrade/crash respawn. `jk engine rotate-token`
        // is the explicit way to invalidate it.
        if (connectionExecutor != null) connectionExecutor.shutdown();
        try {
            if (connectionExecutor != null) connectionExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // A trainer is this engine's child and must not outlive it or keep store jars open.
        List<Long> orphans = PluginAot.quiesceTrainers(TRAINER_SHUTDOWN_MILLIS);
        if (!orphans.isEmpty()) {
            log.accept("jk engine: stopped " + orphans.size() + " AOT trainer(s) on shutdown (pid " + orphans + ")");
        }
        election.retire();
    }

    /** Grace for a trainer to die with its engine; short — the engine is already on its way out. */
    private static final long TRAINER_SHUTDOWN_MILLIS = 5_000;

    /**
     * Size the shared worker-JVM memory plan once for the process (core-count concurrency). Hosted
     * builds pass {@code applyMemoryPlan=false} so concurrent requests do not overwrite it.
     */
    private void planSharedWorkerMemoryOnce() {
        int cap = Jobs.resolve(JkEngineConfig.resolve());
        JvmOptions.planAndApply(HeapPlan.requestedJvms(cap, 1, false, cap));
    }

    private static void closeQuietly(SocketChannel ch) {
        try {
            ch.close();
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
