// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.config.JkHistoryConfig;
import cc.jumpkick.config.JkHttpConfig;
import cc.jumpkick.engine.api.InFlightBuilds;
import cc.jumpkick.engine.api.WireWriter;
import cc.jumpkick.engine.http.HttpEngineServer;
import cc.jumpkick.engine.http.HttpEvents;
import cc.jumpkick.engine.http.StatusSnapshot;
import cc.jumpkick.engine.jobs.JobEnvelope;
import cc.jumpkick.engine.jobs.JobSessions;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.journal.JournalWriter;
import cc.jumpkick.engine.plugin.PluginAot;
import cc.jumpkick.engine.verbs.VerbRegistry;
import cc.jumpkick.engine.verbs.VerbShape;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.runtime.base.BuildMetrics;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.EngineTransport;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

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
    private final @Nullable JkHttpConfig httpConfig;

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

    /** Connections closed by the reader's idle timer since start (never spoke, or went quiet). */
    private final AtomicLong idleDropped = new AtomicLong();

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
    private final @Nullable HttpEvents httpEvents;

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

    private @Nullable ServerSocketChannel serverChannel;
    private @Nullable ExecutorService connectionExecutor;

    /** Non-null only on the loopback-TCP transport (Windows) — see {@link EngineTransport}. */
    private @Nullable String expectedToken;

    /** Serves accepted sockets; built once the election has settled the token. */
    private @Nullable EngineConnection connection;

    /** Decides, once a second, whether this engine is primary, displaced or orphaned. */
    private final DisplacementWatchdog watchdog;

    /**
     * Quiet background revalidation of {@code store/libs.global.toml} and {@code store/jdks.json}
     * (every 12 h). Started only after winning the resident-engine election — never in {@code --job}
     * mode.
     */
    private @Nullable StoreFeedRefresh storeFeedRefresh;

    /** One-minute chore loop (config mtime + wall-clock 12 h maintenance). */
    private @Nullable EngineMaintenance engineMaintenance;

    public EngineServer(
            EnginePaths.Paths paths, JkEngineConfig config, String version, @Nullable Consumer<String> log) {
        this(paths, config, null, version, BuildIdentity.buildId(), log);
    }

    /** As above plus the optional {@code [http]} table ({@code null} = feature off). */
    public EngineServer(
            EnginePaths.Paths paths,
            JkEngineConfig config,
            @Nullable JkHttpConfig httpConfig,
            String version,
            @Nullable Consumer<String> log) {
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
            @Nullable JkHttpConfig httpConfig,
            String version,
            String buildId,
            @Nullable Consumer<String> log) {
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
        this.drain = newDrainReporter();
        this.watchdog = newDisplacementWatchdog();
        this.idle = newIdleHousekeeping();
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
        this.jobs = newJobEnvelope();
        this.verbs = newVerbRegistry();
        this.http = newHttpFront();
        this.vitals = newVitals();
    }

    // ---- constructor wiring, in the order the constructor assigns them ---------------------

    private DrainReporter newDrainReporter() {
        return new DrainReporter(
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
    }

    private DisplacementWatchdog newDisplacementWatchdog() {
        return new DisplacementWatchdog(
                election,
                activeBuildPlans::get,
                this::liveEventStreams,
                () -> shuttingDown,
                this::yieldListeners,
                () -> {
                    aot.stopQuietly();
                    close();
                },
                this.log);
    }

    private IdleHousekeeping newIdleHousekeeping() {
        return new IdleHousekeeping(
                activeBuildPlans,
                cacheGate,
                historyConfig,
                journal,
                () -> metricsFile,
                paths.dir(),
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
    }

    private JobEnvelope newJobEnvelope() {
        return new JobEnvelope(
                new EngineEnvelopeHost(
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
                        journal),
                config.jobLimits());
    }

    private VerbRegistry newVerbRegistry() {
        return VerbRegistry.standard(new EngineVerbBridge(
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
    }

    private EngineHttpFront newHttpFront() {
        return new EngineHttpFront(
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
                jobs::cancelJob,
                jobs::cancelJobsForDir,
                cacheGate);
    }

    private EngineVitals newVitals() {
        return new EngineVitals(
                this.version,
                this.pid,
                this.startedAtMillis,
                this.engineEpoch,
                peakActiveConnections,
                peakActiveBuildPlans,
                activeConnections,
                activeBuildPlans,
                this::httpServer,
                aot::pid,
                idleDropped::get);
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
        connection = new EngineConnection(new EngineConnection.Context(
                verbs,
                jobs,
                version,
                pid,
                startedAtMillis,
                buildId,
                expectedToken,
                this::statusSnapshot,
                http,
                () -> draining,
                drain,
                this::handleShutdown,
                this::noteIdleDropped));

        connectionExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("jk-engine-conn-", 0).factory());
        EngineStartup.Started started =
                new EngineStartup(version, pid, election, aot, http, journal, idle, log).run(won);
        storeFeedRefresh = started.feeds();
        engineMaintenance = started.maintenance();
        watchdog.start();
        acceptLoop();
        awaitDrainComplete();
        cleanup();
        log.accept("jk engine: stopped");
        return true;
    }

    private void acceptLoop() {
        ServerSocketChannel listener = Objects.requireNonNull(serverChannel, "serverChannel");
        ExecutorService executor = Objects.requireNonNull(connectionExecutor, "connectionExecutor");
        EngineConnection conn = Objects.requireNonNull(connection, "connection");
        while (!shuttingDown) {
            SocketChannel ch;
            try {
                ch = listener.accept();
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
            executor.execute(() -> {
                try {
                    conn.serve(ch);
                } finally {
                    onConnectionFinished();
                }
            });
        }
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

    private void noteIdleDropped() {
        idleDropped.incrementAndGet();
        log.accept("jk engine: closed an idle connection (no request within the idle bound)");
    }

    private @Nullable HttpEngineServer httpServer() {
        return http.server();
    }

    private StatusSnapshot statusSnapshot() {
        return vitals.snapshot();
    }

    /** Dashboard and MCP streams attached right now; zero when HTTP is off. */
    private int liveEventStreams() {
        return http.liveEventStreams();
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

    /** Test seam: one watchdog decision on the calling thread. */
    boolean displacementTick() throws IOException {
        return watchdog.tick();
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

    private static void closeQuietly(SocketChannel ch) {
        try {
            ch.close();
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
