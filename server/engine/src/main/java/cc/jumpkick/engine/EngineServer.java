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
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.engine.plugin.HeapPlan;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.engine.plugin.MemoryProbe;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.protocol.Jsonl;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.BuildPlanView;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.BuildMetrics;
import cc.jumpkick.runtime.BuildService;
import cc.jumpkick.runtime.CacheBenefit;
import cc.jumpkick.runtime.ChromeTimeline;
import cc.jumpkick.runtime.ExplainPlan;
import cc.jumpkick.runtime.ModuleOutcome;
import cc.jumpkick.runtime.ModulePlan;
import cc.jumpkick.runtime.PreflightMemo;
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
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Resident engine server: single-instance election, socket accept loop, and hosted operations
 * (workspace/single builds, tests, explain) each on their own connection/{@link Session} with
 * pipeline events streamed over the wire. Runs until explicit stop or drain.
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

    private final Object lifecycleLock = new Object();
    private final AtomicInteger activeConnections = new AtomicInteger();
    /** High-water marks for concurrent load instrumentation (ticket-1015). */
    private final AtomicInteger peakActiveConnections = new AtomicInteger();

    /**
     * Sidecar AOT trainer spawner/process. Spawned only after winning election; reaped on exit.
     * Clients never talk to it.
     */
    private volatile java.util.function.Supplier<Process> aotTrainerSpawner;

    private volatile Process aotTrainer;
    private final AtomicInteger activeBuildPlans = new AtomicInteger();
    private final AtomicInteger peakActiveBuildPlans = new AtomicInteger();

    private void noteConnectionOpened() {
        int n = activeConnections.incrementAndGet();
        peakActiveConnections.accumulateAndGet(n, Math::max);
    }

    private void noteBuildPlanStarted() {
        int n = activeBuildPlans.incrementAndGet();
        peakActiveBuildPlans.accumulateAndGet(n, Math::max);
    }

    /**
     * Atomically decide "not shutting down" <em>and</em> join the active-pipeline count, under
     * {@link #lifecycleLock}.
     *
     * <p>Checking {@code draining} and incrementing separately is a real race: displacement and
     * {@code jk engine stop} both decide under this lock, so a job that passed the check but had
     * not yet incremented is invisible to them — they see zero pipelines, set {@code shuttingDown},
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
     * rejected). Deliberately not {@code noteBuildPlanFinished}: no work happened, so this must not
     * trigger the idle-housekeeping that a real pipeline completion does.
     */
    private void abandonBuildPlanSlot() {
        activeBuildPlans.decrementAndGet();
    }

    /** Dashboard SSE fan-out; non-null only when {@link #httpConfig} is set. */
    private final cc.jumpkick.engine.http.HttpEvents httpEvents;

    /** Ids for {@code request-start}/{@code request-finish} events and {@code POST /api/build} acks. */
    private final java.util.concurrent.atomic.AtomicLong requestIds = new java.util.concurrent.atomic.AtomicLong();

    /**
     * Last aggregate {@code progress} percent (0–100) per request id for MCP/SSE riders /
     * . Updated only from {@link cc.jumpkick.runtime.WorkspaceProgressTracker} — never from
     * module-local pipeline ticks.
     */
    private final java.util.concurrent.ConcurrentHashMap<Long, Double> lastProgressByRequest =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Denominator behind each request's held peak: when the tracker's denominator grows
     * (calibrate — preflight band joins the execute total), the held percent is stale by
     * construction and must rebase instead of pinning the rider at the preflight peak.
     */
    private final java.util.concurrent.ConcurrentHashMap<Long, Long> lastProgressDenByRequest =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Per-request workspace aggregate progress. */
    private final java.util.concurrent.ConcurrentHashMap<Long, cc.jumpkick.runtime.WorkspaceProgressTracker>
            progressTrackers = new java.util.concurrent.ConcurrentHashMap<>();

    /** Workspace root dir for {@code workspace-progress} events. */
    private final java.util.concurrent.ConcurrentHashMap<Long, String> progressRoots =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Plan weight per module dir, for slice calibration. */
    private final java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.ConcurrentHashMap<String, Long>>
            progressWeights = new java.util.concurrent.ConcurrentHashMap<>();

    /** Throttle state: {@code [lastEmitEpochMs, lastEmitPercentMillis]} (percent × 10). */
    private final java.util.concurrent.ConcurrentHashMap<Long, long[]> progressEmitState =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Serialize workspace-progress emit per request ordered stream). */
    private final java.util.concurrent.ConcurrentHashMap<Long, Object> progressEmitLocks =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Per-request journal accumulators; persisted at request-finish regardless of SSE subscribers. */
    private final java.util.concurrent.ConcurrentHashMap<Long, BuildAccumulator> accumulators =
            new java.util.concurrent.ConcurrentHashMap<>();

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
     * Fair RW lock: pipelines hold read for their run; cache maintenance holds write so sweeps
     * never delete under an in-flight pipeline. Cross-process safety still uses on-disk {@code
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
            cc.jumpkick.util.OwnerOnlyFiles.write(
                    active.token().getParent(), active.token(), expectedToken);
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
        startAotTrainerIfConfigured();
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
                                stopAotTrainerQuietly();
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
                                stopAotTrainerQuietly();
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
                    case EngineProtocol.CALIBRATE_REQUEST -> handleCalibrateRequest(line, writer);
                    case EngineProtocol.OPTIMIZE_REQUEST -> handleOptimizeRequest(line, writer);
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
                            stopAotTrainerQuietly();
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
                    case EngineProtocol.HISTORY_LIST_REQUEST -> handleHistoryList(line, writer);
                    case EngineProtocol.HISTORY_SHOW_REQUEST -> handleHistoryShow(line, writer);
                    case EngineProtocol.HISTORY_DELETE_REQUEST -> handleHistoryDelete(line, writer);
                    case EngineProtocol.CANCEL_REQUEST -> handleCancelRequest(line, writer);
                    case EngineProtocol.METRICS_REQUEST -> handleMetrics(line, writer);
                    case EngineProtocol.BUILD_REQUEST -> {
                        // Owns the rest of this connection's lifecycle: forks the build onto its own
                        // thread and keeps reading this loop for a build-cancel/EOF while it runs.
                        handleBuildRequest(line, reader, writer);
                        return;
                    }
                    case EngineProtocol.TEST_REQUEST -> {
                        // Same shape as BUILD_REQUEST but for a single project's test pipeline (Task 3).
                        handleTestRequest(line, reader, writer);
                        return;
                    }
                    case EngineProtocol.SINGLE_BUILD_REQUEST -> {
                        // Same shape as TEST_REQUEST but a real (non-testOnly) build pipeline.
                        handleSingleBuildRequest(line, reader, writer);
                        return;
                    }
                    case EngineProtocol.LOCK_REQUEST -> {
                        // Same fork-and-watch shape as BUILD_REQUEST, hosting jk lock's cascade.
                        handleAsyncBuildPlanRequest(line, reader, writer, "jk-engine-lock-", "lock", this::runLock);
                        return;
                    }
                    case EngineProtocol.UPDATE_REQUEST -> {
                        // jk update rides jk lock's event vocabulary (plus the --git splice mode).
                        handleAsyncBuildPlanRequest(
                                line, reader, writer, "jk-engine-update-", "update", this::runUpdate);
                        return;
                    }
                    case EngineProtocol.SYNC_REQUEST -> {
                        // jk sync is a single pipeline — TEST_REQUEST's wire shape.
                        handleAsyncBuildPlanRequest(line, reader, writer, "jk-engine-sync-", "sync", this::runSync);
                        return;
                    }
                    case EngineProtocol.AUDIT_REQUEST -> {
                        // Hosted worker command: single pipeline, worker forked engine-side.
                        handleAsyncBuildPlanRequest(line, reader, writer, "jk-engine-audit-", "audit", this::runAudit);
                        return;
                    }
                    case EngineProtocol.FORMAT_REQUEST -> {
                        handleAsyncBuildPlanRequest(
                                line, reader, writer, "jk-engine-format-", "format", this::runFormat);
                        return;
                    }
                    case EngineProtocol.PUBLISH_REQUEST -> {
                        handleAsyncBuildPlanRequest(
                                line, reader, writer, "jk-engine-publish-", "publish", this::runPublish);
                        return;
                    }
                    case EngineProtocol.IMAGE_REQUEST -> {
                        handleAsyncBuildPlanRequest(line, reader, writer, "jk-engine-image-", "image", this::runImage);
                        return;
                    }
                    case EngineProtocol.IMPORT_REQUEST -> {
                        handleAsyncBuildPlanRequest(
                                line, reader, writer, "jk-engine-import-", "import", this::runImport);
                        return;
                    }
                    case EngineProtocol.PROVISION_REQUEST -> {
                        // One-shot (no pipeline events), but the worker may download a whole Maven/Gradle
                        // distribution — same fork-and-watch shape so an EOF still cancels.
                        handleAsyncBuildPlanRequest(
                                line, reader, writer, "jk-engine-provision-", "provision", this::runProvision);
                        return;
                    }
                    case EngineProtocol.COMPILE_REQUEST -> {
                        // Hosted pipeline command: jk compile is a single pipeline.
                        handleAsyncBuildPlanRequest(
                                line, reader, writer, "jk-engine-compile-", "compile", this::runCompile);
                        return;
                    }
                    case EngineProtocol.NATIVE_REQUEST -> {
                        // jk native's serial module cascade, speaking BUILD_REQUEST's workspace vocabulary.
                        handleAsyncBuildPlanRequest(
                                line, reader, writer, "jk-engine-native-", "native", this::runNative);
                        return;
                    }
                    case EngineProtocol.INSTALL_REQUEST -> {
                        // jk install's build + cache-install halves; make-install stays client-side.
                        handleAsyncBuildPlanRequest(
                                line, reader, writer, "jk-engine-install-", "install", this::runInstall);
                        return;
                    }
                    case EngineProtocol.GIT_FETCH_REQUEST -> {
                        // jk install <git-url>'s clone half (git runs in-process in the engine).
                        handleAsyncBuildPlanRequest(
                                line, reader, writer, "jk-engine-gitfetch-", "git-fetch", this::runGitFetch);
                        return;
                    }
                    case EngineProtocol.SCRIPT_PREPARE_REQUEST -> {
                        handleAsyncBuildPlanRequest(
                                line, reader, writer, "jk-engine-script-", "script", this::runScriptPrepare);
                        return;
                    }
                    case EngineProtocol.TOOL_RESOLVE_REQUEST -> {
                        // Hosted long-tail command: jk tool install/run Maven resolve+fetch.
                        handleAsyncBuildPlanRequest(
                                line, reader, writer, "jk-engine-tool-", "tool", this::runToolResolve);
                        return;
                    }
                    case EngineProtocol.CACHE_PRUNE_REQUEST -> {
                        // Cache maintenance is an idle-boundary job, not a pipeline: it waits for
                        // activeBuildPlans to drain (and blocks new ones) instead of joining them.
                        handleAsyncBuildPlanRequest(
                                line, reader, writer, "jk-engine-cache-", "cache", this::runCacheMaintenance, false);
                        return;
                    }
                    case EngineProtocol.EXPLAIN_REQUEST -> {
                        // Synchronous read, no worker JVM forked — handled inline, connection continues.
                        handleExplainRequest(line, writer);
                    }
                    case EngineProtocol.FORECAST_REQUEST -> {
                        // Synchronous read-only pre-flight (jk build's fully-cached shortcut)
                        // handled inline, connection continues.
                        handleForecastRequest(line, writer);
                    }
                    case EngineProtocol.PROJECT_INFO_REQUEST -> handleProjectInfoRequest(line, writer);
                    case EngineProtocol.OUTDATED_REQUEST -> handleOutdatedRequest(line, writer);
                    case EngineProtocol.EXEC_PLAN_REQUEST -> handleExecPlanRequest(line, writer);
                    case EngineProtocol.EDIT_REQUEST -> handleEditRequest(line, writer);
                    case EngineProtocol.DENY_CHECK_REQUEST -> handleDenyCheckRequest(line, writer);
                    case EngineProtocol.TREE_REQUEST -> handleTreeRequest(line, writer);
                    case EngineProtocol.WHY_REQUEST -> handleWhyRequest(line, writer);
                    case EngineProtocol.GENERATE_REQUEST -> handleGenerateRequest(line, writer);
                    case EngineProtocol.PLUGIN_VERB_REQUEST -> handlePluginCommandRequest(line, writer);
                    case EngineProtocol.IDE_MODEL_REQUEST -> handleIdeModelRequest(line, writer);
                    default ->
                        sendQuiet(
                                writer,
                                EngineProtocol.error(EngineProtocol.ERR_PROTOCOL, "unknown request type: " + type));
                }
            }
        }
    }

    /**
     * Run a workspace build on its own thread (so this method can keep reading the connection for a
     * {@link EngineProtocol#BUILD_CANCEL} or EOF meanwhile) and stream every {@link
     * WorkspaceBuildListener}/{@link BuildPlanListener} callback back as a wire event. Returns once the
     * build finishes and its terminal message has been sent, or the connection drops.
     */
    private void handleBuildRequest(String requestLine, BufferedReader reader, BufferedWriter writer) {
        // The one stream whose terminal is workspace-finish (see LiveJob.workspaceStream).
        handleAsyncBuildPlanRequest(
                requestLine, reader, writer, "jk-engine-build-", "build", this::runBuild, true, true);
    }

    /** An engine-hosted operation's body: decode the request, run it, stream events to {@code writer}. */
    @FunctionalInterface
    private interface BuildPlanRunner {
        void run(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer);
    }

    /**
     * Fork {@code runner} onto its own thread (so this method can keep reading the connection for a
     * {@link EngineProtocol#BUILD_CANCEL} or EOF meanwhile) and wait for it to finish. Shared by every
     * request type that owns the rest of its connection's lifecycle ({@link #handleBuildRequest},
     * {@link #handleTestRequest}, {@link #handleSingleBuildRequest}) — they differ only in what
     * {@code runner} actually builds and runs.
     */
    private void handleAsyncBuildPlanRequest(
            String requestLine,
            BufferedReader reader,
            BufferedWriter writer,
            String threadPrefix,
            String kind,
            BuildPlanRunner runner) {
        handleAsyncBuildPlanRequest(requestLine, reader, writer, threadPrefix, kind, runner, true, false);
    }

    /**
     * As above; {@code pipeline=false} for a cache maintenance job, which is deliberately <em>not</em>
     * a pipeline: it doesn't join {@link #activeBuildPlans} or hold {@link #cacheGate}'s read side
     * its runner takes the write side itself (see {@link #runCacheMaintenance}).
     */
    private void handleAsyncBuildPlanRequest(
            String requestLine,
            BufferedReader reader,
            BufferedWriter writer,
            String threadPrefix,
            String kind,
            BuildPlanRunner runner,
            boolean pipeline) {
        handleAsyncBuildPlanRequest(requestLine, reader, writer, threadPrefix, kind, runner, pipeline, false);
    }

    /**
     * As above; {@code workspaceStream=true} only for the workspace build stream, whose terminal
     * wire line is {@code workspace-finish} — every other stream ends on {@code pipeline-finish}
     * and a cancelled terminal must match.
     */
    private void handleAsyncBuildPlanRequest(
            String requestLine,
            BufferedReader reader,
            BufferedWriter writer,
            String threadPrefix,
            String kind,
            BuildPlanRunner runner,
            boolean pipeline,
            boolean workspaceStream) {
        // Refuse new jobs while draining (a graceful shutdown is finishing in-flight work). The client
        // normally can't even get here — its handshake sees `draining` and fails first — but guard the
        // server too so a raced/last-moment request is rejected instead of prolonging the drain.
        // A pipeline claims its slot in the same breath, so shutdown can never observe zero
        // pipelines for a job that is about to start (JK-1470).
        boolean claimedBuildPlanSlot = false;
        if (pipeline) {
            claimedBuildPlanSlot = tryStartBuildPlan();
        }
        if (pipeline ? !claimedBuildPlanSlot : draining) {
            try {
                send(
                        writer,
                        EngineProtocol.error(
                                EngineProtocol.ERR_SHUTTING_DOWN,
                                "the engine is shutting down (draining) — retry; the successor engine takes over"));
            } catch (IOException ignored) {
                // Client vanished mid-refusal — nothing to do; the connection is closing anyway.
            }
            return;
        }
        Session.CancelToken cancelToken = Session.CancelToken.live();
        CountDownLatch done = new CountDownLatch(1);
        long eventRequestId = requestIds.incrementAndGet();
        // The kind rides explicitly from the dispatch site (never parsed back out of a thread
        // name); the journal dir falls back to a request's specific location field so non-build
        // requests never record the literal string "null".
        String eventKind = kind;
        String eventDir = journalDir(requestLine);
        long eventStartMillis = clockMillis.getAsLong();
        boolean rebuildRun = Jsonl.bool(requestLine, "rebuild", false) || Jsonl.bool(requestLine, "force", false);
        // How the build was started: default "cli"; optimize/calibrate mark synthetic history.
        String trigger = Jsonl.str(requestLine, "trigger");
        if (trigger == null || trigger.isBlank()) trigger = "cli";
        // exclusive fingerprint + start-time build number for journaled kinds.
        AdmitResult admit = admitJob(
                eventRequestId,
                eventKind,
                eventDir,
                BuildJobFingerprint.ofRequest(eventKind, requestLine),
                trigger);
        if (admit.rejected() != null) {
            try {
                InFlightBuilds.Hold h = admit.rejected();
                String label = "test".equals(eventKind) ? "Test" : "Build";
                String msg = label + " #" + h.buildNumber() + " is already running";
                send(writer, EngineProtocol.alreadyRunning(h.buildNumber(), h.requestId(), msg));
            } catch (IOException ignored) {
                // client gone
            }
            if (claimedBuildPlanSlot) abandonBuildPlanSlot(); // nothing ran — give the slot back
            return;
        }
        publishRequestStart(eventRequestId, eventKind, eventDir, admit.buildNumber());
        registerAccumulator(
                eventRequestId,
                eventKind,
                eventDir,
                trigger,
                Jsonl.bool(requestLine, "noTimeline", false),
                rebuildRun,
                admit.buildNumber(),
                admit.journalId());
        // The slot was already claimed above, atomically with the shutdown check.
        Thread heartbeatThread = null;
        java.util.concurrent.atomic.AtomicReference<Thread> runnerRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        // Public jid surfaceclient tracks this for Ctrl-C / jk cancel.
        try {
            send(writer, jobStartLine(eventRequestId, eventKind, eventDir, admit));
        } catch (IOException ignored) {
            // client gone before job body — still run cancel registration below
        }
        // Capture this connection thread so remote cancel can wake it off client-readLine. The
        // wake is Thread.interrupt, which on a thread blocked in an InterruptibleChannel read also
        // CLOSES the channel — so only interrupt while actually parked on the read;
        // an interrupt landing after the loop exits would poison teardown I/O instead.
        java.util.concurrent.atomic.AtomicBoolean parkedOnRead = new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread connectionThread = Thread.currentThread();
        registerLiveJob(
                eventRequestId, cancelToken, runnerRef, writer, connectionThread, eventDir, eventKind, workspaceStream);
        try {
            Thread started = Thread.ofVirtual().name(threadPrefix, 0).start(() -> {
                if (pipeline) cacheGate.readLock().lock();
                currentEventRequestId.set(eventRequestId);
                JobWorkers.open(eventRequestId);
                // Every Session this request builds adopts this ledger, so fetches/cache traffic on
                // the shared pools all land in one place (see IoLedger).
                cc.jumpkick.task.IoLedger.open(runIo(eventRequestId));
                try {
                    runner.run(requestLine, cancelToken, writer);
                } finally {
                    cc.jumpkick.task.IoLedger.close();
                    JobWorkers.close();
                    JobWorkers.clear(eventRequestId);
                    currentEventRequestId.remove();
                    if (pipeline) cacheGate.readLock().unlock();
                    // Free exclusive fingerprint as soon as pipeline work ends — before the
                    // connection thread finishes teardown — so a follow-up same-project build is
                    // not rejected as already-running while journal/idle chores run.
                    inFlightBuilds.release(eventRequestId);
                    unregisterLiveJob(eventRequestId);
                    done.countDown();
                    // Unblock the connection thread only if it is parked on client readLine
                    // waiting for BUILD_CANCEL / EOF — remote cancel finishes the runner without
                    // the client writing anything. A blanket interrupt here landed after
                    // the read loop too, leaving the flag set through teardown so the journal
                    // completion died on ClosedByInterruptException — a phantom "running" job in
                    // jk jobs until engine restart.
                    if (parkedOnRead.get()) connectionThread.interrupt();
                }
            });
            runnerRef.set(started);
            // Keep-alive + optional wall deadline while the job runs /.
            // Client stream idle (JK_STREAM_IDLE_MS) resets on each heartbeat line. On deadline:
            // cancel + worker shutdown (grace→force) + interrupt runner; connection join is bounded.
            // User cancel / EOFsame worker policy with a short cancel grace — never hang.
            long heartbeatMs = jobHeartbeatMs();
            long deadlineMs = jobDeadlineMs();
            long graceMs = jobDeadlineGraceMs();
            long cancelGraceMs = JobWorkers.cancelGraceMs();
            if (heartbeatMs > 0 || deadlineMs > 0) {
                heartbeatThread = Thread.ofVirtual().name("jk-job-watchdog", 0).start(() -> {
                    long start = clockMillis.getAsLong();
                    while (done.getCount() > 0) {
                        long elapsed = clockMillis.getAsLong() - start;
                        long wait = heartbeatMs > 0 ? heartbeatMs : 1_000L;
                        if (deadlineMs > 0) {
                            long remaining = deadlineMs - elapsed;
                            if (remaining <= 0) {
                                enforceDeadline(eventRequestId, cancelToken, runnerRef.get(), writer, deadlineMs);
                                return;
                            }
                            wait = Math.min(wait, remaining);
                        }
                        try {
                            if (done.await(wait, java.util.concurrent.TimeUnit.MILLISECONDS)) return;
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        if (done.getCount() == 0) return;
                        if (heartbeatMs > 0) {
                            sendQuiet(writer, EngineProtocol.heartbeat(clockMillis.getAsLong() - start));
                        }
                    }
                });
            }
            try {
                // Stay responsive after remote cancel: the client never writes on this socket, so a
                // pure blocking readLine would park forever even after the runner finished. Cancel
                // (and runner teardown) interrupt this thread so we can join and run the finally
                // safety-net terminal.
                while (done.getCount() > 0) {
                    try {
                        parkedOnRead.set(true);
                        String line = reader.readLine();
                        parkedOnRead.set(false);
                        if (line == null) {
                            // EOF / client gone mid-job — same bounded cancel path (not explicit:
                            // an EOF after a reported failure is the terminal-read race, JK-1521).
                            beginUserCancel(eventRequestId, cancelToken, runnerRef, cancelGraceMs, false);
                            break;
                        }
                        if (EngineProtocol.BUILD_CANCEL.equals(EngineProtocol.typeOf(line))) {
                            // Explicit cancel on this socket: cooperative flag + worker grace→force.
                            beginUserCancel(eventRequestId, cancelToken, runnerRef, cancelGraceMs, true);
                        }
                    } catch (IOException e) {
                        parkedOnRead.set(false);
                        // Interrupt during read (ClosedByInterruptException, etc.) or a real error.
                        if (done.getCount() == 0 || Thread.currentThread().isInterrupted()) {
                            break; // runner done / cancel wake — join below
                        }
                        beginUserCancel(eventRequestId, cancelToken, runnerRef, cancelGraceMs, false);
                        break;
                    }
                }
                parkedOnRead.set(false);
            } catch (RuntimeException ignored) {
                if (done.getCount() > 0) {
                    beginUserCancel(eventRequestId, cancelToken, runnerRef, cancelGraceMs, false);
                }
            }
            // Clear interrupt so await/join below is not spuriously skipped.
            Thread.interrupted();
            try {
                // Bound the join so a wedged runner cannot hang the connection forever.
                if (deadlineMs > 0) {
                    long elapsed = clockMillis.getAsLong() - eventStartMillis;
                    long budget = Math.max(1L, deadlineMs + graceMs - elapsed);
                    if (!done.await(budget, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        enforceDeadline(eventRequestId, cancelToken, runnerRef.get(), writer, deadlineMs);
                        // Last chance for the runner to unwind after worker kill / interrupt.
                        // Cap hard so UX never waits the full 30s grace when the job is deadlocked.
                        long lastChance = Math.min(graceMs, Math.max(cancelGraceMs + 200L, 1_000L));
                        if (!done.await(lastChance, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                            log.accept("jk engine: job "
                                    + eventRequestId
                                    + " still running after deadline+"
                                    + lastChance
                                    + "ms grace — abandoned; workers killed");
                        }
                    }
                } else if (cancelToken.cancelled() && done.getCount() > 0) {
                    // User cancel without wall deadline: join only for cancelGrace + small buffer.
                    long joinBudget = cancelGraceMs + 500L;
                    if (!done.await(joinBudget, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        JobWorkers.shutdownForRequest(eventRequestId, 0L);
                        interruptRunner(runnerRef.get());
                        if (!done.await(200L, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                            log.accept("jk engine: job "
                                    + eventRequestId
                                    + " still running after cancel+"
                                    + joinBudget
                                    + "ms — abandoned; workers force-killed");
                        }
                    }
                } else {
                    done.await();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } finally {
            // A late runner/cancel interrupt may have landed after the joins: clear it before any
            // teardown I/O, or journal completion dies on ClosedByInterruptException and jk jobs
            // shows this build as running forever. This thread ends after teardown, so
            // there is nothing to restore the flag for.
            Thread.interrupted();
            if (heartbeatThread != null) heartbeatThread.interrupt();
            // Belts: any leftover workers die now (grace 0 — request is ending).
            JobWorkers.shutdownForRequest(eventRequestId, 0L);
            JobWorkers.clear(eventRequestId);
            // Idempotent: runner finally usually released already; covers admit-without-run paths.
            inFlightBuilds.release(eventRequestId);
            long elapsedMillis = clockMillis.getAsLong() - eventStartMillis;
            // cancelToken.cancelled also trips on the benign end-of-request EOF, so a finished
            // build (success or failure) can look cancelled. Correct it once here for both the
            // dashboard event and the journal.
            boolean cancelled = effectiveCancelled(eventRequestId, cancelToken.cancelled());
            // success: same default as BuildAccumulator.toRecord — HTTP jobs always sent it; CLI
            // socket jobs used to omit it and force the SPA to derive from module rows (JK-1499).
            BuildAccumulator finishAcc = accumulators.get(eventRequestId);
            boolean success = finishAcc != null
                    ? finishAcc.effectiveSuccess(cancelled)
                    : !cancelled;
            // Pin 100% only on success — a failed build keeps its last true percent, matching the
            // workspace-runner path and the stated policy (JK-1521).
            if (success && !cancelled) lastProgressByRequest.put(eventRequestId, 100.0);
            // Safety netif the runner was abandoned/interrupted without a terminal
            // wire event, still tell the CLI the job was cancelled so it does not report a crash.
            // Harmless if the runner already sent workspace-/pipeline-finish (client has returned).
            if (cancelled && writer != null) {
                // Same shape rule as pushCancelledTerminal: single builds journal as "build" but
                // their client loop only ends on pipeline-finish.
                sendQuiet(writer, cancelledTerminalLine(workspaceStream, eventDir));
            }
            publishEvent(
                    "request-finish",
                    withProgress(
                            withIo(
                                    cc.jumpkick.engine.http.JsonOut.object()
                                            .put("schema", 1)
                                            .put("type", "request-finish")
                                            .put("requestId", eventRequestId)
                                            .put("jid", eventRequestId)
                                            .put("kind", eventKind)
                                            .put("dir", eventDir)
                                            .put("success", success)
                                            .put("cancelled", cancelled)
                                            .put("millis", elapsedMillis),
                                    eventRequestId),
                            eventRequestId));
            clearProgress(eventRequestId);
            writeJournal(eventRequestId, cancelled, elapsedMillis, writer);
            // Idle boundary after finish side-effects so prune/GC see journal + event garbage too.
            // Cache maintenance (pipeline=false) only GCs when nothing else is in flight.
            if (pipeline) maybeIdleBoundary();
            else maybeIdleGc();
        }
    }

    /**
     * Result of {@link #admitJob}: either a rejection hold (same fingerprint already running) or
     * allocated build number + optional journal id for the new request.
     */
    private record AdmitResult(InFlightBuilds.Hold rejected, long buildNumber, String journalId) {
        static AdmitResult reject(InFlightBuilds.Hold h) {
            return new AdmitResult(h, 0, null);
        }

        static AdmitResult ok(long buildNumber, String journalId) {
            return new AdmitResult(null, buildNumber, journalId);
        }
    }

    /** job-start wire line with buildNumber + details path for the CLI transcript. */
    private String jobStartLine(long jid, String kind, String dir, AdmitResult admit) {
        String detailsPath = null;
        if (admit.buildNumber() > 0) {
            detailsPath = journal.detailsFile(coordOf(dir), dir, admit.buildNumber())
                    .map(Path::toString)
                    .orElseGet(() -> journal.detailsFile(java.lang.Long.toString(admit.buildNumber()))
                            .map(Path::toString)
                            .orElse(null));
        }
        return EngineProtocol.jobStart(jid, kind, dir, admit.buildNumber(), detailsPath, -1);
    }

    /**
     * Allocate a build number (journaled kinds), take an exclusive fingerprint slot when required,
     * and persist an in-flight journal stub.
     */
    private AdmitResult admitJob(long requestId, String kind, String dir, String fingerprint, String trigger) {
        boolean exclusive = BuildJobFingerprint.isExclusiveKind(kind);
        String fp = exclusive && fingerprint != null ? fingerprint : "";
        // Reject before allocating a build number so collisions do not burn sequence values.
        if (exclusive && !fp.isEmpty()) {
            var existing = inFlightBuilds.peek(fp);
            if (existing.isPresent()) return AdmitResult.reject(existing.get());
        }
        String canonDir = BuildJobFingerprint.canonicalDir(dir);
        String coord = coordOf(dir);
        long buildNumber = 0L;
        if (JOURNALED_KINDS.contains(kind) && canonDir != null && !canonDir.isBlank()) {
            buildNumber = cc.jumpkick.runtime.BuildNumberAllocator.allocate(canonDir, coord);
        }
        long startedAt = clockMillis.getAsLong();
        String journalId = null;
        if (JOURNALED_KINDS.contains(kind) && historyConfig.enabled() && buildNumber > 0) {
            journalId = journal.begin(BuildRecord.running(buildNumber, kind, dir, coord, startedAt, version, trigger));
        }
        InFlightBuilds.Hold candidate =
                new InFlightBuilds.Hold(requestId, buildNumber, fp, kind, dir, coord, startedAt, journalId, trigger);
        if (exclusive && !fp.isEmpty()) {
            var raced = inFlightBuilds.tryAcquire(candidate);
            if (raced.isPresent()) {
                // Scoped: journalId is this project's build number, which another project may
                // also use (JK-1471).
                if (journalId != null) journal.delete(journalId, coord, dir);
                return AdmitResult.reject(raced.get());
            }
        } else {
            inFlightBuilds.tryAcquire(candidate);
        }
        return AdmitResult.ok(buildNumber, journalId);
    }

    /**
     * User / EOF cancelset cooperative flag and shut down workers with a short
     * grace→force window on a helper thread so the connection reader is not blocked. Idempotent.
     *
     * <p>Also stamps the accumulator as user-cancelled <em>immediately</em>. Without that, a force-
     * killed runner that never emits {@link BuildPlanResult#userCancelled} was journaled as a plain
     * success/failure with the truncated wall-clock — and truncated successes poisoned ETA history.
     */
    private void beginUserCancel(
            long eventRequestId,
            Session.CancelToken cancelToken,
            java.util.concurrent.atomic.AtomicReference<Thread> runnerRef,
            long cancelGraceMs,
            boolean explicit) {
        cancelToken.cancel();
        markUserCancelled(eventRequestId, explicit);
        Thread.ofVirtual().name("jk-cancel-" + eventRequestId, 0).start(() -> {
            int killed = JobWorkers.shutdownForRequest(eventRequestId, cancelGraceMs);
            interruptRunner(runnerRef != null ? runnerRef.get() : null);
            if (killed > 0) {
                log.accept("jk engine: cancel job "
                        + eventRequestId
                        + " — shut down "
                        + killed
                        + " worker process(es) (grace "
                        + cancelGraceMs
                        + "ms)");
            }
        });
    }

    // ---- Live job cancel registry-----------------------------------

    /**
     * Every admitted job (CLI JSONL or HTTP/MCP) registers here so {@code jk cancel <jid>} /
     * {@code POST /api/cancel} / MCP {@code jk_cancel} share one kill path.
     */
    private final java.util.concurrent.ConcurrentHashMap<Long, LiveJob> liveJobs =
            new java.util.concurrent.ConcurrentHashMap<>();

    private record LiveJob(
            Session.CancelToken token,
            java.util.concurrent.atomic.AtomicReference<Thread> runnerRef,
            /** Streaming socket for this job — used to push an immediate cancelled terminal. */
            BufferedWriter writer,
            /** Connection thread parked on client readLine — interrupted so teardown can run. */
            Thread connectionThread,
            String dir,
            String kind,
            /**
             * True when the stream's terminal line is {@code workspace-finish}; false for single
             * pipelines (single build, test, lock, …), whose client loop only ends on
             * {@code pipeline-finish}. Kind alone cannot tell: single builds journal as
             * {@code "build"} too.
             */
            boolean workspaceStream) {}

    private void registerLiveJob(
            long jid,
            Session.CancelToken token,
            java.util.concurrent.atomic.AtomicReference<Thread> runnerRef,
            BufferedWriter writer,
            Thread connectionThread,
            String dir,
            String kind,
            boolean workspaceStream) {
        liveJobs.put(jid, new LiveJob(token, runnerRef, writer, connectionThread, dir, kind, workspaceStream));
    }

    private void unregisterLiveJob(long jid) {
        liveJobs.remove(jid);
    }

    /**
     * Cancel one live job by jid. Returns {@code false} if unknown/already finished (idempotent soft
     * miss). Covers CLI-socket jobs and HTTP/MCP jobs.
     *
     * <p>Pushes a cancelled terminal on the job's stream immediately so a remote {@code jk cancel}
     * settles the building CLI without waiting for the runner to unwind.
     */
    boolean cancelJob(long jid) {
        LiveJob job = liveJobs.get(jid);
        if (job != null) {
            // Remote `jk cancel` / POST /api/cancel — an explicit signal (JK-1521).
            beginUserCancel(jid, job.token(), job.runnerRef(), JobWorkers.cancelGraceMs(), true);
            // Terminal + reader wake happen off-thread: the job's stream writer can be wedged in a
            // socket write (client not draining), and `jk cancel` / POST /api/cancel must ack
            // without waiting behind that monitor. Order inside the task still matters:
            // terminal first, then the interrupt that may close the channel.
            Thread.ofVirtual().name("jk-cancel-settle-" + jid).start(() -> {
                // Immediate terminal on the streaming connection — the building CLI is blocked
                // reading this writer; without this it often only sees EOF after the runner is
                // abandoned.
                pushCancelledTerminal(job);
                if (job.connectionThread() != null) {
                    try {
                        job.connectionThread().interrupt();
                    } catch (RuntimeException ignored) {
                        // best-effort wake
                    }
                }
            });
            return true;
        }
        // HTTP path may still hold tokens briefly if registration order differs.
        return cancelHttpJob(jid);
    }

    /**
     * Tell the streaming client this job was cancelled. Synchronized {@link #send} so concurrent
     * progress lines cannot interleave mid-message.
     */
    private void pushCancelledTerminal(LiveJob job) {
        if (job == null || job.writer() == null) return;
        sendQuiet(job.writer(), cancelledTerminalLine(job.workspaceStream(), job.dir()));
    }

    /**
     * The cancelled terminal matching the stream's real shape: a single-project build registers
     * kind "build" too, but its client loop only ends on {@code pipeline-finish} — a
     * {@code workspace-finish} there is a no-op and the CLI settles as "engine disconnected"
     * instead of cancelled.
     */
    static String cancelledTerminalLine(boolean workspaceStream, String dir) {
        return workspaceStream
                ? EngineProtocol.workspaceFinish(false, 1, List.of(), true)
                : EngineProtocol.pipelineFinish(dir == null ? "" : dir, false, true);
    }

    /** Cancel every live job whose dir matches (canonical absolute path). */
    int cancelJobsForDir(String dir) {
        if (dir == null || dir.isBlank()) return 0;
        String want = BuildJobFingerprint.canonicalDir(dir);
        if (want == null || want.isBlank())
            want = Path.of(dir).toAbsolutePath().normalize().toString();
        int n = 0;
        for (var e : liveJobs.entrySet()) {
            String d = e.getValue().dir();
            String got = d == null ? "" : BuildJobFingerprint.canonicalDir(d);
            if (got == null || got.isBlank()) {
                try {
                    got = Path.of(d).toAbsolutePath().normalize().toString();
                } catch (RuntimeException ignored) {
                    got = d;
                }
            }
            if (want.equals(got)) {
                if (cancelJob(e.getKey())) n++;
            }
        }
        return n;
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
            int n = cancelJobsForDir(dir);
            // jid=0 means "dir batch"; cancelled true if any job was live.
            send(
                    writer,
                    EngineProtocol.cancelAck(
                            0, n > 0, n > 0 ? ("cancelled " + n + " job(s)") : "no running jobs for dir"));
            return;
        }
        send(writer, EngineProtocol.cancelAck(-1, false, "cancel-request requires jid or dir"));
    }

    /** Stamp the request's accumulator so journal/metrics never treat a cancelled wall as success. */
    private void markUserCancelled(long requestId, boolean explicit) {
        BuildAccumulator a = accumulators.get(requestId);
        if (a != null) a.markUserCancelled(explicit);
    }

    private static void interruptRunner(Thread runnerThread) {
        if (runnerThread == null) return;
        try {
            runnerThread.interrupt();
        } catch (RuntimeException ignored) {
            // best-effort
        }
    }

    /**
     * Wall-deadline killcooperative cancel + worker grace→force + interrupt
     * runner. Idempotent; safe from the watchdog and the connection thread. Stamps the accumulator so
     * deadline-truncated wall-clock never trains ETA (same as user cancel).
     */
    private void enforceDeadline(
            long eventRequestId,
            Session.CancelToken cancelToken,
            Thread runnerThread,
            BufferedWriter writer,
            long deadlineMs) {
        cancelToken.cancel();
        markUserCancelled(eventRequestId, true);
        // Soft then force within cancel grace (not the 30s join grace).
        int killed = JobWorkers.shutdownForRequest(eventRequestId, JobWorkers.cancelGraceMs());
        interruptRunner(runnerThread);
        sendQuiet(
                writer,
                EngineProtocol.error(
                        EngineProtocol.ERR_DEADLINE,
                        "job exceeded "
                                + deadlineMs
                                + "ms (JK_ENGINE_JOB_DEADLINE_MS); cancelled"
                                + (killed > 0 ? " (killed " + killed + " worker process(es))" : "")));
    }

    /**
     * Whether the build was genuinely cancelled.
     *
     * <p>{@code cancelToken.cancelled} alone is unreliable — it also trips on the benign
     * end-of-request EOF (client closes the socket the instant it reads the terminal message). For a
     * request with an accumulator we trust an explicit stamp ({@link BuildAccumulator#markUserCancelled}
     * from BUILD_CANCEL / mid-job EOF / deadline, or {@link BuildPlanResult#userCancelled}). A runner
     * that already stamped a terminal outcome (success <em>or</em> failure) is never re-labelled
     * cancelled by that race — otherwise a failed test run journals as "Cancelled" in the web UI
     * after the CLI closes the socket. No accumulator → raw token.
     */
    private boolean effectiveCancelled(long requestId, boolean rawCancelled) {
        BuildAccumulator a = accumulators.get(requestId);
        if (a == null) return rawCancelled;
        if (a.wasCancelled()) return true;
        // Token cancelled mid-job but stamp missed (legacy path): still cancel unless the runner
        // already reported a terminal outcome (EOF-after-finish race for success or failure).
        return rawCancelled && !a.hasOutcome();
    }

    /** The request's location for journal/dashboard rows: {@code dir}, else the nearest thing. */
    private static String journalDir(String requestLine) {
        String dir = Jsonl.str(requestLine, "dir");
        if (dir != null) return dir;
        String cache = Jsonl.str(requestLine, "cache");
        if (cache != null) return cache;
        return "";
    }

    /**
     * Publish an <strong>inflicted</strong> build/activity frame to the dashboard SSE hub (JK-1499).
     * Call only when the engine already mutated user-visible state — never batch build progress on
     * the sampled vitals timer ({@link cc.jumpkick.engine.http.LiveVitals}). No-op without
     * subscribers. Sampled chrome ({@code status}/{@code cache}) is separate: change-gated and
     * nudged only on request start/finish so Builds Running / storage totals stay timely.
     */
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
    }

    /**
     * Attach last known <em>workspace aggregate</em> {@code progress} (0–100 or null) from the
     * engine tracker. Never compute from module-local ticks here.
     */
    private cc.jumpkick.engine.http.JsonOut withProgress(cc.jumpkick.engine.http.JsonOut payload, long requestId) {
        Double p = requestId > 0 ? lastProgressByRequest.get(requestId) : null;
        return payload.putNullable("progress", p);
    }

    /**
     * The ambient byte ledger for a request: the journal accumulator's when the kind is journaled,
     * else a throwaway so metering call sites never branch on whether anyone is recording.
     */
    private cc.jumpkick.task.IoLedger runIo(long requestId) {
        BuildAccumulator a = accumulators.get(requestId);
        return a != null ? a.io() : new cc.jumpkick.task.IoLedger();
    }

    /**
     * Add the run's byte counters to a terminal event so a live dashboard card shows them without
     * waiting for the history backfill. Omitted entirely for a run that moved nothing.
     */
    private cc.jumpkick.engine.http.JsonOut withIo(cc.jumpkick.engine.http.JsonOut payload, long requestId) {
        BuildAccumulator a = accumulators.get(requestId);
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
    private final java.util.concurrent.ConcurrentHashMap<Long, Boolean> retiredRequests =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** How far below the newest request id a retired marker is still worth keeping. */
    private static final long RETIRED_WINDOW = 1024L;

    private void clearProgress(long requestId) {
        if (requestId <= 0) return;
        retiredRequests.put(requestId, Boolean.TRUE);
        lastProgressByRequest.remove(requestId);
        lastProgressDenByRequest.remove(requestId);
        progressTrackers.remove(requestId);
        progressRoots.remove(requestId);
        progressWeights.remove(requestId);
        progressEmitState.remove(requestId);
        progressEmitLocks.remove(requestId);
        long cutoff = requestIds.get() - RETIRED_WINDOW;
        if (cutoff > 0) retiredRequests.keySet().removeIf(id -> id < cutoff);
    }

    /** True once {@link #clearProgress} has retired this request — late emits must not re-register. */
    private boolean progressRetired(long requestId) {
        return retiredRequests.containsKey(requestId);
    }

    /**
     * For a retired request, a detached tracker that is never stored: callers keep a non-null
     * object to update (no null checks at eight call sites) and the update goes nowhere.
     */
    private cc.jumpkick.runtime.WorkspaceProgressTracker progressTracker(long requestId) {
        if (progressRetired(requestId)) return new cc.jumpkick.runtime.WorkspaceProgressTracker();
        return progressTrackers.computeIfAbsent(requestId, id -> new cc.jumpkick.runtime.WorkspaceProgressTracker());
    }

    private long planWeight(long requestId, String dir) {
        if (requestId <= 0 || dir == null) return 0;
        var m = progressWeights.get(requestId);
        if (m == null) return 0;
        Long w = m.get(dir);
        return w != null ? w : 0;
    }

    /**
     * Feed module pipeline ticks into the workspace tracker and optionally emit {@code
     * workspace-progress}.
     */
    private void trackModuleBuildPlan(
            long requestId, String dir, BuildPlanView view, java.io.BufferedWriter writer, boolean forceEmit) {
        if (requestId <= 0 || view == null) return;
        progressTracker(requestId)
                .moduleProgress(dir, planWeight(requestId, dir), view.numerator(), view.denominator());
        emitWorkspaceProgress(requestId, writer, forceEmit);
    }

    private void trackModuleComplete(long requestId, String dir, long lastDen, java.io.BufferedWriter writer) {
        if (requestId <= 0) return;
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
        Object lock = progressEmitLocks.computeIfAbsent(requestId, id -> new Object());
        synchronized (lock) {
            cc.jumpkick.runtime.WorkspaceProgressTracker tracker = progressTrackers.get(requestId);
            if (tracker == null) return;
            var snap = tracker.snapshot();
            if (snap.hasPercent()) {
                // Peak-hold machine progressnever publish a lower % than already
                // emitted — but rebase when the denominator grew (calibrate), or the preflight
                // peak pins the rider for the whole execute phase.
                Double prevPct = lastProgressByRequest.get(requestId);
                Long prevDen = lastProgressDenByRequest.get(requestId);
                double pct = snap.percent();
                boolean denGrew = prevDen != null && snap.denominator() > prevDen;
                if (!denGrew && prevPct != null && pct + 1e-9 < prevPct) {
                    pct = prevPct;
                }
                lastProgressByRequest.put(requestId, pct);
                lastProgressDenByRequest.put(requestId, snap.denominator());
            }
            if (!force && !shouldEmitWorkspaceProgress(requestId, snap)) return;
            String dir = progressRoots.getOrDefault(requestId, "");
            long num = snap.numerator();
            long den = snap.denominator();
            // If peak-holding percent, still emit the snapped phase counters but progress rider uses peak.
            String line = EngineProtocol.workspaceProgress(
                    dir, num, den, snap.phase(), snap.modulesComplete(), snap.modulesTotal());
            if (writer != null) sendQuiet(writer, line);
            if (eventsWanted()) {
                publishEvent(
                        "workspace-progress",
                        withProgress(
                                cc.jumpkick.engine.http.JsonOut.object()
                                        .put("schema", 1)
                                        .put("type", "workspace-progress")
                                        .put("requestId", requestId)
                                        .put("dir", dir)
                                        .put("numerator", num)
                                        .put("denominator", den)
                                        .put("phase", snap.phase())
                                        .put("modulesComplete", snap.modulesComplete())
                                        .put("modulesTotal", snap.modulesTotal()),
                                requestId),
                        dashboardOnly);
            }
            Double held = lastProgressByRequest.get(requestId);
            long pctMillis = held != null
                    ? Math.round(held * 10.0)
                    : (snap.hasPercent() ? Math.round(snap.percent() * 10.0) : -1L);
            progressEmitState.put(requestId, new long[] {System.currentTimeMillis(), pctMillis});
        }
    }

    /** ≥0.1% change or one TTY frame (WorkspaceProgressTracker.TTY_FRAME_MS) since last emit. */
    private boolean shouldEmitWorkspaceProgress(
            long requestId, cc.jumpkick.runtime.WorkspaceProgressTracker.Snapshot snap) {
        long[] prev = progressEmitState.get(requestId);
        if (prev == null) return true;
        long now = System.currentTimeMillis();
        if (now - prev[0] >= cc.jumpkick.runtime.WorkspaceProgressTracker.TTY_FRAME_MS) return true;
        if (!snap.hasPercent()) return false;
        long pctMillis = Math.round(snap.percent() * 10.0);
        return Math.abs(pctMillis - prev[1]) >= 1; // 0.1%
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
                .put("coord", coord);
        if (buildNumber > 0) payload = payload.put("buildNumber", buildNumber);
        publishEvent("request-start", withProgress(payload, requestId), dashboardOnly);
    }

    private void publishStepStart(long requestId, String dir, String step, String phase) {
        if (!eventsWanted()) return;
        // Field names align with CLI JsonlShape (schema + type + step + phase).
        publishEvent(
                "task-start",
                withProgress(
                        cc.jumpkick.engine.http.JsonOut.object()
                                .put("schema", 1)
                                .put("type", "task-start")
                                .put("requestId", requestId)
                                .put("dir", dir)
                                .put("task", step)
                                .put("phase", phase),
                        requestId));
    }

    private void publishStepFinish(long requestId, String dir, String step, String phase, String status) {
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
                                .put("phase", phase)
                                .put("status", status),
                        requestId));
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
        return group == null ? "" : group;
    }

    private void publishOutput(long requestId, String dir, String step, String line) {
        if (!eventsWanted()) return;
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

    /** Failure detail is bounded on the wire: a compile explosion must not flood the event stream. */
    private static final int MAX_DIAGNOSTIC_EVENTS = 8;

    /** Publish structured {@link BuildPlanResult.Diagnostic}s for a failed request card. */
    private void publishDiagnostics(long requestId, String dir, java.util.List<BuildPlanResult.Diagnostic> errors) {
        if (!eventsWanted() || errors.isEmpty()) return;
        int shown = Math.min(errors.size(), MAX_DIAGNOSTIC_EVENTS);
        for (int i = 0; i < shown; i++) {
            BuildPlanResult.Diagnostic d = errors.get(i);
            // type "error" matches CLI JsonlShape; SSE event name stays "diagnostic" for the SPA.
            publishEvent(
                    "diagnostic",
                    withProgress(
                            cc.jumpkick.engine.http.JsonOut.object()
                                    .put("schema", 1)
                                    .put("type", "error")
                                    .put("requestId", requestId)
                                    .put("dir", dir)
                                    .put("task", d.step())
                                    .put("code", d.code())
                                    .put("message", redactEnv(dir, d.message()))
                                    .put("test", d.test())
                                    .put("exceptionClass", d.exceptionClass()),
                            requestId));
        }
        if (errors.size() > shown) {
            publishRequestError(requestId, dir, "+ " + (errors.size() - shown) + " more errors — see the CLI output");
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
     * Fine-grained module pipeline ticks for the dashboard (step detail). Aggregate % on SSE/MCP:
     * workspace builds use {@link cc.jumpkick.runtime.WorkspaceProgressTracker}; single-pipeline
     * jobs (build/test/compile) have no tracker yet — the pipeline <em>is</em> the whole request, so
     * feed {@link #lastProgressByRequest} from this view.
     */
    private void publishBuildPlanProgress(long requestId, String dir, BuildPlanView view) {
        if (requestId > 0 && view != null && !progressTrackers.containsKey(requestId) && view.denominator() > 0) {
            double p = cc.jumpkick.runtime.WorkspaceProgressTracker.percentOf(view.numerator(), view.denominator());
            if (!Double.isNaN(p)) lastProgressByRequest.put(requestId, p);
        }
        if (!eventsWanted()) return;
        publishEvent(
                "pipeline-progress",
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
     * {@link #publishEvent} so hot listener callbacks (per-pipeline, per-module) pay one boolean check,
     * not a {@code JsonOut} allocation, when no dashboard is open.
     */
    private boolean eventsWanted() {
        return httpEvents != null && httpEvents.hasSubscribers();
    }

    /**
     * After the last in-flight pipeline finishes: all idle housekeeping, with {@link System#gc()}
     * strictly last (after prune, journal/metrics retention, metrics harvest, and any host warmup).
     */
    private void maybeIdleBoundary() {
        if (activeBuildPlans.decrementAndGet() != 0) return;
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
     * Full GC only when no pipeline is in flight and no idle housekeeping is still running.
     * Prefer {@link #runIdleHousekeeping} so GC trails the whole workset.
     */
    private void maybeIdleGc() {
        if (activeBuildPlans.get() != 0 || warmupRunning.get()) return;
        System.gc();
    }

    /**
     * Coordinated idle chores. Order is fixed; {@link System#gc()} is always last for the workset.
     * Prune/journal/harvest run here; when warmup is needed a daemon does warmup + the trailing GC
     * instead (so the build connection is not blocked for multi-minute AOT train).
     */
    private void runIdleHousekeeping() {
        if (shuttingDown) return;
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
    }

    /**
     * Free the exclusive fingerprint as soon as project-mutating pipeline work finishes (idempotent).
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
     * cadence is due — the engine-internal replacement for the detached {@code jk cache prune
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
        // If a pipeline is running, only queue; maybeIdleBoundary drains on finish.
        if (activeBuildPlans.get() == 0) {
            pendingWarmupForce.compareAndSet(null, Boolean.FALSE);
            runIdleHousekeeping();
        } else {
            scheduleHostWarmupIfNeeded(false);
        }
    }

    /**
     * Run the queued opportunistic prune, if any, now that no pipeline is in flight. Runs on the
     * finishing request's connection thread or the 12 h feed-refresh thread when already idle; a
     * pipeline that starts concurrently wins the {@link #cacheGate} race and the prune stays queued
     * for the next boundary. Mirrors the legacy {@code --background} flags: sweep on, TTL/budget
     * from {@code [cache]} config, {@code.prune.lock} held, {@code.last-pruned} stamped.
     */
    private void drainPendingPrune() {
        Path cache = pendingPruneCache.getAndSet(null);
        if (cache == null) return;
        if (!cacheGate.writeLock().tryLock()) {
            pendingPruneCache.compareAndSet(null, cache); // a new pipeline raced in — retry next boundary
            return;
        }
        try (FileChannel lockChan =
                FileChannel.open(cache.resolve(".prune.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock pruneLock = lockChan.tryLock();
            if (pruneLock == null) return; // another process's prune is running — it'll stamp.last-pruned
            try {
                var config = cc.jumpkick.config.JkCacheConfig.resolve();
                cc.jumpkick.run.BuildPlan pipeline = cc.jumpkick.runtime.CachePipelines.pruneBuildPlan(
                        cache,
                        config.recordTtlDays(),
                        false,
                        true,
                        config.storeBudgetConfigured() ? config.maxStoreSizeMb() + "M" : null,
                        false);
                cc.jumpkick.run.BuildPlanResult result = pipeline.run();
                if (result.success()) {
                    Files.writeString(
                            cache.resolve(cc.jumpkick.task.CachePruneScheduler.LAST_PRUNED_FILE),
                            Long.toString(clockMillis.getAsLong()),
                            StandardCharsets.UTF_8);
                    log.accept("jk engine: idle-boundary cache prune removed "
                            + pipeline.get(cc.jumpkick.runtime.CachePipelines.FILES)
                                    .orElse(0L)
                            + " files ("
                            + pipeline.get(cc.jumpkick.runtime.CachePipelines.BYTES)
                                    .orElse(0L)
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

    /** Decode the request, reconstruct a {@link Session}/{@link WorkspaceRequest}, and run it. */
    private void runBuild(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            String entryDirStr = Jsonl.str(requestLine, "dir");
            String cacheStr = Jsonl.str(requestLine, "cache");
            String jdksDirStr = Jsonl.str(requestLine, "jdksDir");
            int workers = Jsonl.intValue(requestLine, "workers", 0); // 0 = auto at run-tests
            String profile = Jsonl.str(requestLine, "profile");
            boolean skipTests = Jsonl.bool(requestLine, "skipTests", false);
            boolean verbose = Jsonl.bool(requestLine, "verbose", false);
            int maxModuleConcurrency = Jsonl.intValue(requestLine, "maxModuleConcurrency", 0);
            boolean parallelTests = Jsonl.bool(requestLine, "parallelTests", false);
            boolean offline = Jsonl.bool(requestLine, "offline", false);
            boolean force = Jsonl.bool(requestLine, "force", false);
            // Distinct from force: rerun bypasses the action cache / freshness stamps without
            // implying refresh, so locked dependencies still come from the local CAS (jk verify).
            boolean rerun = Jsonl.bool(requestLine, "rebuild", false);
            // jk build sends true (auto-freshen a stale workspace lock engine-side before building);
            // jk verify's scratch rebuild sends false (pinned lock used verbatim).
            boolean freshenLock = Jsonl.bool(requestLine, "freshenLock", false);
            // jk verify only: scratch-salted action keys never recur — tasks must not persist them.
            boolean ephemeralActions = Jsonl.bool(requestLine, "ephemeralActions", false);

            Path entryDir = Path.of(entryDirStr);
            Path cache = Path.of(cacheStr);
            Path jdksDir = jdksDirStr != null ? Path.of(jdksDirStr) : null;

            JkBuild entryBuild = JkBuildParser.parse(entryDir.resolve("jk.toml"));
            WorkspaceRequest req = new WorkspaceRequest(
                            entryDir,
                            entryBuild,
                            cache,
                            jdksDir,
                            workers,
                            profile,
                            skipTests,
                            verbose,
                            maxModuleConcurrency,
                            null, // engine forecasts dirty modules
                            false, // this engine plans memory once at startup, not per request
                            freshenLock)
                    .withEphemeralActions(ephemeralActions)
                    .withVariant(EngineProtocol.variantOf(requestLine), EngineProtocol.clientEnvOf(requestLine));

            JkConfig config = new JkConfig(
                    Optional.empty(),
                    Optional.of(offline),
                    Optional.of(rerun),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(verbose),
                    Optional.empty(),
                    Optional.of(force),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());
            Session session = Session.defaults()
                    .withConfig(config)
                    .withWorkingDir(entryDir)
                    .withCacheDir(cache)
                    .withJdksDir(jdksDir)
                    .withParallelTests(parallelTests)
                    .withTestSelection(EngineProtocol.testSelectionOf(requestLine))
                    .withCancel(cancelToken)
                    .withJvm(EngineProtocol.jvmTuning(requestLine));

            long rid = eventRequestId();
            if (rid > 0) progressRoots.put(rid, entryDirStr);
            WorkspaceBuildListener listener = wireListener(writer, entryDirStr);
            WorkspaceResult result = SessionContext.where(session, () -> BuildService.buildWorkspace(req, listener));
            // Exclusive build work is done; free the fingerprint before finish events / bookkeeping.
            releaseExclusiveSlot();
            // User/deadline cancel may set the token after modules already failed — trust either flag.
            boolean cancelled = result.cancelled() || effectiveCancelled(rid, cancelToken.cancelled());
            accOutcome(rid, result.success() && !cancelled, result.exitCode());
            if (rid > 0) {
                // finish pins 100%/done — a failed build keeps its last true percent.
                if (result.success() && !cancelled) progressTracker(rid).finish();
                emitWorkspaceProgress(rid, writer, true);
            }
            // Chrome timeline before terminal event so the client still has the socket open.
            flushTimelineToClient(rid, writer);
            java.util.List<String> safeErrors = result.errors().stream()
                    .map(err -> redactEnv(entryDirStr, err))
                    .toList();
            // Always terminal with cancelled so the CLI never sees a bare disconnect after Ctrl-C /
            // jk cancel / web cancel.
            send(
                    writer,
                    EngineProtocol.workspaceFinish(
                            result.success() && !cancelled, result.exitCode(), safeErrors, cancelled));
            if (!result.success() && !cancelled) {
                for (String error : safeErrors.stream().limit(5).toList()) {
                    publishRequestError(eventRequestId(), entryDirStr, error);
                }
            }
        } catch (Exception e) {
            String dir = Jsonl.str(requestLine, "dir");
            long rid = eventRequestId();
            boolean cancelled = effectiveCancelled(rid, cancelToken.cancelled());
            if (cancelled) {
                // Cancelled mid-flight: settle as cancelled, not a crash / request-failed.
                sendQuiet(writer, EngineProtocol.workspaceFinish(false, 1, List.of(), true));
            } else {
                String msg = redactEnv(dir, String.valueOf(e.getMessage()));
                sendQuiet(writer, requestFailedLine(dir, msg));
                publishRequestError(rid, dir, msg);
            }
        }
    }

    /**
     * As {@link #handleBuildRequest}, but for a single project's test pipeline (Task 3): forks the run
     * onto its own thread and keeps reading the connection for a cancel/EOF meanwhile.
     */
    private void handleTestRequest(String requestLine, BufferedReader reader, BufferedWriter writer) {
        handleAsyncBuildPlanRequest(requestLine, reader, writer, "jk-engine-test-", "test", this::runTest);
    }

    /**
     * As {@link #handleTestRequest}, but for a single (non-workspace) project's real build pipeline — the
     * engine-hosted counterpart of {@code BuildCommand.runForDir}.
     */
    private void handleSingleBuildRequest(String requestLine, BufferedReader reader, BufferedWriter writer) {
        handleAsyncBuildPlanRequest(requestLine, reader, writer, "jk-engine-1build-", "build", this::runSingleBuild);
    }

    /** {@link EngineProtocol#PROJECT_INFO_REQUEST}: synchronous project summary. */
    private void handleProjectInfoRequest(String requestLine, BufferedWriter writer) {
        cc.jumpkick.engine.protocol.ProjectInfo info;
        try {
            info = cc.jumpkick.runtime.ExecPlans.projectInfo(Path.of(Jsonl.str(requestLine, "dir")));
        } catch (RuntimeException e) {
            info = cc.jumpkick.engine.protocol.ProjectInfo.error(String.valueOf(e.getMessage()));
        }
        sendQuiet(writer, info.encode());
    }

    /**
     * Answer {@link EngineProtocol#OUTDATED_REQUEST}: the read-only {@code jk outdated} report.
     * Synchronous, inline — parse + version enumeration only, never writes jk-lock.toml. The session's
     * offline/force flags ride the request so version enumeration honors them (metadata TTL bypass,
     * stale-but-usable when offline). Errors ride the ack's {@code error} field.
     */
    private void handleOutdatedRequest(String requestLine, BufferedWriter writer) {
        cc.jumpkick.engine.protocol.OutdatedReport report;
        try {
            Path dir = Path.of(Jsonl.str(requestLine, "dir"));
            Path cache = Path.of(Jsonl.str(requestLine, "cache"));
            String repoUrl = Jsonl.str(requestLine, "repoUrl");
            JkConfig config = new JkConfig(
                    Optional.empty(),
                    Optional.of(Jsonl.bool(requestLine, "offline", false)),
                    Optional.of(Jsonl.bool(requestLine, "rebuild", false)),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(Jsonl.bool(requestLine, "force", false)),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());
            Session session =
                    Session.defaults().withConfig(config).withWorkingDir(dir).withCacheDir(cache);
            report = SessionContext.where(
                    session,
                    () -> cc.jumpkick.runtime.OutdatedPipelines.compute(
                            dir, cache, repoUrl == null ? null : java.net.URI.create(repoUrl)));
        } catch (Exception e) {
            report = cc.jumpkick.engine.protocol.OutdatedReport.error(String.valueOf(e.getMessage()));
        }
        sendQuiet(writer, report.encode());
    }

    /**
     * Answer {@link EngineProtocol#EXEC_PLAN_REQUEST}: a complete execution plan (run/dev argv,
     * install layout, aot-cache layout) — the engine decides, the client executes. Synchronous,
     * read-only, inline.
     */
    /** Answer {@link EngineProtocol#TREE_REQUEST}: the marker-tagged dependency tree, engine-side. */
    private void handleTreeRequest(String requestLine, BufferedWriter writer) {
        String error = null;
        String rendered = null;
        try {
            rendered = cc.jumpkick.runtime.GraphOps.treeRender(
                    Path.of(Jsonl.str(requestLine, "dir")),
                    Jsonl.intValue(requestLine, "maxDepth", Integer.MAX_VALUE),
                    Jsonl.bool(requestLine, "flatten", false),
                    Jsonl.bool(requestLine, "stack", false),
                    Jsonl.strArray(requestLine, "scopes"));
        } catch (java.io.IOException | RuntimeException e) {
            error = String.valueOf(e.getMessage());
        }
        sendQuiet(writer, EngineProtocol.treeAck(error, rendered));
    }

    /** Answer {@link EngineProtocol#WHY_REQUEST}: lock matches + provenance paths, engine-side. */
    private void handleWhyRequest(String requestLine, BufferedWriter writer) {
        cc.jumpkick.engine.protocol.WhyReport report;
        try {
            report = cc.jumpkick.runtime.GraphOps.why(
                    Path.of(Jsonl.str(requestLine, "dir")), Jsonl.str(requestLine, "query"));
        } catch (RuntimeException e) {
            report = cc.jumpkick.engine.protocol.WhyReport.error(String.valueOf(e.getMessage()));
        }
        sendQuiet(writer, report.encode());
    }

    private static final java.util.Set<String> DELEGATABLE = java.util.Set.of(
            EngineProtocol.BUILD_REQUEST,
            EngineProtocol.TEST_REQUEST,
            EngineProtocol.SINGLE_BUILD_REQUEST,
            EngineProtocol.COMPILE_REQUEST,
            EngineProtocol.NATIVE_REQUEST,
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

    /** Answer {@link EngineProtocol#PLUGIN_VERB_REQUEST}: a plugin-declared command, worker-executed. */
    private void handlePluginCommandRequest(String requestLine, BufferedWriter writer) {
        cc.jumpkick.engine.protocol.PluginCommandReport report;
        try {
            report = cc.jumpkick.runtime.PluginCommands.run(
                    Path.of(Jsonl.str(requestLine, "dir")),
                    Path.of(Jsonl.str(requestLine, "cache")),
                    Jsonl.str(requestLine, "command"),
                    Jsonl.strArray(requestLine, "args"),
                    EngineProtocol.variantOf(requestLine),
                    EngineProtocol.clientEnvOf(requestLine));
        } catch (RuntimeException e) {
            report = cc.jumpkick.engine.protocol.PluginCommandReport.error(String.valueOf(e.getMessage()));
        }
        sendQuiet(writer, report.encode());
    }

    /** Answer {@link EngineProtocol#GENERATE_REQUEST}: full-model generator payloads, engine-side. */
    private void handleGenerateRequest(String requestLine, BufferedWriter writer) {
        cc.jumpkick.engine.protocol.GeneratedFiles files;
        try {
            files = cc.jumpkick.runtime.GenerateOps.generate(
                    Path.of(Jsonl.str(requestLine, "dir")),
                    Jsonl.str(requestLine, "kind"),
                    EngineProtocol.generateParams(requestLine));
        } catch (RuntimeException e) {
            files = cc.jumpkick.engine.protocol.GeneratedFiles.error(String.valueOf(e.getMessage()));
        }
        sendQuiet(writer, files.encode());
    }

    /** Answer {@link EngineProtocol#IDE_MODEL_REQUEST}: the IDE-agnostic workspace model, engine-side. */
    private void handleIdeModelRequest(String requestLine, BufferedWriter writer) {
        cc.jumpkick.engine.protocol.IdeWireModel model;
        try {
            String jdksDir = Jsonl.str(requestLine, "jdksDir");
            model = cc.jumpkick.runtime.IdeOps.ideModel(
                    Path.of(Jsonl.str(requestLine, "dir")),
                    Path.of(Jsonl.str(requestLine, "cache")),
                    jdksDir == null ? null : Path.of(jdksDir),
                    false);
        } catch (RuntimeException e) {
            model = cc.jumpkick.engine.protocol.IdeWireModel.error(String.valueOf(e.getMessage()));
        }
        sendQuiet(writer, model.encode());
    }

    /** Answer {@link EngineProtocol#DENY_CHECK_REQUEST}: policy parse + lock read + check, engine-side. */
    private void handleDenyCheckRequest(String requestLine, BufferedWriter writer) {
        cc.jumpkick.engine.protocol.DenyReport report;
        try {
            report = cc.jumpkick.runtime.PolicyOps.denyCheck(Path.of(Jsonl.str(requestLine, "dir")));
        } catch (RuntimeException e) {
            report = cc.jumpkick.engine.protocol.DenyReport.error(String.valueOf(e.getMessage()));
        }
        sendQuiet(writer, report.encode());
    }

    /** Answer {@link EngineProtocol#EDIT_REQUEST}: one named jk.toml edit, engine-side. */
    private void handleEditRequest(String requestLine, BufferedWriter writer) {
        cc.jumpkick.runtime.EditOps.Result result;
        try {
            result = cc.jumpkick.runtime.EditOps.apply(
                    Path.of(Jsonl.str(requestLine, "file")),
                    Jsonl.str(requestLine, "op"),
                    Jsonl.strArray(requestLine, "args"));
        } catch (RuntimeException e) {
            result = new cc.jumpkick.runtime.EditOps.Result(false, String.valueOf(e.getMessage()));
        }
        sendQuiet(writer, EngineProtocol.editAck(result.changed(), result.error()));
    }

    private void handleExecPlanRequest(String requestLine, BufferedWriter writer) {
        cc.jumpkick.engine.protocol.ExecPlan plan;
        try {
            String binDir = Jsonl.str(requestLine, "binDir");
            String libDir = Jsonl.str(requestLine, "libDir");
            plan = cc.jumpkick.runtime.ExecPlans.execPlan(
                    Path.of(Jsonl.str(requestLine, "dir")),
                    Path.of(Jsonl.str(requestLine, "cache")),
                    Jsonl.str(requestLine, "kind"),
                    Jsonl.str(requestLine, "mainOverride"),
                    Jsonl.str(requestLine, "binName"),
                    binDir == null ? null : Path.of(binDir),
                    libDir == null ? null : Path.of(libDir),
                    EngineProtocol.variantOf(requestLine),
                    EngineProtocol.clientEnvOf(requestLine));
        } catch (RuntimeException e) {
            plan = cc.jumpkick.engine.protocol.ExecPlan.error("unknown", String.valueOf(e.getMessage()));
        }
        sendQuiet(writer, plan.encode());
    }

    private void handleForecastRequest(String requestLine, BufferedWriter writer) {
        try {
            Path entryDir = Path.of(Jsonl.str(requestLine, "dir"));
            Path cache = Path.of(Jsonl.str(requestLine, "cache"));
            boolean skipTests = Jsonl.bool(requestLine, "skipTests", false);
            JkConfig config = new JkConfig(
                    Optional.empty(),
                    Optional.of(Jsonl.bool(requestLine, "offline", false)),
                    Optional.of(Jsonl.bool(requestLine, "rebuild", false)),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(Jsonl.bool(requestLine, "force", false)),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());
            Session session = Session.defaults()
                    .withConfig(config)
                    .withWorkingDir(entryDir)
                    .withCacheDir(cache);
            JkBuild entryBuild = JkBuildParser.parse(entryDir.resolve("jk.toml"));
            SessionContext.where(session, () -> {
                BuildService.ResolvedGraph graph;
                try {
                    graph = BuildService.resolveGraph(entryDir, entryBuild);
                } catch (java.io.IOException e) {
                    sendQuiet(
                            writer,
                            EngineProtocol.forecastAck(
                                    List.of(), false, false, List.of(String.valueOf(e.getMessage()))));
                    return null;
                }
                if (graph.hasErrors()) {
                    sendQuiet(writer, EngineProtocol.forecastAck(List.of(), false, false, graph.errors()));
                    return null;
                }
                List<String> dirty = new java.util.ArrayList<>();
                for (Path d : BuildService.forecastDirtyDirs(graph, cache, skipTests, entryDir))
                    dirty.add(d.toString());
                boolean lockStale = BuildService.workspaceLockStale(
                        entryDir, entryBuild, cc.jumpkick.lock.LockPaths.lockFile(entryDir));
                sendQuiet(writer, EngineProtocol.forecastAck(dirty, lockStale, graph.isEmpty(), List.of()));
                return null;
            });
        } catch (Exception e) {
            sendQuiet(
                    writer,
                    EngineProtocol.forecastAck(List.of(), false, false, List.of(String.valueOf(e.getMessage()))));
        }
    }

    /**
     * Forecast a build via {@link BuildService#explain} and stream the plan as a burst of
     * module/step/edge messages — the engine-hosted counterpart of {@code ExplainCommand}'s call
     * into the same facade. Synchronous and inline (no worker JVM forked, no cancel/EOF fork needed
     * unlike {@link #handleBuildRequest}/{@link #handleTestRequest}/{@link #handleSingleBuildRequest}).
     */
    private void handleExplainRequest(String requestLine, BufferedWriter writer) {
        try {
            String entryDirStr = Jsonl.str(requestLine, "dir");
            String cacheStr = Jsonl.str(requestLine, "cache");
            Path entryDir = Path.of(entryDirStr);
            Path cache = Path.of(cacheStr);
            // --redo rides the same session flag as jk build --redo so forecast
            // (all steps RUN) and ETA (build:rebuild history) match the live rebuild path.
            boolean rebuild = Jsonl.bool(requestLine, "rebuild", false);
            boolean force = Jsonl.bool(requestLine, "force", false);
            boolean offline = Jsonl.bool(requestLine, "offline", false);
            boolean verbose = Jsonl.bool(requestLine, "verbose", false);
            JkConfig config = new JkConfig(
                    Optional.empty(),
                    Optional.of(offline),
                    Optional.of(rebuild),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(verbose),
                    Optional.empty(),
                    Optional.of(force),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());
            Session session = Session.defaults()
                    .withConfig(config)
                    .withWorkingDir(entryDir)
                    .withCacheDir(cache);
            JkBuild entryBuild = JkBuildParser.parse(entryDir.resolve("jk.toml"));
            ExplainPlan plan = SessionContext.where(session, () -> BuildService.explain(entryDir, entryBuild, cache));
            if (plan.hasErrors()) {
                for (String err : plan.errors()) {
                    sendQuiet(writer, requestFailedLine(entryDir.toString(), err));
                }
                sendQuiet(writer, EngineProtocol.explainDone(1, 0));
                return;
            }
            for (cc.jumpkick.runtime.TaskForecast.Module m : plan.modules()) {
                String dir = m.dir().toString();
                sendQuiet(
                        writer,
                        EngineProtocol.explainModule(
                                dir, m.coord(), m.sourceCount(), m.testCount(), m.producesJar(), m.producesImage()));
                for (cc.jumpkick.runtime.TaskForecast.Task p : m.steps()) {
                    sendQuiet(
                            writer,
                            EngineProtocol.explainStep(dir, p.name(), p.status().name(), p.text(), p.key()));
                }
            }
            for (var e : plan.edges().entrySet()) {
                for (Path dep : e.getValue()) {
                    sendQuiet(writer, EngineProtocol.explainEdge(e.getKey().toString(), dep.toString()));
                }
            }
            // Schedule-aware ETA; 0 = unknown. Under same session as explain (rebuild/force).
            String etaJdksDirStr = Jsonl.str(requestLine, "jdksDir");
            long etaMillis = SessionContext.where(
                    session,
                    () -> BuildService.estimateEtaMillis(
                            plan,
                            entryDir,
                            cache,
                            Jsonl.intValue(requestLine, "workers", 0),
                            etaJdksDirStr != null ? Path.of(etaJdksDirStr) : null,
                            Jsonl.str(requestLine, "profile"),
                            Jsonl.bool(requestLine, "skipTests", false),
                            verbose,
                            Jsonl.bool(requestLine, "serial", false),
                            Jsonl.bool(requestLine, "parallelTests", false)));
            sendQuiet(writer, EngineProtocol.eta(etaMillis));
            sendQuiet(
                    writer,
                    EngineProtocol.explainDone(
                            plan.maxReadyWidth(), plan.modules().size()));
        } catch (Exception e) {
            sendQuiet(writer, requestFailedLine(null, e));
            sendQuiet(writer, EngineProtocol.explainDone(0, 0));
        }
    }

    /**
     * Build and run the test-only {@code BuildPlan} exactly as {@code TestCommand} does in-process, but
     * streaming its {@link BuildPlanListener} events over the wire via {@link #wireBuildPlanListener} — the
     * same single-pipeline event vocabulary {@link #runBuild} already speaks per module, here tagged with
     * the fixed {@link EngineProtocol#SINGLE_PIPELINE_DIR} sentinel since there's only one pipeline.
     */
    private void runTest(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            String entryDirStr = Jsonl.str(requestLine, "dir");
            String cacheStr = Jsonl.str(requestLine, "cache");
            String jdksDirStr = Jsonl.str(requestLine, "jdksDir");
            // 0 = auto (JUnitLauncher resolves min(jobs, classes) + heap clamp).
            int workers = Jsonl.intValue(requestLine, "workers", 0);
            String profile = Jsonl.str(requestLine, "profile");
            boolean verbose = Jsonl.bool(requestLine, "verbose", false);
            boolean offline = Jsonl.bool(requestLine, "offline", false);
            boolean force = Jsonl.bool(requestLine, "force", false);
            boolean parallelTests = Jsonl.bool(requestLine, "parallelTests", false);

            Path entryDir = Path.of(entryDirStr);
            Path cache = Path.of(cacheStr);
            Path jdksDir = jdksDirStr != null ? Path.of(jdksDirStr) : null;
            Path buildFile = entryDir.resolve("jk.toml");
            Path lockFile = cc.jumpkick.lock.LockPaths.lockFile(entryDir);
            int workerCount = Math.max(0, workers);

            // Size the bar/ETA to the SELECTED suites, simple/traditional roots incl.
            boolean compactTests = cc.jumpkick.layout.ModuleLayout.isCompact(entryDir);
            int estimatedTestCount = cc.jumpkick.runtime.TestSupport.estimateSelectedSuiteTestCount(
                    entryDir, compactTests, EngineProtocol.testSelectionOf(requestLine));

            // The request's cache-relevant flags ride the session config exactly as
            // runSingleBuild's do — without this, `jk test --force` was silently
            // dropped on the hosted path (the run-tests stamp skip guards on rerunOr,
            // which reads the force flag).
            JkConfig config = new JkConfig(
                    Optional.empty(),
                    Optional.of(offline),
                    Optional.of(Jsonl.bool(requestLine, "rebuild", false)),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(verbose),
                    Optional.empty(),
                    Optional.of(force),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());
            Session session = Session.defaults()
                    .withConfig(config)
                    .withWorkingDir(entryDir)
                    .withCacheDir(cache)
                    .withJdksDir(jdksDir)
                    .withCancel(cancelToken)
                    .withJvm(EngineProtocol.jvmTuning(requestLine))
                    .withParallelTests(parallelTests)
                    .withTestSelection(EngineProtocol.testSelectionOf(requestLine));

            // The session rides Inputs EXPLICITLY (canonical constructor): the delegating
            // constructors capture SessionContext.current at construction time, which here
            // outside SessionContext.where — is the engine's ambient default, not this request.
            // Steps read in.session for the force/rerun guards, so the ambient capture was
            // exactly how `--force` got dropped.
            cc.jumpkick.runtime.BuildPipelines.Inputs inputs = new cc.jumpkick.runtime.BuildPipelines.Inputs(
                            entryDir,
                            cache,
                            buildFile,
                            lockFile,
                            lockFile.getParent(),
                            workerCount,
                            estimatedTestCount,
                            profile,
                            jdksDir,
                            /* skipTests */ false,
                            verbose,
                            /* testOnly */ true,
                            /* compileOnly */ false,
                            java.util.Set.of(),
                            session)
                    .withVariant(EngineProtocol.variantOf(requestLine), EngineProtocol.clientEnvOf(requestLine));
            cc.jumpkick.run.BuildPlan pipeline =
                    cc.jumpkick.runtime.BuildPipelines.coreBuilder(inputs).build();

            String dir = EngineProtocol.SINGLE_PIPELINE_DIR;
            for (Task p : pipeline.steps()) {
                sendQuiet(
                        writer,
                        EngineProtocol.planStep(
                                dir, p.name(), p.label(), phaseWire(p.phase().orElse(null))));
            }
            sendQuiet(writer, EngineProtocol.planDone(1));
            pipeline.addListener(wireBuildPlanListener(dir, writer, pipeline));

            cc.jumpkick.run.BuildPlanResult result = SessionContext.where(session, pipeline::run);
            // pipelineFinish already sent; free exclusive slot before bookkeeping (see releaseExclusiveSlot).
            releaseExclusiveSlot();
            accTests(
                    eventRequestId(),
                    pipeline.get(cc.jumpkick.runtime.BuildPipelines.TEST_RESULT).orElse(null));
            accOutcome(eventRequestId(), result.success(), result.success() ? 0 : 1);
            // pipelineFinish (with test counts, if any) was already sent by wireBuildPlanListener's own
            // pipelineFinish handling — nothing further to send here; the connection close signals "done".
        } catch (Exception e) {
            sendQuiet(writer, requestFailedLine(null, e));
        }
    }

    /**
     * Single-project build pipeline (same wire shape as {@link #runTest}, {@code testOnly=false}).
     * On success: update host calibration and queue idle-boundary cache prune.
     */
    private void runSingleBuild(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            String entryDirStr = Jsonl.str(requestLine, "dir");
            String cacheStr = Jsonl.str(requestLine, "cache");
            String jdksDirStr = Jsonl.str(requestLine, "jdksDir");
            int workers = Jsonl.intValue(requestLine, "workers", 0);
            String profile = Jsonl.str(requestLine, "profile");
            boolean skipTests = Jsonl.bool(requestLine, "skipTests", false);
            boolean verbose = Jsonl.bool(requestLine, "verbose", false);
            boolean offline = Jsonl.bool(requestLine, "offline", false);
            boolean force = Jsonl.bool(requestLine, "force", false);

            Path entryDir = Path.of(entryDirStr);
            Path cache = Path.of(cacheStr);
            Path jdksDir = jdksDirStr != null ? Path.of(jdksDirStr) : null;
            Path buildFile = entryDir.resolve("jk.toml");
            Path lockFile = cc.jumpkick.lock.LockPaths.lockFile(entryDir);
            int workerCount = Math.max(0, workers);

            int estimatedTestCount = skipTests
                    ? 0
                    : cc.jumpkick.runtime.TestSupport.estimateSelectedSuiteTestCount(
                            entryDir,
                            cc.jumpkick.layout.ModuleLayout.isCompact(entryDir),
                            EngineProtocol.testSelectionOf(requestLine));

            // resolveSession carries assemblyOverride / rebuild / force from the wire envelope.
            Session session = resolveSession(requestLine, cancelToken, false).withJdksDir(jdksDir);

            // Session threaded explicitly — see runTest: the delegating Inputs constructors
            // capture the engine's ambient session at construction, dropping --force/--offline.
            cc.jumpkick.runtime.BuildPipelines.Inputs inputs = new cc.jumpkick.runtime.BuildPipelines.Inputs(
                            entryDir,
                            cache,
                            buildFile,
                            lockFile,
                            lockFile.getParent(),
                            workerCount,
                            estimatedTestCount,
                            profile,
                            jdksDir,
                            skipTests,
                            verbose,
                            /* testOnly */ false,
                            /* compileOnly */ false,
                            java.util.Set.of(),
                            session)
                    .withVariant(EngineProtocol.variantOf(requestLine), EngineProtocol.clientEnvOf(requestLine));
            // BuildPlan construction must see session.assemblyOverride (applyAssemblyOverride);
            // run under SessionContext.where so ambient helpers agree with Inputs.session.
            cc.jumpkick.run.BuildPlan pipeline = SessionContext.where(session, () -> {
                cc.jumpkick.run.BuildPlan.Builder builder =
                        cc.jumpkick.runtime.BuildPipelines.coreBuilder(inputs, false);
                cc.jumpkick.runtime.BuildPipelines.appendDeclaredTails(builder, inputs);
                return builder.build();
            });
            long barWeight = pipeline.estimatedTotalWeight();

            String dir = EngineProtocol.SINGLE_PIPELINE_DIR;
            for (Task p : pipeline.steps()) {
                sendQuiet(
                        writer,
                        EngineProtocol.planStep(
                                dir, p.name(), p.label(), phaseWire(p.phase().orElse(null))));
            }
            sendQuiet(writer, EngineProtocol.planDone(1));
            pipeline.addListener(wireBuildPlanListener(dir, writer, pipeline));

            // snapshot graph + fingerprints BEFORE the run — a post-build fingerprint
            // would record mid-build edits as clean. Guard on the resolved session, not the
            // ambient one (the request thread's ambient session is the engine default).
            BuildGraph.Result preGraph = null;
            java.util.Map<Path, String> preFps = null;
            if (!session.config().rebuildOr(false) && !session.config().forceOr(false)) {
                try {
                    // Path-based parse: applyWorkspace must run, or a thin member manifest
                    // yields sentinel group/version and the memo silently never stores.
                    cc.jumpkick.model.JkBuild entry = cc.jumpkick.config.JkBuildParser.parse(buildFile);
                    BuildGraph.Result g = BuildGraph.resolve(entryDir, entry);
                    if (!g.hasErrors()) {
                        preGraph = g;
                        preFps = PreflightMemo.snapshotFingerprints(g, skipTests);
                    }
                } catch (Exception ignored) {
                    // fail-open
                }
            }

            long startNanos = System.nanoTime();
            cc.jumpkick.run.BuildPlanResult result = SessionContext.where(session, pipeline::run);
            // pipelineFinish already sent; free exclusive slot before calibration / memo / prune queue.
            releaseExclusiveSlot();
            accTests(
                    eventRequestId(),
                    pipeline.get(cc.jumpkick.runtime.BuildPipelines.TEST_RESULT).orElse(null));
            accOutcome(eventRequestId(), result.success(), result.success() ? 0 : 1);
            if (result.success() && barWeight > 0) {
                long moduleMs = (System.nanoTime() - startNanos) / 1_000_000;
                if (moduleMs > 0) {
                    cc.jumpkick.runtime.Calibration.refine(moduleMs / (double) barWeight, System.currentTimeMillis());
                }
                maybeEnqueuePrune(cache);
            }
            // single-module success → preflight dirty memo = all clean (parity with
            // workspace), using the pre-run snapshot.
            if (result.success() && preGraph != null && preFps != null) {
                PreflightMemo.storeDirty(entryDir, preGraph, skipTests, java.util.Set.of(), preFps);
                PreflightMemo.storeGraph(entryDir, preGraph);
            }
        } catch (Exception e) {
            sendQuiet(writer, requestFailedLine(null, e));
        }
    }

    /**
     * Decode a {@link EngineProtocol#LOCK_REQUEST} and run {@code jk lock}'s cascade in-session:
     * the entry project, then (for a workspace root) each declared module in declaration order
     * each module a {@link EngineProtocol#LOCK_MODULE} + plan-step burst + the standard pipeline
     * events, ending in a {@link EngineProtocol#LOCK_FINISH} terminal. Per-package resolution
     * streams as {@link EngineProtocol#LOCK_PACKAGE} (plain structured text; the client formats and
     * colorizes). Forge tokens for git-source materialization resolve exactly as in the CLI — the
     * same {@code JK_HOME} / platform product layout token store and environment, which this engine process inherits from its
     * spawner.
     */
    private void runLock(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            java.util.List<String> features = Jsonl.strArray(requestLine, "features");
            boolean withDefaults = !Jsonl.bool(requestLine, "noDefaultFeatures", false);
            boolean sources = Jsonl.bool(requestLine, "sources", false);
            boolean conservative = Jsonl.bool(requestLine, "conservative", false);
            Session session = resolveSession(requestLine, cancelToken, false);
            java.net.URI repoUrl = repoUrlOf(requestLine);
            SessionContext.where(session, () -> {
                lockCascade(
                        session.workingDir(),
                        session.cacheDir(),
                        repoUrl,
                        features,
                        withDefaults,
                        sources,
                        false,
                        null,
                        conservative,
                        writer);
                return null;
            });
        } catch (Exception e) {
            sendQuiet(writer, requestFailedLine(null, e));
        }
    }

    /**
     * Decode a {@link EngineProtocol#UPDATE_REQUEST}: either the full re-resolve cascade (riding
     * {@link #runLock}'s exact event vocabulary, with {@code jk update}'s always-fresh pipeline) or the
     * {@code --git} splice mode, which runs no pipeline at all — just the {@link
     * EngineProtocol#LOCK_FINISH} terminal carrying the refreshed count.
     */
    private void runUpdate(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            java.util.List<String> features = Jsonl.strArray(requestLine, "features");
            boolean withDefaults = !Jsonl.bool(requestLine, "noDefaultFeatures", false);
            boolean gitOnly = Jsonl.bool(requestLine, "gitOnly", false);
            String gitTarget = Jsonl.str(requestLine, "gitTarget");
            Session session = resolveSession(requestLine, cancelToken, false);
            java.net.URI repoUrl = repoUrlOf(requestLine);
            String platformOverride = Jsonl.str(requestLine, "platform");
            if (platformOverride != null && platformOverride.isBlank()) platformOverride = null;
            String platformFinal = platformOverride;
            SessionContext.where(session, () -> {
                Path entryDir = session.workingDir();
                Path cache = session.cacheDir();
                if (gitOnly) {
                    java.nio.file.Files.createDirectories(cache);
                    JkBuild root;
                    try {
                        root = JkBuildParser.parse(entryDir.resolve("jk.toml"));
                    } catch (RuntimeException e) {
                        sendQuiet(
                                writer,
                                EngineProtocol.lockFinish(
                                        false,
                                        cc.jumpkick.model.command.Exit.CONFIG,
                                        java.util.List.of(String.valueOf(e.getMessage())),
                                        -1));
                        return null;
                    }
                    var outcome = cc.jumpkick.runtime.LockPipelines.updateGitOnly(
                            entryDir, root, cache, repoUrl, features, withDefaults, gitTarget);
                    sendQuiet(
                            writer,
                            EngineProtocol.lockFinish(
                                    outcome.exitCode() == 0,
                                    outcome.exitCode(),
                                    outcome.error() != null ? java.util.List.of(outcome.error()) : java.util.List.of(),
                                    outcome.refreshed()));
                } else {
                    lockCascade(entryDir, cache, repoUrl, features, withDefaults, false, true, platformFinal, writer);
                }
                return null;
            });
        } catch (Exception e) {
            sendQuiet(writer, requestFailedLine(null, e));
        }
    }

    /**
     * Decode a {@link EngineProtocol#SYNC_REQUEST} and run {@code jk sync}'s single pipeline in-session
     * — {@link EngineProtocol#TEST_REQUEST}'s exact wire shape, with the fetched/up-to-date counts
     * riding the terminal pipeline-finish. The pipeline is built with {@code allowJdkInstall = false}: JDK
     * installs never happen inside the engine (the client pre-flights them — see {@link
     * cc.jumpkick.runtime.SyncPipelines}). On success, queues the opportunistic cache prune for the
     * next idle boundary — the post-success step the CLI used to run, moved here since the engine
     * did the work.
     */
    private void runSync(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            boolean sources = Jsonl.bool(requestLine, "sources", false);
            boolean refresh = Jsonl.bool(requestLine, "refresh", false);
            String jdksDirStr = Jsonl.str(requestLine, "jdksDir");
            Path jdksDir = jdksDirStr != null ? Path.of(jdksDirStr) : null;
            Session session = resolveSession(requestLine, cancelToken, refresh).withJdksDir(jdksDir);
            java.net.URI repoUrl = repoUrlOf(requestLine);
            SessionContext.where(session, () -> {
                Path entryDir = session.workingDir();
                Path cache = session.cacheDir();
                java.nio.file.Files.createDirectories(cache);
                java.util.concurrent.atomic.AtomicInteger fetched = new java.util.concurrent.atomic.AtomicInteger();
                java.util.concurrent.atomic.AtomicInteger upToDate = new java.util.concurrent.atomic.AtomicInteger();
                cc.jumpkick.run.BuildPlan pipeline = cc.jumpkick.runtime.SyncPipelines.syncBuildPlan(
                        entryDir, cache, jdksDir, repoUrl, sources, fetched, upToDate, null, false);
                String dir = EngineProtocol.SINGLE_PIPELINE_DIR;
                for (Task p : pipeline.steps()) {
                    sendQuiet(
                            writer,
                            EngineProtocol.planStep(
                                    dir,
                                    p.name(),
                                    p.label(),
                                    phaseWire(p.phase().orElse(null))));
                }
                sendQuiet(writer, EngineProtocol.planDone(1));
                pipeline.addListener(
                        wireBuildPlanListener(dir, writer, (java.util.function.Function<BuildPlanResult, String>)
                                result -> EngineProtocol.pipelineFinishSync(
                                        dir, result.success(), fetched.get(), upToDate.get())));
                BuildPlanResult result = pipeline.run();
                if (result.success()) {
                    maybeEnqueuePrune(cache);
                }
                return null;
            });
        } catch (Exception e) {
            sendQuiet(writer, requestFailedLine(null, e));
        }
    }

    // ---- hosted worker commands ---------------------------------------------------------------------

    /**
     * Stream one single-pipeline command over the wire — the shared tail of every Wave-2 handler: the
     * {@link EngineProtocol#SINGLE_PIPELINE_DIR}-tagged plan-step burst, the standard pipeline events via
     * {@link #wireBuildPlanListener}, and {@code finishEncoder}'s terminal {@code pipeline-finish} variant.
     */
    private void streamSingleBuildPlan(
            cc.jumpkick.run.BuildPlan pipeline,
            Session session,
            BufferedWriter writer,
            java.util.function.Function<BuildPlanResult, String> finishEncoder)
            throws Exception {
        String dir = EngineProtocol.SINGLE_PIPELINE_DIR;
        for (Task p : pipeline.steps()) {
            sendQuiet(
                    writer,
                    EngineProtocol.planStep(
                            dir, p.name(), p.label(), phaseWire(p.phase().orElse(null))));
        }
        sendQuiet(writer, EngineProtocol.planDone(1));
        pipeline.addListener(wireBuildPlanListener(dir, writer, finishEncoder));
        SessionContext.where(session, pipeline::run);
    }

    /**
     * Decode an {@link EngineProtocol#AUDIT_REQUEST} and run {@code jk audit}'s pipeline in-session,
     * forking the auditor worker engine-side and streaming each finding as a structured {@link
     * EngineProtocol#AUDIT_FINDING} event (the client assembles/renders the report and applies the
     * severity threshold itself).
     */
    private void runAudit(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            Path entryDir = Path.of(Jsonl.str(requestLine, "dir"));
            Path cache = Path.of(Jsonl.str(requestLine, "cache"));
            String severity = Jsonl.str(requestLine, "severity");
            String batch = Jsonl.str(requestLine, "osvBatchUrl");
            String vulns = Jsonl.str(requestLine, "osvVulnsUrl");
            Session session = Session.defaults()
                    .withWorkingDir(entryDir)
                    .withCacheDir(cache)
                    .withCancel(cancelToken)
                    .withJvm(EngineProtocol.jvmTuning(requestLine));
            String dir = EngineProtocol.SINGLE_PIPELINE_DIR;
            cc.jumpkick.run.BuildPlan pipeline = cc.jumpkick.runtime.AuditPipelines.auditBuildPlan(
                    cc.jumpkick.lock.LockPaths.lockFile(entryDir),
                    cache,
                    severity,
                    batch != null ? java.net.URI.create(batch) : null,
                    vulns != null ? java.net.URI.create(vulns) : null,
                    (module, version, vulnId, sev, summary) ->
                            sendQuiet(writer, EngineProtocol.auditFinding(dir, module, version, vulnId, sev, summary)));
            streamSingleBuildPlan(
                    pipeline, session, writer, result -> EngineProtocol.pipelineFinish(dir, result.success()));
        } catch (Exception e) {
            sendQuiet(writer, requestFailedLine(null, e));
        }
    }

    /**
     * Decode a {@link EngineProtocol#FORMAT_REQUEST} and run {@code jk format}'s pipeline in-session:
     * source collection, formatter-jar resolution (through jk's own resolver — previously done in
     * the client process), and the formatter worker fork, with per-file results streaming as {@link
     * EngineProtocol#FORMAT_FILE} events and the counts riding the terminal pipeline-finish.
     */
    private void runFormat(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            boolean check = Jsonl.bool(requestLine, "check", false);
            String javaStyle = Jsonl.str(requestLine, "javaStyle");
            String kotlinStyle = Jsonl.str(requestLine, "kotlinStyle");
            boolean optimizeImports = Jsonl.bool(requestLine, "optimizeImports", true);
            String rewriteConfig = Jsonl.str(requestLine, "rewriteConfig");
            Session session = resolveSession(requestLine, cancelToken, false);
            String dir = EngineProtocol.SINGLE_PIPELINE_DIR;
            cc.jumpkick.run.BuildPlan pipeline = cc.jumpkick.runtime.FormatPipelines.formatBuildPlan(
                    session.workingDir(),
                    session.cacheDir(),
                    check,
                    javaStyle,
                    kotlinStyle,
                    optimizeImports,
                    rewriteConfig != null ? Path.of(rewriteConfig) : null,
                    (path, status, message, index, total) ->
                            sendQuiet(writer, EngineProtocol.formatFile(dir, path, status, message, index, total)));
            streamSingleBuildPlan(
                    pipeline,
                    session,
                    writer,
                    result -> EngineProtocol.pipelineFinishFormat(
                            dir,
                            result.success(),
                            pipeline.get(cc.jumpkick.runtime.FormatPipelines.CHANGED)
                                    .orElse(-1),
                            pipeline.get(cc.jumpkick.runtime.FormatPipelines.CLEAN)
                                    .orElse(-1),
                            pipeline.get(cc.jumpkick.runtime.FormatPipelines.ERRORS)
                                    .orElse(-1),
                            pipeline.get(cc.jumpkick.runtime.FormatPipelines.TOTAL)
                                    .orElse(-1),
                            pipeline.get(cc.jumpkick.runtime.FormatPipelines.WORKER_EXIT)
                                    .orElse(-1)));
        } catch (Exception e) {
            sendQuiet(writer, requestFailedLine(null, e));
        }
    }

    /**
     * Decode a {@link EngineProtocol#PUBLISH_REQUEST} and run {@code jk publish}'s pipeline in-session.
     * The credential/passphrase fields were resolved client-side (env/keychain live there); they
     * pass straight through to the worker's 0600 spec file and are never logged.
     */
    private void runPublish(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            Path entryDir = Path.of(Jsonl.str(requestLine, "dir"));
            Path cache = Path.of(Jsonl.str(requestLine, "cache"));
            String jar = Jsonl.str(requestLine, "jar");
            String keyFile = Jsonl.str(requestLine, "keyFile");
            cc.jumpkick.credential.RepoCredential credential =
                    switch (String.valueOf(Jsonl.str(requestLine, "authType"))) {
                        case "basic" ->
                            new cc.jumpkick.credential.RepoCredential.Basic(
                                    Jsonl.str(requestLine, "user"),
                                    Jsonl.str(requestLine, "pass") != null ? Jsonl.str(requestLine, "pass") : "");
                        case "bearer" ->
                            new cc.jumpkick.credential.RepoCredential.Bearer(Jsonl.str(requestLine, "token"));
                        default -> cc.jumpkick.credential.RepoCredential.ANONYMOUS;
                    };
            cc.jumpkick.runtime.PublishPipelines.Request req = new cc.jumpkick.runtime.PublishPipelines.Request(
                    java.net.URI.create(Jsonl.str(requestLine, "repoUrl")),
                    Jsonl.str(requestLine, "region"),
                    Jsonl.str(requestLine, "endpoint"),
                    jar != null ? Path.of(jar) : null,
                    Jsonl.bool(requestLine, "allowSnapshot", false),
                    Jsonl.bool(requestLine, "dryRun", false),
                    keyFile != null ? Path.of(keyFile) : null,
                    Jsonl.str(requestLine, "gpgPassphrase"),
                    Jsonl.bool(requestLine, "sigstore", false),
                    Jsonl.bool(requestLine, "slsa", false),
                    Jsonl.bool(requestLine, "sbom", false),
                    credential);
            Session session = Session.defaults()
                    .withWorkingDir(entryDir)
                    .withCacheDir(cache)
                    .withCancel(cancelToken);
            String dir = EngineProtocol.SINGLE_PIPELINE_DIR;
            cc.jumpkick.run.BuildPlan pipeline =
                    cc.jumpkick.runtime.PublishPipelines.publishBuildPlan(entryDir, cache, req);
            streamSingleBuildPlan(
                    pipeline,
                    session,
                    writer,
                    result -> EngineProtocol.pipelineFinishPublish(
                            dir,
                            result.success(),
                            pipeline.get(cc.jumpkick.runtime.PublishPipelines.FILES)
                                    .orElse(-1)));
        } catch (Exception e) {
            sendQuiet(writer, requestFailedLine(null, e));
        }
    }

    /**
     * Decode an {@link EngineProtocol#IMAGE_REQUEST} and run {@code jk image}'s pipeline in-session
     * the full build pipeline plus the image tail (Jib worker or Dockerfile child process), all
     * engine-side. The terminal pipeline-finish carries the structured success-tail fields alongside
     * the test counts the client's exit-code logic needs.
     */
    private void runImage(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            Path entryDir = Path.of(Jsonl.str(requestLine, "dir"));
            Path cache = Path.of(Jsonl.str(requestLine, "cache"));
            String jdksDirStr = Jsonl.str(requestLine, "jdksDir");
            Path jdksDir = jdksDirStr != null ? Path.of(jdksDirStr) : null;
            boolean skipTests = Jsonl.bool(requestLine, "skipTests", false);
            boolean verbose = Jsonl.bool(requestLine, "verbose", false);
            JkConfig config = new JkConfig(
                    Optional.empty(),
                    Optional.of(Jsonl.bool(requestLine, "offline", false)),
                    Optional.of(Jsonl.bool(requestLine, "rebuild", false)),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(verbose),
                    Optional.empty(),
                    Optional.of(Jsonl.bool(requestLine, "force", false)),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());
            Session session = Session.defaults()
                    .withConfig(config)
                    .withWorkingDir(entryDir)
                    .withCacheDir(cache)
                    .withJdksDir(jdksDir)
                    .withCancel(cancelToken)
                    .withVariant(EngineProtocol.variantOf(requestLine), EngineProtocol.clientEnvOf(requestLine));
            String dir = EngineProtocol.SINGLE_PIPELINE_DIR;
            // Constructed in-session: the pipeline factory's BuildPipelines.Inputs captures the
            // ambient SessionContext at construction, so building it outside where would
            // silently pin this request to the engine's default config (dropping --force et al).
            cc.jumpkick.run.BuildPlan pipeline = SessionContext.where(
                    session,
                    () -> cc.jumpkick.runtime.ImagePipelines.imageBuildPlan(
                            entryDir,
                            cache,
                            jdksDir,
                            skipTests,
                            verbose,
                            Jsonl.str(requestLine, "mainClass"),
                            Jsonl.str(requestLine, "registry"),
                            Jsonl.str(requestLine, "tag"),
                            Jsonl.str(requestLine, "tarball"),
                            Jsonl.str(requestLine, "dockerExecutable")));
            streamSingleBuildPlan(pipeline, session, writer, result -> {
                cc.jumpkick.run.TestSummary testResult = pipeline.get(cc.jumpkick.runtime.BuildPipelines.TEST_RESULT)
                        .orElse(null);
                cc.jumpkick.image.ImageConfig cfg =
                        pipeline.get(cc.jumpkick.runtime.ImagePipelines.CONFIG).orElse(null);
                Path tarball = pipeline.get(cc.jumpkick.runtime.ImagePipelines.TARBALL_PATH)
                        .orElse(null);
                JkBuild project =
                        pipeline.get(cc.jumpkick.runtime.BuildPipelines.PROJECT).orElse(null);
                boolean daemonMode = tarball == null
                        && (cfg == null
                                || cfg.registry() == null
                                || cfg.registry().isBlank());
                String daemonExe = !daemonMode
                        ? null
                        : cfg != null && cfg.dockerExecutable() != null ? cfg.dockerExecutable() : "docker";
                return EngineProtocol.pipelineFinishImage(
                        dir,
                        result.success(),
                        testResult != null ? testResult.total() : -1,
                        testResult != null ? testResult.succeeded() : -1,
                        testResult != null ? testResult.failed() : -1,
                        testResult != null ? testResult.skipped() : -1,
                        pipeline.get(cc.jumpkick.runtime.ImagePipelines.IMAGE_REF)
                                .orElse(null),
                        tarball != null ? tarball.toString() : null,
                        project != null ? project.project().name() : null,
                        project != null ? project.project().version() : null,
                        daemonExe);
            });
        } catch (Exception e) {
            sendQuiet(writer, requestFailedLine(null, e));
        }
    }

    /**
     * Decode an {@link EngineProtocol#IMPORT_REQUEST} and run {@code jk import}'s single-step pipeline
     * in-session, streaming the worker's progress notes as {@link EngineProtocol#IMPORT_NOTE}
     * events. The worker's exit code/warnings/error ride the terminal pipeline-finish (a non-zero
     * worker exit is a result the client renders, not a pipeline failure).
     */
    private void runImport(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            Path baseDir = Path.of(Jsonl.str(requestLine, "baseDir"));
            Path cache = Path.of(Jsonl.str(requestLine, "cache"));
            String report = Jsonl.str(requestLine, "report");
            Session session = Session.defaults()
                    .withWorkingDir(baseDir)
                    .withCacheDir(cache)
                    .withCancel(cancelToken);
            String dir = EngineProtocol.SINGLE_PIPELINE_DIR;
            cc.jumpkick.run.BuildPlan pipeline = cc.jumpkick.runtime.CompatPipelines.importBuildPlan(
                    Path.of(Jsonl.str(requestLine, "source")),
                    Path.of(Jsonl.str(requestLine, "out")),
                    baseDir,
                    Path.of(Jsonl.str(requestLine, "tmpDir")),
                    Jsonl.bool(requestLine, "force", false),
                    report != null ? Path.of(report) : null,
                    cache,
                    (kind, text) -> sendQuiet(writer, EngineProtocol.importNote(dir, kind, text)));
            streamSingleBuildPlan(
                    pipeline,
                    session,
                    writer,
                    result -> EngineProtocol.pipelineFinishImport(
                            dir,
                            result.success(),
                            pipeline.get(cc.jumpkick.runtime.CompatPipelines.EXIT)
                                    .orElse(1),
                            pipeline.get(cc.jumpkick.runtime.CompatPipelines.WARNINGS)
                                    .orElse(0),
                            pipeline.get(cc.jumpkick.runtime.CompatPipelines.ERROR)
                                    .orElse(null),
                            pipeline.get(cc.jumpkick.runtime.CompatPipelines.DIAG)
                                    .orElse(null)));
        } catch (Exception e) {
            sendQuiet(writer, requestFailedLine(null, e));
        }
    }

    /**
     * Decode a {@link EngineProtocol#PROVISION_REQUEST}, provision the Maven/Gradle distribution
     * via the compat-bridge worker, and reply with the one-shot {@link
     * EngineProtocol#PROVISION_RESULT} terminal. The exec of the provisioned tool stays client-side.
     */
    private void runProvision(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            var outcome = cc.jumpkick.runtime.CompatPipelines.provision(
                    Path.of(Jsonl.str(requestLine, "cache")),
                    Path.of(Jsonl.str(requestLine, "dir")),
                    Path.of(Jsonl.str(requestLine, "toolsRoot")),
                    Jsonl.bool(requestLine, "noDiscover", false),
                    Jsonl.bool(requestLine, "gradle", false));
            sendQuiet(
                    writer,
                    EngineProtocol.provisionResult(
                            outcome.bin(),
                            outcome.version(),
                            outcome.source(),
                            outcome.error(),
                            outcome.exit(),
                            outcome.diag()));
        } catch (Exception e) {
            sendQuiet(writer, requestFailedLine(null, e));
        }
    }

    // ---- hosted pipeline commands -------------------------------------------------------------------

    /**
     * Decode a {@link EngineProtocol#COMPILE_REQUEST} and run {@code jk compile}'s single
     * compile-only pipeline in-session — {@link EngineProtocol#TEST_REQUEST}'s exact wire shape with a
     * plain terminal pipeline-finish (the command has no structured summary beyond success).
     */
    private void runCompile(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            String profile = Jsonl.str(requestLine, "profile");
            boolean verbose = Jsonl.bool(requestLine, "verbose", false);
            Session session = resolveSession(requestLine, cancelToken, false);
            String dir = EngineProtocol.SINGLE_PIPELINE_DIR;
            // Constructed in-session — see runImage's note on ambient-session capture.
            cc.jumpkick.run.BuildPlan pipeline = SessionContext.where(
                    session,
                    () -> cc.jumpkick.runtime.CompilePipelines.compileBuildPlan(
                            session.workingDir(), session.cacheDir(), profile, verbose));
            streamSingleBuildPlan(
                    pipeline, session, writer, result -> EngineProtocol.pipelineFinish(dir, result.success()));
        } catch (Exception e) {
            sendQuiet(writer, requestFailedLine(null, e));
        }
    }

    /**
     * Decode an {@link EngineProtocol#INSTALL_REQUEST} and run {@code jk install}'s build +
     * cache-install pipeline in-session (see {@link cc.jumpkick.runtime.InstallPipelines}). The terminal
     * pipeline-finish carries the test counts for the client's exit-code logic; the launcher-writing
     * "make install" half runs client-side after this succeeds.
     */
    private void runInstall(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            boolean skipTests = Jsonl.bool(requestLine, "skipTests", false);
            boolean verbose = Jsonl.bool(requestLine, "verbose", false);
            String m2DirStr = Jsonl.str(requestLine, "m2Dir");
            String graalHomeStr = Jsonl.str(requestLine, "graalHome");
            Session session = resolveSession(requestLine, cancelToken, false);
            String dir = EngineProtocol.SINGLE_PIPELINE_DIR;
            // Constructed in-session — see runImage's note on ambient-session capture.
            cc.jumpkick.run.BuildPlan pipeline = SessionContext.where(
                    session,
                    () -> cc.jumpkick.runtime.InstallPipelines.projectInstallBuildPlan(
                            session.workingDir(),
                            session.cacheDir(),
                            Path.of(m2DirStr),
                            skipTests,
                            verbose,
                            graalHomeStr != null ? Path.of(graalHomeStr) : null));
            streamSingleBuildPlan(pipeline, session, writer, result -> {
                cc.jumpkick.run.TestSummary testResult = pipeline.get(cc.jumpkick.runtime.BuildPipelines.TEST_RESULT)
                        .orElse(null);
                return testResult == null
                        ? EngineProtocol.pipelineFinish(dir, result.success())
                        : EngineProtocol.pipelineFinish(
                                dir,
                                result.success(),
                                testResult.total(),
                                testResult.succeeded(),
                                testResult.failed(),
                                testResult.skipped());
            });
        } catch (Exception e) {
            sendQuiet(writer, requestFailedLine(null, e));
        }
    }

    /**
     * Decode a {@link EngineProtocol#GIT_FETCH_REQUEST} and materialize the checkout in-session
     * (git runs in-process — {@link cc.jumpkick.git.GitFetcher} prefers the git CLI, else JGit);
     * the terminal pipeline-finish carries the checkout path + sha the client's follow-up {@link
     * EngineProtocol#INSTALL_REQUEST} needs.
     */
    private void runGitFetch(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            Path cache = Path.of(Jsonl.str(requestLine, "cache"));
            boolean refresh = Jsonl.bool(requestLine, "refresh", false);
            JkConfig config = new JkConfig(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(refresh),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());
            Session session =
                    Session.defaults().withConfig(config).withCacheDir(cache).withCancel(cancelToken);
            String dir = EngineProtocol.SINGLE_PIPELINE_DIR;
            cc.jumpkick.run.BuildPlan pipeline = cc.jumpkick.runtime.InstallPipelines.gitFetchBuildPlan(
                    Jsonl.str(requestLine, "url"),
                    Jsonl.str(requestLine, "canonicalUrl"),
                    Jsonl.str(requestLine, "ref"),
                    cache,
                    refresh,
                    Jsonl.bool(requestLine, "requireJkToml", true));
            streamSingleBuildPlan(pipeline, session, writer, result -> {
                Path checkout = pipeline.get(cc.jumpkick.runtime.InstallPipelines.CHECKOUT)
                        .orElse(null);
                String sha = pipeline.get(cc.jumpkick.runtime.InstallPipelines.FETCHED_SHA)
                        .orElse(null);
                return EngineProtocol.pipelineFinishGitFetch(
                        dir, result.success(), checkout != null ? checkout.toString() : null, sha);
            });
        } catch (Exception e) {
            sendQuiet(writer, requestFailedLine(null, e));
        }
    }

    // ---- hosted long-tail commands ------------------------------------------------------------------

    /**
     * Decode a {@link EngineProtocol#SCRIPT_PREPARE_REQUEST} and run the shared script-preparation
     * pipeline ({@code jk tool run <file>}'s parse/resolve/compile half — see {@link
     * cc.jumpkick.runtime.ScriptPipelines}). The terminal pipeline-finish carries the exec ingredients; the
     * exec stays client-side.
     */
    private void runScriptPrepare(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            String mode = String.valueOf(Jsonl.str(requestLine, "mode"));
            Path script = Path.of(Jsonl.str(requestLine, "script"));
            Path cache = Path.of(Jsonl.str(requestLine, "cache"));
            String stateDirStr = Jsonl.str(requestLine, "stateDir");
            Path stateDir = stateDirStr != null ? Path.of(stateDirStr) : cc.jumpkick.util.JkDirs.state();
            java.net.URI repoUrl = repoUrlOf(requestLine);
            boolean forceRecompile = Jsonl.bool(requestLine, "forceRecompile", false);
            java.nio.file.Files.createDirectories(cache);
            Session session = Session.defaults()
                    .withWorkingDir(script.toAbsolutePath().getParent())
                    .withCacheDir(cache)
                    .withCancel(cancelToken);
            java.util.List<cc.jumpkick.model.Dependency> extraDeps = Jsonl.strArray(requestLine, "with").stream()
                    .map(cc.jumpkick.script.ScriptHeaderParser::parseDependency)
                    .toList();
            cc.jumpkick.run.BuildPlan pipeline =
                    switch (mode) {
                        case "java" ->
                            cc.jumpkick.runtime.ScriptPipelines.javaScriptBuildPlan(
                                    script, cache, stateDir, repoUrl, forceRecompile, extraDeps);
                        case "kt" ->
                            cc.jumpkick.runtime.ScriptPipelines.kotlinScriptBuildPlan(
                                    script, cache, stateDir, repoUrl, forceRecompile, extraDeps);
                        case "kts" ->
                            cc.jumpkick.runtime.ScriptPipelines.ktsScriptBuildPlan(script, cache, repoUrl, extraDeps);
                        case "jar" -> cc.jumpkick.runtime.ScriptPipelines.jarBuildPlan(script, cache, repoUrl);
                        default -> throw new IllegalArgumentException("unknown script mode: " + mode);
                    };
            String dir = EngineProtocol.SINGLE_PIPELINE_DIR;
            streamSingleBuildPlan(pipeline, session, writer, result -> {
                Path classesDir = pipeline.get(cc.jumpkick.runtime.ScriptPipelines.CLASSES_DIR)
                        .orElse(null);
                Path kotlincBin = pipeline.get(cc.jumpkick.runtime.ScriptPipelines.KOTLINC_BIN)
                        .orElse(null);
                Path stdlib = pipeline.get(cc.jumpkick.runtime.ScriptPipelines.KT_STDLIB)
                        .orElse(null);
                return EngineProtocol.pipelineFinishScript(
                        dir,
                        result.success(),
                        pipeline.get(cc.jumpkick.runtime.ScriptPipelines.MAIN_CLASS)
                                .orElse(null),
                        cc.jumpkick.runtime.ScriptPipelines.classpathOf(pipeline).stream()
                                .map(Path::toString)
                                .toList(),
                        classesDir != null ? classesDir.toString() : null,
                        kotlincBin != null ? kotlincBin.toString() : null,
                        stdlib != null ? stdlib.toString() : null);
            });
        } catch (Exception e) {
            sendQuiet(writer, requestFailedLine(null, e));
        }
    }

    /**
     * Decode a {@link EngineProtocol#TOOL_RESOLVE_REQUEST} and run the shared tool-resolution pipeline
     * in-session ({@code jk tool install}/{@code jk tool run}/{@code jk install <g:a:v>}'s Maven
     * resolve + fetch — see {@link cc.jumpkick.runtime.ToolPipelines}). The terminal pipeline-finish carries
     * the resolved main class + classpath; the launcher write / inheritIO exec stays client-side.
     */
    private void runToolResolve(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            Path cache = Path.of(Jsonl.str(requestLine, "cache"));
            String coord = Jsonl.str(requestLine, "coord");
            String bin = Jsonl.str(requestLine, "bin");
            String mainClass = Jsonl.str(requestLine, "mainClass");
            java.net.URI repoUrl = repoUrlOf(requestLine);
            java.nio.file.Files.createDirectories(cache);
            cc.jumpkick.model.ToolCoordSpec spec = cc.jumpkick.model.ToolCoordSpec.parse(coord);
            java.util.List<cc.jumpkick.model.ToolCoordSpec> with = Jsonl.strArray(requestLine, "with").stream()
                    .map(cc.jumpkick.model.ToolCoordSpec::parse)
                    .toList();
            Session session = Session.defaults().withCacheDir(cache).withCancel(cancelToken);
            String dir = EngineProtocol.SINGLE_PIPELINE_DIR;
            // Plain g:a[:v] label — coordinate colorization is a client-side concern.
            cc.jumpkick.run.BuildPlan pipeline = cc.jumpkick.runtime.ToolPipelines.resolveBuildPlan(
                    spec, with, bin, mainClass, repoUrl, cache, coord);
            streamSingleBuildPlan(pipeline, session, writer, result -> {
                cc.jumpkick.tool.ToolEnv env =
                        pipeline.get(cc.jumpkick.runtime.ToolPipelines.TOOL_ENV).orElse(null);
                return EngineProtocol.pipelineFinishTool(
                        dir,
                        result.success(),
                        env != null ? env.primary().toGav() : null,
                        env != null ? env.mainClass() : null,
                        env != null
                                ? env.classpath().stream().map(Path::toString).toList()
                                : java.util.List.of());
            });
        } catch (Exception e) {
            sendQuiet(writer, requestFailedLine(null, e));
        }
    }

    /**
     * Decode a {@link EngineProtocol#CACHE_PRUNE_REQUEST} and run its maintenance op ({@code prune}
     * / {@code purge} / {@code sweep} / {@code gc}) as an idle-boundary job: take {@link #cacheGate}'s write side
     * (emitting {@link EngineProtocol#PRUNE_WAIT} first when pipelines are in flight, so the client
     * isn't staring at silence) and the cross-process {@code.prune.lock}, then stream the shared
     * {@link cc.jumpkick.runtime.CachePipelines} pipeline — {@link EngineProtocol#TEST_REQUEST}'s wire shape
     * with a {@link EngineProtocol#pipelineFinishCache} terminal.
     */
    private void runCacheMaintenance(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            String op = String.valueOf(Jsonl.str(requestLine, "op"));
            Path cache = Path.of(Jsonl.str(requestLine, "cache"));
            boolean dryRun = Jsonl.bool(requestLine, "dryRun", false);

            if (!cacheGate.writeLock().tryLock()) {
                sendQuiet(writer, EngineProtocol.pruneWait(activeBuildPlans.get(), false));
                cacheGate.writeLock().lock();
            }
            try {
                java.nio.file.Files.createDirectories(cache);
                try (FileChannel lockChan = FileChannel.open(
                        cache.resolve(".prune.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                    FileLock pruneLock = lockChan.tryLock();
                    if (pruneLock == null) {
                        // Another process's prune holds the cross-process lock — wait for it too.
                        sendQuiet(writer, EngineProtocol.pruneWait(0, true));
                        pruneLock = lockChan.lock();
                    }
                    try {
                        cc.jumpkick.run.BuildPlan pipeline =
                                switch (op) {
                                    case "purge" -> cc.jumpkick.runtime.CachePipelines.purgeBuildPlan(cache);
                                    case "sweep" ->
                                        cc.jumpkick.runtime.CachePipelines.sweepBuildPlan(
                                                cache, dryRun, Jsonl.str(requestLine, "maxSize"));
                                    case "gc" -> cc.jumpkick.runtime.CachePipelines.gcBuildPlan(cache);
                                    case "clear" ->
                                        cc.jumpkick.runtime.CachePipelines.clearBuildPlan(
                                                cache, Path.of(Jsonl.str(requestLine, "dir")), dryRun);
                                    default ->
                                        cc.jumpkick.runtime.CachePipelines.pruneBuildPlan(
                                                cache,
                                                Jsonl.intValue(requestLine, "olderThanDays", 30),
                                                dryRun,
                                                Jsonl.bool(requestLine, "sweep", false),
                                                Jsonl.str(requestLine, "maxSize"),
                                                Jsonl.bool(requestLine, "includeJkTmp", false));
                                };
                        Session session = Session.defaults().withCacheDir(cache).withCancel(cancelToken);
                        String dir = EngineProtocol.SINGLE_PIPELINE_DIR;
                        streamSingleBuildPlan(
                                pipeline,
                                session,
                                writer,
                                result -> EngineProtocol.pipelineFinishCache(
                                        dir,
                                        result.success(),
                                        pipeline.get(cc.jumpkick.runtime.CachePipelines.FILES)
                                                .orElse(-1L),
                                        pipeline.get(cc.jumpkick.runtime.CachePipelines.BYTES)
                                                .orElse(-1L),
                                        pipeline.get(cc.jumpkick.runtime.CachePipelines.REACHABLE_EVICTED)
                                                .orElse(-1L),
                                        pipeline.get(cc.jumpkick.runtime.CachePipelines.REPO_LINKS)
                                                .orElse(-1L)));
                    } finally {
                        pruneLock.release();
                    }
                }
            } finally {
                cacheGate.writeLock().unlock();
            }
        } catch (Exception e) {
            sendQuiet(writer, requestFailedLine(null, e));
        }
    }

    /**
     * Decode a {@link EngineProtocol#NATIVE_REQUEST} and run {@code jk native}'s serial module
     * cascade in-session, speaking {@link EngineProtocol#BUILD_REQUEST}'s workspace event
     * vocabulary (a single project is a cascade of one): a full plan burst first (so the client
     * calibrates its aggregate bar to the whole-workspace weight up front), then each module's pipeline
     * — the {@code native-image} child process forking engine-side — stopping at the first failure.
     * Exit codes are computed here ({@link cc.jumpkick.runtime.NativePipelines#failureExitCode}) and
     * ride {@code module-finish}/{@code workspace-finish}.
     */
    private void runNative(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            String jdksDirStr = Jsonl.str(requestLine, "jdksDir");
            Path jdksDir = jdksDirStr != null ? Path.of(jdksDirStr) : null;
            String mainClass = Jsonl.str(requestLine, "mainClass");
            boolean skipTests = Jsonl.bool(requestLine, "skipTests", false);
            boolean verbose = Jsonl.bool(requestLine, "verbose", false);
            java.util.List<String> extraArgs = Jsonl.strArray(requestLine, "extraArgs");
            java.util.Map<Path, Path> graalByDir = new java.util.HashMap<>();
            Jsonl.strMap(requestLine, "graalHomes").forEach((d, h) -> graalByDir.put(Path.of(d), Path.of(h)));
            java.util.List<Path> selectedDirs = new java.util.ArrayList<>();
            for (String d : Jsonl.strArray(requestLine, "moduleDirs")) {
                if (d != null && !d.isBlank())
                    selectedDirs.add(Path.of(d).toAbsolutePath().normalize());
            }
            Session session = resolveSession(requestLine, cancelToken, false).withJdksDir(jdksDir);
            SessionContext.where(session, () -> {
                nativeCascade(
                        session.workingDir(),
                        session.cacheDir(),
                        jdksDir,
                        mainClass,
                        extraArgs,
                        graalByDir,
                        selectedDirs,
                        skipTests,
                        verbose,
                        writer);
                return null;
            });
        } catch (Exception e) {
            sendQuiet(writer, requestFailedLine(null, e));
        }
    }

    /** The module cascade {@link #runNative} streams — see its javadoc for the wire shape. */
    private void nativeCascade(
            Path entryDir,
            Path cache,
            Path jdksDir,
            String mainClass,
            java.util.List<String> extraArgs,
            java.util.Map<Path, Path> graalByDir,
            java.util.List<Path> selectedDirs,
            boolean skipTests,
            boolean verbose,
            BufferedWriter writer) {
        JkBuild root;
        try {
            root = JkBuildParser.parse(entryDir.resolve("jk.toml"));
        } catch (RuntimeException | IOException e) {
            sendQuiet(
                    writer,
                    EngineProtocol.workspaceFinish(
                            false,
                            cc.jumpkick.model.command.Exit.CONFIG,
                            java.util.List.of(String.valueOf(e.getMessage()))));
            return;
        }

        var scopes = new java.util.LinkedHashMap<Path, JkBuild>();
        // Canonical (real-path) identities of the modules the CLIENT selected — engine-added
        // prereqs are absent and build jar-only (JK-1361); null = no selection, all native-compile.
        java.util.Set<Path> selectedCanonical = null;
        if (root.isWorkspaceRoot()) {
            java.util.Map<Path, JkBuild> modulesByDir;
            try {
                modulesByDir = cc.jumpkick.config.WorkspaceLoader.loadModules(entryDir, root);
            } catch (RuntimeException | IOException e) {
                sendQuiet(
                        writer,
                        EngineProtocol.workspaceFinish(
                                false,
                                cc.jumpkick.model.command.Exit.CONFIG,
                                java.util.List.of(String.valueOf(e.getMessage()))));
                return;
            }
            // -m / --modules: keep selected modules + transitive build prereqs. Identities are the
            // graph's canonical (real) paths so symlinked checkouts do not silently drop prereqs,
            // and an unresolvable graph fails the request instead of degrading (JK-1362).
            if (selectedDirs != null && !selectedDirs.isEmpty()) {
                java.util.Set<Path> want = new java.util.LinkedHashSet<>();
                for (Path p : selectedDirs) want.add(cc.jumpkick.runtime.BuildGraph.canonicalPath(p));
                selectedCanonical = java.util.Set.copyOf(want);
                try {
                    var graph = cc.jumpkick.runtime.BuildGraph.resolve(entryDir, root);
                    if (graph.hasErrors()) {
                        sendQuiet(
                                writer,
                                EngineProtocol.workspaceFinish(
                                        false,
                                        cc.jumpkick.model.command.Exit.CONFIG,
                                        java.util.List.copyOf(graph.errors())));
                        return;
                    }
                    java.util.Map<Path, java.util.Set<Path>> edges = graph.edges();
                    java.util.ArrayDeque<Path> q = new java.util.ArrayDeque<>(want);
                    while (!q.isEmpty()) {
                        Path d = q.poll();
                        for (Path pre : edges.getOrDefault(d, java.util.Set.of())) {
                            Path n = cc.jumpkick.runtime.BuildGraph.canonicalPath(pre);
                            if (want.add(n)) q.add(n);
                        }
                    }
                } catch (IOException e) {
                    sendQuiet(
                            writer,
                            EngineProtocol.workspaceFinish(
                                    false,
                                    cc.jumpkick.model.command.Exit.CONFIG,
                                    java.util.List.of(
                                            "module selection: cannot resolve the build graph — " + e.getMessage())));
                    return;
                }
                java.util.Map<Path, JkBuild> filtered = new java.util.LinkedHashMap<>();
                for (var e : modulesByDir.entrySet()) {
                    Path d = cc.jumpkick.runtime.BuildGraph.canonicalPath(e.getKey());
                    if (want.contains(d)) filtered.put(e.getKey(), e.getValue());
                }
                modulesByDir = filtered;
            }
            for (Path dir : cc.jumpkick.runtime.BuildGraph.orderModules(modulesByDir)) {
                scopes.put(dir, modulesByDir.get(dir));
            }
        } else {
            scopes.put(entryDir, root);
        }

        // Assemble every module's pipeline up front and send the whole plan burst first, so the
        // client's aggregate bar calibrates to the workspace total before any module runs.
        var pipelines = new java.util.LinkedHashMap<Path, cc.jumpkick.run.BuildPlan>();
        var coords = new java.util.LinkedHashMap<Path, String>();
        for (var scope : scopes.entrySet()) {
            Path dir = scope.getKey();
            boolean allowNative = selectedCanonical == null
                    || selectedCanonical.contains(cc.jumpkick.runtime.BuildGraph.canonicalPath(dir));
            cc.jumpkick.run.BuildPlan pipeline = cc.jumpkick.runtime.NativePipelines.moduleBuildPlan(
                    dir,
                    scope.getValue(),
                    cache,
                    jdksDir,
                    graalByDir.get(dir),
                    mainClass,
                    extraArgs,
                    skipTests,
                    verbose,
                    allowNative);
            pipelines.put(dir, pipeline);
            coords.put(dir, cc.jumpkick.runtime.LockPipelines.coordLabel(scope.getValue(), dir));
        }
        for (var entry : pipelines.entrySet()) {
            String dirTag = entry.getKey().toString();
            cc.jumpkick.run.BuildPlan pipeline = entry.getValue();
            sendQuiet(
                    writer,
                    EngineProtocol.planModule(
                            dirTag,
                            coords.get(entry.getKey()),
                            pipeline.name(),
                            (int) Math.min(Integer.MAX_VALUE, pipeline.estimatedTotalWeight()),
                            false));
            for (Task p : pipeline.steps()) {
                sendQuiet(
                        writer,
                        EngineProtocol.planStep(
                                dirTag, p.name(), p.label(), phaseWire(p.phase().orElse(null))));
            }
        }
        sendQuiet(writer, EngineProtocol.planDone(pipelines.size()));

        for (var entry : pipelines.entrySet()) {
            Path dir = entry.getKey();
            String dirTag = dir.toString();
            cc.jumpkick.run.BuildPlan pipeline = entry.getValue();
            sendQuiet(writer, EngineProtocol.moduleStart(dirTag));
            pipeline.addListener(wireBuildPlanListener(dirTag, writer, pipeline));
            long startNanos = System.nanoTime();
            BuildPlanResult result = pipeline.run();
            long millis = (System.nanoTime() - startNanos) / 1_000_000;
            int exitCode = result.success() ? 0 : cc.jumpkick.runtime.NativePipelines.failureExitCode(pipeline, result);
            boolean didWork = !result.success() || cc.jumpkick.runtime.BuildService.moduleDidWork(result);
            sendQuiet(
                    writer,
                    EngineProtocol.moduleFinish(dirTag, coords.get(dir), result.success(), exitCode, millis, didWork));
            if (!result.success()) {
                sendQuiet(writer, EngineProtocol.workspaceFinish(false, exitCode, java.util.List.of()));
                return;
            }
        }
        sendQuiet(writer, EngineProtocol.workspaceFinish(true, 0, java.util.List.of()));
    }

    /**
     * The lock/update path both {@link #runLock} and {@link #runUpdate} stream: parse the entry
     * manifest, resolve workspace ownership, then run <strong>one</strong> {@link
     * cc.jumpkick.runtime.LockPipelines} pipeline that writes the single workspace (or standalone)
     * {@code jk-lock.toml}. Members never get their own lockfile — locking from a member updates
     * the workspace root lock with the full merged graph.
     */
    private void lockCascade(
            Path entryDir,
            Path cache,
            java.net.URI repoUrl,
            java.util.List<String> features,
            boolean withDefaults,
            boolean sources,
            boolean update,
            String platformOverride,
            BufferedWriter writer)
            throws Exception {
        lockCascade(entryDir, cache, repoUrl, features, withDefaults, sources, update, platformOverride, false, writer);
    }

    private void lockCascade(
            Path entryDir,
            Path cache,
            java.net.URI repoUrl,
            java.util.List<String> features,
            boolean withDefaults,
            boolean sources,
            boolean update,
            String platformOverride,
            boolean conservative,
            BufferedWriter writer)
            throws Exception {
        java.nio.file.Files.createDirectories(cache);
        // One lock scope: workspace root (merged) or standalone project. Members redirect to root
        // (shared with the HTTP/MCP lock job —.
        Path lockDir;
        JkBuild effective;
        String coord;
        try {
            var scope = cc.jumpkick.runtime.LockPipelines.lockScope(entryDir);
            lockDir = scope.lockDir();
            effective = scope.effective();
            coord = scope.coord();
        } catch (RuntimeException e) {
            sendQuiet(
                    writer,
                    EngineProtocol.lockFinish(
                            false,
                            cc.jumpkick.model.command.Exit.CONFIG,
                            java.util.List.of(String.valueOf(e.getMessage())),
                            -1));
            return;
        }

        // Serialize per lock dir (JK-1356). A conservative freshen that waited here may find the
        // lock already fresh — a concurrent job won the flight; the bare lock-finish is a complete
        // stream (the client returns on the terminal without any pipeline events).
        synchronized (cc.jumpkick.runtime.LockGate.monitorFor(lockDir)) {
            if (conservative
                    && !cc.jumpkick.lock.LockFreshness.isStale(lockDir, cc.jumpkick.lock.LockPaths.lockFile(lockDir))) {
                sendQuiet(writer, EngineProtocol.lockFinish(true, 0, java.util.List.of(), -1));
                return;
            }
            Path dir = lockDir;
            String dirTag = dir.toString();
            sendQuiet(writer, EngineProtocol.lockModule(dirTag, coord));

            CoalescingLockPackages lockPkgs = new CoalescingLockPackages(
                    (d, name, ver, total) -> sendQuiet(writer, EngineProtocol.lockPackage(d, name, ver, total)));
            cc.jumpkick.resolver.ResolveObserver observer = new cc.jumpkick.resolver.ResolveObserver() {
                @Override
                public void onTotal(int total) {
                    // tick growth already rides the pipeline's tick-update events
                }

                @Override
                public void onPackage(String module, String version) {
                    lockPkgs.onPackage(dirTag, module, version);
                }
            };
            cc.jumpkick.run.BuildPlan pipeline = update
                    ? cc.jumpkick.runtime.LockPipelines.updateBuildPlan(
                            dir, effective, cache, repoUrl, features, withDefaults, platformOverride)
                    : cc.jumpkick.runtime.LockPipelines.lockBuildPlan(
                            dir,
                            effective,
                            cache,
                            repoUrl,
                            features,
                            withDefaults,
                            sources,
                            conservative,
                            observer,
                            null);
            for (Task p : pipeline.steps()) {
                sendQuiet(
                        writer,
                        EngineProtocol.planStep(
                                dirTag, p.name(), p.label(), phaseWire(p.phase().orElse(null))));
            }
            sendQuiet(writer, EngineProtocol.planDone(1));
            pipeline.addListener(wireBuildPlanListener(
                    dirTag, writer, (java.util.function.Function<BuildPlanResult, String>) result -> {
                        lockPkgs.flush();
                        lockPkgs.close();
                        cc.jumpkick.lock.Lockfile lock = pipeline.get(cc.jumpkick.runtime.LockPipelines.LOCKFILE)
                                .orElse(null);
                        return EngineProtocol.pipelineFinishLock(
                                dirTag,
                                result.success(),
                                lock != null ? lock.artifacts().size() : -1,
                                lock != null
                                        ? lock.artifacts().stream()
                                                .filter(a -> a.sourcesChecksum() != null)
                                                .count()
                                        : -1,
                                lock != null ? lock.plugins().size() : -1);
                    }));

            BuildPlanResult result = pipeline.run();
            lockPkgs.close();
            if (!result.success()) {
                sendQuiet(
                        writer,
                        EngineProtocol.lockFinish(
                                false,
                                cc.jumpkick.runtime.LockPipelines.failureExitCode(result),
                                java.util.List.of(),
                                -1));
                return;
            }
        }
        sendQuiet(writer, EngineProtocol.lockFinish(true, 0, java.util.List.of(), -1));
    }

    /**
     * Reconstruct the request's {@link Session} from the flat config fields every lock/sync/update
     * request carries ({@code offline}/{@code force}/{@code verbose}, plus sync's {@code refresh})
     * — the same fields {@link #runBuild} decodes inline.
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
                // The variant selection rides the session: every pipeline factory's Inputs defaults
                // from it, so compile/install/native/publish/... are parameterized generically.
                .withVariant(EngineProtocol.variantOf(requestLine), EngineProtocol.clientEnvOf(requestLine))
                .withAssemblyOverride(EngineProtocol.assemblyOverrideOf(requestLine));
    }

    /** The optional {@code repoUrl} request field ({@code --repo-url} overrides), or {@code null}. */
    private static java.net.URI repoUrlOf(String requestLine) {
        String s = Jsonl.str(requestLine, "repoUrl");
        return s != null ? java.net.URI.create(s) : null;
    }

    /** Translate every {@link WorkspaceBuildListener} callback into a wire event on {@code writer}. */
    private WorkspaceBuildListener wireListener(BufferedWriter writer, String workspaceDir) {
        // Created on the runner's thread — capture the request id for the dashboard events now;
        // the callbacks below fire on scheduler/worker threads where the ThreadLocal isn't set.
        long eventRequestId = eventRequestId();
        if (eventRequestId > 0 && workspaceDir != null) progressRoots.put(eventRequestId, workspaceDir);
        // Each module's pipeline, kept from onModuleStart so onModuleFinish can read its TEST_RESULT and
        // fold per-module test counts into the run's record — the workspace path has no single test
        // pipeline, so tests would otherwise never reach a dashboard-triggered build's history.
        java.util.Map<String, cc.jumpkick.run.BuildPlan> moduleBuildPlans =
                new java.util.concurrent.ConcurrentHashMap<>();
        java.util.concurrent.ConcurrentHashMap<String, Long> lastDenByDir =
                new java.util.concurrent.ConcurrentHashMap<>();
        return new WorkspaceBuildListener() {
            @Override
            public void onPreflight(String stage, int done, int total, String label) {
                sendQuiet(writer, EngineProtocol.preflight(stage, done, total, label));
                // Map coarse preflight stages onto user-visible InvocationPhases.
                String inv = switch (stage == null ? "" : stage) {
                    case "lock", "graph" -> "resolve";
                    case "checking", "plan", "prepare", "calibrate" -> "plan";
                    default -> null;
                };
                if (inv != null) {
                    String status = (total > 0 && done >= total) ? "finish" : "start";
                    sendQuiet(writer, EngineProtocol.invocationPhase(inv, status));
                }
                if (eventRequestId > 0) {
                    progressTracker(eventRequestId).preflight(stage, done, total);
                    emitWorkspaceProgress(eventRequestId, writer, true);
                }
            }

            @Override
            public void onPlan(java.util.List<ModulePlan> plan) {
                long totalWeight = 0;
                // Id-less builds must not insert a key clearProgress can never remove.
                var weights = eventRequestId > 0
                        ? progressWeights.computeIfAbsent(
                                eventRequestId, id -> new java.util.concurrent.ConcurrentHashMap<String, Long>())
                        : new java.util.concurrent.ConcurrentHashMap<String, Long>();
                for (ModulePlan m : plan) {
                    String dir = m.dir().toString();
                    totalWeight += m.weight();
                    weights.put(dir, (long) m.weight());
                    sendQuiet(
                            writer,
                            EngineProtocol.planModule(
                                    dir, m.coord(), m.pipeline().name(), m.weight(), m.fullyCached()));
                    for (Task p : m.pipeline().steps()) {
                        sendQuiet(
                                writer,
                                EngineProtocol.planStep(
                                        dir,
                                        p.name(),
                                        p.label(),
                                        phaseWire(p.phase().orElse(null))));
                    }
                }
                sendQuiet(writer, EngineProtocol.planDone(plan.size()));
                if (eventRequestId > 0) {
                    progressTracker(eventRequestId).calibrate(totalWeight, plan.size());
                    emitWorkspaceProgress(eventRequestId, writer, true);
                }
                publishPlan(eventRequestId, totalWeight);
            }

            @Override
            public void onModuleGraph(java.util.Map<Path, java.util.Set<Path>> prereqs) {
                accModuleGraph(eventRequestId, prereqs);
            }

            @Override
            public void onEtaEstimate(long millis) {
                sendQuiet(writer, EngineProtocol.eta(millis));
                publishEta(eventRequestId, millis);
            }

            @Override
            public BuildPlanListener onModuleStart(ModulePlan m) {
                String dir = m.dir().toString();
                moduleBuildPlans.put(dir, m.pipeline()); // read its TEST_RESULT at finish (see onModuleFinish)
                sendQuiet(writer, EngineProtocol.moduleStart(dir));
                publishModuleStart(eventRequestId, dir, m.coord());
                // wireBuildPlanListener captures the dashboard request id from the currentEventRequestId
                // ThreadLocal — but onModuleStart runs on a WorkspaceScheduler thread where it isn't
                // set, so without this seed every per-module step/pipeline-progress hub event would
                // publish under id -1 and be dropped (no per-module chains or weight bar). Seed it
                // with the request id captured on the request thread when wireListener was created.
                Long prev = currentEventRequestId.get();
                currentEventRequestId.set(eventRequestId);
                try {
                    return wrapBuildPlanForWorkspace(
                            wireBuildPlanListener(dir, writer, (cc.jumpkick.run.BuildPlan) null),
                            eventRequestId,
                            dir,
                            writer,
                            lastDenByDir);
                } finally {
                    if (prev == null) currentEventRequestId.remove();
                    else currentEventRequestId.set(prev);
                }
            }

            @Override
            public void onModuleFinish(ModuleOutcome o) {
                String dir = o.dir().toString();
                long lastDen = lastDenByDir.getOrDefault(dir, 0L);
                trackModuleComplete(eventRequestId, dir, lastDen, writer);
                sendQuiet(
                        writer,
                        EngineProtocol.moduleFinish(
                                dir, o.coord(), o.success(), o.exitCode(), o.millis(), o.didWork()));
                publishModuleFinish(eventRequestId, dir, o.coord(), o.success(), o.millis(), o.didWork());
                accModule(eventRequestId, o);
                cc.jumpkick.run.BuildPlan g = moduleBuildPlans.remove(dir);
                if (g != null) {
                    accTests(
                            eventRequestId,
                            g.get(cc.jumpkick.runtime.BuildPipelines.TEST_RESULT)
                                    .orElse(null));
                }
            }
        };
    }

    /** Decorate a module pipeline listener to feed the workspace aggregate tracker. */
    private BuildPlanListener wrapBuildPlanForWorkspace(
            BuildPlanListener inner,
            long requestId,
            String dir,
            java.io.BufferedWriter writer,
            java.util.concurrent.ConcurrentHashMap<String, Long> lastDenByDir) {
        return new BuildPlanListener() {
            @Override
            public void pipelineStart(BuildPlanView view) {
                lastDenByDir.put(dir, view.denominator());
                trackModuleBuildPlan(requestId, dir, view, writer, false);
                inner.pipelineStart(view);
            }

            @Override
            public void progress(String step, int delta, BuildPlanView view) {
                lastDenByDir.put(dir, view.denominator());
                trackModuleBuildPlan(requestId, dir, view, writer, false);
                inner.progress(step, delta, view);
            }

            @Override
            public void tickUpdate(String step, int delta, BuildPlanView view) {
                lastDenByDir.put(dir, view.denominator());
                trackModuleBuildPlan(requestId, dir, view, writer, false);
                inner.tickUpdate(step, delta, view);
            }

            @Override
            public void stepStart(String step, String group, int ticks) {
                inner.stepStart(step, group, ticks);
            }

            @Override
            public void stepFinish(
                    String step,
                    String group,
                    cc.jumpkick.run.TaskStatus status,
                    Duration duration) {
                inner.stepFinish(step, group, status, duration);
            }

            @Override
            public void label(String step, String label) {
                inner.label(step, label);
            }

            @Override
            public void output(String step, String line) {
                inner.output(step, line);
            }

            @Override
            public void warn(String step, String code, String message) {
                inner.warn(step, code, message);
            }

            @Override
            public void error(String step, String code, String message) {
                inner.error(step, code, message);
            }

            @Override
            public void error(String step, String code, String message, String test, String exceptionClass) {
                inner.error(step, code, message, test, exceptionClass);
            }

            @Override
            public void pipelineFinish(BuildPlanResult result) {
                inner.pipelineFinish(result);
            }
        };
    }

    /** The current thread's hosted-request id for dashboard events; {@code -1} outside a request. */
    private long eventRequestId() {
        Long id = currentEventRequestId.get();
        return id != null ? id : -1;
    }

    private void publishModuleStart(long requestId, String dir, String coord) {
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
                        requestId));
    }

    private void publishModuleFinish(
            long requestId, String dir, String coord, boolean success, long millis, boolean didWork) {
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
                        requestId));
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

    /** Request kinds we journal — the actual "build" commands; lock/sync/tool/etc. are not history. */
    private static final java.util.Set<String> JOURNALED_KINDS = java.util.Set.of("build", "test");

    /**
     * Open an accumulator for a journaled build kind (no-op for other kinds). Always on — even with
     * history disabled the accumulator feeds the running {@link BuildMetrics}; only the journal
     * append itself is gated on {@code historyConfig.enabled}.
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
        if (!JOURNALED_KINDS.contains(kind)) return;
        Path projectDir = null;
        try {
            if (dir != null && !dir.isBlank()) projectDir = Path.of(dir);
        } catch (RuntimeException ignored) {
            projectDir = null;
        }
        ChromeTimeline timeline = ChromeTimeline.open(projectDir, noTimeline);
        accumulators.put(
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
        BuildAccumulator a = accumulators.get(requestId);
        if (a != null) a.addModule(o);
    }

    private void accModuleGraph(long requestId, java.util.Map<Path, java.util.Set<Path>> prereqs) {
        BuildAccumulator a = accumulators.get(requestId);
        if (a != null) a.setModuleEdges(prereqs);
    }

    private void accBuildPlanFinish(long requestId, String dir, BuildPlanResult result) {
        BuildAccumulator a = accumulators.get(requestId);
        if (a != null) a.addBuildPlan(dir, result);
    }

    /**
     * Record one finished step under its module dir — the same {@code stepFinish} signal the
     * dashboard renders, so the journal's per-module chains match the live cards exactly (a
     * workspace module's {@code BuildPlanResult.steps} isn't reliably populated, so we capture the
     * events directly).
     */
    private void accStepFinish(long requestId, String dir, String step, String phase, String status, long millis) {
        BuildAccumulator a = accumulators.get(requestId);
        if (a != null) a.addTask(dir, step, phase, status, millis);
    }

    private void accTests(long requestId, TestSummary tests) {
        BuildAccumulator a = accumulators.get(requestId);
        if (a != null && tests != null) a.addTests(tests);
    }

    private void accOutcome(long requestId, boolean success, int exitCode) {
        BuildAccumulator a = accumulators.get(requestId);
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
        BuildAccumulator a = accumulators.remove(requestId);
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
     * every per-module step (workspace) and top-level step (single-pipeline, whose steps carry the
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
            // Single-module pipeline records often have empty modules list — treat as 1 when steps ran.
            if (dirty == 0 && r.steps() != null && !r.steps().isEmpty()) dirty = 1;
            var shape = new BuildService.HistoryShape(rebuild, dirty);
            kind = shape.kind();
            if (!dir.isEmpty()) dir = shape.dirKey(Path.of(dir));
        }
        return new BuildMetrics.Outcome(kind, dir, r.coord(), r.success(), r.cancelled(), r.millis(), steps);
    }

    /**
     * Legacy wire path (no public CLI): queue host warmup. Prefer engine self-heal on start / 12 h.
     */
    private void handleOptimizeRequest(String requestLine, BufferedWriter writer) {
        try {
            boolean force = Jsonl.bool(requestLine, "force", false);
            boolean scheduled = scheduleHostWarmupIfNeeded(force);
            sendQuiet(
                    writer,
                    scheduled
                            ? EngineProtocol.optimizeAck(
                                    true, "", "scheduled", "scheduled: host warmup on idle worker")
                            : EngineProtocol.optimizeAck(
                                    true, "", "", "nothing to do: worker AOT and calibration are current"));
        } catch (RuntimeException e) {
            sendQuiet(writer, EngineProtocol.optimizeAck(false, "", "", "optimize failed: " + e.getMessage()));
        }
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
     * Drain queued host warmup on a daemon thread when no pipeline is in flight. When {@code
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
                            pendingWarmupForce.updateAndGet(
                                    prev -> prev == null ? force : (prev || force));
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
     * multi-probe host calibration (or re-run with {@code force}). Network probes on by
     * default ({@code allowNetwork} defaults true); opt out with global {@code --offline}. Optional
     * {@code engineColdStartMs} from the CLI (timed cold engine spawn) is folded into the file.
     */
    private void handleCalibrateRequest(String requestLine, BufferedWriter writer) {
        try {
            boolean force = Jsonl.bool(requestLine, "force", false);
            boolean allowNetwork = Jsonl.bool(requestLine, "allowNetwork", true);
            long cold = Jsonl.longValue(requestLine, "engineColdStartMs", 0);
            cc.jumpkick.runtime.Calibration cal = cc.jumpkick.runtime.Calibration.ensure(null, force, allowNetwork);
            if (cold > 0) {
                cal = cc.jumpkick.runtime.Calibration.recordEngineColdStart(cold, System.currentTimeMillis());
            }
            sendQuiet(
                    writer,
                    EngineProtocol.calibrateAck(
                            cal.present(),
                            cal.msPerWeight(),
                            cal.jvmForkMs(),
                            cal.javacMs(),
                            cal.diskIoMs(),
                            cal.hashCpuMs(),
                            cal.junitForkMs(),
                            cal.junitRunMs(),
                            cal.junitPlatformMs(),
                            cal.resolveMs(),
                            cal.engineColdStartMs(),
                            cal.measured(),
                            cal.junitPlatformUsed(),
                            cal.resolveUsed(),
                            cal.summary()));
        } catch (Exception e) {
            sendQuiet(
                    writer,
                    EngineProtocol.calibrateAck(
                            false,
                            0,
                            0,
                            0,
                            0,
                            0,
                            0,
                            0,
                            0,
                            0,
                            0,
                            false,
                            false,
                            false,
                            "calibration failed: " + e.getMessage()));
        }
    }

    /**
     * {@code metrics-request} → one flat {@code metrics-entry} per aggregate row, then
     * {@code metrics-done}. An optional {@code dir} keeps only that project's rows (the global
     * tiers are always included so the client can render its summary alongside).
     */
    private void handleMetrics(String requestLine, BufferedWriter writer) throws IOException {
        String dirFilter = Jsonl.str(requestLine, "dir");
        int n = 0;
        for (BuildMetrics.Entry e : BuildMetrics.load(metricsFile).entries()) {
            // Project rows are stored as bare dir and dirty-count shapes (dir#dN). Match
            // the project's base path so `jk status` sees the folded project tier.
            if (dirFilter != null && !e.dir().isEmpty() && !BuildMetrics.sameBaseDir(dirFilter, e.dir())) {
                continue;
            }
            send(writer, metricsEntryJson(e));
            n++;
        }
        send(
                writer,
                JsonOut.object()
                        .put("type", EngineProtocol.METRICS_DONE)
                        .put("count", n)
                        .toString());
    }

    /** One aggregate row as a flat wire object; avg is pre-computed so clients stay arithmetic-free. */
    private static String metricsEntryJson(BuildMetrics.Entry e) {
        boolean global = e.dir().isEmpty();
        String scope = e.step() == null ? (global ? "global" : "project") : (global ? "step" : "project/step");
        return JsonOut.object()
                .put("type", EngineProtocol.METRICS_ENTRY)
                .put("scope", scope)
                .put("kind", e.kind())
                .put("dir", e.dir())
                .put("coord", e.coord())
                .put("task", e.step())
                .put("okCount", e.ok().count())
                .put("okTotalMillis", e.ok().totalMillis())
                .put("okMinMillis", e.ok().minMillis())
                .put("okMaxMillis", e.ok().maxMillis())
                .put("okAvgMillis", e.ok().avgMillis())
                .put("failCount", e.failed().count())
                .put("failTotalMillis", e.failed().totalMillis())
                .put("failMinMillis", e.failed().minMillis())
                .put("failMaxMillis", e.failed().maxMillis())
                .put("cancelledCount", e.cancelled().count())
                .put("cancelledTotalMillis", e.cancelled().totalMillis())
                .put("cancelledMinMillis", e.cancelled().minMillis())
                .put("cancelledMaxMillis", e.cancelled().maxMillis())
                .put("updated", e.updatedMillis())
                .toString();
    }

    /** {@code history-list-request} → one flat {@code history-entry} per entry, then {@code history-done}. */
    private void handleHistoryList(String requestLine, BufferedWriter writer) throws IOException {
        int limit = Math.max(1, Jsonl.intValue(requestLine, "limit", 200));
        // Truncate in the journal (synthetic fixtures are already filtered there, JK-1390) rather
        // than materialising every record on disk and then dropping most of them (JK-1481).
        java.util.List<BuildRecord> records = journal.list(limit);
        int n = Math.min(records.size(), limit);
        for (int i = 0; i < n; i++) {
            BuildRecord r = records.get(i);
            BuildRecord.Tests t = r.tests();
            BuildRecord.CacheBenefit b = r.benefit();
            int failedModules =
                    (int) r.modules().stream().filter(m -> !m.success()).count();
            // Live progress + jid for in-flight rows — match by build number + project dir.
            int progressPct = -1;
            long jid = 0;
            if (r.running()) {
                for (InFlightBuilds.Hold h : inFlightBuilds.list()) {
                    boolean sameRun = r.buildNumber() > 0
                            && r.buildNumber() == h.buildNumber()
                            && r.dir() != null
                            && r.dir().equals(h.dir());
                    boolean sameLocator = h.journalId() != null
                            && (h.journalId().equals(Long.toString(r.buildNumber()))
                                    || h.journalId().equals(r.id()));
                    if (sameRun || sameLocator) {
                        jid = h.requestId();
                        Double p = lastProgressByRequest.get(h.requestId());
                        if (p != null && !Double.isNaN(p)) progressPct = (int) Math.round(p);
                        break;
                    }
                }
            }
            long elapsed = r.running() && r.startedAt() > 0
                    ? Math.max(0, clockMillis.getAsLong() - r.startedAt())
                    : r.millis();
            var entry = JsonOut.object()
                    .put("type", EngineProtocol.HISTORY_ENTRY)
                    .put("id", r.id())
                    .put("buildNumber", r.buildNumber())
                    .put("kind", r.kind())
                    .put("dir", r.dir())
                    .put("coord", r.coord())
                    .put("startedAt", r.startedAt())
                    .put("finishedAt", r.finishedAt())
                    .put("millis", elapsed)
                    .put("success", r.success())
                    .put("cancelled", r.cancelled())
                    .put("running", r.running())
                    .put("exitCode", r.exitCode())
                    .put("testsTotal", t != null ? t.total() : -1)
                    .put("testsFailed", t != null ? t.failed() : -1)
                    .put("moduleCount", r.modules().size())
                    .put("failedModules", failedModules)
                    .put("savedMillis", b != null ? b.savedMillis() : -1)
                    .put("estimatedUncachedMillis", b != null ? b.estimatedUncachedMillis() : -1);
            if (jid > 0) {
                entry = entry.put("jid", jid).put("requestId", jid);
            }
            if (progressPct >= 0) entry = entry.put("progress", progressPct);
            send(writer, entry.toString());
        }
        send(
                writer,
                JsonOut.object()
                        .put("type", EngineProtocol.HISTORY_DONE)
                        .put("count", n)
                        .toString());
    }

    /** {@code history-show-request} → a {@code history-record} header + module/step/diag rows + {@code history-done}. */
    private void handleHistoryShow(String requestLine, BufferedWriter writer) throws IOException {
        String id = Jsonl.str(requestLine, "id");
        java.util.Optional<BuildRecord> found = id == null ? java.util.Optional.empty() : journal.get(id);
        if (found.isEmpty()) {
            send(
                    writer,
                    JsonOut.object()
                            .put("type", EngineProtocol.ERROR)
                            .put("code", EngineProtocol.ERR_REQUEST_FAILED)
                            .put("message", "no such build: " + id)
                            .toString());
            return;
        }
        BuildRecord r = found.get();
        BuildRecord.Tests t = r.tests();
        BuildRecord.CacheBenefit b = r.benefit();
        send(
                writer,
                JsonOut.object()
                        .put("type", EngineProtocol.HISTORY_RECORD)
                        .put("id", r.id())
                        .put("kind", r.kind())
                        .put("dir", r.dir())
                        .put("coord", r.coord())
                        .put("startedAt", r.startedAt())
                        .put("finishedAt", r.finishedAt())
                        .put("millis", r.millis())
                        .put("success", r.success())
                        .put("cancelled", r.cancelled())
                        .put("exitCode", r.exitCode())
                        .put("jkVersion", r.jkVersion())
                        .put("testsTotal", t != null ? t.total() : -1)
                        .put("testsSucceeded", t != null ? t.succeeded() : -1)
                        .put("testsFailed", t != null ? t.failed() : -1)
                        .put("testsSkipped", t != null ? t.skipped() : -1)
                        .put("savedMillis", b != null ? b.savedMillis() : -1)
                        .put("estimatedUncachedMillis", b != null ? b.estimatedUncachedMillis() : -1)
                        .put("coveredSkips", b != null ? b.coveredSkips() : -1)
                        .put("totalSkips", b != null ? b.totalSkips() : -1)
                        .toString());
        int stepCount = 0;
        for (BuildRecord.Module m : r.modules()) {
            send(
                    writer,
                    JsonOut.object()
                            .put("type", EngineProtocol.HISTORY_MODULE)
                            .put("coord", m.coord())
                            .put("dir", m.dir())
                            .put("success", m.success())
                            .put("exitCode", m.exitCode())
                            .put("millis", m.millis())
                            .toString());
            // Each module's own step chain, tagged with the module so the CLI can group them.
            String label = m.coord() != null ? m.coord() : m.dir();
            for (BuildRecord.Task p : m.steps()) {
                send(writer, stepLine(p, label));
                stepCount++;
            }
        }
        // Single-pipeline builds carry their steps at the record's top level (no module rows).
        for (BuildRecord.Task p : r.steps()) {
            send(writer, stepLine(p, null));
            stepCount++;
        }
        for (BuildRecord.Diag d : r.diagnostics()) {
            send(
                    writer,
                    JsonOut.object()
                            .put("type", EngineProtocol.HISTORY_DIAG)
                            .put("severity", d.severity())
                            .put("task", d.step())
                            .put("code", d.code())
                            .put("message", d.message())
                            .put("test", d.test())
                            .put("exceptionClass", d.exceptionClass())
                            .toString());
        }
        send(
                writer,
                JsonOut.object()
                        .put("type", EngineProtocol.HISTORY_DONE)
                        .put(
                                "count",
                                r.modules().size() + stepCount + r.diagnostics().size())
                        .toString());
    }

    /** A {@code history-step} line, optionally tagged with its module label (null for single-pipeline). */
    private static String stepLine(BuildRecord.Task p, String module) {
        return JsonOut.object()
                .put("type", EngineProtocol.HISTORY_STEP)
                .put("module", module)
                .put("name", p.name())
                .put("status", p.status())
                .put("millis", p.millis())
                .toString();
    }

    /** {@code history-delete-request} → {@code history-deleted} carrying whether the entry existed. */
    private void handleHistoryDelete(String requestLine, BufferedWriter writer) throws IOException {
        String id = Jsonl.str(requestLine, "id");
        boolean deleted = id != null && journal.delete(id);
        send(
                writer,
                JsonOut.object()
                        .put("type", EngineProtocol.HISTORY_DELETED)
                        .put("id", id)
                        .put("deleted", deleted)
                        .toString());
    }

    /**
     * Translate every {@link BuildPlanListener} callback for one pipeline into a {@code dir}-tagged wire
     * event. {@code realBuildPlan} is non-null only for {@link #runTest}/{@link #runSingleBuild} — its
     * {@code TEST_RESULT}/{@code BUILD_OUTCOME} keys (populated by the run-tests/parse-build steps)
     * ride along on the {@link EngineProtocol#BUILDPLAN_FINISH} message so the client can render its
     * summary line before it even sees the terminal message; {@code null} for a plain per-module
     * workspace-build pipeline (where neither applies at the module level).
     */
    private BuildPlanListener wireBuildPlanListener(
            String dir, BufferedWriter writer, cc.jumpkick.run.BuildPlan realBuildPlan) {
        // realBuildPlan non-null ⇒ single-project run: flush chrome timeline on pipeline finish.
        // Workspace modules pass null and flush once on workspace finish instead.
        boolean flushTimeline = realBuildPlan != null;
        return wireBuildPlanListener(
                dir,
                writer,
                (java.util.function.Function<BuildPlanResult, String>) result -> {
                    cc.jumpkick.run.TestSummary testResult = realBuildPlan == null
                            ? null
                            : realBuildPlan
                                    .get(cc.jumpkick.runtime.BuildPipelines.TEST_RESULT)
                                    .orElse(null);
                    String buildOutcome = realBuildPlan == null
                            ? null
                            : realBuildPlan
                                    .get(cc.jumpkick.runtime.BuildPipelines.BUILD_OUTCOME)
                                    .orElse(null);
                    // Wire "cancelled" is user/deadline cancel only. BuildPlanResult.cancelled is also
                    // set on cooperative fail-fast (remaining steps aborted after a real FAIL) — that
                    // must not look like the user cancelled the job.
                    boolean cancelled = result.userCancelled();
                    String finish = testResult == null && buildOutcome == null
                            ? EngineProtocol.pipelineFinish(dir, result.success(), cancelled)
                            : EngineProtocol.withCancelled(
                                    EngineProtocol.pipelineFinish(
                                            dir,
                                            result.success(),
                                            buildOutcome,
                                            testResult != null ? testResult.total() : -1,
                                            testResult != null ? testResult.succeeded() : -1,
                                            testResult != null ? testResult.failed() : -1,
                                            testResult != null ? testResult.skipped() : -1),
                                    cancelled);
                    return finish;
                },
                flushTimeline);
    }

    /**
     * As {@link #wireBuildPlanListener(String, BufferedWriter, cc.jumpkick.run.BuildPlan)}, but with a
     * pluggable terminal encoder: {@code finishEncoder} maps the finished {@link BuildPlanResult} to the
     * {@link EngineProtocol#BUILDPLAN_FINISH} message to send (after the {@link
     * EngineProtocol#BUILDPLAN_DIAGNOSTIC} burst) — how lock/update/sync ride their summary counts on the
     * same message the build/test pipelines already send.
     */
    private BuildPlanListener wireBuildPlanListener(
            String dir, BufferedWriter writer, java.util.function.Function<BuildPlanResult, String> finishEncoder) {
        return wireBuildPlanListener(dir, writer, finishEncoder, false);
    }

    private BuildPlanListener wireBuildPlanListener(
            String dir,
            BufferedWriter writer,
            java.util.function.Function<BuildPlanResult, String> finishEncoder,
            boolean flushTimelineOnBuildPlanFinish) {
        // Created on the runner's thread (directly, or via wireListener's onModuleStart which runs
        // on a scheduler thread — there the ThreadLocal is unset and module events carry the id).
        long eventRequestId = eventRequestId();
        // Human-paced progress/label/tickstructural events still flush immediately.
        return new CoalescingBuildPlanListener(new BuildPlanListener() {
            @Override
            public void pipelineStart(BuildPlanView view) {
                sendQuiet(
                        writer,
                        EngineProtocol.pipelineStart(
                                dir,
                                view.pipelineName(),
                                view.numerator(),
                                view.denominator(),
                                view.stepsTotal(),
                                view.stepsComplete(),
                                view.cancelled()));
                publishBuildPlanProgress(eventRequestId, dir, view);
            }

            @Override
            public void stepStart(String step, String group, int ticks) {
                sendQuiet(writer, EngineProtocol.stepStart(dir, step, phaseWire(group), ticks));
                publishStepStart(eventRequestId, dir, step, phaseWire(group));
            }

            @Override
            public void progress(String step, int delta, BuildPlanView view) {
                sendQuiet(
                        writer,
                        EngineProtocol.progress(
                                dir,
                                step,
                                delta,
                                view.numerator(),
                                view.denominator(),
                                view.stepsTotal(),
                                view.stepsComplete(),
                                view.cancelled()));
                publishBuildPlanProgress(eventRequestId, dir, view);
            }

            @Override
            public void tickUpdate(String step, int delta, BuildPlanView view) {
                sendQuiet(
                        writer,
                        EngineProtocol.tickUpdate(
                                dir,
                                step,
                                delta,
                                view.numerator(),
                                view.denominator(),
                                view.stepsTotal(),
                                view.stepsComplete(),
                                view.cancelled()));
                publishBuildPlanProgress(eventRequestId, dir, view);
            }

            @Override
            public void label(String step, String label) {
                String safe = redactEnv(dir, label);
                sendQuiet(writer, EngineProtocol.label(dir, step, safe));
                publishLabel(eventRequestId, dir, step, safe);
            }

            @Override
            public void output(String step, String line) {
                String safe = redactEnv(dir, line);
                sendQuiet(writer, EngineProtocol.output(dir, step, safe));
                publishOutput(eventRequestId, dir, step, safe);
            }

            @Override
            public void warn(String step, String code, String message) {
                sendQuiet(writer, EngineProtocol.warn(dir, step, code, redactEnv(dir, message)));
            }

            @Override
            public void error(String step, String code, String message, String test, String exceptionClass) {
                sendQuiet(
                        writer,
                        EngineProtocol.errorLine(dir, step, code, redactEnv(dir, message), test, exceptionClass));
            }

            @Override
            public void stepFinish(
                    String step,
                    String group,
                    cc.jumpkick.run.TaskStatus status,
                    Duration duration) {
                sendQuiet(writer, EngineProtocol.stepFinish(dir, step, phaseWire(group), status.name()));
                publishStepFinish(eventRequestId, dir, step, phaseWire(group), status.name());
                accStepFinish(eventRequestId, dir, step, phaseWire(group), status.name(), duration.toMillis());
            }

            @Override
            public void pipelineFinish(BuildPlanResult result) {
                for (BuildPlanResult.Diagnostic d : result.errors()) {
                    sendQuiet(
                            writer,
                            EngineProtocol.pipelineDiagnostic(
                                    dir,
                                    d.step(),
                                    d.code(),
                                    redactEnv(dir, d.message()),
                                    d.test(),
                                    d.exceptionClass()));
                }
                // Single-pipeline builds: timeline before terminal finish. Workspace modules skip
                // (flush once in runBuild before workspace-finish).
                if (flushTimelineOnBuildPlanFinish) flushTimelineToClient(eventRequestId, writer);
                // Free exclusive fingerprint before the terminal line so a client that reconnects
                // immediately is not rejected as already-running (single-pipeline only; workspace
                // releases after BuildService.buildWorkspace returns).
                if (flushTimelineOnBuildPlanFinish) inFlightBuilds.release(eventRequestId);
                sendQuiet(writer, finishEncoder.apply(result));
                publishBuildPlanFinish(eventRequestId, dir, result.success());
                if (!result.success()) publishDiagnostics(eventRequestId, dir, result.errors());
                accBuildPlanFinish(eventRequestId, dir, result);
            }
        });
    }

    /** Write chrome timeline (if any) and notify the socket client. Idempotent per request. */
    private void flushTimelineToClient(long requestId, BufferedWriter writer) {
        BuildAccumulator a = accumulators.get(requestId);
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

    /**
     * Mask {@code.env}-sourced values in free-form text that leaves the enginewire
     * events, journal diagnostics, SSE, and request-failed messages. Lookup is by module/workspace
     * dir so masking follows {@link cc.jumpkick.config.EnvLookup#isFromFile} (source, not name
     * heuristics). When {@code dir} is blank, the ambient session's working dir is used. Failures
     * fall through to the original text — redaction must never break a build.
     */
    static String redactEnv(String dir, String text) {
        if (text == null || text.isEmpty()) return text;
        try {
            Path root;
            if (dir != null && !dir.isBlank()) {
                root = Path.of(dir);
            } else {
                root = cc.jumpkick.config.SessionContext.current().workingDir();
            }
            if (root == null) return text;
            return cc.jumpkick.config.BuildEnv.secretsFor(root).redact(text);
        } catch (RuntimeException e) {
            return text;
        }
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
        return envLongMs("JK_ENGINE_HEARTBEAT_MS", 30_000L);
    }

    /**
     * Optional per-request wall deadline /. Default {@code 0} = off (huge
     * monorepos). Env: {@code JK_ENGINE_JOB_DEADLINE_MS}. When set, the engine cancels the job,
     * {@code destroyForcibly}s registered worker processes, interrupts the runner, and bounds the
     * connection join to deadline + {@link #jobDeadlineGraceMs}.
     */
    static long jobDeadlineMs() {
        return envLongMs("JK_ENGINE_JOB_DEADLINE_MS", 0L);
    }

    /**
     * Grace after the wall deadline for the runner to unwind after worker kill. Default
     * 30s. Env: {@code JK_ENGINE_JOB_DEADLINE_GRACE_MS}.
     */
    static long jobDeadlineGraceMs() {
        return envLongMs("JK_ENGINE_JOB_DEADLINE_GRACE_MS", 30_000L);
    }

    private static long envLongMs(String name, long defaultMs) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) return defaultMs;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return defaultMs;
        }
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
                () -> cc.jumpkick.engine.http.CacheSnapshot.capture(cc.jumpkick.util.JkDirs.cache()),
                log);
        // Hard-refresh mid-build: history rows carry live requestId/progress; SSE connect replays
        // request-start + current workspace-progress so the SPA rebinds the stream.
        candidate.setLiveRunSupport(this::liveRunsSnapshot, this::rehydrateLiveRunsOnSseConnect);
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

    /** Snapshot of in-flight holds for dashboard history enrichment. */
    private java.util.List<cc.jumpkick.engine.http.HttpEngineServer.LiveRun> liveRunsSnapshot() {
        java.util.List<cc.jumpkick.engine.http.HttpEngineServer.LiveRun> out = new java.util.ArrayList<>();
        for (InFlightBuilds.Hold h : inFlightBuilds.list()) {
            Double p = lastProgressByRequest.get(h.requestId());
            out.add(new cc.jumpkick.engine.http.HttpEngineServer.LiveRun(
                    h.requestId(),
                    h.buildNumber(),
                    h.kind(),
                    h.dir(),
                    h.coord(),
                    h.startedAt(),
                    p != null && !p.isNaN() ? p : Double.NaN,
                    h.journalId()));
        }
        return out;
    }

    /**
     * After a new dashboard SSE subscription: re-emit request-start + current aggregate progress
     * for every still-running job so a refreshed tab does not sit on a frozen history stub.
     */
    private void rehydrateLiveRunsOnSseConnect() {
        for (InFlightBuilds.Hold h : inFlightBuilds.list()) {
            // Dashboard-only: existing tabs fold the duplicate request-start idempotently; MCP
            // streams must not see a replayed "job began" (JK-1523).
            publishRequestStart(h.requestId(), h.kind(), h.dir(), h.buildNumber(), true);
            emitWorkspaceProgress(h.requestId(), null, true, true);
        }
    }

    /** HTTP/MCP job cancel tokens and runner threads. */
    private final java.util.concurrent.ConcurrentHashMap<Long, Session.CancelToken> httpCancelTokens =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final java.util.concurrent.ConcurrentHashMap<Long, Thread> httpJobThreads =
            new java.util.concurrent.ConcurrentHashMap<>();

    private cc.jumpkick.engine.http.EngineHttpJobs httpJobs() {
        return new cc.jumpkick.engine.http.EngineHttpJobs() {
            @Override
            public long triggerBuild(String dir) {
                return triggerHttpWorkspace(dir, "build", /* skipTests */ false, /* testOnly */ false);
            }

            @Override
            public long triggerTest(String dir) {
                // True test-only: same graph as build, each module uses testOnly pipelines (no package).
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

    /**
     * {@code POST /api/build} / MCP {@code jk_build}/{@code jk_test}: start a workspace job and
     * return a request id immediately. Progress is SSE (dashboard {@code /api/events} or MCP {@code
     * GET /mcp} event-stream).
     */
    private long triggerHttpWorkspace(String dirStr, String kind, boolean skipTests, boolean testOnly) {
        // Claim the pipeline slot atomically with the shutdown check, so displacement/stop can
        // never see zero pipelines for a job that is about to start (JK-1470). Any failure before
        // the runner thread is started gives the slot back — a leaked slot would stall shutdown.
        if (!tryStartBuildPlan()) {
            throw new IllegalStateException("engine is shutting down");
        }
        boolean started = false;
        try {
            long id = startHttpWorkspace(dirStr, kind, skipTests, testOnly);
            started = true;
            return id;
        } finally {
            if (!started) abandonBuildPlanSlot();
        }
    }

    private long startHttpWorkspace(String dirStr, String kind, boolean skipTests, boolean testOnly) {
        Path entryDir = Path.of(dirStr);
        if (!entryDir.isAbsolute()) {
            throw new IllegalArgumentException("dir must be an absolute path");
        }
        if (!Files.isRegularFile(entryDir.resolve("jk.toml"))) {
            throw new IllegalArgumentException("no jk.toml in " + entryDir);
        }
        long eventRequestId = requestIds.incrementAndGet();
        long startMillis = clockMillis.getAsLong();
        String fp = BuildJobFingerprint.ofHttp(kind, entryDir, skipTests, testOnly);
        AdmitResult admit = admitJob(eventRequestId, kind, entryDir.toString(), fp, "web");
        if (admit.rejected() != null) {
            InFlightBuilds.Hold h = admit.rejected();
            String label = "test".equals(kind) ? "Test" : "Build";
            throw new IllegalStateException(label + " #" + h.buildNumber() + " is already running");
        }
        Session.CancelToken cancelToken = Session.CancelToken.live();
        httpCancelTokens.put(eventRequestId, cancelToken);
        java.util.concurrent.atomic.AtomicReference<Thread> runnerRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        // HTTP/SSE has no CLI stream writer — cancel settles via request-finish on the dashboard.
        // No CLI stream (writer null) — workspaceStream is moot for the terminal push.
        registerLiveJob(eventRequestId, cancelToken, runnerRef, null, null, entryDir.toString(), kind, true);
        publishRequestStart(eventRequestId, kind, entryDir.toString(), admit.buildNumber());
        registerAccumulator(
                eventRequestId, kind, entryDir.toString(), "web", false, false, admit.buildNumber(), admit.journalId());
        // Unstarted: registration below must complete before the body can reach its finally and
        // remove the very keys we are about to insert, which would leak a dead Thread under this
        // id forever and make a cancel arriving in that window a no-op (JK-1478).
        Thread t = Thread.ofVirtual().name("jk-engine-http-" + kind + "-", 0).unstarted(() -> {
            cacheGate.readLock().lock();
            currentEventRequestId.set(eventRequestId);
            JobWorkers.open(eventRequestId);
            cc.jumpkick.task.IoLedger.open(runIo(eventRequestId));
            boolean success = false;
            boolean cancelled = false;
            try {
                success = runHttpWorkspace(entryDir, skipTests, testOnly, cancelToken);
                cancelled = cancelToken.cancelled() && !success;
            } finally {
                cc.jumpkick.task.IoLedger.close();
                JobWorkers.close();
                httpCancelTokens.remove(eventRequestId);
                httpJobThreads.remove(eventRequestId);
                unregisterLiveJob(eventRequestId);
                currentEventRequestId.remove();
                cacheGate.readLock().unlock();
                // Free exclusive fingerprint before journal/idle chores so a follow-up build can start.
                inFlightBuilds.release(eventRequestId);
                long elapsedMillis = clockMillis.getAsLong() - startMillis;
                if (success) lastProgressByRequest.put(eventRequestId, 100.0);
                publishEvent(
                        "request-finish",
                        withProgress(
                                withIo(
                                        cc.jumpkick.engine.http.JsonOut.object()
                                                .put("schema", 1)
                                                .put("type", "request-finish")
                                                .put("requestId", eventRequestId)
                                                .put("jid", eventRequestId)
                                                .put("kind", kind)
                                                .put("dir", entryDir.toString())
                                                .put("success", success)
                                                .put("cancelled", cancelled)
                                                .put("millis", elapsedMillis),
                                        eventRequestId),
                                eventRequestId));
                clearProgress(eventRequestId);
                writeJournal(eventRequestId, cancelled, elapsedMillis);
                // After finish/journal so idle chores + GC include that allocation.
                maybeIdleBoundary();
            }
        });
        runnerRef.set(t);
        httpJobThreads.put(eventRequestId, t);
        t.start();
        return eventRequestId;
    }

    private long triggerHttpLock(String dirStr) {
        // Claim the pipeline slot atomically with the shutdown check, so displacement/stop can
        // never see zero pipelines for a job that is about to start (JK-1470). Any failure before
        // the runner thread is started gives the slot back — a leaked slot would stall shutdown.
        if (!tryStartBuildPlan()) {
            throw new IllegalStateException("engine is shutting down");
        }
        boolean started = false;
        try {
            long id = startHttpLock(dirStr);
            started = true;
            return id;
        } finally {
            if (!started) abandonBuildPlanSlot();
        }
    }

    private long startHttpLock(String dirStr) {
        Path entryDir = Path.of(dirStr);
        if (!entryDir.isAbsolute()) {
            throw new IllegalArgumentException("dir must be an absolute path");
        }
        if (!Files.isRegularFile(entryDir.resolve("jk.toml"))) {
            throw new IllegalArgumentException("no jk.toml in " + entryDir);
        }
        long eventRequestId = requestIds.incrementAndGet();
        long startMillis = clockMillis.getAsLong();
        Session.CancelToken cancelToken = Session.CancelToken.live();
        httpCancelTokens.put(eventRequestId, cancelToken);
        java.util.concurrent.atomic.AtomicReference<Thread> runnerRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        registerLiveJob(eventRequestId, cancelToken, runnerRef, null, null, entryDir.toString(), "lock", false);
        publishRequestStart(eventRequestId, "lock", entryDir.toString());
        registerAccumulator(eventRequestId, "lock", entryDir.toString(), "web");
        // Unstarted — see the note in startHttpWorkspace (JK-1478).
        Thread t = Thread.ofVirtual().name("jk-engine-http-lock-", 0).unstarted(() -> {
            cacheGate.readLock().lock();
            currentEventRequestId.set(eventRequestId);
            JobWorkers.open(eventRequestId);
            cc.jumpkick.task.IoLedger.open(runIo(eventRequestId));
            boolean success = false;
            boolean cancelled = false;
            try {
                success = runHttpLock(entryDir, cancelToken);
                cancelled = cancelToken.cancelled() && !success;
            } finally {
                cc.jumpkick.task.IoLedger.close();
                JobWorkers.close();
                httpCancelTokens.remove(eventRequestId);
                httpJobThreads.remove(eventRequestId);
                unregisterLiveJob(eventRequestId);
                currentEventRequestId.remove();
                cacheGate.readLock().unlock();
                long elapsedMillis = clockMillis.getAsLong() - startMillis;
                if (success) lastProgressByRequest.put(eventRequestId, 100.0);
                publishEvent(
                        "request-finish",
                        withProgress(
                                withIo(
                                        cc.jumpkick.engine.http.JsonOut.object()
                                                .put("schema", 1)
                                                .put("type", "request-finish")
                                                .put("requestId", eventRequestId)
                                                .put("jid", eventRequestId)
                                                .put("kind", "lock")
                                                .put("dir", entryDir.toString())
                                                .put("success", success)
                                                .put("cancelled", cancelled)
                                                .put("millis", elapsedMillis),
                                        eventRequestId),
                                eventRequestId));
                clearProgress(eventRequestId);
                writeJournal(eventRequestId, cancelled, elapsedMillis);
                // After finish/journal so idle chores + GC include that allocation.
                maybeIdleBoundary();
            }
        });
        runnerRef.set(t);
        httpJobThreads.put(eventRequestId, t);
        t.start();
        return eventRequestId;
    }

    private boolean cancelHttpJob(long requestId) {
        Session.CancelToken token = httpCancelTokens.get(requestId);
        if (token == null) return false;
        token.cancel();
        markUserCancelled(requestId, true);
        JobWorkers.shutdownForRequest(requestId, JobWorkers.cancelGraceMs());
        Thread runner = httpJobThreads.get(requestId);
        if (runner != null) {
            try {
                runner.interrupt();
            } catch (RuntimeException ignored) {
                // best-effort
            }
        }
        return true;
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
            if (rid > 0) progressRoots.put(rid, entryDir.toString());
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
            log.accept("jk engine: http-triggered job of " + entryDir + " failed: "
                    + redactEnv(entryDir.toString(), String.valueOf(e.getMessage())));
            publishRequestError(eventRequestId(), entryDir.toString(), String.valueOf(e.getMessage()));
            return false;
        }
    }

    private boolean runHttpLock(Path entryDir, Session.CancelToken cancelToken) {
        try {
            Path cache = cc.jumpkick.util.JkDirs.cache();
            // Same scope rule as the JSONL lockCascade: a workspace member redirects to its root
            // and locks the merged union — a module-scoped resolution must never overwrite the
            // root jk-lock.toml.
            var scope = cc.jumpkick.runtime.LockPipelines.lockScope(entryDir);
            Path lockDir = scope.lockDir();
            Session session = Session.defaults()
                    .withWorkingDir(lockDir)
                    .withCacheDir(cache)
                    .withCancel(cancelToken);
            cc.jumpkick.run.BuildPlan pipeline = cc.jumpkick.runtime.LockPipelines.lockBuildPlan(
                    lockDir,
                    scope.effective(),
                    cache,
                    null,
                    java.util.List.of(),
                    true,
                    false,
                    ResolveObserver.NOOP,
                    null);
            pipeline.addListener(singleBuildPlanHubListener(lockDir.toString()));
            cc.jumpkick.run.BuildPlanResult result;
            // Serialize per lock dir with every other lock entry point (JK-1356).
            synchronized (cc.jumpkick.runtime.LockGate.monitorFor(lockDir)) {
                result = SessionContext.where(session, pipeline::run);
            }
            accOutcome(eventRequestId(), result.success(), result.success() ? 0 : 1);
            if (!result.success()) {
                for (var d : result.errors().stream().limit(5).toList()) {
                    publishRequestError(eventRequestId(), entryDir.toString(), d.message());
                }
            }
            return result.success();
        } catch (Exception e) {
            log.accept("jk engine: http-triggered lock of " + entryDir + " failed: "
                    + redactEnv(entryDir.toString(), String.valueOf(e.getMessage())));
            publishRequestError(eventRequestId(), entryDir.toString(), String.valueOf(e.getMessage()));
            return false;
        }
    }

    /** BuildPlan events for a single HTTP lock job → SSE hub. */
    private BuildPlanListener singleBuildPlanHubListener(String dir) {
        long eventRequestId = eventRequestId();
        return new BuildPlanListener() {
            @Override
            public void pipelineStart(BuildPlanView view) {
                publishBuildPlanProgress(eventRequestId, dir, view);
            }

            @Override
            public void progress(String step, int delta, BuildPlanView view) {
                publishBuildPlanProgress(eventRequestId, dir, view);
            }

            @Override
            public void tickUpdate(String step, int delta, BuildPlanView view) {
                publishBuildPlanProgress(eventRequestId, dir, view);
            }

            @Override
            public void stepStart(String step, String group, int ticks) {
                publishStepStart(eventRequestId, dir, step, phaseWire(group));
            }

            @Override
            public void stepFinish(
                    String step,
                    String group,
                    cc.jumpkick.run.TaskStatus status,
                    Duration duration) {
                publishStepFinish(eventRequestId, dir, step, phaseWire(group), status.name());
            }

            @Override
            public void label(String step, String label) {
                publishLabel(eventRequestId, dir, step, label);
            }
        };
    }

    /** Module/pipeline events to the dashboard hub only — the HTTP trigger's counterpart of {@link #wireListener}. */
    private WorkspaceBuildListener hubListener(String workspaceDir) {
        long eventRequestId = eventRequestId();
        if (eventRequestId > 0 && workspaceDir != null) progressRoots.put(eventRequestId, workspaceDir);
        // As in wireListener: keep each module's pipeline so onModuleFinish can fold its TEST_RESULT into
        // the record — a web-triggered build has no single test pipeline, so tests would otherwise never
        // reach the journal for dashboard builds.
        java.util.Map<String, cc.jumpkick.run.BuildPlan> moduleBuildPlans =
                new java.util.concurrent.ConcurrentHashMap<>();
        java.util.concurrent.ConcurrentHashMap<String, Long> lastDenByDir =
                new java.util.concurrent.ConcurrentHashMap<>();
        return new WorkspaceBuildListener() {
            @Override
            public void onPreflight(String stage, int done, int total, String label) {
                if (eventRequestId > 0) {
                    progressTracker(eventRequestId).preflight(stage, done, total);
                    emitWorkspaceProgress(eventRequestId, null, true);
                }
            }

            @Override
            public void onPlan(java.util.List<ModulePlan> plan) {
                long totalWeight = 0;
                // Id-less builds must not insert a key clearProgress can never remove.
                var weights = eventRequestId > 0
                        ? progressWeights.computeIfAbsent(
                                eventRequestId, id -> new java.util.concurrent.ConcurrentHashMap<String, Long>())
                        : new java.util.concurrent.ConcurrentHashMap<String, Long>();
                for (ModulePlan m : plan) {
                    totalWeight += m.weight();
                    weights.put(m.dir().toString(), (long) m.weight());
                }
                if (eventRequestId > 0) {
                    progressTracker(eventRequestId).calibrate(totalWeight, plan.size());
                    emitWorkspaceProgress(eventRequestId, null, true);
                }
                publishPlan(eventRequestId, totalWeight);
            }

            @Override
            public void onModuleGraph(java.util.Map<Path, java.util.Set<Path>> prereqs) {
                accModuleGraph(eventRequestId, prereqs);
            }

            @Override
            public void onEtaEstimate(long millis) {
                publishEta(eventRequestId, millis);
            }

            @Override
            public BuildPlanListener onModuleStart(ModulePlan m) {
                String dir = m.dir().toString();
                moduleBuildPlans.put(dir, m.pipeline());
                publishModuleStart(eventRequestId, dir, m.coord());
                return new BuildPlanListener() {
                    @Override
                    public void pipelineStart(BuildPlanView view) {
                        lastDenByDir.put(dir, view.denominator());
                        trackModuleBuildPlan(eventRequestId, dir, view, null, false);
                        publishBuildPlanProgress(eventRequestId, dir, view);
                    }

                    @Override
                    public void progress(String step, int delta, BuildPlanView view) {
                        lastDenByDir.put(dir, view.denominator());
                        trackModuleBuildPlan(eventRequestId, dir, view, null, false);
                        publishBuildPlanProgress(eventRequestId, dir, view);
                    }

                    @Override
                    public void tickUpdate(String step, int delta, BuildPlanView view) {
                        lastDenByDir.put(dir, view.denominator());
                        trackModuleBuildPlan(eventRequestId, dir, view, null, false);
                        publishBuildPlanProgress(eventRequestId, dir, view);
                    }

                    @Override
                    public void stepStart(String step, String group, int ticks) {
                        publishStepStart(eventRequestId, dir, step, phaseWire(group));
                    }

                    @Override
                    public void stepFinish(
                            String step,
                            String group,
                            cc.jumpkick.run.TaskStatus status,
                            Duration duration) {
                        publishStepFinish(eventRequestId, dir, step, phaseWire(group), status.name());
                        accStepFinish(eventRequestId, dir, step, phaseWire(group), status.name(), duration.toMillis());
                    }

                    @Override
                    public void label(String step, String label) {
                        publishLabel(eventRequestId, dir, step, label);
                    }

                    @Override
                    public void output(String step, String line) {
                        publishOutput(eventRequestId, dir, step, line);
                    }

                    @Override
                    public void pipelineFinish(BuildPlanResult result) {
                        publishBuildPlanFinish(eventRequestId, dir, result.success());
                        if (!result.success()) publishDiagnostics(eventRequestId, dir, result.errors());
                        accBuildPlanFinish(eventRequestId, dir, result);
                    }
                };
            }

            @Override
            public void onModuleFinish(ModuleOutcome o) {
                String dir = o.dir().toString();
                trackModuleComplete(eventRequestId, dir, lastDenByDir.getOrDefault(dir, 0L), null);
                publishModuleFinish(eventRequestId, dir, o.coord(), o.success(), o.millis(), o.didWork());
                accModule(eventRequestId, o);
                cc.jumpkick.run.BuildPlan g = moduleBuildPlans.remove(dir);
                if (g != null) {
                    accTests(
                            eventRequestId,
                            g.get(cc.jumpkick.runtime.BuildPipelines.TEST_RESULT)
                                    .orElse(null));
                }
            }
        };
    }

    /** The one source of engine vitals — feeds both the socket {@code status-ack} and {@code /api/status}. */
    private cc.jumpkick.engine.http.StatusSnapshot statusSnapshot() {
        Runtime rt = Runtime.getRuntime();
        long heapCommitted = rt.totalMemory();
        // Same available-memory semantics as HeapPlan (MemAvailable / reclaimable / MXBean free).
        MemoryProbe.Memory host = MemoryProbe.current();
        return new cc.jumpkick.engine.http.StatusSnapshot(
                version,
                pid,
                startedAtMillis,
                activeConnections.get(),
                activeBuildPlans.get(),
                heapCommitted - rt.freeMemory(),
                heapCommitted,
                rt.maxMemory(),
                MemoryProbe.ownRssBytes(),
                aotTrainingPid(),
                rt.availableProcessors(),
                host.totalBytes(),
                host.availableBytes(),
                systemCpuLoad(),
                peakActiveConnections.get(),
                peakActiveBuildPlans.get());
    }

    /**
     * Recent whole-host CPU utilisation in {@code [0, 1]}, or {@code -1} until the first sample / when
     * the platform bean can't answer. The dashboard renders this as a percent next to CORES.
     */
    private static double systemCpuLoad() {
        try {
            var os = (com.sun.management.OperatingSystemMXBean)
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            double load = os.getCpuLoad();
            return load >= 0 && load <= 1 ? load : -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** The sidecar AOT trainer's pid while one is alive, {@code -1} otherwise. */
    private long aotTrainingPid() {
        Process p = aotTrainer;
        return (p != null && p.isAlive()) ? p.pid() : -1;
    }

    /**
     * Install the sidecar AOT-trainer factory; must be called before {@link #run}. The factory
     * is invoked once, only if this engine wins its election and starts serving; it may return
     * {@code null} (nothing to train after all — e.g. the cache appeared meanwhile).
     */
    public void aotTrainerSpawner(java.util.function.Supplier<Process> spawner) {
        this.aotTrainerSpawner = spawner;
    }

    /**
     * Spawn and adopt the sidecar AOT trainer. Best-effort: a trainer that fails to start (or
     * never finishes) costs a log line, never the engine. The trainer self-terminates in seconds;
     * the timeout is a belt against a hung child, generous enough to never fire on a healthy one.
     */
    private void startAotTrainerIfConfigured() {
        java.util.function.Supplier<Process> spawner = aotTrainerSpawner;
        if (spawner == null) return;
        try {
            Process p = spawner.get();
            if (p == null) return;
            aotTrainer = p;
            log.accept("jk engine: AOT training sidecar started (pid " + p.pid() + ")");
            p.onExit().orTimeout(5, java.util.concurrent.TimeUnit.MINUTES).whenComplete((proc, err) -> {
                if (err != null) {
                    p.destroyForcibly();
                    log.accept("jk engine: AOT training sidecar overran; killed (pid " + p.pid() + ")");
                } else {
                    log.accept("jk engine: AOT training sidecar finished (pid " + p.pid() + ", exit " + proc.exitValue()
                            + ")");
                }
                aotTrainer = null;
            });
        } catch (RuntimeException e) {
            log.accept("jk engine: AOT training sidecar failed to start: " + e.getMessage());
        }
    }

    /**
     * Kill a live engine AOT sidecar, clear the spawner, and suppress <em>all</em> AOT training
     * (workers included) so a lame-duck process cannot refill {@code state/aot} after the primary
     * wipe (JK-1452). Idempotent.
     */
    private void stopAotTrainerQuietly() {
        cc.jumpkick.util.AotSettings.suppressTraining();
        aotTrainerSpawner = null;
        Process p = aotTrainer;
        aotTrainer = null;
        if (p == null || !p.isAlive()) return;
        try {
            p.destroyForcibly();
            log.accept("jk engine: killed AOT training sidecar (no longer primary, pid " + p.pid() + ")");
        } catch (RuntimeException ignored) {
            // best-effort
        }
    }

    /** Whether the endpoint pointer still names this generation's socket. */
    private boolean endpointNamesThisEngine() {
        if (active == null) return false;
        try {
            Path ep = EnginePaths.endpoint(paths);
            if (!Files.isRegularFile(ep)) return false;
            return active.socket().getFileName().toString().equals(Files.readString(ep).trim());
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
        // Heartbeat + pipeline workers may write concurrently.
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

    /**
     * Thread-safe collector of one build's outcome, folded from {@link WorkspaceBuildListener}/{@link
     * BuildPlanListener} callbacks that fire on scheduler/worker threads, then frozen into a {@link
     * BuildRecord} at request-finish. Success is taken from the runner's terminal result when set,
     * else derived (no failed module/pipeline and not cancelled).
     */
    // Package-private so the cancel-stamp guard is unit-testable (JK-1521).
    static final class BuildAccumulator {
        private final String kind;
        private final String dir;
        private final String coord;
        private final String trigger; // how the build was started: "cli" (socket) or "web" (dashboard)
        /** Per-request chrome timeline; null when disabled. Same step millis as metrics. */
        private final ChromeTimeline timeline;
        /** request was {@code --redo}/{@code --force} — train {@code build:rebuild} metrics. */
        private final boolean rebuild;

        /**
         * This run's byte accounting. Opened as the ambient ledger on the runner thread, so every
         * session the request builds meters into it (see {@link cc.jumpkick.task.IoLedger}).
         */
        private final cc.jumpkick.task.IoLedger io = new cc.jumpkick.task.IoLedger();

        private final java.util.List<ModuleOutcome> modules = new java.util.concurrent.CopyOnWriteArrayList<>();
        // Steps per module dir (name → Step, arrival order, last status wins). The single-pipeline path
        // uses the "" (SINGLE_PIPELINE_DIR) bucket; workspace modules use their real dir. Rendered as a
        // chain per module (the dashboard shows one chain per module, not one merged strip).
        private final java.util.Map<String, java.util.Map<String, BuildRecord.Task>> stepsByDir =
                new java.util.concurrent.ConcurrentHashMap<>();
        // Step dependency edges (dir → step name → requires), captured from the genuine in-process
        // BuildPlanResult in addBuildPlan. Reconstructs each module's step DAG for the critical-path
        // cache-benefit metric; the wire's stepFinish carries no edges, so this is the only source.
        private final java.util.Map<String, java.util.Map<String, java.util.List<String>>> requiresByDir =
                new java.util.concurrent.ConcurrentHashMap<>();
        // Module dependency graph (dir → prereq dirs) from onModuleGraph; empty for single-pipeline builds.
        private volatile java.util.Map<String, java.util.Set<String>> moduleEdges = java.util.Map.of();
        private final java.util.List<BuildRecord.Diag> diagnostics = new java.util.concurrent.CopyOnWriteArrayList<>();
        private volatile BuildRecord.Tests tests;
        private volatile boolean anyFailure;
        private volatile boolean userCancelled;
        private volatile Boolean success;
        private volatile int exitCode;

        BuildAccumulator(String kind, String dir, String coord, String trigger) {
            this(kind, dir, coord, trigger, null, false);
        }

        BuildAccumulator(String kind, String dir, String coord, String trigger, ChromeTimeline timeline) {
            this(kind, dir, coord, trigger, timeline, false);
        }

        /** Start-time build number; 0 when unnumbered. */
        private final long buildNumber;
        /** In-flight journal id from begin(); null when history disabled or non-journaled. */
        private final String journalId;

        BuildAccumulator(
                String kind, String dir, String coord, String trigger, ChromeTimeline timeline, boolean rebuild) {
            this(kind, dir, coord, trigger, timeline, rebuild, 0L, null);
        }

        BuildAccumulator(
                String kind,
                String dir,
                String coord,
                String trigger,
                ChromeTimeline timeline,
                boolean rebuild,
                long buildNumber,
                String journalId) {
            this.kind = kind;
            this.dir = dir;
            this.coord = coord;
            this.trigger = trigger;
            this.timeline = timeline;
            this.rebuild = rebuild;
            this.buildNumber = buildNumber;
            this.journalId = journalId;
        }

        boolean rebuild() {
            return rebuild;
        }

        long buildNumber() {
            return buildNumber;
        }

        String journalId() {
            return journalId;
        }

        cc.jumpkick.task.IoLedger io() {
            return io;
        }

        String dir() {
            return dir;
        }

        /** True only when the runner explicitly reported success (not merely "no failure seen yet"). */
        boolean succeeded() {
            return Boolean.TRUE.equals(success);
        }

        /** True when the runner already stamped success or failure via {@link #setOutcome}. */
        boolean hasOutcome() {
            return success != null;
        }

        /**
         * Outcome for SSE {@code request-finish} — same default as {@link #toRecord}: explicit
         * stamp when set, else not-failed and not cancelled.
         */
        boolean effectiveSuccess(boolean cancelled) {
            if (cancelled) return false;
            return success != null ? success : !anyFailure;
        }

        /**
         * Genuine user/deadline cancellation — set by {@link #markUserCancelled} when BUILD_CANCEL /
         * mid-job EOF / deadline fires, or by a finished pipeline with
         * {@link BuildPlanResult#userCancelled}. Not the racy end-of-request EOF after a terminal
         * outcome (that is ignored in {@link #markUserCancelled} / {@link #toRecord}).
         */
        boolean wasCancelled() {
            return userCancelled;
        }

        /**
         * Stamp cancel immediately so a force-killed runner still journals as cancelled, not success.
         * No-op once {@link #setOutcome} ran. For a non-{@code explicit} signal (socket EOF), also a
         * no-op once a module/pipeline reported failure ({@code anyFailure}): the client often closes
         * the socket the instant it reads a terminal failure, and that EOF must not re-label a
         * test/compile failure as cancelled. An {@code explicit} signal (BUILD_CANCEL, dashboard
         * cancel, wall deadline) is not that race — a genuine abort after a module failure still
         * journals as cancelled (JK-1521).
         */
        void markUserCancelled(boolean explicit) {
            if (success != null) return;
            if (!explicit && anyFailure) return;
            userCancelled = true;
        }

        void addModule(ModuleOutcome o) {
            modules.add(o);
            if (!o.success()) anyFailure = true;
        }

        /** One finished step, stored under its module dir ("" for a single-pipeline build). */
        void addTask(String dir, String step, String phase, String status, long millis) {
            stepsByDir
                    .computeIfAbsent(
                            dir == null ? "" : dir,
                            k -> java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>()))
                    .put(step, new BuildRecord.Task(step, phase, status, millis));
            if (timeline != null) {
                timeline.complete(timelineModule(dir), step, status == null ? "" : status, millis);
            }
        }

        /** Track label for chrome: entry coord when single-pipeline; else module path leaf. */
        private String timelineModule(String stepDir) {
            if (stepDir == null || stepDir.isBlank()) {
                return coord != null && !coord.isBlank() ? coord : (dir != null ? dir : "_");
            }
            try {
                Path p = Path.of(stepDir);
                Path name = p.getFileName();
                return name != null ? name.toString() : stepDir;
            } catch (RuntimeException e) {
                return stepDir;
            }
        }

        private volatile boolean timelineFlushed;

        java.util.Optional<Path> flushTimeline() {
            if (timeline == null || timelineFlushed) return java.util.Optional.empty();
            java.util.Optional<Path> written = timeline.flush();
            if (written.isPresent()) timelineFlushed = true;
            return written;
        }

        /** Diagnostics + failure flag from a finished pipeline (steps come from {@link #addTask}). */
        void addBuildPlan(String dir, BuildPlanResult result) {
            String d0 = dir == null ? "" : dir;
            // Prefer the pipeline's own dir for.env lookup; fall back to the run's entry dir.
            String redactDir = (dir != null && !dir.isBlank()) ? dir : this.dir;
            for (BuildPlanResult.Diagnostic d : result.errors()) {
                diagnostics.add(new BuildRecord.Diag(
                        "error",
                        d0,
                        d.step(),
                        d.code(),
                        redactEnv(redactDir, d.message()),
                        d.test(),
                        d.exceptionClass()));
            }
            for (BuildPlanResult.Diagnostic d : result.warnings()) {
                diagnostics.add(new BuildRecord.Diag(
                        "warning",
                        d0,
                        d.step(),
                        d.code(),
                        redactEnv(redactDir, d.message()),
                        d.test(),
                        d.exceptionClass()));
            }
            // Capture the step dependency edges from the genuine in-process result (engine-side
            // result.steps is reliably populated, unlike a client-side reconstruction).
            for (BuildPlanResult.StepReport s : result.steps()) {
                requiresByDir
                        .computeIfAbsent(d0, k -> new java.util.concurrent.ConcurrentHashMap<>())
                        .put(s.name(), java.util.List.copyOf(s.requires()));
            }
            if (!result.success()) anyFailure = true;
            if (result.userCancelled()) userCancelled = true;
        }

        void setModuleEdges(java.util.Map<Path, java.util.Set<Path>> edges) {
            java.util.Map<String, java.util.Set<String>> m = new java.util.HashMap<>();
            if (edges != null) {
                for (var e : edges.entrySet()) {
                    java.util.Set<String> prereqs = new java.util.HashSet<>();
                    for (Path p : e.getValue()) prereqs.add(p.toString());
                    m.put(e.getKey().toString(), prereqs);
                }
            }
            this.moduleEdges = m;
        }

        /** Per-module step inputs (status + millis + dependency edges) for the cache-benefit metric. */
        java.util.List<CacheBenefit.ModuleInput> benefitModules() {
            java.util.List<CacheBenefit.ModuleInput> out = new java.util.ArrayList<>();
            for (String d : stepsByDir.keySet()) {
                java.util.Map<String, java.util.List<String>> req = requiresByDir.getOrDefault(d, java.util.Map.of());
                java.util.List<CacheBenefit.StepInput> steps = new java.util.ArrayList<>();
                for (BuildRecord.Task s : stepsFor(d)) {
                    steps.add(new CacheBenefit.StepInput(
                            s.name(), s.status(), s.millis(), req.getOrDefault(s.name(), java.util.List.of())));
                }
                out.add(new CacheBenefit.ModuleInput(d, steps));
            }
            return out;
        }

        java.util.Map<String, java.util.Set<String>> benefitModuleEdges() {
            return moduleEdges;
        }

        private java.util.List<BuildRecord.Task> stepsFor(String dir) {
            java.util.Map<String, BuildRecord.Task> m = stepsByDir.get(dir == null ? "" : dir);
            if (m == null) return java.util.List.of();
            synchronized (m) {
                return new java.util.ArrayList<>(m.values());
            }
        }

        /**
         * Fold in one pipeline's test summary. Single-pipeline {@code jk test}/{@code 1build} call this once;
         * a workspace build calls it per module (each module's {@code TEST_RESULT}), so the counts
         * accumulate into the run's total rather than the last module overwriting the rest.
         */
        synchronized void addTests(TestSummary t) {
            if (t == null) return;
            tests = tests == null
                    ? new BuildRecord.Tests(t.total(), t.succeeded(), t.failed(), t.skipped())
                    : new BuildRecord.Tests(
                            tests.total() + t.total(),
                            tests.succeeded() + t.succeeded(),
                            tests.failed() + t.failed(),
                            tests.skipped() + t.skipped());
        }

        void setOutcome(boolean ok, int exit) {
            this.success = ok;
            this.exitCode = exit;
            if (!ok) anyFailure = true;
        }

        String diagnosticsText() {
            if (diagnostics.isEmpty()) return null;
            StringBuilder b = new StringBuilder();
            for (BuildRecord.Diag d : diagnostics) {
                b.append('[').append(d.severity()).append("] ");
                if (notBlank(d.step())) b.append(d.step()).append(": ");
                if (notBlank(d.test())) b.append(d.test()).append(" — ");
                if (notBlank(d.exceptionClass()))
                    b.append('(').append(d.exceptionClass()).append(") ");
                b.append(d.message() == null ? "" : d.message()).append('\n');
            }
            return b.toString();
        }

        BuildRecord toRecord(long finishedAt, boolean cancelled, long millis, String jkVersion, String commit) {
            return toRecord(finishedAt, cancelled, millis, jkVersion, commit, null);
        }

        BuildRecord toRecord(
                long finishedAt,
                boolean cancelled,
                long millis,
                String jkVersion,
                String commit,
                CacheBenefit.Result benefit) {
            boolean ok = success != null ? success : (!anyFailure && !cancelled);
            int exit = success != null ? exitCode : (ok ? 0 : 1);
            // cancelToken / late markUserCancelled also trip on the benign end-of-request EOF (the
            // client closes the socket as soon as it reads the terminal). Trust a stamped outcome:
            // success is never cancelled; an explicit failure is cancelled only when the user/deadline
            // stamp was set (not merely cancelled=true from cooperative fail-fast / EOF race).
            boolean cancelledEffective = resolveCancelledFlag(success, userCancelled, cancelled);
            // Each workspace module carries its own step chain (keyed by its dir); a single-pipeline
            // build has no module rows, so its steps live in the record's top-level list (the ""
            // bucket). This is exactly the two shapes the dashboard renders (per-module vs compact).
            java.util.List<BuildRecord.Module> moduleList = new java.util.ArrayList<>();
            for (ModuleOutcome o : modules) {
                String mdir = o.dir() == null ? "" : o.dir().toString();
                moduleList.add(
                        new BuildRecord.Module(o.coord(), mdir, o.success(), o.exitCode(), o.millis(), stepsFor(mdir)));
            }
            java.util.List<BuildRecord.Task> topSteps = moduleList.isEmpty() ? stepsFor("") : java.util.List.of();
            BuildRecord.CacheBenefit benefitRow = benefit == null
                    ? null
                    : new BuildRecord.CacheBenefit(
                            benefit.estimatedUncachedMillis(),
                            benefit.savedMillis(),
                            benefit.coveredSkips(),
                            benefit.totalSkips());
            cc.jumpkick.task.IoLedger.Totals bytes = io.totals();
            BuildRecord.Io ioRow = bytes.isEmpty()
                    ? null
                    : new BuildRecord.Io(bytes.remoteUp(), bytes.remoteDown(), bytes.localUp(), bytes.localDown());
            return new BuildRecord(
                    null,
                    0L,
                    BuildRecord.SCHEMA,
                    kind,
                    dir,
                    coord,
                    finishedAt - millis,
                    finishedAt,
                    millis,
                    ok,
                    cancelledEffective,
                    exit,
                    jkVersion,
                    tests,
                    moduleList,
                    topSteps,
                    new java.util.ArrayList<>(diagnostics),
                    trigger,
                    commit,
                    benefitRow,
                    false,
                    ioRow);
        }

        private static boolean notBlank(String s) {
            return s != null && !s.isBlank();
        }
    }

    /**
     * Journal / SSE cancel bit from a stamped runner outcome + cancel flags.
     *
     * <ul>
     *   <li>Stamped success → never cancelled (EOF-after-finish race).
     *   <li>Stamped failure → cancelled only when the user/deadline stamp was set (not cooperative
     *       fail-fast or post-finish EOF).
     *   <li>No outcome yet (force-killed mid-job) → honour the cancel hint.
     * </ul>
     */
    static boolean resolveCancelledFlag(Boolean successStamp, boolean userCancelled, boolean cancelHint) {
        if (Boolean.TRUE.equals(successStamp)) return false;
        if (userCancelled) return true;
        if (successStamp != null) return false; // explicit failure without a user-cancel stamp
        return cancelHint;
    }
}
