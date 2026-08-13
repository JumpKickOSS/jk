// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.config.JkHistoryConfig;
import cc.jumpkick.config.JkHttpConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.http.HttpEngineServer;
import cc.jumpkick.engine.http.JsonOut;
import cc.jumpkick.engine.jobs.JobEnvelope;
import cc.jumpkick.engine.jobs.JobRequest;
import cc.jumpkick.engine.jobs.JobSession;
import cc.jumpkick.engine.jobs.JobSessions;
import cc.jumpkick.engine.jobs.JobTransport;
import cc.jumpkick.engine.journal.BuildAccumulator;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.engine.listen.BridgingPlanListener;
import cc.jumpkick.engine.listen.BridgingWorkspaceListener;
import cc.jumpkick.engine.listen.EventRedaction;
import cc.jumpkick.engine.listen.EventSink;
import cc.jumpkick.engine.listen.NoopEventSink;
import cc.jumpkick.engine.listen.WireEventSink;
import cc.jumpkick.engine.plugin.HeapPlan;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.verbs.HostedVerb;
import cc.jumpkick.engine.verbs.VerbHost;
import cc.jumpkick.engine.verbs.VerbRegistry;
import cc.jumpkick.engine.verbs.VerbShape;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.protocol.Jsonl;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.BuildPlanView;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.runtime.BuildMetrics;
import cc.jumpkick.runtime.BuildService;
import cc.jumpkick.runtime.CacheBenefit;
import cc.jumpkick.runtime.ChromeTimeline;
import cc.jumpkick.runtime.ModuleOutcome;
import cc.jumpkick.runtime.WorkspaceBuildListener;
import cc.jumpkick.runtime.WorkspaceRequest;
import cc.jumpkick.runtime.WorkspaceResult;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Resident engine server: single-instance election, socket accept loop, and hosted operations
 * (workspace/single builds, tests, explain) each on their own connection/{@link Session} with
 * plan events streamed over the wire. Runs until explicit stop or drain.
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
    /** High-water marks for concurrent load instrumentation (ticket-1015). */
    private final AtomicInteger peakActiveConnections = new AtomicInteger();

    /**
     * Sidecar AOT trainer spawner/process. Spawned only after winning election; reaped on exit.
     * Clients never talk to it.
     */
    private final AotTrainer aot;

    private final EngineVitals vitals;
    private final AtomicInteger activeBuildPlans = new AtomicInteger();
    private final AtomicInteger peakActiveBuildPlans = new AtomicInteger();

    private void noteConnectionOpened() {
        activeConnections.incrementAndGet();
        // Combined high-water mark (UDS + SSE surfaces) — same metric the SSE admission hook and
        // statusSnapshot() bump, so the reported peak means one thing (JK-1861).
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
     * deciders use closes that window (JK-1470).
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
    private final cc.jumpkick.engine.http.HttpEvents httpEvents;

    /** Ids for {@code request-start}/{@code request-finish} events and {@code POST /api/build} acks. */
    private final java.util.concurrent.atomic.AtomicLong requestIds = new java.util.concurrent.atomic.AtomicLong();

    /**
     * One row per request: progress, accumulator, emit throttle. Retired ids cannot
     * {@code computeIfAbsent} a zombie (JK-1474).
     */
    private final JobSessions sessions = new JobSessions(requestIds::get);

    private final JobEnvelope jobs = new JobEnvelope(new EnvelopeHost());

    private final VerbRegistry verbs = VerbRegistry.standard(new VerbBridge());

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

    /** Test seam: inspect exclusive holds. */
    InFlightBuilds inFlightBuildsForTests() {
        return inFlightBuilds;
    }

    /** Event-request id for the hosted op on this thread (set around the runner). */
    private final ThreadLocal<Long> currentEventRequestId = new ThreadLocal<>();

    /**
     * Fair RW lock: plans hold read for their run; cache maintenance holds write so sweeps
     * never delete under an in-flight plan. Cross-process safety still uses on-disk {@code
     * .prune.lock}.
     */
    private final java.util.concurrent.locks.ReentrantReadWriteLock cacheGate =
            new java.util.concurrent.locks.ReentrantReadWriteLock(true);

    /**
     * The cache root a deferred opportunistic prune should run against, or {@code null} when none
     * is queued. Set by a successful build/sync when the auto-prune cadence is due, or by the
     * 12‑hour {@link StoreFeedRefresh} tick (CI-friendly night-time GC). Never double-queued —
     * {@code compareAndSet(null, …)} only. Drained at the idle boundary, or immediately when the
     * scheduler finds the engine already idle.
     */
    private final java.util.concurrent.atomic.AtomicReference<Path> pendingPruneCache =
            new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * Queued host warmup (worker AOT + calibration) for the idle boundary. {@code null} = none;
     * otherwise force-AOT flag. Self-heal on first start and every 12 h feed/GC tick.
     */
    private final java.util.concurrent.atomic.AtomicReference<Boolean> pendingWarmupForce =
            new java.util.concurrent.atomic.AtomicReference<>();

    /** Serializes idle-boundary warmup (never overlap two passes). */
    private final java.util.concurrent.atomic.AtomicBoolean warmupRunning =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private volatile boolean shuttingDown;
    // Graceful-drain pre-state: the listener stays open and quick commands (hello/ping/status) keep
    // answering, but new jobs are refused and the engine exits cleanly once in-flight jobs finish.
    private volatile boolean draining;

    /** One-shot child mode ({@code --job}): no election, no endpoint, no re-delegation. */
    private volatile boolean jobMode;

    private FileChannel lockChannel;
    private FileLock lock;
    /** The generation this engine bound (socket/lock/pid/token) — see EnginePaths.generation. */
    private EnginePaths.Paths active;

    private FileLock genLock;
    private FileChannel genLockChannel;
    private ServerSocketChannel serverChannel;
    private ExecutorService connectionExecutor;

    /** Non-null once the embedded HTTP server is up; stays null when disabled or bind failed. */
    private HttpEngineServer httpServer;

    /** Non-null when {@code [http]} is enabled but the server failed to start — surfaced in status. */
    private volatile String httpError;

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
        this(paths, config, null, version, cc.jumpkick.model.BuildIdentity.buildId(), log);
    }

    /** As above plus the optional {@code [http]} table ({@code null} = feature off). */
    public EngineServer(
            EnginePaths.Paths paths,
            JkEngineConfig config,
            JkHttpConfig httpConfig,
            String version,
            Consumer<String> log) {
        this(paths, config, httpConfig, version, cc.jumpkick.model.BuildIdentity.buildId(), log);
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
        this.httpEvents = httpConfig != null ? new cc.jumpkick.engine.http.HttpEvents() : null;
        this.version = version;
        this.buildId = buildId == null ? "" : buildId;
        this.log = log != null ? log : s -> {};
        this.clockMillis = System::currentTimeMillis;
        this.pid = ProcessHandle.current().pid();
        this.startedAtMillis = clockMillis.getAsLong();
        // Process-scoped generation id for the dashboard hard-refresh contract (JK-1724).
        String bid = this.buildId.isEmpty() ? "" : "+" + this.buildId;
        this.engineEpoch = version + bid + "@" + this.startedAtMillis;
        this.aot = new AotTrainer(this.log);
        this.vitals = new EngineVitals(
                this.version,
                this.pid,
                this.startedAtMillis,
                this.engineEpoch,
                peakActiveConnections,
                peakActiveBuildPlans,
                activeConnections,
                activeBuildPlans,
                () -> httpServer,
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
        Files.createDirectories(paths.dir());
        // Startup mutex: serializes concurrent spawns/takeovers through bind + endpoint write.
        lockChannel = FileChannel.open(paths.lock(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            lock = lockChannel.tryLock();
        } catch (OverlappingFileLockException e) {
            lock = null;
        }
        if (lock == null) {
            lockChannel.close();
            lockChannel = null;
            return false; // another engine is mid-startup — it wins this race
        }

        // Where clients currently connect — the engine this one displaces (drained below).
        // Captured BEFORE we bind, and null when nothing was live: once the compat pointer is
        // written the flat path names US, and a drain aimed there is a self-shutdown. (That
        // self-drain shipped for a while, masked only by accidents — on TCP the old raw-token
        // auth failed, on Unix the closed drain connection made the bye reply throw before
        // shuttingDown was set. See EngineTcpTransportTest.)
        Path previousActive = EnginePaths.activeSocket(paths);
        if (!Files.exists(previousActive)) previousActive = null;

        // Same-version election: if a live engine of THIS version AND build identity already
        // serves, this instance is a redundant spawn-race participant — lose quietly. A different
        // version — or the same -SNAPSHOT version with a DIFFERENT buildId (a rebuilt dev
        // engine; stale incumbents once won these elections and served old code) — proceeds to
        // takeover. An empty buildId on either side means "no opinion": version rule only.
        Incumbent incumbent = helloProbe(previousActive, version);
        if (incumbent != null
                && version.equals(incumbent.version())
                && (buildId.isEmpty() || incumbent.buildId().isEmpty() || buildId.equals(incumbent.buildId()))) {
            releaseStartupLock();
            return false;
        }

        // Claim the first free generation. The winner's gen lock is held for the engine's whole
        // life; a crashed engine's stale gen files are reclaimed here by winning its lock.
        for (int n = 1; n < 10_000 && active == null; n++) {
            EnginePaths.Paths cand = EnginePaths.generation(paths, n);
            FileChannel gc = FileChannel.open(cand.lock(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock gl;
            try {
                gl = gc.tryLock();
            } catch (OverlappingFileLockException e) {
                gl = null;
            }
            if (gl != null) {
                active = cand;
                genLock = gl;
                genLockChannel = gc;
            } else {
                gc.close();
            }
        }
        if (active == null) {
            releaseStartupLock();
            return false;
        }

        // Stale files from a crashed prior owner of this generation.
        Files.deleteIfExists(active.socket());
        Files.deleteIfExists(active.token());

        if (EngineTransport.useLoopbackTcp()) {
            // Windows: no dependable Unix-domain-socket support — bind an ephemeral loopback TCP
            // port instead, and gate every connection on a shared secret (see EngineTransport),
            // since a TCP port (unlike a socket file) isn't filesystem-permission-gated by default.
            serverChannel = ServerSocketChannel.open();
            serverChannel.bind(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0));
            int port = ((java.net.InetSocketAddress) serverChannel.getLocalAddress()).getPort();
            expectedToken = EngineTransport.newToken();
            // This token gates every engine RPC — i.e. arbitrary code execution as the engine
            // owner. It must be owner-only, like the HTTP bearer token, not left to the ambient
            // umask on a shared machine (JK-1467).
            cc.jumpkick.util.OwnerOnlyFiles.write(active.token().getParent(), active.token(), expectedToken);
            Files.writeString(active.socket(), Integer.toString(port));
        } else {
            serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            serverChannel.bind(UnixDomainSocketAddress.of(active.socket()));
        }
        writePidFile();

        // TAKEOVER: from this write on, every new connection resolves to this generation.
        EnginePaths.writeEndpoint(paths, active.socket());
        releaseStartupLock();

        connectionExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("jk-engine-conn-", 0).factory());
        planSharedWorkerMemoryOnce();

        log.accept("jk engine: listening on " + active.socket() + " (pid " + pid + ")");

        // Order matters (JK-1452, JK-1475): tell the predecessor to drain FIRST — that is what
        // makes it suppress training and kill its trainer sidecar. Wiping before that signal
        // leaves a window in which its in-flight trainer can atomically rename a fresh cache into
        // the directory we just swept, which is exactly the refill this was meant to prevent.
        // Our own trainer starts last, after the sweep, so it never sweeps its own output.
        drainDisplaced(previousActive);
        // Drop other product versions' AOT (engine + workers); keep ours (named *-<version>-*).
        try {
            int wiped = cc.jumpkick.cache.VersionStore.wipeAotDirectory(
                    cc.jumpkick.util.JkDirs.state().resolve("aot"), version);
            if (wiped > 0) {
                log.accept("jk engine: retired " + wiped + " AOT cache(s) from other versions");
            }
        } catch (RuntimeException ignored) {
            // best-effort
        }
        aot.startIfConfigured();
        // HTTP binds only after the displaced predecessor has been told to drain — it still holds the
        // fixed port until it exits, so binding earlier loses the handoff race with "Address already in
        // use" and (being advisory, never retried for the engine's life) sticks in `jk engine status`.
        startHttpIfEnabled();
        // leftover running=true journal rows from a killed engine cannot still be live.
        int abandoned = journal.abandonStaleRunning(version);
        if (abandoned > 0) {
            log.accept("jk engine: abandoned " + abandoned + " stale in-flight journal entries");
        }
        // Store feeds are revalidated by HostWarmup / EngineMaintenance (not a 12 h process sleep).
        storeFeedRefresh = new StoreFeedRefresh(log, null);
        // 1-minute loop: config.toml mtime reload + wall-clock 12 h maintenance (feeds, templates,
        // GC, AOT/cal). Laptop suspend-safe — due work runs on the next minute tick after resume.
        engineMaintenance = new EngineMaintenance(log, storeFeedRefresh, this::enqueueScheduledCacheGc);
        engineMaintenance.start();
        // First-start self-heal: feeds → templates → AOT/cal on the idle worker (does not block accept).
        scheduleHostWarmupIfNeeded(false);
        // Touch resolve/PubGrub classes so the first real lock does not pay classload on the critical path.
        scheduleResolveClassWarmup();
        startDisplacementWatchdog();
        acceptLoop();
        cleanup();
        log.accept("jk engine: stopped");
        return true;
    }

    private void releaseStartupLock() {
        try {
            if (lock != null) lock.release();
            if (lockChannel != null) lockChannel.close();
        } catch (IOException ignored) {
            // best-effort; process exit releases it regardless
        }
        lock = null;
        lockChannel = null;
    }

    /** Graceful drain of a displaced engine: {@code shutdown force=false}, exit at idle. */
    private void drainDisplaced(Path previousActive) {
        if (previousActive == null || previousActive.equals(active.socket())) return;
        if (!Files.exists(previousActive)) return;
        if (namesSelf(previousActive)) return; // a stale flat pointer we just re-claimed — never self-drain
        try (SocketChannel ch = openClient(previousActive)) {
            java.io.BufferedWriter w = new java.io.BufferedWriter(new java.io.OutputStreamWriter(
                    java.nio.channels.Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            w.write(EngineProtocol.shutdown(false));
            w.write('\n');
            w.flush();
            log.accept("jk engine: drained displaced engine at " + previousActive.getFileName());
        } catch (IOException e) {
            // Nothing live there (stale file) — fine; the watchdog on the other side also covers us.
        }
    }

    /**
     * True when {@code candidate} resolves to THIS engine's own listener — the flat compat
     * pointer after we've re-claimed a crashed generation's name (Unix symlink → our gen socket;
     * TCP → a copy of our own port). Drain/probe traffic must never target it.
     */
    private boolean namesSelf(Path candidate) {
        try {
            if (EngineTransport.useLoopbackTcp()) {
                return Files.readString(candidate)
                        .trim()
                        .equals(Files.readString(active.socket()).trim());
            }
            return candidate.toRealPath().equals(active.socket().toRealPath());
        } catch (IOException e) {
            return false; // unreadable/vanished — the connect attempt sorts it out
        }
    }

    /** A live engine's identity as answered on the wire. */
    private record Incumbent(String version, String buildId) {}

    /** The identity a live engine at {@code socket} answers with, or {@code null}. */
    private static Incumbent helloProbe(Path socket, String probeVersion) {
        if (socket == null || !Files.exists(socket)) return null;
        try (SocketChannel ch = openClient(socket)) {
            java.io.BufferedWriter w = new java.io.BufferedWriter(new java.io.OutputStreamWriter(
                    java.nio.channels.Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                    java.nio.channels.Channels.newInputStream(ch), StandardCharsets.UTF_8));
            w.write(EngineProtocol.hello(probeVersion, "probe"));
            w.write('\n');
            w.flush();
            String ack = r.readLine();
            if (ack == null || !EngineProtocol.HELLO_ACK.equals(EngineProtocol.typeOf(ack))) return null;
            String v = cc.jumpkick.plugin.protocol.Jsonl.str(ack, "version");
            if (v == null) return null;
            String id = cc.jumpkick.plugin.protocol.Jsonl.str(ack, "buildId");
            return new Incumbent(v, id == null ? "" : id);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Minimal client connect for engine→engine signalling (token-gated on the TCP transport). */
    private static SocketChannel openClient(Path socket) throws IOException {
        if (EngineTransport.useLoopbackTcp()) {
            int port = Integer.parseInt(Files.readString(socket).trim());
            SocketChannel ch =
                    SocketChannel.open(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), port));
            Path token = EnginePaths.tokenFor(socket);
            java.io.BufferedWriter w = new java.io.BufferedWriter(new java.io.OutputStreamWriter(
                    java.nio.channels.Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            // The auth envelope, exactly as the CLI client sends it — authenticate accepts
            // nothing else (a raw token line here once broke takeover/election on TCP).
            w.write(EngineProtocol.auth(Files.readString(token).trim()));
            w.write('\n');
            w.flush();
            return ch;
        }
        return SocketChannel.open(UnixDomainSocketAddress.of(socket));
    }

    /**
     * Lifecycle watchdog over the endpoint pointer, which is the only thing that decides whether a CLI
     * can reach this engine. Three states, and they get deliberately different treatment:
     *
     * <ul>
     * <li><b>The pointer names someone else</b> — displaced. A newer engine took over and its drain
     * signal was lost. Surrender the Web UI port immediately so the successor can bind it, and
     * drain jobs. Attached dashboard streams get no vote here: the successor needs the port, and a
     * tab reconnects to it.
     * <li><b>The pointer is absent</b> — orphaned. Nothing names this engine, so no CLI will ever reach
     * it again, and no successor is waiting for its port either. That combination used to mean
     * serving forever: the displacement test required the pointer to EXIST, so a deleted one left an
     * unreachable engine running indefinitely (three were found alive for over an hour,.
     * Exit once genuinely unused — no jobs and no attached streams. Keep the port while a browser
     * is attached, because here there is no successor to hand it to and dropping it would strand
     * the tab.
     * <li><b>The pointer names this engine</b> — primary. Never self-terminates. An HTTP-enabled engine
     * never idles out; the dashboard is written against that invariant and treats a lost stream as
     * an anomaly rather than routine.
     * </ul>
     */
    private void startDisplacementWatchdog() {
        Thread t = new Thread(
                () -> {
                    String mine = active.socket().getFileName().toString();
                    while (!shuttingDown) {
                        try {
                            Thread.sleep(5_000);
                        } catch (InterruptedException e) {
                            return;
                        }
                        try {
                            Path ep = EnginePaths.endpoint(paths);
                            if (Files.isRegularFile(ep)
                                    && !mine.equals(Files.readString(ep).trim())) {
                                log.accept("jk engine: displaced by a newer generation — draining");
                                // JK-1452: do not finish / re-start engine AOT for a lame-duck generation.
                                aot.stopQuietly();
                                synchronized (lifecycleLock) {
                                    if (activeBuildPlans.get() == 0) {
                                        shuttingDown = true;
                                        closeServerChannelQuietly();
                                    } else {
                                        draining = true;
                                    }
                                }
                                stopHttpQuietly(); // hand the Web UI port to the successor right away
                                return;
                            }
                            if (!Files.exists(ep) && orphanedAndUnused()) {
                                log.accept("jk engine: no endpoint names this engine and it is unused — exiting");
                                aot.stopQuietly();
                                synchronized (lifecycleLock) {
                                    shuttingDown = true;
                                    closeServerChannelQuietly();
                                }
                                return;
                            }
                        } catch (IOException ignored) {
                            // transient read failure — check again next tick
                        }
                    }
                },
                "jk-engine-displacement-watchdog");
        t.setDaemon(true);
        t.start();
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
                if (shuttingDown) {
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
        return java.security.MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
    }

    private void handleConnection(SocketChannel ch) {
        try (ch;
                BufferedReader reader = new cc.jumpkick.plugin.protocol.BoundedLineReader(
                        new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8));
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8))) {
            if (expectedToken != null && !authenticate(reader)) {
                // Typed refusal (then close): a silent close is indistinguishable from a crash.
                sendQuiet(writer, EngineProtocol.error(EngineProtocol.ERR_AUTH, "engine token rejected"));
                return;
            }
            serveConnection(reader, writer);
        } catch (IOException ignored) {
            // client disconnected / socket error mid-exchange — nothing to do
        } finally {
            onConnectionFinished();
        }
    }

    /**
     * One-shot {@code jk-engine --job}: serve requests over the given streams and return (no
     * socket/daemon/election). Used when a newer daemon runs a build pinned to this older version.
     */
    public void serveJob(BufferedReader reader, BufferedWriter writer) {
        jobMode = true;
        connectionExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("jk-engine-job-", 0).factory());
        planSharedWorkerMemoryOnce();
        noteConnectionOpened();
        try {
            serveConnection(reader, writer);
        } catch (IOException ignored) {
            // parent disconnected mid-exchange — nothing to do
        } finally {
            onConnectionFinished();
        }
    }

    private void serveConnection(BufferedReader reader, BufferedWriter writer) throws IOException {
        {
            String line;
            while ((line = reader.readLine()) != null) {
                String type = EngineProtocol.typeOf(line);
                if (type == null) {
                    // A garbled REQUEST gets a typed refusal, never silence — a silently-dropped
                    // request wedges a streaming client that is waiting for a terminal event.
                    sendQuiet(
                            writer,
                            EngineProtocol.error(
                                    EngineProtocol.ERR_PROTOCOL,
                                    "unparseable request line (no \"type\" discriminator)"));
                    continue;
                }
                // Downward-delegation gate for artifact-producing requests (engine-versioning §3).
                if (DELEGATABLE.contains(type) && maybeDelegate(line, reader, writer)) {
                    return; // served by the pinned version's child engine (see EngineDelegate)
                }
                HostedVerb verb = verbs.find(type);
                if (verb != null) {
                    if (dispatchVerb(verb, line, reader, writer)) return;
                    continue;
                }
                switch (type) {
                    case EngineProtocol.HELLO -> {
                        int clientProto = Jsonl.intValue(line, "proto", EngineProtocol.PROTOCOL);
                        if (clientProto > EngineProtocol.PROTOCOL) {
                            // A newer-protocol client: this engine must not serve wire semantics
                            // it postdates — the client reacts by taking over (spawn + drain).
                            send(
                                    writer,
                                    EngineProtocol.error(
                                            EngineProtocol.ERR_VERSION_SKEW,
                                            "client speaks protocol " + clientProto + " but this engine speaks "
                                                    + EngineProtocol.PROTOCOL + " — start a matching engine"));
                            return;
                        }
                        send(writer, EngineProtocol.helloAck(version, pid, startedAtMillis, draining, buildId));
                    }
                    case EngineProtocol.PING -> send(writer, EngineProtocol.pong());
                    case EngineProtocol.STATUS -> {
                        cc.jumpkick.engine.http.StatusSnapshot s = statusSnapshot();
                        send(
                                writer,
                                EngineProtocol.statusAck(
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
                                        httpServer != null ? httpServer.url() : null,
                                        httpError,
                                        httpServer != null && httpServer.mcpEnabled(),
                                        s.peakActiveRequests(),
                                        s.peakActiveBuildPlans()));
                    }
                    case EngineProtocol.SHUTDOWN -> {
                        boolean force = cc.jumpkick.plugin.protocol.Jsonl.bool(line, "force", false);
                        // Takeover already repointed the endpoint before sending shutdown — kill the
                        // engine AOT sidecar so it cannot re-publish engine-<old-v>-* (JK-1452).
                        // Voluntary `jk engine stop` still names us; leave train to finish then.
                        if (!endpointNamesThisEngine()) {
                            aot.stopQuietly();
                        }
                        synchronized (lifecycleLock) {
                            int jobs = activeBuildPlans.get();
                            if (force || jobs == 0) {
                                // Immediate: no in-flight jobs, or an explicit force — close the listener
                                // now so run returns and the JVM exits cleanly (AOT still assembles when
                                // we remain primary).
                                send(writer, EngineProtocol.bye(jobs, false));
                                shuttingDown = true;
                                closeServerChannelQuietly();
                            } else {
                                // Graceful drain: keep the listener open (so new commands get a clear
                                // "shutting down" handshake and in-flight jobs finish); the last job to
                                // complete triggers the clean exit (see maybeIdleBoundary).
                                draining = true;
                                send(writer, EngineProtocol.bye(jobs, true));
                            }
                        }
                        stopHttpQuietly(); // hand the Web UI port to the successor right away
                        return;
                    }
                    case EngineProtocol.CANCEL_REQUEST -> handleCancelRequest(line, writer);
                    default ->
                        sendQuiet(
                                writer,
                                EngineProtocol.error(EngineProtocol.ERR_PROTOCOL, "unknown request type: " + type));
                }
            }
        }
    }

    /**
     * Registry dispatch. {@code true} means the verb owns the rest of this connection
     * (async plan / cache maint).
     */
    private boolean dispatchVerb(HostedVerb verb, String line, BufferedReader reader, BufferedWriter writer)
            throws IOException {
        return switch (verb.shape()) {
            case VerbShape.AsyncPlan() -> {
                jobs.submit(line, verb.toJobRequest(), new JobTransport.SocketWatch(reader, writer));
                yield true;
            }
            case VerbShape.CacheMaint() -> {
                jobs.submit(line, verb.toJobRequest(), new JobTransport.SocketWatch(reader, writer));
                yield true;
            }
            case VerbShape.SyncRead() -> {
                verb.run(line, Session.defaults().cancel(), writer);
                yield false;
            }
            case VerbShape.Lifecycle() ->
                throw new IllegalStateException("lifecycle stays on the process, not the verb registry");
        };
    }

    private void handleCancelRequest(String requestLine, BufferedWriter writer) throws IOException {
        long jid = Jsonl.longValue(requestLine, "jid", -1);
        if (jid < 0) jid = Jsonl.longValue(requestLine, "requestId", -1);
        String dir = Jsonl.str(requestLine, "dir");
        if (jid >= 0) {
            boolean ok = cancelJob(jid);
            send(writer, EngineProtocol.cancelAck(jid, ok, ok ? null : "unknown or already finished jid"));
            return;
        }
        if (dir != null && !dir.isBlank()) {
            int n = jobs.cancelJobsForDir(dir);
            send(
                    writer,
                    EngineProtocol.cancelAck(
                            0, n > 0, n > 0 ? ("cancelled " + n + " job(s)") : "no running jobs for dir"));
            return;
        }
        send(writer, EngineProtocol.cancelAck(-1, false, "cancel-request requires jid or dir"));
    }

    boolean cancelJob(long jid) {
        return jobs.cancelJob(jid);
    }

    int cancelJobsForDir(String dir) {
        return jobs.cancelJobsForDir(dir);
    }

    static String cancelledTerminalLine(boolean workspaceStream, String dir) {
        return JobEnvelope.cancelledTerminalLine(workspaceStream, dir);
    }

    /**
     * Publish an <strong>inflicted</strong> build/activity frame to the dashboard SSE hub (JK-1499).
     * Call only when the engine already mutated user-visible state — never batch build progress on
     * the sampled vitals timer ({@link cc.jumpkick.engine.http.LiveVitals}). No-op without
     * subscribers. Sampled chrome ({@code status}/{@code cache}) is separate: change-gated and
     * nudged only on request start/finish so Builds Running / storage totals stay timely.
     */
    /**
     * Orders wire-event publication against dashboard SSE connect hydration (JK-1837).
     * Publishers take the read side around each publish (accumulation happens strictly before,
     * in program order); a connecting dashboard takes the write side around snapshot capture →
     * {@code deliverTo} → {@code attach}. Any publish that completed before the write section
     * accumulated before the snapshot was captured (so its effect is in the snapshot); any
     * publish after it reaches the attached queue. Overlap yields duplicates, which the SPA
     * folds idempotently — gaps, which it cannot heal, are impossible.
     */
    private final java.util.concurrent.locks.ReentrantReadWriteLock sseConnect =
            new java.util.concurrent.locks.ReentrantReadWriteLock();

    private void publishEvent(String type, cc.jumpkick.engine.http.JsonOut payload) {
        publishEvent(type, payload, false);
    }

    /**
     * As {@link #publishEvent(String, cc.jumpkick.engine.http.JsonOut)}; {@code dashboardOnly}
     * frames (SSE-connect rehydrate replays) skip MCP subscriptions — the dashboard folds a
     * duplicate {@code request-start} idempotently, but an MCP agent treating it as "job began"
     * would double-count (JK-1523).
     */
    private void publishEvent(String type, cc.jumpkick.engine.http.JsonOut payload, boolean dashboardOnly) {
        sseConnect.readLock().lock();
        try {
            if (httpEvents != null && httpEvents.hasSubscribers()) {
                if (dashboardOnly) httpEvents.publishDashboard(type, payload);
                else httpEvents.publish(type, payload);
            }
            // Sampled chrome (status/cache SSE) is change-gated; nudge it when jobs start/finish so
            // Builds Running and storage totals do not wait for the next timer tick (JK-1495/1497).
            HttpEngineServer http = httpServer;
            if (http != null && ("request-start".equals(type) || "request-finish".equals(type))) {
                http.notifyLiveStatus();
                if ("request-finish".equals(type)) http.notifyLiveCache();
            }
        } finally {
            sseConnect.readLock().unlock();
        }
    }

    /**
     * Attach last known <em>workspace aggregate</em> {@code progress} (0–100 or null) from the
     * engine tracker. Never compute from module-local ticks here.
     */
    private cc.jumpkick.engine.http.JsonOut withProgress(cc.jumpkick.engine.http.JsonOut payload, long requestId) {
        Double p = requestId > 0 ? lastProgressOf(requestId) : null;
        return payload.putNullable("progress", p);
    }

    /**
     * The ambient byte ledger for a request: the journal accumulator's when the kind is journaled,
     * else a throwaway so metering call sites never branch on whether anyone is recording.
     */
    private cc.jumpkick.task.IoLedger runIo(long requestId) {
        BuildAccumulator a = accumulatorOf(requestId);
        return a != null ? a.io() : new cc.jumpkick.task.IoLedger();
    }

    /**
     * Add the run's byte counters to a terminal event so a live dashboard card shows them without
     * waiting for the history backfill. Omitted entirely for a run that moved nothing.
     */
    private cc.jumpkick.engine.http.JsonOut withIo(cc.jumpkick.engine.http.JsonOut payload, long requestId) {
        BuildAccumulator a = accumulatorOf(requestId);
        if (a == null) return payload;
        cc.jumpkick.task.IoLedger.Totals t = a.io().totals();
        if (t.isEmpty()) return payload;
        return payload.put("remoteUpBytes", t.remoteUp())
                .put("remoteDownBytes", t.remoteDown())
                .put("localUpBytes", t.localUp())
                .put("localDownBytes", t.localDown());
    }

    /**
     * Requests whose progress state has been torn down.
     *
     * <p>A job the engine gave up on ("still running after deadline+grace — abandoned") keeps
     * emitting: its module listener calls back into {@link #progressTracker} and
     * {@code emitWorkspaceProgress} <em>after</em> {@link #clearProgress} ran, and those are
     * {@code computeIfAbsent}/{@code put} sites — so every abandoned job used to strand five
     * permanent map entries in a process that runs for days, and two threads could even hold
     * different emit locks for one request. Marking the id retired makes those late writes
     * no-ops (JK-1474).
     *
     * <p>Bounded: request ids come from a monotonic counter, so ids far below the newest can no
     * longer be live and are pruned on each teardown.
     */
    private void clearProgress(long requestId) {
        sessions.retire(requestId);
    }

    /** True once {@link #clearProgress} has retired this request — late emits must not re-register. */
    private boolean progressRetired(long requestId) {
        return sessions.retired(requestId);
    }

    /**
     * For a retired request, a detached tracker that is never stored: callers keep a non-null
     * object to update (no null checks at eight call sites) and the update goes nowhere.
     */
    private cc.jumpkick.runtime.WorkspaceProgressTracker progressTracker(long requestId) {
        if (sessions.retired(requestId)) {
            return new cc.jumpkick.runtime.WorkspaceProgressTracker(null);
        }
        JobSession s = sessions.open(requestId);
        if (s == null) return new cc.jumpkick.runtime.WorkspaceProgressTracker(null);
        return s.tracker();
    }

    private long planWeight(long requestId, String dir) {
        if (requestId <= 0 || dir == null) return 0;
        JobSession s = sessions.get(requestId);
        if (s == null) return 0;
        Long w = s.weights().get(dir);
        return w != null ? w : 0;
    }

    private void putMode(long id, cc.jumpkick.runtime.progress.ProgressBarMode mode) {
        JobSession s = sessions.open(id);
        if (s != null) s.mode(mode);
    }

    private void putRemaining(long id, cc.jumpkick.runtime.RemainingWork rw) {
        JobSession s = sessions.open(id);
        if (s != null) s.remaining(rw);
    }

    private cc.jumpkick.runtime.RemainingWork remainingOf(long id) {
        JobSession s = sessions.get(id);
        return s == null ? null : s.remaining();
    }

    private void putProgressRoot(long id, String root) {
        JobSession s = sessions.open(id);
        if (s != null) s.progressRoot(root);
    }

    private String progressRootOf(long id) {
        JobSession s = sessions.get(id);
        String r = s == null ? null : s.progressRoot();
        return r == null ? "" : r;
    }

    private void putLastProgress(long id, double p) {
        JobSession s = sessions.open(id);
        if (s != null) s.lastProgress(p);
    }

    private Double lastProgressOf(long id) {
        JobSession s = sessions.get(id);
        return s == null ? null : s.lastProgress();
    }

    private void putLastProgressDen(long id, long den) {
        JobSession s = sessions.open(id);
        if (s != null) s.lastProgressDen(den);
    }

    private Long lastProgressDenOf(long id) {
        JobSession s = sessions.get(id);
        return s == null ? null : s.lastProgressDen();
    }

    private void putAccumulator(long id, BuildAccumulator acc) {
        JobSession s = sessions.open(id);
        if (s != null) s.accumulator(acc);
    }

    private BuildAccumulator accumulatorOf(long id) {
        JobSession s = sessions.get(id);
        return s == null ? null : s.accumulator();
    }

    private BuildAccumulator takeAccumulator(long id) {
        JobSession s = sessions.get(id);
        if (s == null) return null;
        BuildAccumulator a = s.accumulator();
        s.accumulator(null);
        return a;
    }

    private java.util.concurrent.ConcurrentHashMap<String, Long> weightsOf(long id) {
        JobSession s = sessions.open(id);
        return s == null ? new java.util.concurrent.ConcurrentHashMap<>() : s.weights();
    }

    private cc.jumpkick.runtime.WorkspaceProgressTracker trackerOrNull(long id) {
        JobSession s = sessions.get(id);
        return s == null ? null : s.existingTracker();
    }

    private Object emitLockOf(long id) {
        JobSession s = sessions.open(id);
        return s == null ? new Object() : s.emitLock();
    }

    private void putEmitState(long id, long[] state) {
        JobSession s = sessions.open(id);
        if (s != null) s.emitState(state);
    }

    private long[] emitStateOf(long id) {
        JobSession s = sessions.get(id);
        return s == null ? null : s.emitState();
    }

    /**
     * Feed module plan ticks into residual {@code R(t)} and the workspace bar, then emit
     * {@code workspace-progress} + updated remaining ETA.
     */
    private void trackModuleBuildPlan(
            long requestId, String dir, BuildPlanView view, java.io.BufferedWriter writer, boolean forceEmit) {
        if (requestId <= 0 || view == null) return;
        double frac = view.denominator() > 0
                ? Math.min(1.0, Math.max(0.0, (double) view.numerator() / (double) view.denominator()))
                : 0.0;
        // Bar: effort-weight slices (plan num/den) + residual annotation for adaptive clock/countdown.
        long slice = planWeight(requestId, dir);
        progressTracker(requestId).moduleProgress(dir, slice, view.numerator(), view.denominator());
        cc.jumpkick.runtime.RemainingWork rw = remainingOf(requestId);
        if (rw != null && dir != null) {
            // Atomic update+recompute+note per request: two scheduler threads interleaving
            // (T1 computes 10s, T2 computes 9s and notes it, T1 notes 10s last) regressed the
            // wire remainingMs (JK-1830). rw's own methods synchronize on rw, so this monitor
            // is reentrant and orders the notes with their computations.
            synchronized (rw) {
                rw.moduleProgress(java.nio.file.Path.of(dir), frac);
                progressTracker(requestId).noteRemaining(rw.remaining(), rw.R0());
            }
        }
        emitWorkspaceProgress(requestId, writer, forceEmit);
    }

    private void trackModuleComplete(long requestId, String dir, long lastDen, java.io.BufferedWriter writer) {
        if (requestId <= 0) return;
        cc.jumpkick.runtime.RemainingWork rw = remainingOf(requestId);
        if (rw != null && dir != null) {
            synchronized (rw) {
                rw.moduleComplete(java.nio.file.Path.of(dir));
                progressTracker(requestId).noteRemaining(rw.remaining(), rw.R0());
            }
        }
        progressTracker(requestId).moduleComplete(dir, lastDen);
        emitWorkspaceProgress(requestId, writer, true);
    }

    /**
     * Emit filterable {@code workspace-progress} on the socket (when {@code writer} non-null) and SSE
     * hub. Throttled unless {@code force} (stage boundaries, module complete, finish).
     */
    private void emitWorkspaceProgress(long requestId, java.io.BufferedWriter writer, boolean force) {
        emitWorkspaceProgress(requestId, writer, force, false);
    }

    private void emitWorkspaceProgress(
            long requestId, java.io.BufferedWriter writer, boolean force, boolean dashboardOnly) {
        if (requestId <= 0) return;
        // A straggler from an abandoned job must not re-register the maps teardown just cleared,
        // nor take a fresh emit lock that no longer serializes against anything (JK-1474).
        if (progressRetired(requestId)) return;
        Object lock = emitLockOf(requestId);
        synchronized (lock) {
            cc.jumpkick.runtime.WorkspaceProgressTracker tracker = trackerOrNull(requestId);
            if (tracker == null) return;
            var snap = tracker.snapshot();
            double heldPct = Double.NaN;
            if (snap.hasPercent()) {
                // Peak-hold machine progressnever publish a lower % than already
                // emitted — but rebase when the denominator grew (calibrate), or the preflight
                // peak pins the rider for the whole execute phase.
                Double prevPct = lastProgressOf(requestId);
                Long prevDen = lastProgressDenOf(requestId);
                heldPct = snap.percent();
                boolean denGrew = prevDen != null && snap.denominator() > prevDen;
                if (!denGrew && prevPct != null && heldPct + 1e-9 < prevPct) {
                    heldPct = prevPct;
                }
                putLastProgress(requestId, heldPct);
                putLastProgressDen(requestId, snap.denominator());
            }
            if (!force && !shouldEmitWorkspaceProgress(requestId, snap)) return;
            String dir = progressRootOf(requestId);
            // snapshot() recomputes open-loop percent when R0 is set (clock strategy).
            long num = snap.numerator();
            long den = snap.denominator();
            long rem = snap.remainingMs();
            long r0 = snap.R0ms();
            // The HELD percent goes on both wire surfaces — the JSONL line used to carry the raw
            // (possibly regressing) value while SSE got the held one via withProgress (JK-1821).
            double pct = heldPct;
            String line = EngineProtocol.workspaceProgress(
                    dir, num, den, snap.phase(), snap.modulesComplete(), snap.modulesTotal(), rem, r0, pct);
            if (writer != null) sendQuiet(writer, line);
            if (eventsWanted()) {
                var body = cc.jumpkick.engine.http.JsonOut.object()
                        .put("schema", 1)
                        .put("type", "workspace-progress")
                        .put("requestId", requestId)
                        .put("dir", dir)
                        .put("numerator", num)
                        .put("denominator", den)
                        .put("phase", snap.phase())
                        .put("modulesComplete", snap.modulesComplete())
                        .put("modulesTotal", snap.modulesTotal())
                        .put("remainingMs", rem)
                        .put("R0", r0);
                if (!Double.isNaN(pct)) body.put("progress", pct);
                publishEvent("workspace-progress", withProgress(body, requestId), dashboardOnly);
            }
            Double held = lastProgressOf(requestId);
            long pctMillis = held != null
                    ? Math.round(held * 10.0)
                    : (snap.hasPercent() ? Math.round(snap.percent() * 10.0) : -1L);
            putEmitState(requestId, new long[] {System.currentTimeMillis(), pctMillis});
        }
    }

    /**
     * Same human cadence as {@link CoalescingBuildPlanListener} / {@code JK_WIRE_PROGRESS_MS}
     * (default 500 ms). CLI TUI and web open-loop the bar between samples; shipping every TTY
     * frame (80 ms) on UDS+SSE was pure wire cost. {@code force} emits still bypass this (stage
     * boundaries, module complete, finish). {@code JK_WIRE_PROGRESS_MS=0} is unbatched.
     */
    private boolean shouldEmitWorkspaceProgress(
            long requestId, cc.jumpkick.runtime.WorkspaceProgressTracker.Snapshot snap) {
        long cadence = CoalescingBuildPlanListener.cadenceFromEnv();
        if (cadence <= 0) return true;
        long[] prev = emitStateOf(requestId);
        if (prev == null) return true;
        long now = System.currentTimeMillis();
        return now - prev[0] >= cadence;
    }

    /**
     * {@code request-start}, enriched with the project's {@code group:name} coordinate when the
     * dir's {@code jk.toml} parses — the dashboard renders coordinates, not paths, when it can
     * (the design's coord coloring). Best-effort and only attempted with a subscriber connected.
     */
    private void publishRequestStart(long requestId, String kind, String dir) {
        publishRequestStart(requestId, kind, dir, 0L);
    }

    private void publishRequestStart(long requestId, String kind, String dir, long buildNumber) {
        publishRequestStart(requestId, kind, dir, buildNumber, false);
    }

    private void publishRequestStart(long requestId, String kind, String dir, long buildNumber, boolean dashboardOnly) {
        long startedAt = inFlightBuilds
                .get(requestId)
                .map(InFlightBuilds.Hold::startedAt)
                .orElse(0L);
        publishRequestStart(requestId, kind, dir, buildNumber, dashboardOnly, startedAt);
    }

    private void publishRequestStart(
            long requestId, String kind, String dir, long buildNumber, boolean dashboardOnly, long startedAt) {
        if (!eventsWanted()) return;
        String coord = null;
        try {
            var project = JkBuildParser.parse(Path.of(dir).resolve("jk.toml")).project();
            coord = project.group() + ":" + project.name();
        } catch (Exception e) {
            // unparseable/missing jk.toml — the dashboard falls back to showing the dir
        }
        var payload = cc.jumpkick.engine.http.JsonOut.object()
                .put("schema", 1)
                .put("type", "request-start")
                .put("requestId", requestId)
                .put("jid", requestId)
                .put("kind", kind)
                .put("dir", dir)
                .put("coord", coord)
                .put("projectId", cc.jumpkick.runtime.ProjectIds.idOf(dir));
        if (buildNumber > 0) payload = payload.put("buildNumber", buildNumber);
        if (startedAt > 0) {
            payload = payload.put("startedAt", startedAt);
            // Engine "now" beside engine startedAt: elapsed = serverNow - startedAt is skew-free,
            // and the SPA re-anchors it to its own clock at receipt (JK-1839).
            payload = payload.put("serverNow", clockMillis.getAsLong());
        }
        payload = payload.put("activeBuildPlans", activeBuildPlans.get());
        publishEvent("request-start", withProgress(payload, requestId), dashboardOnly);
    }

    private void publishStepStart(long requestId, String dir, String step, String phase) {
        publishStepStart(requestId, dir, step, phase, false);
    }

    private void publishStepStart(long requestId, String dir, String step, String phase, boolean dashboardOnly) {
        accStepStart(requestId, dir, step, phase);
        if (!eventsWanted()) return;
        // Field names align with CLI JsonlShape (schema + type + task + group).
        publishEvent(
                "task-start",
                withProgress(
                        cc.jumpkick.engine.http.JsonOut.object()
                                .put("schema", 1)
                                .put("type", "task-start")
                                .put("requestId", requestId)
                                .put("dir", dir)
                                .put("task", step)
                                .put("stage", phase),
                        requestId),
                dashboardOnly);
    }

    private void publishStepFinish(long requestId, String dir, String step, String phase, String status, long millis) {
        publishStepFinish(requestId, dir, step, phase, status, millis, false);
    }

    private void publishStepFinish(
            long requestId, String dir, String step, String phase, String status, long millis, boolean dashboardOnly) {
        if (!eventsWanted()) return;
        publishEvent(
                "task-finish",
                withProgress(
                        cc.jumpkick.engine.http.JsonOut.object()
                                .put("schema", 1)
                                .put("type", "task-finish")
                                .put("requestId", requestId)
                                .put("dir", dir)
                                .put("task", step)
                                .put("stage", phase)
                                .put("status", status)
                                .put("millis", millis),
                        requestId),
                dashboardOnly);
    }

    /**
     * Live step detail (test class.method, "shrinking jar", …) — same payload as the socket
     * {@code label} line. The SPA paints it after the running phase node (CLI tree-row parity).
     */
    private void publishLabel(long requestId, String dir, String step, String label) {
        if (!eventsWanted()) return;
        publishEvent(
                "label",
                withProgress(
                        cc.jumpkick.engine.http.JsonOut.object()
                                .put("schema", 1)
                                .put("type", "label")
                                .put("requestId", requestId)
                                .put("dir", dir)
                                .put("task", step)
                                .put("label", redactEnv(dir, label)),
                        requestId));
    }

    /** Wire spelling of a step's coarse {@link String} — {@code ""} when unset. */
    private static String phaseWire(String group) {
        return BridgingPlanListener.phaseWire(group);
    }

    private void publishOutput(long requestId, String dir, String step, String line) {
        if (!eventsWanted()) return;
        // Rate is owned by CoalescingBuildPlanListener (JK_WIRE_PROGRESS_MS) on both CLI wire and
        // HTTP hub paths — do not double-throttle here.
        // Redact here, not per caller: the HTTP/MCP job listener feeds raw step output and the
        // SSE stream is readable token-free on loopback. Idempotent for callers that
        // already masked.
        publishEvent(
                "output",
                withProgress(
                        cc.jumpkick.engine.http.JsonOut.object()
                                .put("schema", 1)
                                .put("type", "output")
                                .put("requestId", requestId)
                                .put("dir", dir)
                                .put("task", step)
                                .put("line", redactEnv(dir, line)),
                        requestId));
    }

    /** Compile-error flood cap. {@code test-failure} diagnostics get their own, higher cap. */
    static final int MAX_DIAGNOSTIC_EVENTS = 8;

    /**
     * Test-failure flood cap for the live card. JK-1871's intent stands — a normal red run shows
     * every failure — but a broken shared fixture can fail thousands of tests, each carrying a
     * stack and snippet; an SSE card is not the place to stream that (the CLI report and journal
     * still have everything).
     */
    static final int MAX_TEST_FAILURE_EVENTS = 100;

    /**
     * Which diagnostics to put on the live SSE card. {@code test-failure} diagnostics are kept up
     * to {@link #MAX_TEST_FAILURE_EVENTS}; other codes (javac, resolve, …) are capped at
     * {@link #MAX_DIAGNOSTIC_EVENTS}.
     */
    static java.util.List<BuildPlanResult.Diagnostic> selectPublishedDiagnostics(
            java.util.List<BuildPlanResult.Diagnostic> errors) {
        if (errors == null || errors.isEmpty()) return java.util.List.of();
        java.util.ArrayList<BuildPlanResult.Diagnostic> out = new java.util.ArrayList<>(errors.size());
        int tests = 0;
        int other = 0;
        for (BuildPlanResult.Diagnostic d : errors) {
            if ("test-failure".equals(d.code())) {
                if (tests < MAX_TEST_FAILURE_EVENTS) {
                    out.add(d);
                    tests++;
                }
                continue;
            }
            if (other < MAX_DIAGNOSTIC_EVENTS) {
                out.add(d);
                other++;
            }
        }
        return out;
    }

    /** How many diagnostics {@link #selectPublishedDiagnostics} dropped — feeds the "+N more" line. */
    static int unpublishedCount(java.util.List<BuildPlanResult.Diagnostic> errors) {
        if (errors == null) return 0;
        int tests = 0;
        int other = 0;
        for (BuildPlanResult.Diagnostic d : errors) {
            if ("test-failure".equals(d.code())) tests++;
            else other++;
        }
        return Math.max(0, other - MAX_DIAGNOSTIC_EVENTS) + Math.max(0, tests - MAX_TEST_FAILURE_EVENTS);
    }

    /** Publish structured {@link BuildPlanResult.Diagnostic}s for a failed request card. */
    private void publishDiagnostics(long requestId, String dir, java.util.List<BuildPlanResult.Diagnostic> errors) {
        if (!eventsWanted() || errors.isEmpty()) return;
        for (BuildPlanResult.Diagnostic d : selectPublishedDiagnostics(errors)) {
            // type "error" matches CLI JsonlShape; SSE event name stays "diagnostic" for the SPA.
            var o = cc.jumpkick.engine.http.JsonOut.object()
                    .put("schema", 1)
                    .put("type", "error")
                    .put("requestId", requestId)
                    .put("dir", dir)
                    .put("task", d.step())
                    .put("code", d.code())
                    .put("message", redactEnv(dir, d.message()));
            if (d.module() != null && !d.module().isEmpty()) o.put("module", d.module());
            if (d.engine() != null && !d.engine().isEmpty()) o.put("engine", d.engine());
            if (d.className() != null && !d.className().isEmpty()) o.put("class", d.className());
            if (d.method() != null && !d.method().isEmpty()) o.put("method", d.method());
            if (d.exceptionClass() != null && !d.exceptionClass().isEmpty())
                o.put("exceptionClass", d.exceptionClass());
            if (d.stack() != null && !d.stack().isEmpty()) o.put("stack", redactEnv(dir, d.stack()));
            if (d.file() != null && !d.file().isEmpty()) o.put("file", d.file());
            if (d.line() > 0) o.put("line", d.line());
            if (d.snippetStart() > 0) o.put("snippetStart", d.snippetStart());
            if (d.snippet() != null && !d.snippet().isEmpty()) o.putStrings("snippet", d.snippet());
            if (d.worker() > 0) o.put("worker", d.worker());
            if (d.test() != null && !d.test().isEmpty()) o.put("test", d.test());
            publishEvent("diagnostic", withProgress(o, requestId));
        }
        int dropped = unpublishedCount(errors);
        if (dropped > 0) {
            publishRequestError(requestId, dir, "+ " + dropped + " more errors — see the CLI output");
        }
    }

    /** A single request-level failure line (bad jk.toml, workspace orchestration error, …). */
    private void publishRequestError(long requestId, String dir, String message) {
        if (!eventsWanted() || message == null || message.isBlank()) return;
        publishEvent(
                "diagnostic",
                withProgress(
                        cc.jumpkick.engine.http.JsonOut.object()
                                .put("schema", 1)
                                .put("type", "error")
                                .put("requestId", requestId)
                                .put("dir", dir)
                                .put("task", "request")
                                .put("code", "error")
                                .put("message", redactEnv(dir, message))
                                .put("test", "")
                                .put("exceptionClass", ""),
                        requestId));
    }

    /**
     * The build's total weight (Σ module weights) — seeds the dashboard bar's denominator up front so
     * the aggregate never jumps backward as later modules start. Mirrors what {@code onPlan} already
     * sends the CLI as per-module {@code plan-module} weights. Weight is the same abstract unit the
     * CLI bar uses ({@code EffortWeights}, ≈150 ms/unit); the dashboard only needs the ratio.
     */
    private void publishPlan(long requestId, long totalWeight) {
        if (!eventsWanted()) return;
        // Plan seeds the bar denominator; progress is still preflight-only until execute ticks.
        publishEvent(
                "plan",
                withProgress(
                        cc.jumpkick.engine.http.JsonOut.object()
                                .put("schema", 1)
                                .put("type", "plan")
                                .put("requestId", requestId)
                                .put("weight", totalWeight),
                        requestId));
    }

    /**
     * Fine-grained module plan ticks for the dashboard (step detail). Aggregate % on SSE/MCP:
     * workspace builds use {@link cc.jumpkick.runtime.WorkspaceProgressTracker}; single-plan
     * jobs (build/test/compile) have no tracker yet — the plan <em>is</em> the whole request, so
     * feed last-progress on the session from this view.
     */
    private void publishBuildPlanProgress(long requestId, String dir, BuildPlanView view) {
        if (requestId > 0 && view != null && trackerOrNull(requestId) == null && view.denominator() > 0) {
            double p = cc.jumpkick.runtime.WorkspaceProgressTracker.percentOf(view.numerator(), view.denominator());
            if (!Double.isNaN(p)) putLastProgress(requestId, p);
        }
        if (!eventsWanted()) return;
        publishEvent(
                "plan-progress",
                withProgress(
                        cc.jumpkick.engine.http.JsonOut.object()
                                .put("schema", 1)
                                .put("type", "progress")
                                .put("requestId", requestId)
                                .put("dir", dir)
                                .put("numerator", view.numerator())
                                .put("denominator", view.denominator()),
                        requestId));
    }

    /**
     * The calibrated ETA in millis — the same value {@code jk build}'s countdown and {@code jk
     * explain}'s estimate show ({@code BuildService.seedEta} + live re-projections). Emitted for the
     * seed and every re-projection, exactly where the socket path sends {@code EngineProtocol.eta}.
     */
    private void publishEta(long requestId, long millis) {
        if (!eventsWanted()) return;
        publishEvent(
                "eta",
                withProgress(
                        cc.jumpkick.engine.http.JsonOut.object()
                                .put("schema", 1)
                                .put("type", "eta")
                                .put("requestId", requestId)
                                .put("millis", millis),
                        requestId));
    }

    /**
     * Guard for event publishers: build the payload only when someone is listening. Split from
     * {@link #publishEvent} so hot listener callbacks (per-plan, per-module) pay one boolean check,
     * not a {@code JsonOut} allocation, when no dashboard is open.
     */
    private boolean eventsWanted() {
        return httpEvents != null && httpEvents.hasSubscribers();
    }

    /**
     * After a plan slot was released via {@link #noteBuildPlanFinished}: all idle housekeeping when
     * nothing remains in flight, with {@link System#gc()} strictly last (after prune, journal/metrics
     * retention, metrics harvest, and any host warmup). Does <em>not</em> decrement the counter —
     * the finish path decrements first so status SSE sees the post-finish plan count (JK-1725).
     */
    private void maybeIdleBoundary() {
        if (activeBuildPlans.get() != 0) return;
        runIdleHousekeeping();
        // The last in-flight job of a graceful drain just finished — close the listener so run
        // returns and the JVM exits cleanly (assembling the AOT cache), same as a normal stop.
        if (draining) {
            synchronized (lifecycleLock) {
                shuttingDown = true;
                closeServerChannelQuietly();
            }
        }
    }

    /**
     * Full GC only when no plan is in flight and no idle housekeeping is still running.
     * Prefer {@link #runIdleHousekeeping} so GC trails the whole workset.
     */
    private void maybeIdleGc() {
        if (activeBuildPlans.get() != 0 || warmupRunning.get()) return;
        System.gc();
    }

    /** Single-flight latch for {@link #runIdleHousekeeping} — see the exactly-once note there. */
    private final java.util.concurrent.atomic.AtomicBoolean idleHousekeepingRunning =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Coordinated idle chores. Order is fixed; {@link System#gc()} is always last for the workset.
     * Prune/journal/harvest run here; when warmup is needed a daemon does warmup + the trailing GC
     * instead (so the build connection is not blocked for multi-minute AOT train).
     */
    private void runIdleHousekeeping() {
        if (shuttingDown) return;
        if (activeBuildPlans.get() != 0) return;
        // Exactly-once at the build boundary (JK-1795): every finish path decrements the plan
        // counter BEFORE publishing request-finish (SSE lockstep, JK-1725), so two
        // near-simultaneous finishes can both observe 0 and land here — and the 12 h scheduled
        // path can race a finish, too. tryAcquire admits one runner; the counter is re-checked
        // under the guard so a build admitted meanwhile skips housekeeping (its own finish
        // reaches the boundary later).
        if (!idleHousekeepingRunning.compareAndSet(false, true)) return;
        try {
            if (activeBuildPlans.get() != 0) return;
            drainPendingPrune();
            pruneJournal();
            pruneMetrics();
            // Wait for coalesced MetricsHarvest so trimmed-mean rewrites finish before heap GC.
            try {
                cc.jumpkick.builds.MetricsHarvest.get().awaitIdle(30_000L);
            } catch (RuntimeException ignored) {
            }
            if (activeBuildPlans.get() != 0) return;

            if (pendingWarmupForce.get() != null || HostWarmup.needsWork()) {
                // Ensure a queue entry so kickPendingWarmup has work; that thread owns the trailing GC.
                pendingWarmupForce.compareAndSet(null, Boolean.FALSE);
                kickPendingWarmup(/* trailGc */ true);
                return;
            }
            // Trailing heap GC after the entire idle workset (prune, harvest).
            if (activeBuildPlans.get() == 0 && !warmupRunning.get()) {
                System.gc();
            }
        } finally {
            idleHousekeepingRunning.set(false);
        }
    }

    /**
     * Free the exclusive fingerprint as soon as project-mutating plan work finishes (idempotent).
     * Connection teardown / journal / idle chores may still run; a follow-up same-project build must
     * not see {@code already-running} during that tail.
     */
    private void releaseExclusiveSlot() {
        long id = eventRequestId();
        if (id > 0) inFlightBuilds.release(id);
    }

    /**
     * Enforce build-history retention at the idle boundary, off the hot path: drop entries past the
     * configured age, then oldest-first past the disk budget (reclaiming the copied snapshots). The
     * journal is its own dir tree — no {@link #cacheGate} needed. Best-effort; a failure is logged.
     */
    private void pruneJournal() {
        if (!historyConfig.enabled()) return;
        try {
            long now = clockMillis.getAsLong();
            BuildJournal.PruneResult r = journal.prune(historyConfig.maxAgeMillis(), historyConfig.maxDiskBytes(), now);
            if (r.removedEntries() > 0) {
                log.accept("jk engine: build journal prune removed " + r.removedEntries() + " entries ("
                        + r.removedBytes() + " bytes)");
            }
        } catch (RuntimeException e) {
            log.accept("jk engine: build journal prune failed: " + e.getMessage());
        }
    }

    /**
     * Enforce metrics retention at the idle boundary: age out rows for projects no longer built
     * here, then oldest-first past the byte cap. Best-effort; a failure is logged.
     */
    private void pruneMetrics() {
        try {
            BuildMetrics.Limits limits =
                    BuildMetrics.Limits.resolve(cc.jumpkick.util.JkDirs.userConfigFile(), System::getenv);
            BuildMetrics.PruneReport r = BuildMetrics.prune(metricsFile, limits, clockMillis.getAsLong(), false);
            if (r.evictedByAge() + r.evictedBySize() > 0) {
                log.accept("jk engine: build metrics prune removed " + (r.evictedByAge() + r.evictedBySize())
                        + " rows (" + r.kept() + " kept, " + r.finalBytes() + " bytes)");
            }
        } catch (RuntimeException e) {
            log.accept("jk engine: build metrics prune failed: " + e.getMessage());
        }
    }

    /**
     * Queue an opportunistic prune of {@code cache} for the next idle boundary if the auto-prune
     * cadence is due — the engine-internal replacement for the detached {@code jk cache clean
     * --background} self-spawn (the engine is the process that did the work, and the idle boundary
     * is the only safe time to mutate the caches it serves).
     */
    private void maybeEnqueuePrune(Path cache) {
        try {
            var config = cc.jumpkick.config.JkCacheConfig.resolve();
            if (config.autoPrune() && cc.jumpkick.task.CachePruneScheduler.shouldRun(config, cache)) {
                pendingPruneCache.compareAndSet(null, cache);
            }
        } catch (IOException ignored) {
            // Best-effort — the opportunistic prune is hygiene, never load-bearing.
        }
    }

    /**
     * 12‑hour {@link StoreFeedRefresh} hook: queue a full cache prune of the machine default cache
     * (when auto-prune is on), never double-queueing if one is already pending. If the engine is
     * already idle, drain immediately; otherwise the next {@link #maybeIdleBoundary} runs it.
     *
     * <p>Unlike {@link #maybeEnqueuePrune}, this path does <em>not</em> consult
     * {@code .last-pruned} — the 12 h wake is the cadence (so a busy CI box still gets a night-time
     * GC once quiet).
     */
    private void enqueueScheduledCacheGc() {
        if (shuttingDown) return;
        var config = cc.jumpkick.config.JkCacheConfig.resolve();
        if (config.autoPrune()) {
            Path cache = cc.jumpkick.util.JkDirs.cache();
            // compareAndSet: already queued → leave the existing entry alone (never double-queue).
            pendingPruneCache.compareAndSet(null, cache);
        }
        // Full 12 h workset when idle: prune + harvest wait + warmup, System.gc() last.
        // If a plan is running, only queue; maybeIdleBoundary drains on finish.
        if (activeBuildPlans.get() == 0) {
            pendingWarmupForce.compareAndSet(null, Boolean.FALSE);
            runIdleHousekeeping();
        } else {
            scheduleHostWarmupIfNeeded(false);
        }
    }

    /**
     * Run the queued opportunistic prune, if any, now that no plan is in flight. Runs on the
     * finishing request's connection thread or the 12 h feed-refresh thread when already idle; a
     * plan that starts concurrently wins the {@link #cacheGate} race and the prune stays queued
     * for the next boundary. Mirrors the legacy {@code --background} flags: sweep on, TTL/budget
     * from {@code [cache]} config, {@code.prune.lock} held, {@code.last-pruned} stamped.
     */
    private void drainPendingPrune() {
        Path cache = pendingPruneCache.getAndSet(null);
        if (cache == null) return;
        if (!cacheGate.writeLock().tryLock()) {
            pendingPruneCache.compareAndSet(null, cache); // a new plan raced in — retry next boundary
            return;
        }
        try (FileChannel lockChan =
                FileChannel.open(cache.resolve(".prune.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock pruneLock = lockChan.tryLock();
            if (pruneLock == null) return; // another process's prune is running — it'll stamp.last-pruned
            try {
                var config = cc.jumpkick.config.JkCacheConfig.resolve();
                cc.jumpkick.run.BuildPlan plan = cc.jumpkick.runtime.CachePlans.pruneBuildPlan(
                        cache, config.recordTtlDays(), false, false, false);
                cc.jumpkick.run.BuildPlanResult result = plan.run();
                if (result.success()) {
                    Files.writeString(
                            cache.resolve(cc.jumpkick.task.CachePruneScheduler.LAST_PRUNED_FILE),
                            Long.toString(clockMillis.getAsLong()),
                            StandardCharsets.UTF_8);
                    log.accept("jk engine: idle-boundary cache prune removed "
                            + plan.get(cc.jumpkick.runtime.CachePlans.FILES).orElse(0L)
                            + " files ("
                            + plan.get(cc.jumpkick.runtime.CachePlans.BYTES).orElse(0L)
                            + " bytes)");
                } else {
                    log.accept("jk engine: idle-boundary cache prune failed");
                }
            } finally {
                pruneLock.release();
            }
        } catch (Exception e) {
            log.accept("jk engine: idle-boundary cache prune failed: " + e.getMessage());
        } finally {
            cacheGate.writeLock().unlock();
        }
    }

    /**
     * Answer {@link EngineProtocol#EXEC_PLAN_REQUEST}: a complete execution plan (run/dev argv,
     * install layout, aot-cache layout) — the engine decides, the client executes. Synchronous,
     * read-only, inline.
     */
    private static final java.util.Set<String> DELEGATABLE = java.util.Set.of(
            EngineProtocol.BUILD_REQUEST,
            EngineProtocol.TEST_REQUEST,
            EngineProtocol.SINGLE_BUILD_REQUEST,
            EngineProtocol.COMPILE_REQUEST,
            EngineProtocol.NATIVE_REQUEST,
            EngineProtocol.TRAIN_REQUEST,
            EngineProtocol.IMAGE_REQUEST,
            EngineProtocol.INSTALL_REQUEST,
            EngineProtocol.PUBLISH_REQUEST);

    /**
     * If the project pins an older jk, run that version as a job child; refuse newer pins.
     * Same version/no pin → serve locally. Job children never re-delegate.
     */
    private boolean maybeDelegate(String requestLine, BufferedReader reader, BufferedWriter writer) {
        if (jobMode) return false; // a job child serves what it was handed — never re-routes
        String entryDir = cc.jumpkick.plugin.protocol.Jsonl.str(requestLine, "dir");
        if (entryDir == null) return false;
        String pin = EngineDelegate.pinnedVersionDiffering(Path.of(entryDir), version);
        if (pin == null) return false;
        if (EngineDelegate.pinIsNewer(pin, version)) {
            sendQuiet(
                    writer,
                    EngineProtocol.error(
                            EngineProtocol.ERR_VERSION_SKEW,
                            "this build pins jk " + pin + " but the engine is "
                                    + version + " — run that project's wrapper (./jk) or `jk self update` to upgrade;"
                                    + " the newer engine takes over without interrupting running builds"));
            return true;
        }
        try {
            EngineDelegate.runAsChild(pin, requestLine, reader, writer, paths.log(), log);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendQuiet(writer, EngineProtocol.requestFailed("interrupted delegating to jk " + pin));
        } catch (IOException e) {
            sendQuiet(writer, requestFailedLine(null, e));
        }
        return true;
    }

    // ---- hosted worker commands ---------------------------------------------------------------------

    // ---- hosted plan commands -------------------------------------------------------------------

    // ---- hosted long-tail commands ------------------------------------------------------------------

    /**
     * Reconstruct the request's {@link Session} from the flat config fields every lock/sync/update
     * request carries ({@code offline}/{@code force}/{@code verbose}, plus sync's {@code refresh})
     * — the same fields {@link cc.jumpkick.engine.verbs.WorkspaceBuildVerb} decodes inline.
     */
    private static Session resolveSession(String requestLine, Session.CancelToken cancelToken, boolean refresh) {
        Path entryDir = Path.of(Jsonl.str(requestLine, "dir"));
        Path cache = Path.of(Jsonl.str(requestLine, "cache"));
        JkConfig config = new JkConfig(
                Optional.empty(),
                Optional.of(Jsonl.bool(requestLine, "offline", false)),
                Optional.of(Jsonl.bool(requestLine, "rebuild", false)),
                Optional.empty(),
                Optional.empty(),
                Optional.of(Jsonl.bool(requestLine, "verbose", false)),
                Optional.empty(),
                Optional.of(Jsonl.bool(requestLine, "force", false) || refresh),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        return Session.defaults()
                .withConfig(config)
                .withWorkingDir(entryDir)
                .withCacheDir(cache)
                .withCancel(cancelToken)
                .withJvm(EngineProtocol.jvmTuning(requestLine))
                // The variant selection rides the session: every plan factory's Inputs defaults
                // from it, so compile/install/native/publish/... are parameterized generically.
                .withVariant(EngineProtocol.variantOf(requestLine), EngineProtocol.clientEnvOf(requestLine))
                .withAssemblyOverride(EngineProtocol.assemblyOverrideOf(requestLine));
    }

    /** The optional {@code repoUrl} request field ({@code --repo-url} overrides), or {@code null}. */
    private static java.net.URI repoUrlOf(String requestLine) {
        String s = Jsonl.str(requestLine, "repoUrl");
        return s != null ? java.net.URI.create(s) : null;
    }

    /** CLI workspace listener: JSONL on {@code writer} plus SSE / journal hooks. */
    private WorkspaceBuildListener wireListener(BufferedWriter writer, String workspaceDir) {
        return workspaceListener(workspaceDir, new WireEventSink(writer), writer);
    }

    /** HTTP/MCP workspace listener: hooks only — the dashboard has no CLI writer. */
    private WorkspaceBuildListener hubListener(String workspaceDir) {
        return workspaceListener(workspaceDir, NoopEventSink.INSTANCE, null);
    }

    private WorkspaceBuildListener workspaceListener(
            String workspaceDir, EventSink sink, java.io.BufferedWriter writer) {
        long eventRequestId = eventRequestId();
        if (eventRequestId > 0 && workspaceDir != null) putProgressRoot(eventRequestId, workspaceDir);
        return new BridgingWorkspaceListener(workspaceDir, sink, workspaceHooks(eventRequestId, writer));
    }

    private BridgingWorkspaceListener.Hooks workspaceHooks(long rid, java.io.BufferedWriter writer) {
        return new BridgingWorkspaceListener.Hooks() {
            @Override
            public void preflight(String stage, int done, int total) {
                if (rid > 0) {
                    progressTracker(rid).preflight(stage, done, total);
                    emitWorkspaceProgress(rid, writer, true);
                }
            }

            @Override
            public void workModel(cc.jumpkick.runtime.WorkModel model) {
                if (rid <= 0) return;
                putRemaining(rid, model.toRemainingWork());
                progressTracker(rid).seedWall(model.R0(), model.costs().size());
                emitWorkspaceProgress(rid, writer, true);
            }

            @Override
            public void recordWeight(String dir, long weight) {
                if (rid > 0) weightsOf(rid).put(dir, weight);
            }

            @Override
            public void planWeights(long totalWeight, int modules) {
                if (rid > 0) {
                    progressTracker(rid).calibrate(totalWeight, modules);
                    emitWorkspaceProgress(rid, writer, true);
                }
                publishPlan(rid, totalWeight);
            }

            @Override
            public void moduleGraph(java.util.Map<Path, java.util.Set<Path>> prereqs) {
                accModuleGraph(rid, prereqs);
            }

            @Override
            public void eta(long remainingMs) {
                publishEta(rid, remainingMs);
            }

            @Override
            public void moduleStarted(String dir, String coord) {
                publishModuleStart(rid, dir, coord);
            }

            @Override
            public void moduleFinished(ModuleOutcome o) {
                accModule(rid, o);
                publishModuleFinish(rid, o.dir().toString(), o.coord(), o.success(), o.millis(), o.didWork());
            }

            @Override
            public void trackModule(String dir, BuildPlanView view) {
                trackModuleBuildPlan(rid, dir, view, writer, false);
            }

            @Override
            public void trackModuleComplete(String dir, long lastDen) {
                EngineServer.this.trackModuleComplete(rid, dir, lastDen, writer);
            }

            @Override
            public BridgingPlanListener.Hooks planHooks(String dir) {
                return EngineServer.this.planHooks(rid, dir, writer, false);
            }

            @Override
            public void testsFrom(cc.jumpkick.run.BuildPlan plan) {
                accTests(
                        rid,
                        plan.get(cc.jumpkick.runtime.BuildPlanner.TEST_RESULT).orElse(null));
            }
        };
    }

    private BridgingPlanListener.Hooks planHooks(
            long rid, String dir, java.io.BufferedWriter writer, boolean releaseSlotOnFinish) {
        return new BridgingPlanListener.Hooks() {
            @Override
            public void planProgress(String d, BuildPlanView view) {
                publishBuildPlanProgress(rid, d, view);
            }

            @Override
            public void stepStarted(String d, String step, String phase) {
                publishStepStart(rid, d, step, phase);
            }

            @Override
            public void stepFinished(String d, String step, String phase, String status, long millis) {
                accStepFinish(rid, d, step, phase, status, millis);
                publishStepFinish(rid, d, step, phase, status, millis);
            }

            @Override
            public void labeled(String d, String step, String text) {
                publishLabel(rid, d, step, text);
            }

            @Override
            public void output(String d, String step, String line) {
                publishOutput(rid, d, step, line);
            }

            @Override
            public void planFinished(String d, BuildPlanResult result) {
                flushTimelineToClient(rid, writer);
                if (releaseSlotOnFinish) inFlightBuilds.release(rid);
                accBuildPlanFinish(rid, d, result);
                publishBuildPlanFinish(rid, d, result.success());
                if (!result.success()) publishDiagnostics(rid, d, result.errors());
            }
        };
    }

    /** The current thread's hosted-request id for dashboard events; {@code -1} outside a request. */
    private long eventRequestId() {
        Long id = currentEventRequestId.get();
        return id != null ? id : -1;
    }

    private void publishModuleStart(long requestId, String dir, String coord) {
        publishModuleStart(requestId, dir, coord, false);
    }

    private void publishModuleStart(long requestId, String dir, String coord, boolean dashboardOnly) {
        if (!eventsWanted()) return;
        publishEvent(
                "module-start",
                withProgress(
                        cc.jumpkick.engine.http.JsonOut.object()
                                .put("schema", 1)
                                .put("type", "module-start")
                                .put("requestId", requestId)
                                .put("dir", dir)
                                .put("coord", coord),
                        requestId),
                dashboardOnly);
    }

    private void publishModuleFinish(
            long requestId, String dir, String coord, boolean success, long millis, boolean didWork) {
        publishModuleFinish(requestId, dir, coord, success, millis, didWork, false);
    }

    private void publishModuleFinish(
            long requestId,
            String dir,
            String coord,
            boolean success,
            long millis,
            boolean didWork,
            boolean dashboardOnly) {
        if (!eventsWanted()) return;
        publishEvent(
                "module-finish",
                withProgress(
                        cc.jumpkick.engine.http.JsonOut.object()
                                .put("schema", 1)
                                .put("type", "module-finish")
                                .put("requestId", requestId)
                                .put("dir", dir)
                                .put("coord", coord)
                                .put("success", success)
                                .put("millis", millis)
                                .put("didWork", didWork),
                        requestId),
                dashboardOnly);
    }

    private void publishBuildPlanFinish(long requestId, String dir, boolean success) {
        if (!eventsWanted()) return;
        // Do not clear the workspace tracker here — modules finish many times per request.
        // Request teardown / finish owns final 100% and clearProgress.
        publishEvent(
                "buildplan-finish",
                withProgress(
                        cc.jumpkick.engine.http.JsonOut.object()
                                .put("schema", 1)
                                .put("type", "buildplan-finish")
                                .put("requestId", requestId)
                                .put("dir", dir)
                                .put("success", success),
                        requestId));
    }

    // ---- build-history journal capture (docs: state/builds) ---------------------

    /**
     * Open an accumulator for a journaled build-like kind (no-op for lock/format/tool/etc.). Always
     * on — even with history disabled the accumulator feeds the running {@link BuildMetrics}; only
     * the journal append itself is gated on {@code historyConfig.enabled}. See {@link
     * BuildHistoryKinds}.
     */
    private void registerAccumulator(long requestId, String kind, String dir, String trigger) {
        registerAccumulator(requestId, kind, dir, trigger, false, false, 0L, null);
    }

    private void registerAccumulator(long requestId, String kind, String dir, String trigger, boolean noTimeline) {
        registerAccumulator(requestId, kind, dir, trigger, noTimeline, false, 0L, null);
    }

    private void registerAccumulator(
            long requestId,
            String kind,
            String dir,
            String trigger,
            boolean noTimeline,
            boolean rebuild,
            long buildNumber,
            String journalId) {
        if (!BuildHistoryKinds.isBuildLike(kind)) return;
        Path projectDir = null;
        try {
            if (dir != null && !dir.isBlank()) projectDir = Path.of(dir);
        } catch (RuntimeException ignored) {
            projectDir = null;
        }
        ChromeTimeline timeline = ChromeTimeline.open(projectDir, noTimeline);
        putAccumulator(
                requestId,
                new BuildAccumulator(kind, dir, coordOf(dir), trigger, timeline, rebuild, buildNumber, journalId));
    }

    /** The project's {@code group:name}, or {@code null} when its {@code jk.toml} doesn't parse. */
    private static String coordOf(String dir) {
        try {
            var project = JkBuildParser.parse(Path.of(dir).resolve("jk.toml")).project();
            return project.group() + ":" + project.name();
        } catch (Exception e) {
            return null;
        }
    }

    private void accModule(long requestId, ModuleOutcome o) {
        BuildAccumulator a = accumulatorOf(requestId);
        if (a != null) a.addModule(o);
    }

    private void accModuleGraph(long requestId, java.util.Map<Path, java.util.Set<Path>> prereqs) {
        BuildAccumulator a = accumulatorOf(requestId);
        if (a != null) a.setModuleEdges(prereqs);
    }

    private void accBuildPlanFinish(long requestId, String dir, BuildPlanResult result) {
        BuildAccumulator a = accumulatorOf(requestId);
        if (a != null) a.addBuildPlan(dir, result);
    }

    /**
     * Record one finished step under its module dir — the same {@code stepFinish} signal the
     * dashboard renders, so the journal's per-module chains match the live cards exactly (a
     * workspace module's {@code BuildPlanResult.steps} isn't reliably populated, so we capture the
     * events directly).
     */
    private void accStepStart(long requestId, String dir, String step, String phase) {
        BuildAccumulator a = accumulatorOf(requestId);
        if (a != null) a.noteTaskStart(dir, step, phase);
    }

    private void accStepFinish(long requestId, String dir, String step, String phase, String status, long millis) {
        BuildAccumulator a = accumulatorOf(requestId);
        if (a != null) a.addTask(dir, step, phase, status, millis);
    }

    private void accTests(long requestId, TestSummary tests) {
        BuildAccumulator a = accumulatorOf(requestId);
        if (a != null && tests != null) a.addTests(tests);
    }

    private void accOutcome(long requestId, boolean success, int exitCode) {
        BuildAccumulator a = accumulatorOf(requestId);
        if (a != null) a.setOutcome(success, exitCode);
    }

    /**
     * The cache's estimated wall-clock benefit for a finished run (two-level critical path), or
     * {@code null} for a build that didn't succeed cleanly. Baselines come from {@link BuildMetrics}
     * as of before this run's own fold — a cache-hit step's cold cost is its prior successful-run
     * average (per-project tier, then the global-per-step tier); a step with no history contributes
     * ~0 and lowers coverage. See {@link CacheBenefit}.
     */
    private CacheBenefit.Result computeBenefit(BuildAccumulator a, long millis) {
        if (!a.succeeded() || a.wasCancelled()) return null;
        BuildMetrics metrics = BuildMetrics.load(metricsFile);
        java.util.function.BiFunction<String, String, java.util.OptionalLong> baseline = (dir, step) -> {
            java.util.Optional<BuildMetrics.Entry> e = metrics.step(dir, step).filter(x -> x.ok().count() > 0);
            if (e.isEmpty()) e = metrics.step("", step).filter(x -> x.ok().count() > 0);
            return e.map(x -> java.util.OptionalLong.of(x.ok().avgMillis())).orElse(java.util.OptionalLong.empty());
        };
        return CacheBenefit.compute(a.benefitModules(), a.benefitModuleEdges(), millis, baseline);
    }

    /**
     * Persist the finished build to the journal — ungated by dashboard subscribers, so every build
     * is captured whether or not a browser is watching, and survives an engine restart. Best-effort:
     * a failure here is logged, never propagated (journaling must not affect the build's outcome).
     */
    private void writeJournal(long requestId, boolean cancelled, long millis) {
        writeJournal(requestId, cancelled, millis, null);
    }

    private void writeJournal(long requestId, boolean cancelled, long millis, BufferedWriter writer) {
        BuildAccumulator a = takeAccumulator(requestId);
        if (a == null) return;
        try {
            long finishedAt = clockMillis.getAsLong();
            String commit = gitCommit(a.dir()); // best-effort short SHA of the project's HEAD
            // Prefer an explicit cancel stamp (BUILD_CANCEL / deadline / HTTP cancel) even if the
            // caller's cancelled flag lagged — truncated wall must never train ok history for ETA.
            boolean cancelledEffective = cancelled || a.wasCancelled();
            // Estimate the cache's wall-clock benefit from baselines as of BEFORE this run's fold.
            CacheBenefit.Result benefit = computeBenefit(a, millis);
            BuildRecord record = a.toRecord(finishedAt, cancelledEffective, millis, version, commit, benefit);
            // Start-time number only — per-run metrics.toml + MetricsHarvest train ETA aggregates.
            long buildNumber = a.buildNumber();
            if (buildNumber > 0) record = record.withBuildNumber(buildNumber);
            // Chrome timeline (web / late path): same step durations as metrics. Socket clients
            // usually already flushed via flushTimelineToClient before terminal events.
            a.flushTimeline().ifPresent(path -> {
                if (writer != null) sendQuiet(writer, EngineProtocol.timeline(path.toString()));
            });
            if (!historyConfig.enabled()) return;
            Path dir = Path.of(a.dir());
            // Snapshot paths mirror BuildLayout.markdownTestResults and the project's jk-lock.toml; each
            // is copied only if it exists at finish, so a skip-tests or lock-less build just omits it.
            BuildJournal.Snapshot snapshot = new BuildJournal.Snapshot(
                    dir.resolve("target").resolve("reports").resolve("test-results.md"),
                    cc.jumpkick.lock.LockPaths.lockFile(dir),
                    a.diagnosticsText());
            // Synthetic optimize/calibrate: do not leave a durable project home (JK-1390).
            if (record.synthetic()) {
                String jid = a.journalId();
                if (jid != null && !jid.isBlank()) {
                    journal.delete(jid, record.coord(), record.dir());
                }
                journal.purgeProject(record.coord(), record.dir());
                return;
            }
            String jid = a.journalId();
            if (jid != null && !jid.isBlank()) {
                // Complete the in-flight stub (same history id / build number) —.
                if (!journal.complete(jid, record, snapshot)) {
                    journal.append(record, snapshot);
                }
            } else {
                journal.append(record, snapshot);
            }
        } catch (RuntimeException e) {
            log.accept("jk engine: build journal append failed: " + e);
        }
    }

    /**
     * The project's git HEAD as a short SHA, or {@code null} when the dir isn't a git repo, git isn't
     * on PATH, or the call errors/times out. Best-effort and non-blocking-ish (1s cap): a commit stamp
     * is a nice-to-have on the history record, never worth failing or stalling journaling.
     */
    private static String gitCommit(String dir) {
        if (dir == null || dir.isEmpty()) return null;
        Process p = null;
        try {
            // stderr is discarded at the OS level and stdout drained on a side thread, so the 1s
            // cap actually holds. Reading stdout to EOF inline deadlocks on a repo where git is
            // chatty enough to fill its stderr pipe (dubious-ownership, many warnings): git can't
            // exit, stdout never sees EOF, and the waitFor below is never reached — on the journal
            // teardown path that hangs the whole request (JK-1473).
            p = new ProcessBuilder("git", "-C", dir, "rev-parse", "--short", "HEAD")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            Process proc = p;
            StringBuilder sb = new StringBuilder();
            Thread drainer = new Thread(
                    () -> {
                        try (var in = proc.getInputStream()) {
                            sb.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                        } catch (IOException ignored) {
                            // killed mid-read — no stamp
                        }
                    },
                    "jk-git-commit-probe");
            drainer.setDaemon(true);
            drainer.start();
            if (!p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            drainer.join(200);
            String out = sb.toString().trim();
            return p.exitValue() == 0 && !out.isEmpty() ? out : null;
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                if (p != null) p.destroyForcibly();
            }
            return null;
        }
    }

    /**
     * Map a finished run's record into the running-metrics input shape: the invocation outcome plus
     * every per-module step (workspace) and top-level step (single-plan, whose steps carry the
     * record's own dir). Keeps journal types out of {@code cc.jumpkick.runtime}.
     */
    private static BuildMetrics.Outcome toOutcome(BuildRecord r) {
        return toOutcome(r, false);
    }

    private static BuildMetrics.Outcome toOutcome(BuildRecord r, boolean rebuildFlag) {
        java.util.ArrayList<BuildMetrics.StepSample> steps = new java.util.ArrayList<>();
        for (BuildRecord.Task p : r.steps()) {
            steps.add(new BuildMetrics.StepSample(r.dir(), p.name(), p.status(), p.millis()));
        }
        for (BuildRecord.Module m : r.modules()) {
            for (BuildRecord.Task p : m.steps()) {
                steps.add(new BuildMetrics.StepSample(m.dir(), p.name(), p.status(), p.millis()));
            }
        }
        // shape-aware metrics key so rebuild vs incremental priors stay separate.
        // Prefer the accumulator's rebuild flag (set from the wire request) over ambient session
        // journal write often runs after SessionContext.where has exited.
        String kind = r.kind() == null ? "build" : r.kind();
        String dir = r.dir() == null ? "" : r.dir();
        if ("build".equals(kind) || kind.startsWith("build")) {
            boolean rebuild = rebuildFlag
                    || SessionContext.current().config().rebuildOr(false)
                    || SessionContext.current().config().forceOr(false);
            int dirty = r.modules() == null ? 0 : r.modules().size();
            // Single-module plan records often have empty modules list — treat as 1 when steps ran.
            if (dirty == 0 && r.steps() != null && !r.steps().isEmpty()) dirty = 1;
            var shape = new BuildService.HistoryShape(rebuild, dirty);
            kind = shape.kind();
            if (!dir.isEmpty()) dir = shape.dirKey(Path.of(dir));
        }
        return new BuildMetrics.Outcome(kind, dir, r.coord(), r.success(), r.cancelled(), r.millis(), steps);
    }

    /**
     * If worker AOT or host calibration is missing (or {@code force}), queue idle-boundary warmup.
     * Returns whether a warmup pass was actually queued.
     */
    private boolean scheduleHostWarmupIfNeeded(boolean force) {
        if (shuttingDown || draining) return false;
        if (!force && !HostWarmup.needsWork()) return false;
        pendingWarmupForce.updateAndGet(prev -> {
            if (prev == null) return force;
            return prev || force;
        });
        if (activeBuildPlans.get() == 0) {
            kickPendingWarmup(/* trailGc */ true);
        }
        return true;
    }

    /**
     * Background classload of resolve/PubGrub hot types after the endpoint is live. Does not run a
     * real lock (would need a store/project); only reduces first-lock classload latency.
     */
    private void scheduleResolveClassWarmup() {
        if (shuttingDown || draining) return;
        Thread.ofVirtual().name("jk-resolve-warmup").start(() -> {
            try {
                Class.forName("cc.jumpkick.resolver.pubgrub.PubGrubSolver");
                Class.forName("cc.jumpkick.resolver.pubgrub.PartialSolution");
                Class.forName("cc.jumpkick.resolver.MavenPackageSource");
                Class.forName("cc.jumpkick.resolver.LockOrchestrator");
                Class.forName("cc.jumpkick.repo.EffectivePomBuilder");
                Class.forName("cc.jumpkick.resolve.ResolveProcessCacheControl");
            } catch (ClassNotFoundException | LinkageError ignored) {
                // best-effort
            }
        });
    }

    /**
     * Drain queued host warmup on a daemon thread when no plan is in flight. When {@code
     * trailGc} is true, {@link System#gc()} runs only after warmup (and any nested chores) finish —
     * never mid-workset.
     */
    private void kickPendingWarmup(boolean trailGc) {
        if (shuttingDown || draining) return;
        if (activeBuildPlans.get() != 0) return;
        Boolean force = pendingWarmupForce.getAndSet(null);
        if (force == null) return;
        if (!warmupRunning.compareAndSet(false, true)) {
            pendingWarmupForce.updateAndGet(prev -> prev == null ? force : (prev || force));
            return;
        }
        Thread t = new Thread(
                () -> {
                    try {
                        if (activeBuildPlans.get() != 0) {
                            pendingWarmupForce.updateAndGet(prev -> prev == null ? force : (prev || force));
                            return;
                        }
                        // Re-drain prune that may have been queued while we waited to start.
                        drainPendingPrune();
                        try {
                            cc.jumpkick.builds.MetricsHarvest.get().awaitIdle(30_000L);
                        } catch (RuntimeException ignored) {
                        }
                        HostWarmup.runIdle(force, log);
                    } catch (RuntimeException e) {
                        log.accept("jk engine: idle host warmup failed: " + e.getMessage());
                    } finally {
                        // Trailing GC while still holding warmupRunning: a concurrent kick
                        // cannot start a fresh pass mid-GC (it re-queues and is drained below).
                        boolean more = pendingWarmupForce.get() != null;
                        if (trailGc && !more && activeBuildPlans.get() == 0) {
                            System.gc();
                        }
                        warmupRunning.set(false);
                        // Anything queued while we ran (or during the GC) gets its own pass.
                        if (pendingWarmupForce.get() != null && activeBuildPlans.get() == 0) {
                            kickPendingWarmup(trailGc);
                        }
                    }
                },
                "jk-idle-warmup");
        t.setDaemon(true);
        t.start();
    }

    /**
     * A {@code history-diag} replay line carrying the FULL persisted shape — the journal keeps
     * module/class/method/stack/snippet/worker (JK-1869) and replay must not flatten a failure
     * back to task+message (JK-1909).
     */
    public static String historyDiagLine(BuildRecord.Diag d) {
        var o = JsonOut.object()
                .put("type", EngineProtocol.HISTORY_DIAG)
                .put("severity", d.severity())
                .put("task", d.step())
                .put("code", d.code())
                .put("message", d.message())
                .put("test", d.test())
                .put("exceptionClass", d.exceptionClass());
        if (d.module() != null && !d.module().isEmpty()) o.put("module", d.module());
        if (d.engine() != null && !d.engine().isEmpty()) o.put("engine", d.engine());
        if (d.className() != null && !d.className().isEmpty()) o.put("class", d.className());
        if (d.method() != null && !d.method().isEmpty()) o.put("method", d.method());
        if (d.stack() != null && !d.stack().isEmpty()) o.put("stack", d.stack());
        if (d.file() != null && !d.file().isEmpty()) o.put("file", d.file());
        if (d.line() > 0) o.put("line", d.line());
        if (d.snippetStart() > 0) o.put("snippetStart", d.snippetStart());
        if (d.snippet() != null && !d.snippet().isEmpty()) o.putStrings("snippet", d.snippet());
        if (d.worker() > 0) o.put("worker", d.worker());
        return o.toString();
    }

    /**
     * Translate every {@link BuildPlanListener} callback for one plan into a {@code dir}-tagged wire
     * event. {@code realBuildPlan} is non-null only for {@link #runTest}/{@link #runSingleBuild} — its
     * {@code TEST_RESULT}/{@code BUILD_OUTCOME} keys (populated by the run-tests/parse-build steps)
     * ride along on the {@link EngineProtocol#BUILDPLAN_FINISH} message so the client can render its
     * summary line before it even sees the terminal message; {@code null} for a plain per-module
     * workspace-build plan (where neither applies at the module level).
     */
    private BuildPlanListener wireBuildPlanListener(
            String dir, BufferedWriter writer, cc.jumpkick.run.BuildPlan realBuildPlan) {
        // realBuildPlan non-null ⇒ single-project run: release the exclusive slot before the
        // terminal plan-finish so a reconnect is not rejected as already-running.
        return hostedPlanListener(
                dir,
                new WireEventSink(writer),
                writer,
                result -> encodePlanFinish(dir, realBuildPlan, result),
                realBuildPlan != null);
    }

    /**
     * As {@link #wireBuildPlanListener(String, BufferedWriter, cc.jumpkick.run.BuildPlan)}, but with a
     * pluggable terminal encoder: {@code finishEncoder} maps the finished {@link BuildPlanResult} to the
     * {@link EngineProtocol#BUILDPLAN_FINISH} message to send (after the {@link
     * EngineProtocol#BUILDPLAN_DIAGNOSTIC} burst) — how lock/update/sync ride their summary counts on the
     * same message the build/test plans already send.
     */
    private BuildPlanListener wireBuildPlanListener(
            String dir, BufferedWriter writer, java.util.function.Function<BuildPlanResult, String> finishEncoder) {
        return hostedPlanListener(dir, new WireEventSink(writer), writer, finishEncoder, false);
    }

    /** HTTP lock: same hooks as CLI, no JSONL writer. */
    private BuildPlanListener singleBuildPlanHubListener(String dir) {
        return hostedPlanListener(dir, NoopEventSink.INSTANCE, null, null, false);
    }

    private BuildPlanListener hostedPlanListener(
            String dir,
            EventSink sink,
            java.io.BufferedWriter writer,
            java.util.function.Function<BuildPlanResult, String> finishEncoder,
            boolean releaseSlotOnFinish) {
        return new CoalescingBuildPlanListener(new BridgingPlanListener(
                dir, sink, planHooks(eventRequestId(), dir, writer, releaseSlotOnFinish), finishEncoder));
    }

    /**
     * Terminal {@code plan-finish} for a single-project build/test. Wire {@code cancelled} is
     * user/deadline cancel only — {@link BuildPlanResult#cancelled()} is also set on cooperative
     * fail-fast and must not look like the user cancelled the job.
     */
    private static String encodePlanFinish(
            String dir, cc.jumpkick.run.BuildPlan realBuildPlan, BuildPlanResult result) {
        TestSummary testResult = realBuildPlan == null
                ? null
                : realBuildPlan
                        .get(cc.jumpkick.runtime.BuildPlanner.TEST_RESULT)
                        .orElse(null);
        String buildOutcome = realBuildPlan == null
                ? null
                : realBuildPlan
                        .get(cc.jumpkick.runtime.BuildPlanner.BUILD_OUTCOME)
                        .orElse(null);
        boolean cancelled = result.userCancelled();
        if (testResult == null && buildOutcome == null) {
            return EngineProtocol.planFinish(dir, result.success(), cancelled);
        }
        return EngineProtocol.withCancelled(
                EngineProtocol.planFinish(
                        dir,
                        result.success(),
                        buildOutcome,
                        testResult != null ? testResult.total() : -1,
                        testResult != null ? testResult.succeeded() : -1,
                        testResult != null ? testResult.failed() : -1,
                        testResult != null ? testResult.skipped() : -1),
                cancelled);
    }

    /** Write chrome timeline (if any) and notify the socket client. Idempotent per request. */
    private void flushTimelineToClient(long requestId, BufferedWriter writer) {
        BuildAccumulator a = accumulatorOf(requestId);
        if (a == null) return;
        a.flushTimeline().ifPresent(path -> {
            if (writer != null) sendQuiet(writer, EngineProtocol.timeline(path.toString()));
        });
    }

    /** Best-effort send: a write failure means the client is gone — nothing more to do for this event. */
    private static void sendQuiet(BufferedWriter writer, String line) {
        try {
            send(writer, line);
        } catch (IOException ignored) {
            // the cancel-watching read loop will notice the same disconnect and cancel the build
        }
    }

    static String redactEnv(String dir, String text) {
        return EventRedaction.redactEnv(dir, text);
    }

    static cc.jumpkick.run.TestFailureInfo redactFailure(String dir, cc.jumpkick.run.TestFailureInfo f) {
        return EventRedaction.redactFailure(dir, f);
    }

    /** {@link EngineProtocol#requestFailed} with {@code .env} values masked. */
    private static String requestFailedLine(String dir, Throwable e) {
        return EngineProtocol.requestFailed(redactEnv(dir, String.valueOf(e.getMessage())));
    }

    private static String requestFailedLine(String dir, String message) {
        return EngineProtocol.requestFailed(redactEnv(dir, message));
    }

    /**
     * Heartbeat interval while an async job runs. Default 30s; {@code 0} disables.
     * Env: {@code JK_ENGINE_HEARTBEAT_MS}.
     */
    static long jobHeartbeatMs() {
        return JobEnvelope.jobHeartbeatMs();
    }

    /**
     * Optional per-request wall deadline /. Default {@code 0} = off (huge
     * monorepos). Env: {@code JK_ENGINE_JOB_DEADLINE_MS}. When set, the engine cancels the job,
     * {@code destroyForcibly}s registered worker processes, interrupts the runner, and bounds the
     * connection join to deadline + {@link #jobDeadlineGraceMs}.
     */
    static long jobDeadlineMs() {
        return JobEnvelope.jobDeadlineMs();
    }

    /**
     * Grace after the wall deadline for the runner to unwind after worker kill. Default
     * 30s. Env: {@code JK_ENGINE_JOB_DEADLINE_GRACE_MS}.
     */
    static long jobDeadlineGraceMs() {
        return JobEnvelope.jobDeadlineGraceMs();
    }

    private void onConnectionFinished() {
        activeConnections.decrementAndGet();
    }

    /** Start embedded HTTP when {@code [http]} is present; bind failure is advisory only. */
    private void startHttpIfEnabled() {
        if (httpConfig == null) return;
        HttpEngineServer candidate = new HttpEngineServer(
                httpConfig,
                httpConfig.webRootPath(),
                paths.httpToken(),
                paths.log(),
                version,
                this::statusSnapshot,
                httpEvents,
                httpJobs(),
                journal,
                () -> BuildMetrics.load(metricsFile).entries(),
                // Single-flight + 30s TTL: dashboard SSE reconnect + GET /api/cache must not each
                // exclusive-walk multi-GiB stores (SerialGC balloons committed heap ~90 MiB).
                cc.jumpkick.engine.http.CacheSnapshot.memoizing(cc.jumpkick.util.JkDirs.cache()),
                log);
        // Hard-refresh mid-build: history rows carry live requestId/progress/phases; SSE connect
        // delivers one compact run-snapshot per job to the new subscription only.
        candidate.setLiveRunSupport(this::liveRunsSnapshot, this::rehydrateLiveRunsOnSseConnect);
        // Combined-connection peak observed at every admission point (UDS accept bumps it too) —
        // not only when a status snapshot happens to run (JK-1861).
        candidate.setOnSseAdmitted(() -> peakActiveConnections.accumulateAndGet(liveConnectionCount(), Math::max));
        try {
            candidate.start();
            Files.writeString(paths.http(), candidate.url());
            httpServer = candidate;
            log.accept("jk engine: http listening on " + candidate.url());
        } catch (IOException | RuntimeException e) {
            candidate.close();
            httpError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.accept("jk engine: http failed to start (" + httpError + ") — continuing without http");
        }
    }

    /** Snapshot of in-flight holds for dashboard history enrichment (progress + phases + ETA). */
    private java.util.List<cc.jumpkick.engine.http.HttpEngineServer.LiveRun> liveRunsSnapshot() {
        java.util.List<cc.jumpkick.engine.http.HttpEngineServer.LiveRun> out = new java.util.ArrayList<>();
        for (InFlightBuilds.Hold h : inFlightBuilds.list()) {
            Double p = lastProgressOf(h.requestId());
            long remainingMs = -1L;
            long r0Ms = -1L;
            long num = 0L;
            long den = 0L;
            cc.jumpkick.runtime.WorkspaceProgressTracker tracker = trackerOrNull(h.requestId());
            if (tracker != null) {
                var snap = tracker.snapshot();
                remainingMs = snap.remainingMs();
                r0Ms = snap.R0ms();
                num = snap.numerator();
                den = snap.denominator();
                if ((p == null || p.isNaN()) && snap.hasPercent()) p = snap.percent();
            }
            BuildAccumulator acc = accumulatorOf(h.requestId());
            BuildAccumulator.MidFlight mid = acc != null
                    ? acc.midFlight()
                    : new BuildAccumulator.MidFlight(java.util.List.of(), java.util.List.of());
            out.add(new cc.jumpkick.engine.http.HttpEngineServer.LiveRun(
                    h.requestId(),
                    h.buildNumber(),
                    h.kind(),
                    h.dir(),
                    h.coord(),
                    h.startedAt(),
                    p != null && !p.isNaN() ? p : Double.NaN,
                    h.journalId(),
                    remainingMs,
                    r0Ms,
                    num,
                    den,
                    mid.modules(),
                    mid.tasks()));
        }
        return out;
    }

    /**
     * After a new dashboard SSE subscription: deliver one compact {@code run-snapshot} per
     * in-flight job to <em>that subscription only</em>. A phase-by-phase replay filled the
     * 256-frame SSE queue and left the SPA frozen for seconds while live progress/ETA sat
     * behind the backlog; one snapshot keeps connect O(jobs) and leaves the wire free for
     * real-time ticks (parity with the TUI).
     */
    private void rehydrateLiveRunsOnSseConnect(cc.jumpkick.engine.http.HttpEvents.Subscription sub) {
        if (sub == null || httpEvents == null) return;
        // Write side of the connect ordering lock (JK-1837): capture + deliver + attach are
        // atomic w.r.t. every publishEvent, so no event can fall between the snapshot and the
        // subscription queue. The subscription is detached until attach() below.
        sseConnect.writeLock().lock();
        try {
            for (cc.jumpkick.engine.http.HttpEngineServer.LiveRun run : liveRunsSnapshot()) {
                httpEvents.deliverTo(sub, "run-snapshot", liveRunSnapshotJson(run, clockMillis.getAsLong()));
            }
            httpEvents.attach(sub);
        } finally {
            sseConnect.writeLock().unlock();
        }
    }

    /** Compact mid-flight JSON for the SPA {@code run-snapshot} fold (same shape as enriched history). */
    private static cc.jumpkick.engine.http.JsonOut liveRunSnapshotJson(
            cc.jumpkick.engine.http.HttpEngineServer.LiveRun run, long serverNow) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("schema", 1);
        m.put("type", "run-snapshot");
        m.put("requestId", run.requestId());
        m.put("jid", run.requestId());
        m.put("kind", run.kind() == null ? "build" : run.kind());
        m.put("dir", run.dir() == null ? "" : run.dir());
        if (run.coord() != null) m.put("coord", run.coord());
        // Guard like history enrichment: startedAt 0 (hold not yet registered) must not reach
        // the SPA's elapsed clock as "now - 0" (JK-1846).
        if (run.startedAt() > 0) {
            m.put("startedAt", run.startedAt());
            // Engine "now": lets the SPA compute skew-free elapsed and re-anchor to its own
            // clock (JK-1839).
            m.put("serverNow", serverNow);
        }
        if (run.dir() != null && !run.dir().isBlank()) {
            // Same project linkage the live request-start carries (JK-1846).
            m.put("projectId", cc.jumpkick.runtime.ProjectIds.idOf(run.dir()));
        }
        m.put("running", true);
        if (run.buildNumber() > 0) m.put("buildNumber", run.buildNumber());
        if (run.journalId() != null && !run.journalId().isBlank()) m.put("historyId", run.journalId());
        if (!Double.isNaN(run.progress())) m.put("progress", run.progress());
        if (run.remainingMs() >= 0) m.put("remainingMs", run.remainingMs());
        if (run.r0Ms() > 0) m.put("R0", run.r0Ms());
        if (run.denominator() > 0) {
            m.put("numerator", run.numerator());
            m.put("denominator", run.denominator());
        }
        if (!run.modules().isEmpty()) {
            java.util.List<Object> mods =
                    new java.util.ArrayList<>(run.modules().size());
            for (var mod : run.modules()) {
                java.util.Map<String, Object> mm = new java.util.LinkedHashMap<>();
                mm.put("dir", mod.dir() == null ? "" : mod.dir());
                if (mod.coord() != null) mm.put("coord", mod.coord());
                // Explicit lifecycle bit (JK-1846) — see HttpEngineServer.liveModulesJson.
                mm.put("finished", mod.finished());
                mm.put("success", mod.finished() && mod.success());
                mm.put("millis", mod.millis());
                if (mod.finished()) mm.put("didWork", mod.didWork());
                java.util.List<Object> tasks =
                        new java.util.ArrayList<>(mod.tasks().size());
                for (var t : mod.tasks()) {
                    java.util.Map<String, Object> tm = new java.util.LinkedHashMap<>();
                    tm.put("name", t.name());
                    tm.put("stage", t.stage() == null ? "" : t.stage());
                    tm.put("status", t.status() == null ? "RUN" : t.status());
                    tm.put("millis", t.millis());
                    tasks.add(tm);
                }
                mm.put("tasks", tasks);
                mods.add(mm);
            }
            m.put("modules", mods);
        } else if (!run.tasks().isEmpty()) {
            java.util.List<Object> tasks = new java.util.ArrayList<>(run.tasks().size());
            for (var t : run.tasks()) {
                java.util.Map<String, Object> tm = new java.util.LinkedHashMap<>();
                tm.put("name", t.name());
                tm.put("stage", t.stage() == null ? "" : t.stage());
                tm.put("status", t.status() == null ? "RUN" : t.status());
                tm.put("millis", t.millis());
                tasks.add(tm);
            }
            m.put("tasks", tasks);
        }
        return cc.jumpkick.engine.http.JsonOut.rawObject(m);
    }

    private cc.jumpkick.engine.http.EngineHttpJobs httpJobs() {
        return new cc.jumpkick.engine.http.EngineHttpJobs() {
            @Override
            public long triggerBuild(String dir) {
                return triggerHttpWorkspace(dir, "build", /* skipTests */ false, /* testOnly */ false);
            }

            @Override
            public long triggerTest(String dir) {
                // True test-only: same graph as build, each module uses testOnly plans (no package).
                return triggerHttpWorkspace(dir, "test", /* skipTests */ false, /* testOnly */ true);
            }

            @Override
            public long triggerLock(String dir) {
                return triggerHttpLock(dir);
            }

            @Override
            public boolean cancel(long requestId) {
                return cancelJob(requestId);
            }
        };
    }

    /** {@code POST /api/build} / MCP: same JobEnvelope as CLI, FireAndForget. */
    private long triggerHttpWorkspace(String dirStr, String kind, boolean skipTests, boolean testOnly) {
        Path entryDir = cc.jumpkick.util.PathUtil.resolveUserPath(dirStr);
        if (!Files.isRegularFile(entryDir.resolve("jk.toml"))) {
            throw new IllegalArgumentException("no jk.toml in " + entryDir);
        }
        String line = "{\"type\":\"build-request\",\"dir\":"
                + cc.jumpkick.plugin.protocol.Jsonl.quote(entryDir.toString())
                + ",\"trigger\":\"web\"}";
        JobRequest req = JobRequest.workspace(
                kind,
                "jk-engine-http-" + kind + "-",
                (l, tok, w) -> runHttpWorkspace(entryDir, skipTests, testOnly, tok));
        return jobs.submitAsync(line, req, BuildJobFingerprint.ofHttp(kind, entryDir, skipTests, testOnly));
    }

    private long triggerHttpLock(String dirStr) {
        Path entryDir = cc.jumpkick.util.PathUtil.resolveUserPath(dirStr);
        if (!Files.isRegularFile(entryDir.resolve("jk.toml"))) {
            throw new IllegalArgumentException("no jk.toml in " + entryDir);
        }
        String line = "{\"type\":\"lock-request\",\"dir\":"
                + cc.jumpkick.plugin.protocol.Jsonl.quote(entryDir.toString())
                + ",\"trigger\":\"web\"}";
        return jobs.submitAsync(
                line, JobRequest.plan("lock", "jk-engine-http-lock-", (l, tok, w) -> runHttpLock(entryDir, tok)), "");
    }

    /** Workspace build/test body for HTTP/MCP — hub-only events. */
    private boolean runHttpWorkspace(
            Path entryDir, boolean skipTests, boolean testOnly, Session.CancelToken cancelToken) {
        try {
            JkBuild entryBuild = JkBuildParser.parse(entryDir.resolve("jk.toml"));
            Path cache = cc.jumpkick.util.JkDirs.cache();
            Path jdksDir = cc.jumpkick.util.JkDirs.jdks();
            WorkspaceRequest req = new WorkspaceRequest(
                            entryDir,
                            entryBuild,
                            cache,
                            jdksDir,
                            Runtime.getRuntime().availableProcessors(), // the shared plan's own worst-case cap
                            null,
                            skipTests,
                            false,
                            0,
                            null, // engine forecasts dirty modules
                            false, // this engine plans memory once at startup, not per request
                            true) // auto-freshen a stale lock, like jk build
                    .withTestOnly(testOnly);
            Session session = Session.defaults()
                    .withWorkingDir(entryDir)
                    .withCacheDir(cache)
                    .withJdksDir(jdksDir)
                    .withCancel(cancelToken);
            long rid = eventRequestId();
            if (rid > 0) putProgressRoot(rid, entryDir.toString());
            WorkspaceResult result = SessionContext.where(
                    session, () -> BuildService.buildWorkspace(req, hubListener(entryDir.toString())));
            accOutcome(rid, result.success(), result.exitCode());
            if (rid > 0) {
                if (result.success()) progressTracker(rid).finish();
                emitWorkspaceProgress(rid, null, true);
            }
            if (!result.success()) {
                for (String error : result.errors().stream().limit(5).toList()) {
                    publishRequestError(rid, entryDir.toString(), error);
                }
            }
            return result.success();
        } catch (Exception e) {
            // The engine log is served by GET /api/log — redact like every other exiting channel.
            accOutcome(eventRequestId(), false, 1);
            log.accept("jk engine: http-triggered job of " + entryDir + " failed: "
                    + redactEnv(entryDir.toString(), String.valueOf(e.getMessage())));
            publishRequestError(eventRequestId(), entryDir.toString(), String.valueOf(e.getMessage()));
            return false;
        }
    }

    private boolean runHttpLock(Path entryDir, Session.CancelToken cancelToken) {
        try {
            Path cache = cc.jumpkick.util.JkDirs.cache();
            // Same scope rule as the JSONL lock cascade: a workspace member redirects to its root
            // and locks the merged union — a module-scoped resolution must never overwrite the
            // root jk-lock.toml.
            var scope = cc.jumpkick.runtime.LockPlans.lockScope(entryDir);
            Path lockDir = scope.lockDir();
            Session session = Session.defaults()
                    .withWorkingDir(lockDir)
                    .withCacheDir(cache)
                    .withCancel(cancelToken);
            cc.jumpkick.run.BuildPlan plan = cc.jumpkick.runtime.LockPlans.lockBuildPlan(
                    lockDir,
                    scope.effective(),
                    cache,
                    null,
                    java.util.List.of(),
                    true,
                    false,
                    ResolveObserver.NOOP,
                    null);
            plan.addListener(singleBuildPlanHubListener(lockDir.toString()));
            cc.jumpkick.run.BuildPlanResult result;
            // Serialize per lock dir with every other lock entry point (JK-1356).
            synchronized (cc.jumpkick.runtime.LockGate.monitorFor(lockDir)) {
                result = SessionContext.where(session, plan::run);
            }
            accOutcome(eventRequestId(), result.success(), result.success() ? 0 : 1);
            if (!result.success()) {
                for (var d : result.errors().stream().limit(5).toList()) {
                    publishRequestError(eventRequestId(), entryDir.toString(), d.message());
                }
            }
            return result.success();
        } catch (Exception e) {
            accOutcome(eventRequestId(), false, 1);
            log.accept("jk engine: http-triggered lock of " + entryDir + " failed: "
                    + redactEnv(entryDir.toString(), String.valueOf(e.getMessage())));
            publishRequestError(eventRequestId(), entryDir.toString(), String.valueOf(e.getMessage()));
            return false;
        }
    }

    private cc.jumpkick.engine.http.StatusSnapshot statusSnapshot() {
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
    public void aotTrainerSpawner(java.util.function.Supplier<Process> spawner) {
        aot.spawner(spawner);
    }

    /**
     * Spawn and adopt the sidecar AOT trainer. Best-effort: a trainer that fails to start (or
     * never finishes) costs a log line, never the engine. The trainer self-terminates in seconds;
     * the timeout is a belt against a hung child, generous enough to never fire on a healthy one.
     */
    /** Whether the endpoint pointer still names this generation's socket. */
    private boolean endpointNamesThisEngine() {
        if (active == null) return false;
        try {
            Path ep = EnginePaths.endpoint(paths);
            if (!Files.isRegularFile(ep)) return false;
            return active.socket()
                    .getFileName()
                    .toString()
                    .equals(Files.readString(ep).trim());
        } catch (IOException e) {
            return false;
        }
    }

    /** Caller-facing graceful stop — same effect as receiving a {@link EngineProtocol#SHUTDOWN} message. */
    @Override
    public void close() {
        synchronized (lifecycleLock) {
            shuttingDown = true;
            closeServerChannelQuietly();
        }
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
     * Release the Web UI port the moment this engine becomes a lame duck. The successor generation is
     * already taking over HTTP, so there is no reason to hold the fixed port while in-flight jobs
     * drain. Without this the port stayed bound until {@link #cleanup} at final exit, and a busy or
     * dashboard-connected drain (the SSE stream forces the full stop-grace) could outlast the
     * successor's bind-retry window — surfacing as "Web UI: failed to start (Address already in use)".
     * Idempotent: cleanup's later close is then a no-op.
     */
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
        HttpEngineServer h = httpServer;
        return h == null || h.liveEventStreams() == 0;
    }

    private void stopHttpQuietly() {
        HttpEngineServer h = httpServer;
        if (h != null) h.stopNow();
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
        if (httpServer != null) httpServer.close();
        deleteQuietly(paths.http()); // the live bound-URL file — stale once we stop
        // The http token is deliberately NOT deleted: it persists across restarts so an open
        // dashboard tab survives an upgrade/crash respawn. `jk engine rotate-token`
        // is the explicit way to invalidate it.
        if (connectionExecutor != null) connectionExecutor.shutdown();
        try {
            if (connectionExecutor != null) connectionExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (active != null) {
            deleteQuietly(active.socket());
            deleteQuietly(active.token());
            deleteQuietly(active.pid());
            // Retire the endpoint only if it still names US — a takeover successor owns it
            // now and must not be un-pointed by the lame duck's exit.
            try {
                Path ep = EnginePaths.endpoint(paths);
                String mine = active.socket().getFileName().toString();
                if (Files.isRegularFile(ep) && mine.equals(Files.readString(ep).trim())) {
                    deleteQuietly(ep);
                }
            } catch (IOException ignored) {
                // best-effort
            }
            try {
                if (genLock != null) genLock.release();
                if (genLockChannel != null) genLockChannel.close();
            } catch (IOException ignored) {
                // process exit releases it regardless
            }
            deleteQuietly(active.lock());
        }
        releaseStartupLock();
        deleteQuietly(paths.lock()); // the transient startup mutex file
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // best-effort cleanup — a leftover file is harmless (recreated/overwritten next start)
        }
    }

    private void writePidFile() throws IOException {
        Files.writeString(active.pid(), pid + "\n" + startedAtMillis + "\n", StandardCharsets.UTF_8);
    }

    /**
     * Size the shared worker-JVM memory plan once for the process (core-count concurrency). Hosted
     * builds pass {@code applyMemoryPlan=false} so concurrent requests do not overwrite it.
     */
    private void planSharedWorkerMemoryOnce() {
        // requested concurrency from [engine] jobs / JK_JOBS (default = cores).
        int cap = cc.jumpkick.config.Jobs.resolve(cc.jumpkick.config.JkEngineConfig.resolve());
        JvmOptions.planAndApply(HeapPlan.requestedJvms(cap, 1, false, cap));
    }

    private static void send(BufferedWriter writer, String line) throws IOException {
        // Heartbeat + plan workers may write concurrently.
        synchronized (writer) {
            writer.write(line);
            writer.write('\n');
            writer.flush();
        }
    }

    private static void closeQuietly(SocketChannel ch) {
        try {
            ch.close();
        } catch (IOException ignored) {
            // best-effort
        }
    }

    static boolean resolveCancelledFlag(Boolean successStamp, boolean userCancelled, boolean cancelHint) {
        return BuildAccumulator.resolveCancelledFlag(successStamp, userCancelled, cancelHint);
    }

    private final class VerbBridge implements VerbHost {
        @Override
        public long eventRequestId() {
            return EngineServer.this.eventRequestId();
        }

        @Override
        public void putProgressRoot(long rid, String dir) {
            EngineServer.this.putProgressRoot(rid, dir);
        }

        @Override
        public WorkspaceBuildListener workspaceListener(BufferedWriter writer, String dir) {
            return wireListener(writer, dir);
        }

        @Override
        public BuildPlanListener planListener(String dir, BufferedWriter writer, cc.jumpkick.run.BuildPlan plan) {
            return wireBuildPlanListener(dir, writer, plan);
        }

        @Override
        public BuildPlanListener planListener(
                String dir, BufferedWriter writer, java.util.function.Function<BuildPlanResult, String> finishEncoder) {
            return wireBuildPlanListener(dir, writer, finishEncoder);
        }

        @Override
        public void releaseExclusiveSlot() {
            EngineServer.this.releaseExclusiveSlot();
        }

        @Override
        public boolean effectiveCancelled(long rid, boolean tokenCancelled) {
            return jobs.effectiveCancelled(rid, tokenCancelled);
        }

        @Override
        public void accOutcome(long rid, boolean success, int exit) {
            EngineServer.this.accOutcome(rid, success, exit);
        }

        @Override
        public void accTests(long rid, TestSummary tests) {
            EngineServer.this.accTests(rid, tests);
        }

        @Override
        public void finishProgress(long rid) {
            progressTracker(rid).finish();
        }

        @Override
        public void emitWorkspaceProgress(long rid, BufferedWriter writer, boolean force) {
            EngineServer.this.emitWorkspaceProgress(rid, writer, force);
        }

        @Override
        public void flushTimeline(long rid, BufferedWriter writer) {
            flushTimelineToClient(rid, writer);
        }

        @Override
        public void send(BufferedWriter writer, String line) throws IOException {
            EngineServer.send(writer, line);
        }

        @Override
        public void sendQuiet(BufferedWriter writer, String line) {
            EngineServer.sendQuiet(writer, line);
        }

        @Override
        public String redactEnv(String dir, String text) {
            return EngineServer.redactEnv(dir, text);
        }

        @Override
        public String requestFailedLine(String dir, Throwable e) {
            return EngineServer.requestFailedLine(dir, e);
        }

        @Override
        public void publishRequestError(long rid, String dir, String message) {
            EngineServer.this.publishRequestError(rid, dir, message);
        }

        @Override
        public Session resolveSession(String requestLine, Session.CancelToken cancel, boolean refresh) {
            return EngineServer.resolveSession(requestLine, cancel, refresh);
        }

        @Override
        public void maybeEnqueuePrune(Path cache) {
            EngineServer.this.maybeEnqueuePrune(cache);
        }

        @Override
        public java.util.concurrent.locks.ReentrantReadWriteLock cacheGate() {
            return cacheGate;
        }

        @Override
        public int activePlanCount() {
            return activeBuildPlans.get();
        }

        @Override
        public long nowMillis() {
            return clockMillis.getAsLong();
        }

        @Override
        public boolean scheduleHostWarmup(boolean force) {
            return scheduleHostWarmupIfNeeded(force);
        }

        @Override
        public cc.jumpkick.engine.journal.BuildJournal journal() {
            return journal;
        }

        @Override
        public JkHistoryConfig historyConfig() {
            return historyConfig;
        }

        @Override
        public Path metricsFile() {
            return metricsFile;
        }

        @Override
        public InFlightBuilds inFlightBuilds() {
            return inFlightBuilds;
        }

        @Override
        public Double lastProgress(long requestId) {
            return lastProgressOf(requestId);
        }
    }

    private final class EnvelopeHost implements JobEnvelope.Host {
        @Override
        public boolean tryStartBuildPlan() {
            return EngineServer.this.tryStartBuildPlan();
        }

        @Override
        public void abandonBuildPlanSlot() {
            EngineServer.this.abandonBuildPlanSlot();
        }

        @Override
        public void noteBuildPlanFinished() {
            EngineServer.this.noteBuildPlanFinished();
        }

        @Override
        public boolean draining() {
            return draining;
        }

        @Override
        public long nextRequestId() {
            return requestIds.incrementAndGet();
        }

        @Override
        public long nowMillis() {
            return clockMillis.getAsLong();
        }

        @Override
        public void putMode(long id, cc.jumpkick.runtime.progress.ProgressBarMode mode) {
            EngineServer.this.putMode(id, mode);
        }

        @Override
        public void publishRequestStart(long id, String kind, String dir, long buildNumber) {
            EngineServer.this.publishRequestStart(id, kind, dir, buildNumber);
        }

        @Override
        public void registerAccumulator(
                long id,
                String kind,
                String dir,
                String trigger,
                boolean noTimeline,
                boolean rebuild,
                long buildNumber,
                String journalId) {
            EngineServer.this.registerAccumulator(id, kind, dir, trigger, noTimeline, rebuild, buildNumber, journalId);
        }

        @Override
        public java.util.concurrent.locks.ReentrantReadWriteLock cacheGate() {
            return cacheGate;
        }

        @Override
        public void bindEventRequestId(long id) {
            currentEventRequestId.set(id);
        }

        @Override
        public void unbindEventRequestId() {
            currentEventRequestId.remove();
        }

        @Override
        public cc.jumpkick.task.IoLedger runIo(long id) {
            return EngineServer.this.runIo(id);
        }

        @Override
        public InFlightBuilds inFlight() {
            return inFlightBuilds;
        }

        @Override
        public BuildAccumulator accumulatorOf(long id) {
            return EngineServer.this.accumulatorOf(id);
        }

        @Override
        public void putLastProgress(long id, double percent) {
            EngineServer.this.putLastProgress(id, percent);
        }

        @Override
        public int activeBuildPlans() {
            return activeBuildPlans.get();
        }

        @Override
        public JsonOut withProgress(JsonOut payload, long id) {
            return EngineServer.this.withProgress(payload, id);
        }

        @Override
        public JsonOut withIo(JsonOut payload, long id) {
            return EngineServer.this.withIo(payload, id);
        }

        @Override
        public void publishEvent(String type, JsonOut payload) {
            EngineServer.this.publishEvent(type, payload);
        }

        @Override
        public void clearProgress(long id) {
            EngineServer.this.clearProgress(id);
        }

        @Override
        public void writeJournal(long id, boolean cancelled, long millis, java.io.BufferedWriter writer) {
            EngineServer.this.writeJournal(id, cancelled, millis, writer);
        }

        @Override
        public void maybeIdleBoundary() {
            EngineServer.this.maybeIdleBoundary();
        }

        @Override
        public void maybeIdleGc() {
            EngineServer.this.maybeIdleGc();
        }

        @Override
        public void log(String message) {
            EngineServer.this.log.accept(message);
        }

        @Override
        public String version() {
            return version;
        }

        @Override
        public JkHistoryConfig historyConfig() {
            return historyConfig;
        }

        @Override
        public BuildJournal journal() {
            return journal;
        }

        @Override
        public String coordOf(String dir) {
            return EngineServer.coordOf(dir);
        }
    }
}
