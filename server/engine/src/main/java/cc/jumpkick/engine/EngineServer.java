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
import cc.jumpkick.engine.listen.EngineEvent;
import cc.jumpkick.engine.listen.EventSink;
import cc.jumpkick.engine.listen.WireEventSink;
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
    private volatile java.util.function.Supplier<Process> aotTrainerSpawner;

    private volatile Process aotTrainer;
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
                        // Same shape as BUILD_REQUEST but for a single project's test plan (Task 3).
                        handleTestRequest(line, reader, writer);
                        return;
                    }
                    case EngineProtocol.SINGLE_BUILD_REQUEST -> {
                        // Same shape as TEST_REQUEST but a real (non-testOnly) build plan.
                        handleSingleBuildRequest(line, reader, writer);
                        return;
                    }
                    case EngineProtocol.LOCK_REQUEST -> {
                        // Same fork-and-watch shape as BUILD_REQUEST, hosting jk lock's cascade.
                        jobs.submit(
                                line,
                                JobRequest.plan("lock", "jk-engine-lock-", this::runLock),
                                new JobTransport.SocketWatch(reader, writer));
                        return;
                    }
                    case EngineProtocol.UPDATE_REQUEST -> {
                        // jk update rides jk lock's event vocabulary (plus the --git splice mode).
                        jobs.submit(
                                line,
                                JobRequest.plan("update", "jk-engine-update-", this::runUpdate),
                                new JobTransport.SocketWatch(reader, writer));
                        return;
                    }
                    case EngineProtocol.SYNC_REQUEST -> {
                        // jk sync is a single plan — TEST_REQUEST's wire shape.
                        jobs.submit(
                                line,
                                JobRequest.plan("sync", "jk-engine-sync-", this::runSync),
                                new JobTransport.SocketWatch(reader, writer));
                        return;
                    }
                    case EngineProtocol.AUDIT_REQUEST -> {
                        // Hosted worker command: single plan, worker forked engine-side.
                        jobs.submit(
                                line,
                                JobRequest.plan("audit", "jk-engine-audit-", this::runAudit),
                                new JobTransport.SocketWatch(reader, writer));
                        return;
                    }
                    case EngineProtocol.FORMAT_REQUEST -> {
                        jobs.submit(
                                line,
                                JobRequest.plan("format", "jk-engine-format-", this::runFormat),
                                new JobTransport.SocketWatch(reader, writer));
                        return;
                    }
                    case EngineProtocol.PUBLISH_REQUEST -> {
                        jobs.submit(
                                line,
                                JobRequest.plan("publish", "jk-engine-publish-", this::runPublish),
                                new JobTransport.SocketWatch(reader, writer));
                        return;
                    }
                    case EngineProtocol.IMAGE_REQUEST -> {
                        jobs.submit(
                                line,
                                JobRequest.plan("image", "jk-engine-image-", this::runImage),
                                new JobTransport.SocketWatch(reader, writer));
                        return;
                    }
                    case EngineProtocol.IMPORT_REQUEST -> {
                        jobs.submit(
                                line,
                                JobRequest.plan("import", "jk-engine-import-", this::runImport),
                                new JobTransport.SocketWatch(reader, writer));
                        return;
                    }
                    case EngineProtocol.PROVISION_REQUEST -> {
                        // One-shot (no plan events), but the worker may download a whole Maven/Gradle
                        // distribution — same fork-and-watch shape so an EOF still cancels.
                        jobs.submit(
                                line,
                                JobRequest.plan("provision", "jk-engine-provision-", this::runProvision),
                                new JobTransport.SocketWatch(reader, writer));
                        return;
                    }
                    case EngineProtocol.COMPILE_REQUEST -> {
                        // Hosted plan command: jk compile is a single plan.
                        jobs.submit(
                                line,
                                JobRequest.plan("compile", "jk-engine-compile-", this::runCompile),
                                new JobTransport.SocketWatch(reader, writer));
                        return;
                    }
                    case EngineProtocol.NATIVE_REQUEST -> {
                        // jk native's serial module cascade, speaking BUILD_REQUEST's workspace vocabulary.
                        jobs.submit(
                                line,
                                JobRequest.plan("native", "jk-engine-native-", this::runNative),
                                new JobTransport.SocketWatch(reader, writer));
                        return;
                    }
                    case EngineProtocol.TRAIN_REQUEST -> {
                        jobs.submit(
                                line,
                                JobRequest.plan("train", "jk-engine-train-", this::runTrain),
                                new JobTransport.SocketWatch(reader, writer));
                        return;
                    }
                    case EngineProtocol.INSTALL_REQUEST -> {
                        // jk install's build + cache-install halves; make-install stays client-side.
                        jobs.submit(
                                line,
                                JobRequest.plan("install", "jk-engine-install-", this::runInstall),
                                new JobTransport.SocketWatch(reader, writer));
                        return;
                    }
                    case EngineProtocol.GIT_FETCH_REQUEST -> {
                        // jk install <git-url>'s clone half (git runs in-process in the engine).
                        jobs.submit(
                                line,
                                JobRequest.plan("git-fetch", "jk-engine-gitfetch-", this::runGitFetch),
                                new JobTransport.SocketWatch(reader, writer));
                        return;
                    }
                    case EngineProtocol.SCRIPT_PREPARE_REQUEST -> {
                        jobs.submit(
                                line,
                                JobRequest.plan("script", "jk-engine-script-", this::runScriptPrepare),
                                new JobTransport.SocketWatch(reader, writer));
                        return;
                    }
                    case EngineProtocol.TOOL_RESOLVE_REQUEST -> {
                        // Hosted long-tail command: jk tool install/run Maven resolve+fetch.
                        jobs.submit(
                                line,
                                JobRequest.plan("tool", "jk-engine-tool-", this::runToolResolve),
                                new JobTransport.SocketWatch(reader, writer));
                        return;
                    }
                    case EngineProtocol.CACHE_PRUNE_REQUEST -> {
                        // Cache maintenance is an idle-boundary job, not a plan: it waits for
                        // activeBuildPlans to drain (and blocks new ones) instead of joining them.
                        jobs.submit(
                                line,
                                JobRequest.maintenance("cache", "jk-engine-cache-", this::runCacheMaintenance),
                                new JobTransport.SocketWatch(reader, writer));
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
                    case EngineProtocol.FRESHEN_CATALOG_REQUEST -> handleFreshenCatalogRequest(line, writer);
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
        jobs.submit(
                requestLine,
                JobRequest.workspace("build", "jk-engine-build-", this::runBuild),
                new JobTransport.SocketWatch(reader, writer));
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
        return group == null ? "" : group;
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
            // Workspace jk test: every module plan stops at run-tests (no package/native tails).
            boolean testOnly = Jsonl.bool(requestLine, "testOnly", false);
            // -m / --affected-since: the client's module selection; null = engine forecasts.
            java.util.List<String> dirtyHintDirs = EngineProtocol.dirtyHintOf(requestLine);

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
                            dirtyHintDirs == null
                                    ? null // engine forecasts dirty modules
                                    : dirtyHintDirs.stream()
                                            .map(Path::of)
                                            .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                            false, // this engine plans memory once at startup, not per request
                            freshenLock)
                    .withTestOnly(testOnly)
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
            if (rid > 0) putProgressRoot(rid, entryDirStr);
            WorkspaceBuildListener listener = wireListener(writer, entryDirStr);
            WorkspaceResult result = SessionContext.where(session, () -> BuildService.buildWorkspace(req, listener));
            // Exclusive build work is done; free the fingerprint before finish events / bookkeeping.
            releaseExclusiveSlot();
            // User/deadline cancel may set the token after modules already failed — trust either flag.
            boolean cancelled = result.cancelled() || jobs.effectiveCancelled(rid, cancelToken.cancelled());
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
            boolean cancelled = jobs.effectiveCancelled(rid, cancelToken.cancelled());
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
     * As {@link #handleBuildRequest}, but for a single project's test plan (Task 3): forks the run
     * onto its own thread and keeps reading the connection for a cancel/EOF meanwhile.
     */
    private void handleTestRequest(String requestLine, BufferedReader reader, BufferedWriter writer) {
        jobs.submit(
                requestLine,
                JobRequest.plan("test", "jk-engine-test-", this::runTest),
                new JobTransport.SocketWatch(reader, writer));
    }

    /**
     * As {@link #handleTestRequest}, but for a single (non-workspace) project's real build plan — the
     * engine-hosted counterpart of {@code BuildCommand.runForDir}.
     */
    private void handleSingleBuildRequest(String requestLine, BufferedReader reader, BufferedWriter writer) {
        jobs.submit(
                requestLine,
                JobRequest.plan("build", "jk-engine-1build-", this::runSingleBuild),
                new JobTransport.SocketWatch(reader, writer));
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
                    () -> cc.jumpkick.runtime.OutdatedPlans.compute(
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

    /**
     * {@link EngineProtocol#FRESHEN_CATALOG_REQUEST}: on-demand, TTL-free freshen of a
     * network-backed catalog ({@code templates}, {@code libraries}, or {@code jdks}) — the network
     * fetch itself always happens here, never client-side. Every client that is already talking to
     * an engine (the web dashboard and MCP are always engine-hosted; the CLI once a healthy engine
     * is running) delegates here, including for {@code jdks} — that lets those non-CLI clients
     * install JDKs too. The one caller that does <em>not</em> require a reachable engine first is
     * {@code jk jdk install}/{@code update} when no engine is running yet: the engine is a JVM
     * process that needs a JDK to run, so it cannot be the sole path to provisioning the first JDK
     * on a bare machine. That CLI path only calls this when an engine already answers, and fetches
     * {@code jdks.json} directly itself otherwise ({@code JdkCatalogClient}) — see {@link
     * cc.jumpkick.cli.engine.EngineClient#freshenCatalogIfRunning}.
     */
    private void handleFreshenCatalogRequest(String requestLine, BufferedWriter writer) {
        String catalog = Jsonl.str(requestLine, "catalog");
        boolean offline = Jsonl.bool(requestLine, "offline", false);
        String url = Jsonl.str(requestLine, "url");
        String cacheFile = Jsonl.str(requestLine, "cacheFile");
        String error = null;
        try {
            switch (String.valueOf(catalog)) {
                case "templates" -> {
                    if (!offline) cc.jumpkick.templates.OfficialTemplatesFreshen.refreshNow(msg -> {});
                }
                case "libraries" ->
                    cc.jumpkick.repo.LibraryRegistrySync.ensurePresent(
                            offline,
                            url != null
                                    ? java.net.URI.create(url)
                                    : cc.jumpkick.repo.LibraryRegistryClient.DEFAULT_SOURCE,
                            cacheFile != null
                                    ? Path.of(cacheFile)
                                    : cc.jumpkick.library.LibraryCatalog.downloadedFile());
                case "jdks" -> {
                    if (!offline) {
                        cc.jumpkick.jdk.JdkCatalogClient client = url != null
                                ? new cc.jumpkick.jdk.JdkCatalogClient(
                                        new cc.jumpkick.http.Http(),
                                        java.net.URI.create(url),
                                        cacheFile != null
                                                ? Path.of(cacheFile)
                                                : cc.jumpkick.jdk.JdkCatalogClient.defaultCachePath(),
                                        java.time.Duration.ZERO)
                                : new cc.jumpkick.jdk.JdkCatalogClient();
                        client.onWarning(msg -> {}).fetch(true);
                    }
                }
                default -> error = "unknown catalog: " + catalog;
            }
        } catch (Exception e) {
            error = String.valueOf(e.getMessage());
        }
        sendQuiet(writer, EngineProtocol.freshenCatalogAck(error == null, error));
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
            boolean skipTests = Jsonl.bool(requestLine, "skipTests", false);
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
            ExplainPlan plan =
                    SessionContext.where(session, () -> BuildService.explain(entryDir, entryBuild, cache, skipTests));
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
            // Schedule-aware ETA; 0 = fully cached. Same estimateEtaMillis as jk build countdown.
            // fullMillis prices the same graph as a full rebuild (--redo) so explain can report
            // rebuild effort as remaining/full (weight/time, not a count average).
            String etaJdksDirStr = Jsonl.str(requestLine, "jdksDir");
            int workers = Jsonl.intValue(requestLine, "workers", 0); // 0 = auto (bare jk build)
            int maxModuleConcurrency = Jsonl.intValue(requestLine, "maxModuleConcurrency", 0);
            if (maxModuleConcurrency <= 0 && Jsonl.bool(requestLine, "serial", false)) {
                maxModuleConcurrency = 1;
            }
            boolean parallelTests = Jsonl.bool(requestLine, "parallelTests", false);
            int maxConc = maxModuleConcurrency;
            Path etaJdksDir = etaJdksDirStr != null ? Path.of(etaJdksDirStr) : null;
            String etaProfile = Jsonl.str(requestLine, "profile");
            long etaMillis = SessionContext.where(
                    session,
                    () -> BuildService.estimateEtaMillis(
                            plan,
                            entryDir,
                            cache,
                            workers,
                            etaJdksDir,
                            etaProfile,
                            skipTests,
                            verbose,
                            parallelTests,
                            maxConc));
            long fullMillis;
            if (rebuild || force) {
                fullMillis = etaMillis; // already priced as full rebuild
            } else {
                Session fullSession = session.withConfig(config.withRebuild(Optional.of(true)));
                fullMillis = SessionContext.where(
                        fullSession,
                        () -> BuildService.estimateEtaMillis(
                                plan,
                                entryDir,
                                cache,
                                workers,
                                etaJdksDir,
                                etaProfile,
                                skipTests,
                                verbose,
                                parallelTests,
                                maxConc));
            }
            sendQuiet(writer, EngineProtocol.eta(etaMillis, fullMillis));
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
     * same single-plan event vocabulary {@link #runBuild} already speaks per module, here tagged with
     * the fixed {@link EngineProtocol#SINGLE_PLAN_DIR} sentinel since there's only one plan.
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
            cc.jumpkick.runtime.BuildPlanner.Inputs inputs = new cc.jumpkick.runtime.BuildPlanner.Inputs(
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
            cc.jumpkick.run.BuildPlan plan =
                    cc.jumpkick.runtime.BuildPlanner.coreBuilder(inputs).build();

            String dir = EngineProtocol.SINGLE_PLAN_DIR;
            for (Task p : plan.steps()) {
                sendQuiet(
                        writer,
                        EngineProtocol.planStep(
                                dir, p.name(), p.label(), phaseWire(p.group().orElse(null))));
            }
            sendQuiet(writer, EngineProtocol.planDone(1));
            plan.addListener(wireBuildPlanListener(dir, writer, plan));

            cc.jumpkick.run.BuildPlanResult result = SessionContext.where(session, plan::run);
            // planFinish already sent; free exclusive slot before bookkeeping (see releaseExclusiveSlot).
            releaseExclusiveSlot();
            accTests(
                    eventRequestId(),
                    plan.get(cc.jumpkick.runtime.BuildPlanner.TEST_RESULT).orElse(null));
            accOutcome(eventRequestId(), result.success(), result.success() ? 0 : 1);
            // planFinish (with test counts, if any) was already sent by wireBuildPlanListener's own
            // planFinish handling — nothing further to send here; the connection close signals "done".
        } catch (Exception e) {
            sendQuiet(writer, requestFailedLine(null, e));
        }
    }

    /**
     * Single-project build plan (same wire shape as {@link #runTest}, {@code testOnly=false}).
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
            cc.jumpkick.runtime.BuildPlanner.Inputs inputs = new cc.jumpkick.runtime.BuildPlanner.Inputs(
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
            cc.jumpkick.run.BuildPlan plan = SessionContext.where(session, () -> {
                cc.jumpkick.run.BuildPlan.Builder builder = cc.jumpkick.runtime.BuildPlanner.coreBuilder(inputs, false);
                cc.jumpkick.runtime.BuildPlanner.appendDeclaredTails(builder, inputs);
                return builder.build();
            });
            long barWeight = plan.estimatedTotalWeight();

            String dir = EngineProtocol.SINGLE_PLAN_DIR;
            for (Task p : plan.steps()) {
                sendQuiet(
                        writer,
                        EngineProtocol.planStep(
                                dir, p.name(), p.label(), phaseWire(p.group().orElse(null))));
            }
            sendQuiet(writer, EngineProtocol.planDone(1));
            plan.addListener(wireBuildPlanListener(dir, writer, plan));

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
            cc.jumpkick.run.BuildPlanResult result = SessionContext.where(session, plan::run);
            // planFinish already sent; free exclusive slot before calibration / memo / prune queue.
            releaseExclusiveSlot();
            accTests(
                    eventRequestId(),
                    plan.get(cc.jumpkick.runtime.BuildPlanner.TEST_RESULT).orElse(null));
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
     * each module a {@link EngineProtocol#LOCK_MODULE} + plan-step burst + the standard plan
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
     * {@link #runLock}'s exact event vocabulary, with {@code jk update}'s always-fresh plan) or the
     * {@code --git} splice mode, which runs no plan at all — just the {@link
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
                    var outcome = cc.jumpkick.runtime.LockPlans.updateGitOnly(
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
     * Decode a {@link EngineProtocol#SYNC_REQUEST} and run {@code jk sync}'s single plan in-session
     * — {@link EngineProtocol#TEST_REQUEST}'s exact wire shape, with the fetched/up-to-date counts
     * riding the terminal plan-finish. The plan is built with {@code allowJdkInstall = false}: JDK
     * installs never happen inside the engine (the client pre-flights them — see {@link
     * cc.jumpkick.runtime.SyncPlans}). On success, queues the opportunistic cache prune for the
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
                cc.jumpkick.run.BuildPlan plan = cc.jumpkick.runtime.SyncPlans.syncBuildPlan(
                        entryDir, cache, jdksDir, repoUrl, sources, fetched, upToDate, null, false);
                String dir = EngineProtocol.SINGLE_PLAN_DIR;
                for (Task p : plan.steps()) {
                    sendQuiet(
                            writer,
                            EngineProtocol.planStep(
                                    dir,
                                    p.name(),
                                    p.label(),
                                    phaseWire(p.group().orElse(null))));
                }
                sendQuiet(writer, EngineProtocol.planDone(1));
                plan.addListener(wireBuildPlanListener(
                        dir, writer, (java.util.function.Function<BuildPlanResult, String>) result ->
                                EngineProtocol.planFinishSync(dir, result.success(), fetched.get(), upToDate.get())));
                BuildPlanResult result = plan.run();
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
     * Stream one single-plan command over the wire — the shared tail of every Wave-2 handler: the
     * {@link EngineProtocol#SINGLE_PLAN_DIR}-tagged plan-step burst, the standard plan events via
     * {@link #wireBuildPlanListener}, and {@code finishEncoder}'s terminal {@code plan-finish} variant.
     */
    private void streamSingleBuildPlan(
            cc.jumpkick.run.BuildPlan plan,
            Session session,
            BufferedWriter writer,
            java.util.function.Function<BuildPlanResult, String> finishEncoder)
            throws Exception {
        String dir = EngineProtocol.SINGLE_PLAN_DIR;
        for (Task p : plan.steps()) {
            sendQuiet(
                    writer,
                    EngineProtocol.planStep(
                            dir, p.name(), p.label(), phaseWire(p.group().orElse(null))));
        }
        sendQuiet(writer, EngineProtocol.planDone(1));
        plan.addListener(wireBuildPlanListener(dir, writer, finishEncoder));
        SessionContext.where(session, plan::run);
    }

    /**
     * Decode an {@link EngineProtocol#AUDIT_REQUEST} and run {@code jk audit}'s plan in-session,
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
            String dir = EngineProtocol.SINGLE_PLAN_DIR;
            cc.jumpkick.run.BuildPlan plan = cc.jumpkick.runtime.AuditPlans.auditBuildPlan(
                    cc.jumpkick.lock.LockPaths.lockFile(entryDir),
                    cache,
                    severity,
                    batch != null ? java.net.URI.create(batch) : null,
                    vulns != null ? java.net.URI.create(vulns) : null,
                    (module, version, vulnId, sev, summary) ->
                            sendQuiet(writer, EngineProtocol.auditFinding(dir, module, version, vulnId, sev, summary)));
            streamSingleBuildPlan(plan, session, writer, result -> EngineProtocol.planFinish(dir, result.success()));
        } catch (Exception e) {
            sendQuiet(writer, requestFailedLine(null, e));
        }
    }

    /**
     * Decode a {@link EngineProtocol#FORMAT_REQUEST} and run {@code jk format}'s plan in-session:
     * source collection, formatter-jar resolution (through jk's own resolver — previously done in
     * the client process), and the formatter worker fork, with per-file results streaming as {@link
     * EngineProtocol#FORMAT_FILE} events and the counts riding the terminal plan-finish.
     */
    private void runFormat(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            boolean check = Jsonl.bool(requestLine, "check", false);
            String javaStyle = Jsonl.str(requestLine, "javaStyle");
            String kotlinStyle = Jsonl.str(requestLine, "kotlinStyle");
            boolean optimizeImports = Jsonl.bool(requestLine, "optimizeImports", true);
            boolean importOrder = Jsonl.bool(requestLine, "importOrder", true);
            boolean removeUnusedImports = Jsonl.bool(requestLine, "removeUnusedImports", true);
            String rewriteConfig = Jsonl.str(requestLine, "rewriteConfig");
            Session session = resolveSession(requestLine, cancelToken, false);
            String dir = EngineProtocol.SINGLE_PLAN_DIR;
            cc.jumpkick.run.BuildPlan plan = cc.jumpkick.runtime.FormatPlans.formatBuildPlan(
                    session.workingDir(),
                    session.cacheDir(),
                    check,
                    javaStyle,
                    kotlinStyle,
                    optimizeImports,
                    importOrder,
                    removeUnusedImports,
                    rewriteConfig != null ? Path.of(rewriteConfig) : null,
                    (path, status, message, index, total) ->
                            sendQuiet(writer, EngineProtocol.formatFile(dir, path, status, message, index, total)));
            streamSingleBuildPlan(
                    plan,
                    session,
                    writer,
                    result -> EngineProtocol.planFinishFormat(
                            dir,
                            result.success(),
                            plan.get(cc.jumpkick.runtime.FormatPlans.CHANGED).orElse(-1),
                            plan.get(cc.jumpkick.runtime.FormatPlans.CLEAN).orElse(-1),
                            plan.get(cc.jumpkick.runtime.FormatPlans.ERRORS).orElse(-1),
                            plan.get(cc.jumpkick.runtime.FormatPlans.TOTAL).orElse(-1),
                            plan.get(cc.jumpkick.runtime.FormatPlans.WORKER_EXIT)
                                    .orElse(-1)));
        } catch (Exception e) {
            sendQuiet(writer, requestFailedLine(null, e));
        }
    }

    /**
     * Decode a {@link EngineProtocol#PUBLISH_REQUEST} and run {@code jk publish}'s plan in-session.
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
            cc.jumpkick.runtime.PublishPlans.Request req = new cc.jumpkick.runtime.PublishPlans.Request(
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
            String dir = EngineProtocol.SINGLE_PLAN_DIR;
            cc.jumpkick.run.BuildPlan plan = cc.jumpkick.runtime.PublishPlans.publishBuildPlan(entryDir, cache, req);
            streamSingleBuildPlan(
                    plan,
                    session,
                    writer,
                    result -> EngineProtocol.planFinishPublish(
                            dir,
                            result.success(),
                            plan.get(cc.jumpkick.runtime.PublishPlans.FILES).orElse(-1)));
        } catch (Exception e) {
            sendQuiet(writer, requestFailedLine(null, e));
        }
    }

    /**
     * Decode an {@link EngineProtocol#IMAGE_REQUEST} and run {@code jk image}'s plan in-session
     * the full build plan plus the image tail (Jib worker or Dockerfile child process), all
     * engine-side. The terminal plan-finish carries the structured success-tail fields alongside
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
            String dir = EngineProtocol.SINGLE_PLAN_DIR;
            // Constructed in-session: the plan factory's BuildPlanner.Inputs captures the
            // ambient SessionContext at construction, so building it outside where would
            // silently pin this request to the engine's default config (dropping --force et al).
            cc.jumpkick.run.BuildPlan plan = SessionContext.where(
                    session,
                    () -> cc.jumpkick.runtime.ImagePlans.imageBuildPlan(
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
            streamSingleBuildPlan(plan, session, writer, result -> {
                cc.jumpkick.run.TestSummary testResult =
                        plan.get(cc.jumpkick.runtime.BuildPlanner.TEST_RESULT).orElse(null);
                cc.jumpkick.image.ImageConfig cfg =
                        plan.get(cc.jumpkick.runtime.ImagePlans.CONFIG).orElse(null);
                Path tarball =
                        plan.get(cc.jumpkick.runtime.ImagePlans.TARBALL_PATH).orElse(null);
                JkBuild project =
                        plan.get(cc.jumpkick.runtime.BuildPlanner.PROJECT).orElse(null);
                boolean daemonMode = tarball == null
                        && (cfg == null
                                || cfg.registry() == null
                                || cfg.registry().isBlank());
                String daemonExe = !daemonMode
                        ? null
                        : cfg != null && cfg.dockerExecutable() != null ? cfg.dockerExecutable() : "docker";
                return EngineProtocol.planFinishImage(
                        dir,
                        result.success(),
                        testResult != null ? testResult.total() : -1,
                        testResult != null ? testResult.succeeded() : -1,
                        testResult != null ? testResult.failed() : -1,
                        testResult != null ? testResult.skipped() : -1,
                        plan.get(cc.jumpkick.runtime.ImagePlans.IMAGE_REF).orElse(null),
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
     * Decode an {@link EngineProtocol#IMPORT_REQUEST} and run {@code jk import}'s single-step plan
     * in-session, streaming the worker's progress notes as {@link EngineProtocol#IMPORT_NOTE}
     * events. The worker's exit code/warnings/error ride the terminal plan-finish (a non-zero
     * worker exit is a result the client renders, not a plan failure).
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
            String dir = EngineProtocol.SINGLE_PLAN_DIR;
            cc.jumpkick.run.BuildPlan plan = cc.jumpkick.runtime.CompatPlans.importBuildPlan(
                    Path.of(Jsonl.str(requestLine, "source")),
                    Path.of(Jsonl.str(requestLine, "out")),
                    baseDir,
                    Path.of(Jsonl.str(requestLine, "tmpDir")),
                    Jsonl.bool(requestLine, "force", false),
                    report != null ? Path.of(report) : null,
                    cache,
                    (kind, text) -> sendQuiet(writer, EngineProtocol.importNote(dir, kind, text)));
            streamSingleBuildPlan(
                    plan,
                    session,
                    writer,
                    result -> EngineProtocol.planFinishImport(
                            dir,
                            result.success(),
                            plan.get(cc.jumpkick.runtime.CompatPlans.EXIT).orElse(1),
                            plan.get(cc.jumpkick.runtime.CompatPlans.WARNINGS).orElse(0),
                            plan.get(cc.jumpkick.runtime.CompatPlans.ERROR).orElse(null),
                            plan.get(cc.jumpkick.runtime.CompatPlans.DIAG).orElse(null)));
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
            var outcome = cc.jumpkick.runtime.CompatPlans.provision(
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

    // ---- hosted plan commands -------------------------------------------------------------------

    /**
     * Decode a {@link EngineProtocol#TRAIN_REQUEST} and run {@code jk train}: package then observe
     * under the tracing agent. Single-module plan (like {@code jk compile}).
     */
    private void runTrain(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            boolean verbose = Jsonl.bool(requestLine, "verbose", false);
            boolean skipTests = Jsonl.bool(requestLine, "skipTests", false);
            boolean force = Jsonl.bool(requestLine, "force", false);
            String profile = Jsonl.str(requestLine, "profile");
            String graalHomeStr = Jsonl.str(requestLine, "graalHome");
            String jdksDirStr = Jsonl.str(requestLine, "jdksDir");
            Session session = resolveSession(requestLine, cancelToken, false);
            String dir = EngineProtocol.SINGLE_PLAN_DIR;
            Path graalHome = graalHomeStr != null && !graalHomeStr.isBlank() ? Path.of(graalHomeStr) : null;
            Path jdksDir = jdksDirStr != null && !jdksDirStr.isBlank() ? Path.of(jdksDirStr) : null;
            Path javaHome = Path.of(System.getProperty("java.home"));
            cc.jumpkick.run.BuildPlan plan = SessionContext.where(session, () -> {
                JkBuild module = cc.jumpkick.config.JkBuildParser.parse(
                        session.workingDir().resolve("jk.toml"));
                return cc.jumpkick.runtime.TrainPlans.moduleBuildPlan(
                        session.workingDir(),
                        module,
                        session.cacheDir(),
                        jdksDir,
                        graalHome,
                        javaHome,
                        profile,
                        force,
                        skipTests,
                        verbose);
            });
            streamSingleBuildPlan(plan, session, writer, result -> EngineProtocol.planFinish(dir, result.success()));
        } catch (Exception e) {
            sendQuiet(writer, requestFailedLine(null, e));
        }
    }

    /**
     * Decode a {@link EngineProtocol#COMPILE_REQUEST} and run {@code jk compile}'s single
     * compile-only plan in-session — {@link EngineProtocol#TEST_REQUEST}'s exact wire shape with a
     * plain terminal plan-finish (the command has no structured summary beyond success).
     */
    private void runCompile(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            String profile = Jsonl.str(requestLine, "profile");
            boolean verbose = Jsonl.bool(requestLine, "verbose", false);
            Session session = resolveSession(requestLine, cancelToken, false);
            String dir = EngineProtocol.SINGLE_PLAN_DIR;
            // Constructed in-session — see runImage's note on ambient-session capture.
            cc.jumpkick.run.BuildPlan plan = SessionContext.where(
                    session,
                    () -> cc.jumpkick.runtime.CompilePlans.compileBuildPlan(
                            session.workingDir(), session.cacheDir(), profile, verbose));
            streamSingleBuildPlan(plan, session, writer, result -> EngineProtocol.planFinish(dir, result.success()));
        } catch (Exception e) {
            sendQuiet(writer, requestFailedLine(null, e));
        }
    }

    /**
     * Decode an {@link EngineProtocol#INSTALL_REQUEST} and run {@code jk install}'s build +
     * cache-install plan in-session (see {@link cc.jumpkick.runtime.InstallPlans}). The terminal
     * plan-finish carries the test counts for the client's exit-code logic; the launcher-writing
     * "make install" half runs client-side after this succeeds.
     */
    private void runInstall(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            boolean skipTests = Jsonl.bool(requestLine, "skipTests", false);
            boolean verbose = Jsonl.bool(requestLine, "verbose", false);
            String m2DirStr = Jsonl.str(requestLine, "m2Dir");
            String graalHomeStr = Jsonl.str(requestLine, "graalHome");
            Session session = resolveSession(requestLine, cancelToken, false);
            String dir = EngineProtocol.SINGLE_PLAN_DIR;
            // Constructed in-session — see runImage's note on ambient-session capture.
            cc.jumpkick.run.BuildPlan plan = SessionContext.where(
                    session,
                    () -> cc.jumpkick.runtime.InstallPlans.projectInstallBuildPlan(
                            session.workingDir(),
                            session.cacheDir(),
                            Path.of(m2DirStr),
                            skipTests,
                            verbose,
                            graalHomeStr != null ? Path.of(graalHomeStr) : null));
            streamSingleBuildPlan(plan, session, writer, result -> {
                cc.jumpkick.run.TestSummary testResult =
                        plan.get(cc.jumpkick.runtime.BuildPlanner.TEST_RESULT).orElse(null);
                return testResult == null
                        ? EngineProtocol.planFinish(dir, result.success())
                        : EngineProtocol.planFinish(
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
     * the terminal plan-finish carries the checkout path + sha the client's follow-up {@link
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
            String dir = EngineProtocol.SINGLE_PLAN_DIR;
            cc.jumpkick.run.BuildPlan plan = cc.jumpkick.runtime.InstallPlans.gitFetchBuildPlan(
                    Jsonl.str(requestLine, "url"),
                    Jsonl.str(requestLine, "canonicalUrl"),
                    Jsonl.str(requestLine, "ref"),
                    cache,
                    refresh,
                    Jsonl.bool(requestLine, "requireJkToml", true));
            streamSingleBuildPlan(plan, session, writer, result -> {
                Path checkout =
                        plan.get(cc.jumpkick.runtime.InstallPlans.CHECKOUT).orElse(null);
                String sha =
                        plan.get(cc.jumpkick.runtime.InstallPlans.FETCHED_SHA).orElse(null);
                return EngineProtocol.planFinishGitFetch(
                        dir, result.success(), checkout != null ? checkout.toString() : null, sha);
            });
        } catch (Exception e) {
            sendQuiet(writer, requestFailedLine(null, e));
        }
    }

    // ---- hosted long-tail commands ------------------------------------------------------------------

    /**
     * Decode a {@link EngineProtocol#SCRIPT_PREPARE_REQUEST} and run the shared script-preparation
     * plan ({@code jk tool run <file>}'s parse/resolve/compile half — see {@link
     * cc.jumpkick.runtime.ScriptPlans}). The terminal plan-finish carries the exec ingredients; the
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
            cc.jumpkick.run.BuildPlan plan =
                    switch (mode) {
                        case "java" ->
                            cc.jumpkick.runtime.ScriptPlans.javaScriptBuildPlan(
                                    script, cache, stateDir, repoUrl, forceRecompile, extraDeps);
                        case "kt" ->
                            cc.jumpkick.runtime.ScriptPlans.kotlinScriptBuildPlan(
                                    script, cache, stateDir, repoUrl, forceRecompile, extraDeps);
                        case "kts" ->
                            cc.jumpkick.runtime.ScriptPlans.ktsScriptBuildPlan(script, cache, repoUrl, extraDeps);
                        case "jar" -> cc.jumpkick.runtime.ScriptPlans.jarBuildPlan(script, cache, repoUrl);
                        default -> throw new IllegalArgumentException("unknown script mode: " + mode);
                    };
            String dir = EngineProtocol.SINGLE_PLAN_DIR;
            streamSingleBuildPlan(plan, session, writer, result -> {
                Path classesDir =
                        plan.get(cc.jumpkick.runtime.ScriptPlans.CLASSES_DIR).orElse(null);
                Path kotlincBin =
                        plan.get(cc.jumpkick.runtime.ScriptPlans.KOTLINC_BIN).orElse(null);
                Path stdlib =
                        plan.get(cc.jumpkick.runtime.ScriptPlans.KT_STDLIB).orElse(null);
                return EngineProtocol.planFinishScript(
                        dir,
                        result.success(),
                        plan.get(cc.jumpkick.runtime.ScriptPlans.MAIN_CLASS).orElse(null),
                        cc.jumpkick.runtime.ScriptPlans.classpathOf(plan).stream()
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
     * Decode a {@link EngineProtocol#TOOL_RESOLVE_REQUEST} and run the shared tool-resolution plan
     * in-session ({@code jk tool install}/{@code jk tool run}/{@code jk install <g:a:v>}'s Maven
     * resolve + fetch — see {@link cc.jumpkick.runtime.ToolPlans}). The terminal plan-finish carries
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
            String dir = EngineProtocol.SINGLE_PLAN_DIR;
            // Plain g:a[:v] label — coordinate colorization is a client-side concern.
            cc.jumpkick.run.BuildPlan plan =
                    cc.jumpkick.runtime.ToolPlans.resolveBuildPlan(spec, with, bin, mainClass, repoUrl, cache, coord);
            streamSingleBuildPlan(plan, session, writer, result -> {
                cc.jumpkick.tool.ToolEnv env =
                        plan.get(cc.jumpkick.runtime.ToolPlans.TOOL_ENV).orElse(null);
                return EngineProtocol.planFinishTool(
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
     * (emitting {@link EngineProtocol#PRUNE_WAIT} first when plans are in flight, so the client
     * isn't staring at silence) and the cross-process {@code.prune.lock}, then stream the shared
     * {@link cc.jumpkick.runtime.CachePlans} plan — {@link EngineProtocol#TEST_REQUEST}'s wire shape
     * with a {@link EngineProtocol#planFinishCache} terminal.
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
                        cc.jumpkick.run.BuildPlan plan =
                                switch (op) {
                                    case "purge" -> cc.jumpkick.runtime.CachePlans.purgeBuildPlan(cache);
                                    case "sweep" -> cc.jumpkick.runtime.CachePlans.sweepBuildPlan(cache, dryRun);
                                    case "gc" -> cc.jumpkick.runtime.CachePlans.gcBuildPlan(cache);
                                    case "clear" ->
                                        cc.jumpkick.runtime.CachePlans.clearBuildPlan(
                                                cache, Path.of(Jsonl.str(requestLine, "dir")), dryRun);
                                    default ->
                                        cc.jumpkick.runtime.CachePlans.pruneBuildPlan(
                                                cache,
                                                Jsonl.intValue(requestLine, "olderThanDays", 30),
                                                dryRun,
                                                Jsonl.bool(requestLine, "sweep", false),
                                                Jsonl.bool(requestLine, "includeJkTmp", false),
                                                Jsonl.bool(requestLine, "dropAllClassC", false));
                                };
                        Session session = Session.defaults().withCacheDir(cache).withCancel(cancelToken);
                        String dir = EngineProtocol.SINGLE_PLAN_DIR;
                        streamSingleBuildPlan(plan, session, writer, result -> {
                            // An explicit clean IS a prune — stamp it, or `usage` keeps warning
                            // "Last cleaned: never" right after a successful clean and the idle
                            // scheduler re-runs work the user just did (JK-1771). Same file for
                            // the store tier: its usage footer reads from its own root.
                            if (result.success() && !dryRun && ("prune".equals(op) || "sweep".equals(op))) {
                                try {
                                    Files.writeString(
                                            cache.resolve(cc.jumpkick.task.CachePruneScheduler.LAST_PRUNED_FILE),
                                            Long.toString(clockMillis.getAsLong()),
                                            StandardCharsets.UTF_8);
                                } catch (IOException ignored) {
                                    // best-effort stamp; the clean itself succeeded
                                }
                            }
                            return EngineProtocol.planFinishCache(
                                    dir,
                                    result.success(),
                                    plan.get(cc.jumpkick.runtime.CachePlans.FILES)
                                            .orElse(-1L),
                                    plan.get(cc.jumpkick.runtime.CachePlans.BYTES)
                                            .orElse(-1L),
                                    plan.get(cc.jumpkick.runtime.CachePlans.REACHABLE_EVICTED)
                                            .orElse(-1L),
                                    plan.get(cc.jumpkick.runtime.CachePlans.REPO_LINKS)
                                            .orElse(-1L));
                        });
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
     * calibrates its aggregate bar to the whole-workspace weight up front), then each module's plan
     * — the {@code native-image} child process forking engine-side — stopping at the first failure.
     * Exit codes are computed here ({@link cc.jumpkick.runtime.NativePlans#failureExitCode}) and
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

        // Assemble every module's plan up front and send the whole plan burst first, so the
        // client's aggregate bar calibrates to the workspace total before any module runs.
        var plans = new java.util.LinkedHashMap<Path, cc.jumpkick.run.BuildPlan>();
        var coords = new java.util.LinkedHashMap<Path, String>();
        for (var scope : scopes.entrySet()) {
            Path dir = scope.getKey();
            boolean allowNative = selectedCanonical == null
                    || selectedCanonical.contains(cc.jumpkick.runtime.BuildGraph.canonicalPath(dir));
            cc.jumpkick.run.BuildPlan plan = cc.jumpkick.runtime.NativePlans.moduleBuildPlan(
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
            plans.put(dir, plan);
            coords.put(dir, cc.jumpkick.runtime.LockPlans.coordLabel(scope.getValue(), dir));
        }
        for (var entry : plans.entrySet()) {
            String dirTag = entry.getKey().toString();
            cc.jumpkick.run.BuildPlan plan = entry.getValue();
            sendQuiet(
                    writer,
                    EngineProtocol.planModule(
                            dirTag,
                            coords.get(entry.getKey()),
                            plan.name(),
                            (int) Math.min(Integer.MAX_VALUE, plan.estimatedTotalWeight()),
                            false));
            for (Task p : plan.steps()) {
                sendQuiet(
                        writer,
                        EngineProtocol.planStep(
                                dirTag, p.name(), p.label(), phaseWire(p.group().orElse(null))));
            }
        }
        sendQuiet(writer, EngineProtocol.planDone(plans.size()));

        for (var entry : plans.entrySet()) {
            Path dir = entry.getKey();
            String dirTag = dir.toString();
            cc.jumpkick.run.BuildPlan plan = entry.getValue();
            sendQuiet(writer, EngineProtocol.moduleStart(dirTag));
            plan.addListener(wireBuildPlanListener(dirTag, writer, plan));
            long startNanos = System.nanoTime();
            BuildPlanResult result = plan.run();
            long millis = (System.nanoTime() - startNanos) / 1_000_000;
            int exitCode = result.success() ? 0 : cc.jumpkick.runtime.NativePlans.failureExitCode(plan, result);
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
     * cc.jumpkick.runtime.LockPlans} plan that writes the single workspace (or standalone)
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
            var scope = cc.jumpkick.runtime.LockPlans.lockScope(entryDir);
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
        // stream (the client returns on the terminal without any plan events).
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
                    // tick growth already rides the plan's tick-update events
                }

                @Override
                public void onPackage(String module, String version) {
                    lockPkgs.onPackage(dirTag, module, version);
                }
            };
            cc.jumpkick.run.BuildPlan plan = update
                    ? cc.jumpkick.runtime.LockPlans.updateBuildPlan(
                            dir, effective, cache, repoUrl, features, withDefaults, platformOverride)
                    : cc.jumpkick.runtime.LockPlans.lockBuildPlan(
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
            for (Task p : plan.steps()) {
                sendQuiet(
                        writer,
                        EngineProtocol.planStep(
                                dirTag, p.name(), p.label(), phaseWire(p.group().orElse(null))));
            }
            sendQuiet(writer, EngineProtocol.planDone(1));
            plan.addListener(wireBuildPlanListener(
                    dirTag, writer, (java.util.function.Function<BuildPlanResult, String>) result -> {
                        lockPkgs.flush();
                        lockPkgs.close();
                        cc.jumpkick.lock.Lockfile lock =
                                plan.get(cc.jumpkick.runtime.LockPlans.LOCKFILE).orElse(null);
                        return EngineProtocol.planFinishLock(
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

            BuildPlanResult result = plan.run();
            lockPkgs.close();
            if (!result.success()) {
                sendQuiet(
                        writer,
                        EngineProtocol.lockFinish(
                                false, cc.jumpkick.runtime.LockPlans.failureExitCode(result), java.util.List.of(), -1));
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

    /** Translate every {@link WorkspaceBuildListener} callback into a wire event on {@code writer}. */
    private WorkspaceBuildListener wireListener(BufferedWriter writer, String workspaceDir) {
        // Created on the runner's thread — capture the request id for the dashboard events now;
        // the callbacks below fire on scheduler/worker threads where the ThreadLocal isn't set.
        long eventRequestId = eventRequestId();
        if (eventRequestId > 0 && workspaceDir != null) putProgressRoot(eventRequestId, workspaceDir);
        // Each module's plan, kept from onModuleStart so onModuleFinish can read its TEST_RESULT and
        // fold per-module test counts into the run's record — the workspace path has no single test
        // plan, so tests would otherwise never reach a dashboard-triggered build's history.
        java.util.Map<String, cc.jumpkick.run.BuildPlan> moduleBuildPlanner =
                new java.util.concurrent.ConcurrentHashMap<>();
        java.util.concurrent.ConcurrentHashMap<String, Long> lastDenByDir =
                new java.util.concurrent.ConcurrentHashMap<>();
        return new WorkspaceBuildListener() {
            @Override
            public void onPreflight(String stage, int done, int total, String label) {
                sendQuiet(writer, EngineProtocol.preflight(stage, done, total, label));
                // Map coarse preflight stages onto user-visible InvocationPhases. Wire names
                // come from the enum — the one vocabulary a future consumer's fromWire parses.
                cc.jumpkick.plugin.build.InvocationPhase inv =
                        switch (stage == null ? "" : stage) {
                            case "lock", "graph" -> cc.jumpkick.plugin.build.InvocationPhase.RESOLVE;
                            case "checking", "plan", "prepare", "calibrate" ->
                                cc.jumpkick.plugin.build.InvocationPhase.PLAN;
                            default -> null;
                        };
                if (inv != null) {
                    String status = (total > 0 && done >= total) ? "finish" : "start";
                    sendQuiet(writer, EngineProtocol.invocationPhase(inv.wireName(), status));
                }
                if (eventRequestId > 0) {
                    progressTracker(eventRequestId).preflight(stage, done, total);
                    emitWorkspaceProgress(eventRequestId, writer, true);
                }
            }

            @Override
            public void onWorkModel(cc.jumpkick.runtime.WorkModel model) {
                if (eventRequestId <= 0 || model == null) return;
                cc.jumpkick.runtime.RemainingWork rw = model.toRemainingWork();
                putRemaining(eventRequestId, rw);
                // Annotate R0 for wire/clients; bar denominator is calibrated from plan weights.
                progressTracker(eventRequestId)
                        .seedWall(model.R0(), model.costs().size());
                emitWorkspaceProgress(eventRequestId, writer, true);
            }

            @Override
            public void onPlan(java.util.List<ModulePlan> plan) {
                long totalWeight = 0;
                // Id-less builds must not insert a key clearProgress can never remove.
                var weights = eventRequestId > 0
                        ? weightsOf(eventRequestId)
                        : new java.util.concurrent.ConcurrentHashMap<String, Long>();
                for (ModulePlan m : plan) {
                    String dir = m.dir().toString();
                    totalWeight += m.weight();
                    weights.put(dir, (long) m.weight());
                    sendQuiet(
                            writer,
                            EngineProtocol.planModule(dir, m.coord(), m.plan().name(), m.weight(), m.fullyCached()));
                    for (Task p : m.plan().steps()) {
                        sendQuiet(
                                writer,
                                EngineProtocol.planStep(
                                        dir,
                                        p.name(),
                                        p.label(),
                                        phaseWire(p.group().orElse(null))));
                    }
                }
                sendQuiet(writer, EngineProtocol.planDone(plan.size()));
                // Bar = Σ effort weights (real work + TOKENs), not wall-ms R0.
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
            public void onEtaEstimate(long remainingMs) {
                sendQuiet(writer, EngineProtocol.eta(remainingMs));
                publishEta(eventRequestId, remainingMs);
            }

            @Override
            public BuildPlanListener onModuleStart(ModulePlan m) {
                String dir = m.dir().toString();
                moduleBuildPlanner.put(dir, m.plan()); // read its TEST_RESULT at finish (see onModuleFinish)
                sendQuiet(writer, EngineProtocol.moduleStart(dir));
                publishModuleStart(eventRequestId, dir, m.coord());
                // wireBuildPlanListener captures the dashboard request id from the currentEventRequestId
                // ThreadLocal — but onModuleStart runs on a WorkspaceScheduler thread where it isn't
                // set, so without this seed every per-module step/plan-progress hub event would
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
                accModule(eventRequestId, o);
                publishModuleFinish(eventRequestId, dir, o.coord(), o.success(), o.millis(), o.didWork());
                cc.jumpkick.run.BuildPlan g = moduleBuildPlanner.remove(dir);
                if (g != null) {
                    accTests(
                            eventRequestId,
                            g.get(cc.jumpkick.runtime.BuildPlanner.TEST_RESULT).orElse(null));
                }
            }
        };
    }

    /** Decorate a module plan listener to feed the workspace aggregate tracker. */
    private BuildPlanListener wrapBuildPlanForWorkspace(
            BuildPlanListener inner,
            long requestId,
            String dir,
            java.io.BufferedWriter writer,
            java.util.concurrent.ConcurrentHashMap<String, Long> lastDenByDir) {
        return new BuildPlanListener() {
            @Override
            public void planStart(BuildPlanView view) {
                lastDenByDir.put(dir, view.denominator());
                trackModuleBuildPlan(requestId, dir, view, writer, false);
                inner.planStart(view);
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
            public void stepFinish(String step, String group, cc.jumpkick.run.TaskStatus status, Duration duration) {
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
            public void error(String step, String code, String message, cc.jumpkick.run.TestFailureInfo failure) {
                inner.error(step, code, message, failure);
            }

            @Override
            public void planFinish(BuildPlanResult result) {
                inner.planFinish(result);
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
     * Legacy wire path (no public CLI): queue host warmup. Prefer engine self-heal on start / 12 h.
     */
    private void handleOptimizeRequest(String requestLine, BufferedWriter writer) {
        try {
            boolean force = Jsonl.bool(requestLine, "force", false);
            boolean scheduled = scheduleHostWarmupIfNeeded(force);
            sendQuiet(
                    writer,
                    scheduled
                            ? EngineProtocol.optimizeAck(true, "", "scheduled", "scheduled: host warmup on idle worker")
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
        return JsonOut.object()
                .put("type", EngineProtocol.METRICS_ENTRY)
                .put("scope", e.scope())
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
        // Oversample then keep only build-like kinds so lock/format/etc. never dilute history.
        java.util.List<BuildRecord> records = journal.list(Math.max(limit * 4, limit));
        int emitted = 0;
        for (BuildRecord r : records) {
            if (!BuildHistoryKinds.isBuildLike(r.kind())) continue;
            if (emitted >= limit) break;
            emitted++;
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
                        Double p = lastProgressOf(h.requestId());
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
                        .put("count", emitted)
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
        // Single-plan builds carry their steps at the record's top level (no module rows).
        for (BuildRecord.Task p : r.steps()) {
            send(writer, stepLine(p, null));
            stepCount++;
        }
        for (BuildRecord.Diag d : r.diagnostics()) {
            send(writer, historyDiagLine(d));
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

    /**
     * A {@code history-diag} replay line carrying the FULL persisted shape — the journal keeps
     * module/class/method/stack/snippet/worker (JK-1869) and replay must not flatten a failure
     * back to task+message (JK-1909).
     */
    static String historyDiagLine(BuildRecord.Diag d) {
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

    /** A {@code history-task} line, optionally tagged with its module label (null for single-plan). */
    private static String stepLine(BuildRecord.Task p, String module) {
        return JsonOut.object()
                .put("type", EngineProtocol.HISTORY_TASK)
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
     * Translate every {@link BuildPlanListener} callback for one plan into a {@code dir}-tagged wire
     * event. {@code realBuildPlan} is non-null only for {@link #runTest}/{@link #runSingleBuild} — its
     * {@code TEST_RESULT}/{@code BUILD_OUTCOME} keys (populated by the run-tests/parse-build steps)
     * ride along on the {@link EngineProtocol#BUILDPLAN_FINISH} message so the client can render its
     * summary line before it even sees the terminal message; {@code null} for a plain per-module
     * workspace-build plan (where neither applies at the module level).
     */
    private BuildPlanListener wireBuildPlanListener(
            String dir, BufferedWriter writer, cc.jumpkick.run.BuildPlan realBuildPlan) {
        // realBuildPlan non-null ⇒ single-project run: flush chrome timeline on plan finish.
        // Workspace modules pass null and flush once on workspace finish instead.
        boolean flushTimeline = realBuildPlan != null;
        return wireBuildPlanListener(
                dir,
                writer,
                (java.util.function.Function<BuildPlanResult, String>) result -> {
                    cc.jumpkick.run.TestSummary testResult = realBuildPlan == null
                            ? null
                            : realBuildPlan
                                    .get(cc.jumpkick.runtime.BuildPlanner.TEST_RESULT)
                                    .orElse(null);
                    String buildOutcome = realBuildPlan == null
                            ? null
                            : realBuildPlan
                                    .get(cc.jumpkick.runtime.BuildPlanner.BUILD_OUTCOME)
                                    .orElse(null);
                    // Wire "cancelled" is user/deadline cancel only. BuildPlanResult.cancelled is also
                    // set on cooperative fail-fast (remaining steps aborted after a real FAIL) — that
                    // must not look like the user cancelled the job.
                    boolean cancelled = result.userCancelled();
                    String finish = testResult == null && buildOutcome == null
                            ? EngineProtocol.planFinish(dir, result.success(), cancelled)
                            : EngineProtocol.withCancelled(
                                    EngineProtocol.planFinish(
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
     * same message the build/test plans already send.
     */
    private BuildPlanListener wireBuildPlanListener(
            String dir, BufferedWriter writer, java.util.function.Function<BuildPlanResult, String> finishEncoder) {
        return wireBuildPlanListener(dir, writer, finishEncoder, false);
    }

    private BuildPlanListener wireBuildPlanListener(
            String dir,
            BufferedWriter writer,
            java.util.function.Function<BuildPlanResult, String> finishEncoder,
            boolean releaseSlotOnBuildPlanFinish) {
        // Created on the runner's thread (directly, or via wireListener's onModuleStart which runs
        // on a scheduler thread — there the ThreadLocal is unset and module events carry the id).
        long eventRequestId = eventRequestId();
        // Human-paced progress/label/tickstructural events still flush immediately.
        EventSink sink = new WireEventSink(writer);
        return new CoalescingBuildPlanListener(new BuildPlanListener() {
            @Override
            public void planStart(BuildPlanView view) {
                sink.emit(new EngineEvent.PlanStart(
                        dir,
                        view.planName(),
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
            public void error(String step, String code, String message, cc.jumpkick.run.TestFailureInfo failure) {
                if (failure == null) {
                    error(step, code, message);
                    return;
                }
                String msg = redactEnv(dir, message == null || message.isEmpty() ? failure.message() : message);
                sendQuiet(writer, EngineProtocol.errorLine(dir, step, code, msg, redactFailure(dir, failure)));
            }

            @Override
            public void stepFinish(String step, String group, cc.jumpkick.run.TaskStatus status, Duration duration) {
                long millis = duration.toMillis();
                sendQuiet(writer, EngineProtocol.stepFinish(dir, step, phaseWire(group), status.name(), millis));
                accStepFinish(eventRequestId, dir, step, phaseWire(group), status.name(), millis);
                publishStepFinish(eventRequestId, dir, step, phaseWire(group), status.name(), millis);
            }

            @Override
            public void planFinish(BuildPlanResult result) {
                for (BuildPlanResult.Diagnostic d : result.errors()) {
                    var tf = d.testFailure();
                    if (tf != null) {
                        sendQuiet(
                                writer,
                                EngineProtocol.planDiagnostic(
                                        dir, d.step(), d.code(), redactEnv(dir, d.message()), redactFailure(dir, tf)));
                    } else {
                        sendQuiet(
                                writer,
                                EngineProtocol.planDiagnostic(
                                        dir,
                                        d.step(),
                                        d.code(),
                                        redactEnv(dir, d.message()),
                                        d.test(),
                                        d.exceptionClass()));
                    }
                }
                // Timeline before the terminal finish, for every socket request that owns an
                // accumulator (no-op otherwise): a client that has returned must not observe the
                // engine still writing target/jk-chrome-profile.json (JK-1714). Workspace modules
                // flush once in runBuild before workspace-finish; the flushTimeline guard makes a
                // second call here idempotent.
                flushTimelineToClient(eventRequestId, writer);
                // Free exclusive fingerprint before the terminal line so a client that reconnects
                // immediately is not rejected as already-running (single-plan only; workspace
                // releases after BuildService.buildWorkspace returns).
                if (releaseSlotOnBuildPlanFinish) inFlightBuilds.release(eventRequestId);
                sendQuiet(writer, finishEncoder.apply(result));
                accBuildPlanFinish(eventRequestId, dir, result);
                publishBuildPlanFinish(eventRequestId, dir, result.success());
                if (!result.success()) publishDiagnostics(eventRequestId, dir, result.errors());
            }
        });
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

    /**
     * {@link #redactEnv} over the free-text fields of a test failure. The first line of
     * {@code printStackTrace} text repeats the raw exception message, so masking {@code message}
     * alone still leaks the secret through {@code stack} (wire, SSE, journal).
     */
    static cc.jumpkick.run.TestFailureInfo redactFailure(String dir, cc.jumpkick.run.TestFailureInfo f) {
        if (f == null) return null;
        String message = redactEnv(dir, f.message());
        String stack = redactEnv(dir, f.stack());
        if (java.util.Objects.equals(message, f.message()) && java.util.Objects.equals(stack, f.stack())) {
            return f;
        }
        return new cc.jumpkick.run.TestFailureInfo(
                f.module(),
                f.engine(),
                f.className(),
                f.method(),
                f.exceptionClass(),
                message,
                stack,
                f.worker(),
                f.file(),
                f.line(),
                f.snippetStart(),
                f.snippet());
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
            log.accept("jk engine: http-triggered lock of " + entryDir + " failed: "
                    + redactEnv(entryDir.toString(), String.valueOf(e.getMessage())));
            publishRequestError(eventRequestId(), entryDir.toString(), String.valueOf(e.getMessage()));
            return false;
        }
    }

    /** BuildPlan events for a single HTTP lock job → SSE hub (same wire cadence as CLI UDS). */
    private BuildPlanListener singleBuildPlanHubListener(String dir) {
        long eventRequestId = eventRequestId();
        return new CoalescingBuildPlanListener(new BuildPlanListener() {
            @Override
            public void planStart(BuildPlanView view) {
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
            public void stepFinish(String step, String group, cc.jumpkick.run.TaskStatus status, Duration duration) {
                publishStepFinish(eventRequestId, dir, step, phaseWire(group), status.name(), duration.toMillis());
            }

            @Override
            public void label(String step, String label) {
                publishLabel(eventRequestId, dir, step, label);
            }

            @Override
            public void output(String step, String line) {
                publishOutput(eventRequestId, dir, step, line);
            }
        });
    }

    /** Module/plan events to the dashboard hub only — the HTTP trigger's counterpart of {@link #wireListener}. */
    private WorkspaceBuildListener hubListener(String workspaceDir) {
        long eventRequestId = eventRequestId();
        if (eventRequestId > 0 && workspaceDir != null) putProgressRoot(eventRequestId, workspaceDir);
        // As in wireListener: keep each module's plan so onModuleFinish can fold its TEST_RESULT into
        // the record — a web-triggered build has no single test plan, so tests would otherwise never
        // reach the journal for dashboard builds.
        java.util.Map<String, cc.jumpkick.run.BuildPlan> moduleBuildPlanner =
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
            public void onWorkModel(cc.jumpkick.runtime.WorkModel model) {
                if (eventRequestId <= 0 || model == null) return;
                putRemaining(eventRequestId, model.toRemainingWork());
                progressTracker(eventRequestId)
                        .seedWall(model.R0(), model.costs().size());
                emitWorkspaceProgress(eventRequestId, null, true);
            }

            @Override
            public void onPlan(java.util.List<ModulePlan> plan) {
                long totalWeight = 0;
                // Id-less builds must not insert a key clearProgress can never remove.
                var weights = eventRequestId > 0
                        ? weightsOf(eventRequestId)
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
            public void onEtaEstimate(long remainingMs) {
                publishEta(eventRequestId, remainingMs);
            }

            @Override
            public BuildPlanListener onModuleStart(ModulePlan m) {
                String dir = m.dir().toString();
                moduleBuildPlanner.put(dir, m.plan());
                publishModuleStart(eventRequestId, dir, m.coord());
                // Same JK_WIRE_PROGRESS_MS coalescing as the CLI UDS path (progress/label/output).
                return new CoalescingBuildPlanListener(new BuildPlanListener() {
                    @Override
                    public void planStart(BuildPlanView view) {
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
                            String step, String group, cc.jumpkick.run.TaskStatus status, Duration duration) {
                        long millis = duration.toMillis();
                        accStepFinish(eventRequestId, dir, step, phaseWire(group), status.name(), millis);
                        publishStepFinish(eventRequestId, dir, step, phaseWire(group), status.name(), millis);
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
                    public void planFinish(BuildPlanResult result) {
                        accBuildPlanFinish(eventRequestId, dir, result);
                        publishBuildPlanFinish(eventRequestId, dir, result.success());
                        if (!result.success()) publishDiagnostics(eventRequestId, dir, result.errors());
                    }
                });
            }

            @Override
            public void onModuleFinish(ModuleOutcome o) {
                String dir = o.dir().toString();
                trackModuleComplete(eventRequestId, dir, lastDenByDir.getOrDefault(dir, 0L), null);
                accModule(eventRequestId, o);
                publishModuleFinish(eventRequestId, dir, o.coord(), o.success(), o.millis(), o.didWork());
                cc.jumpkick.run.BuildPlan g = moduleBuildPlanner.remove(dir);
                if (g != null) {
                    accTests(
                            eventRequestId,
                            g.get(cc.jumpkick.runtime.BuildPlanner.TEST_RESULT).orElse(null));
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
        // "Connections" on the Admin tile / status-ack: every live client surface, not only the
        // UDS accept loop. A browser on /api/events (or an MCP SSE) is a real attachment — without
        // this, Admin shows 0 while the dashboard is open because only CLI socket clients used to
        // bump activeConnections.
        int connections = liveConnectionCount();
        peakActiveConnections.accumulateAndGet(connections, Math::max);
        return new cc.jumpkick.engine.http.StatusSnapshot(
                version,
                pid,
                startedAtMillis,
                connections,
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
                systemLoadAverage(),
                engineEpoch,
                peakActiveConnections.get(),
                peakActiveBuildPlans.get());
    }

    /**
     * Live client attachments: CLI/UDS (or TCP) engine-protocol sockets + long-lived HTTP SSE
     * (dashboard {@code /api/events} and MCP event streams). Short REST GETs are not counted — they
     * release their admission permit as soon as the response finishes.
     */
    private int liveConnectionCount() {
        int n = activeConnections.get();
        HttpEngineServer h = httpServer;
        if (h != null) n += h.liveEventStreams();
        return n;
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

    /** OS 1-minute load average, or {@code -1} when the platform bean cannot answer. */
    private static double systemLoadAverage() {
        try {
            double avg = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
                    .getSystemLoadAverage();
            return avg >= 0 ? avg : -1;
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
