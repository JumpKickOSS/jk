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

    private final IdleHousekeeping idle;
    private final EngineVitals vitals;
    private final SsePublisher sse;
    private final LiveRuns liveRuns;
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
                    }
                });
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
        this.sse = new SsePublisher(
                sessions,
                inFlightBuilds,
                httpEvents,
                () -> httpServer,
                clockMillis,
                activeBuildPlans,
                sseConnect,
                this::accStepStart);
        this.liveRuns = new LiveRuns(inFlightBuilds, sessions, httpEvents, sseConnect, clockMillis);
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
        engineMaintenance = new EngineMaintenance(log, storeFeedRefresh, idle::enqueueScheduledCacheGc);
        engineMaintenance.start();
        // First-start self-heal: feeds → templates → AOT/cal on the idle worker (does not block accept).
        idle.scheduleHostWarmup(false);
        // Touch resolve/PubGrub classes so the first real lock does not pay classload on the critical path.
        idle.scheduleResolveClassWarmup();
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

    private void maybeIdleBoundary() {
        idle.maybeIdleBoundary();
    }

    private void maybeIdleGc() {
        idle.maybeIdleGc();
    }

    private void maybeEnqueuePrune(Path cache) {
        idle.maybeEnqueuePrune(cache);
    }

    private boolean scheduleHostWarmupIfNeeded(boolean force) {
        return idle.scheduleHostWarmup(force);
    }

    private void releaseExclusiveSlot() {
        long id = eventRequestId();
        if (id > 0) inFlightBuilds.release(id);
    }

    /** Single-flight latch for {@link #runIdleHousekeeping} — see the exactly-once note there. */
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
        if (eventRequestId > 0 && workspaceDir != null) sessions.progressRoot(eventRequestId, workspaceDir);
        return new BridgingWorkspaceListener(workspaceDir, sink, workspaceHooks(eventRequestId, writer));
    }

    private BridgingWorkspaceListener.Hooks workspaceHooks(long rid, java.io.BufferedWriter writer) {
        return new BridgingWorkspaceListener.Hooks() {
            @Override
            public void preflight(String stage, int done, int total) {
                if (rid > 0) {
                    sessions.tracker(rid).preflight(stage, done, total);
                    sse.emitWorkspaceProgress(rid, writer, true);
                }
            }

            @Override
            public void workModel(cc.jumpkick.runtime.WorkModel model) {
                if (rid <= 0) return;
                sessions.remaining(rid, model.toRemainingWork());
                sessions.tracker(rid).seedWall(model.R0(), model.costs().size());
                sse.emitWorkspaceProgress(rid, writer, true);
            }

            @Override
            public void recordWeight(String dir, long weight) {
                if (rid > 0) sessions.weights(rid).put(dir, weight);
            }

            @Override
            public void planWeights(long totalWeight, int modules) {
                if (rid > 0) {
                    sessions.tracker(rid).calibrate(totalWeight, modules);
                    sse.emitWorkspaceProgress(rid, writer, true);
                }
                sse.publishPlan(rid, totalWeight);
            }

            @Override
            public void moduleGraph(java.util.Map<Path, java.util.Set<Path>> prereqs) {
                accModuleGraph(rid, prereqs);
            }

            @Override
            public void eta(long remainingMs) {
                sse.publishEta(rid, remainingMs);
            }

            @Override
            public void moduleStarted(String dir, String coord) {
                sse.publishModuleStart(rid, dir, coord);
            }

            @Override
            public void moduleFinished(ModuleOutcome o) {
                accModule(rid, o);
                sse.publishModuleFinish(rid, o.dir().toString(), o.coord(), o.success(), o.millis(), o.didWork());
            }

            @Override
            public void trackModule(String dir, BuildPlanView view) {
                sse.trackModuleBuildPlan(rid, dir, view, writer, false);
            }

            @Override
            public void trackModuleComplete(String dir, long lastDen) {
                sse.trackModuleComplete(rid, dir, lastDen, writer);
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
                sse.publishBuildPlanProgress(rid, d, view);
            }

            @Override
            public void stepStarted(String d, String step, String phase) {
                sse.publishStepStart(rid, d, step, phase);
            }

            @Override
            public void stepFinished(String d, String step, String phase, String status, long millis) {
                accStepFinish(rid, d, step, phase, status, millis);
                sse.publishStepFinish(rid, d, step, phase, status, millis);
            }

            @Override
            public void labeled(String d, String step, String text) {
                sse.publishLabel(rid, d, step, text);
            }

            @Override
            public void output(String d, String step, String line) {
                sse.publishOutput(rid, d, step, line);
            }

            @Override
            public void planFinished(String d, BuildPlanResult result) {
                flushTimelineToClient(rid, writer);
                if (releaseSlotOnFinish) inFlightBuilds.release(rid);
                accBuildPlanFinish(rid, d, result);
                sse.publishBuildPlanFinish(rid, d, result.success());
                if (!result.success()) sse.publishDiagnostics(rid, d, result.errors());
            }
        };
    }

    /** The current thread's hosted-request id for dashboard events; {@code -1} outside a request. */
    private long eventRequestId() {
        Long id = currentEventRequestId.get();
        return id != null ? id : -1;
    }

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
        sessions.accumulator(
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
        BuildAccumulator a = sessions.accumulator(requestId);
        if (a != null) a.addModule(o);
    }

    private void accModuleGraph(long requestId, java.util.Map<Path, java.util.Set<Path>> prereqs) {
        BuildAccumulator a = sessions.accumulator(requestId);
        if (a != null) a.setModuleEdges(prereqs);
    }

    private void accBuildPlanFinish(long requestId, String dir, BuildPlanResult result) {
        BuildAccumulator a = sessions.accumulator(requestId);
        if (a != null) a.addBuildPlan(dir, result);
    }

    /**
     * Record one finished step under its module dir — the same {@code stepFinish} signal the
     * dashboard renders, so the journal's per-module chains match the live cards exactly (a
     * workspace module's {@code BuildPlanResult.steps} isn't reliably populated, so we capture the
     * events directly).
     */
    private void accStepStart(long requestId, String dir, String step, String phase) {
        BuildAccumulator a = sessions.accumulator(requestId);
        if (a != null) a.noteTaskStart(dir, step, phase);
    }

    private void accStepFinish(long requestId, String dir, String step, String phase, String status, long millis) {
        BuildAccumulator a = sessions.accumulator(requestId);
        if (a != null) a.addTask(dir, step, phase, status, millis);
    }

    private void accTests(long requestId, TestSummary tests) {
        BuildAccumulator a = sessions.accumulator(requestId);
        if (a != null && tests != null) a.addTests(tests);
    }

    private void accOutcome(long requestId, boolean success, int exitCode) {
        BuildAccumulator a = sessions.accumulator(requestId);
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
        BuildAccumulator a = sessions.takeAccumulator(requestId);
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
        BuildAccumulator a = sessions.accumulator(requestId);
        if (a == null) return;
        a.flushTimeline().ifPresent(path -> {
            if (writer != null) sendQuiet(writer, EngineProtocol.timeline(path.toString()));
        });
    }

    /** Best-effort send: a write failure means the client is gone — nothing more to do for this event. */
    static void sendQuiet(BufferedWriter writer, String line) {
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
        candidate.setLiveRunSupport(liveRuns::snapshot, liveRuns::rehydrate);
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
            if (rid > 0) sessions.progressRoot(rid, entryDir.toString());
            WorkspaceResult result = SessionContext.where(
                    session, () -> BuildService.buildWorkspace(req, hubListener(entryDir.toString())));
            accOutcome(rid, result.success(), result.exitCode());
            if (rid > 0) {
                if (result.success()) sessions.tracker(rid).finish();
                sse.emitWorkspaceProgress(rid, null, true);
            }
            if (!result.success()) {
                for (String error : result.errors().stream().limit(5).toList()) {
                    sse.publishRequestError(rid, entryDir.toString(), error);
                }
            }
            return result.success();
        } catch (Exception e) {
            // The engine log is served by GET /api/log — redact like every other exiting channel.
            accOutcome(eventRequestId(), false, 1);
            log.accept("jk engine: http-triggered job of " + entryDir + " failed: "
                    + redactEnv(entryDir.toString(), String.valueOf(e.getMessage())));
            sse.publishRequestError(eventRequestId(), entryDir.toString(), String.valueOf(e.getMessage()));
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
                    sse.publishRequestError(eventRequestId(), entryDir.toString(), d.message());
                }
            }
            return result.success();
        } catch (Exception e) {
            accOutcome(eventRequestId(), false, 1);
            log.accept("jk engine: http-triggered lock of " + entryDir + " failed: "
                    + redactEnv(entryDir.toString(), String.valueOf(e.getMessage())));
            sse.publishRequestError(eventRequestId(), entryDir.toString(), String.valueOf(e.getMessage()));
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
            sessions.progressRoot(rid, dir);
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
            sessions.tracker(rid).finish();
        }

        @Override
        public void emitWorkspaceProgress(long rid, BufferedWriter writer, boolean force) {
            sse.emitWorkspaceProgress(rid, writer, force);
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
            sse.publishRequestError(rid, dir, message);
        }

        @Override
        public Session resolveSession(String requestLine, Session.CancelToken cancel, boolean refresh) {
            return EngineServer.resolveSession(requestLine, cancel, refresh);
        }

        @Override
        public void maybeEnqueuePrune(Path cache) {
            idle.maybeEnqueuePrune(cache);
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
            return idle.scheduleHostWarmup(force);
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
            return sessions.lastProgress(requestId);
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
            sessions.mode(id, mode);
        }

        @Override
        public void publishRequestStart(long id, String kind, String dir, long buildNumber) {
            sse.publishRequestStart(id, kind, dir, buildNumber);
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
            return sse.runIo(id);
        }

        @Override
        public InFlightBuilds inFlight() {
            return inFlightBuilds;
        }

        @Override
        public BuildAccumulator accumulatorOf(long id) {
            return sessions.accumulator(id);
        }

        @Override
        public void putLastProgress(long id, double percent) {
            sessions.lastProgress(id, percent);
        }

        @Override
        public int activeBuildPlans() {
            return activeBuildPlans.get();
        }

        @Override
        public JsonOut withProgress(JsonOut payload, long id) {
            return sse.withProgress(payload, id);
        }

        @Override
        public JsonOut withIo(JsonOut payload, long id) {
            return sse.withIo(payload, id);
        }

        @Override
        public void publishEvent(String type, JsonOut payload) {
            sse.publishEvent(type, payload);
        }

        @Override
        public void clearProgress(long id) {
            sessions.retire(id);
        }

        @Override
        public void writeJournal(long id, boolean cancelled, long millis, java.io.BufferedWriter writer) {
            EngineServer.this.writeJournal(id, cancelled, millis, writer);
        }

        @Override
        public void maybeIdleBoundary() {
            idle.maybeIdleBoundary();
        }

        @Override
        public void maybeIdleGc() {
            idle.maybeIdleGc();
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
