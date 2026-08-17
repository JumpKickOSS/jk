// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.config.JkHistoryConfig;
import cc.jumpkick.config.JkHttpConfig;
import cc.jumpkick.engine.http.HttpEngineServer;
import cc.jumpkick.engine.jobs.JobEnvelope;
import cc.jumpkick.engine.jobs.JobSessions;
import cc.jumpkick.engine.jobs.JobTransport;
import cc.jumpkick.engine.journal.BuildAccumulator;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.journal.JournalWriter;
import cc.jumpkick.engine.listen.EventRedaction;
import cc.jumpkick.engine.plugin.HeapPlan;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoLifecycle;
import cc.jumpkick.engine.verbs.HostedVerb;
import cc.jumpkick.engine.verbs.VerbRegistry;
import cc.jumpkick.engine.verbs.VerbShape;
import cc.jumpkick.plugin.protocol.Jsonl;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
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
import java.security.MessageDigest;
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
    private final cc.jumpkick.engine.http.HttpEvents httpEvents;

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
    private Path metricsFile = cc.jumpkick.runtime.BuildMetrics.defaultFile();

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
    private final ReentrantReadWriteLock cacheGate = new ReentrantReadWriteLock(true);

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
        // Process-scoped generation id for the dashboard hard-refresh contract.
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
        // written the flat path names US, and a drain aimed there is a self-shutdown.
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
            serverChannel.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            int port = ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();
            expectedToken = EngineTransport.newToken();
            // This token gates every engine RPC — i.e. arbitrary code execution as the engine
            // owner. It must be owner-only, like the HTTP bearer token, not left to the ambient
            // umask on a shared machine.
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

        // Order matters: tell the predecessor to drain FIRST — that is what
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
        http.start();
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
            BufferedWriter w =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            w.write(ProtoLifecycle.shutdown(false));
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
            BufferedWriter w =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader r =
                    new BufferedReader(new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8));
            w.write(ProtoLifecycle.hello(probeVersion, "probe"));
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
            SocketChannel ch = SocketChannel.open(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            Path token = EnginePaths.tokenFor(socket);
            BufferedWriter w =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            // The auth envelope, exactly as the CLI client sends it — authenticate accepts
            // nothing else (a raw token line here once broke takeover/election on TCP).
            w.write(ProtoLifecycle.auth(Files.readString(token).trim()));
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
     * it again, and no successor is waiting for its port either. Exit once genuinely unused —
     * no jobs and no attached streams. Keep the port while a browser is attached, because
     * here there is no successor to hand it to and dropping it would strand the tab.
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
                                // do not finish / re-start engine AOT for a lame-duck generation.
                                aot.stopQuietly();
                                synchronized (lifecycleLock) {
                                    if (activeBuildPlans.get() == 0) {
                                        shuttingDown = true;
                                        closeServerChannelQuietly();
                                    } else {
                                        draining = true;
                                    }
                                }
                                http.stopNow(); // hand the Web UI port to the successor right away
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
        return MessageDigest.isEqual(
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
                sendQuiet(writer, ProtoLifecycle.error(EngineProtocol.ERR_AUTH, "engine token rejected"));
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
        String line;
        while ((line = reader.readLine()) != null) {
            String type = EngineProtocol.typeOf(line);
            if (type == null) {
                // A garbled REQUEST gets a typed refusal, never silence — a silently-dropped
                // request wedges a streaming client that is waiting for a terminal event.
                sendQuiet(
                        writer,
                        ProtoLifecycle.error(
                                EngineProtocol.ERR_PROTOCOL, "unparseable request line (no \"type\" discriminator)"));
                continue;
            }
            // Downward-delegation gate for artifact-producing requests (engine-versioning §3).
            if (EngineDelegate.DELEGATABLE.contains(type)
                    && EngineDelegate.maybeDelegate(jobMode, version, line, reader, writer, paths.log(), log)) {
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
                                ProtoLifecycle.error(
                                        EngineProtocol.ERR_VERSION_SKEW,
                                        "client speaks protocol " + clientProto + " but this engine speaks "
                                                + EngineProtocol.PROTOCOL + " — start a matching engine"));
                        return;
                    }
                    send(writer, ProtoLifecycle.helloAck(version, pid, startedAtMillis, draining, buildId));
                }
                case EngineProtocol.PING -> send(writer, ProtoLifecycle.pong());
                case EngineProtocol.STATUS -> {
                    cc.jumpkick.engine.http.StatusSnapshot s = statusSnapshot();
                    HttpEngineServer hs = http.server();
                    send(
                            writer,
                            ProtoLifecycle.statusAck(
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
                                    s.peakActiveBuildPlans()));
                }
                case EngineProtocol.SHUTDOWN -> {
                    boolean force = cc.jumpkick.plugin.protocol.Jsonl.bool(line, "force", false);
                    // Takeover already repointed the endpoint before sending shutdown — kill the
                    // engine AOT sidecar so it cannot re-publish engine-<old-v>-*.
                    // Voluntary `jk engine stop` still names us; leave train to finish then.
                    if (!endpointNamesThisEngine()) {
                        aot.stopQuietly();
                    }
                    synchronized (lifecycleLock) {
                        int n = activeBuildPlans.get();
                        if (force || n == 0) {
                            // Immediate: no in-flight jobs, or an explicit force — close the listener
                            // now so run returns and the JVM exits cleanly (AOT still assembles when
                            // we remain primary).
                            send(writer, ProtoLifecycle.bye(n, false));
                            shuttingDown = true;
                            closeServerChannelQuietly();
                        } else {
                            // Graceful drain: keep the listener open (so new commands get a clear
                            // "shutting down" handshake and in-flight jobs finish); the last job to
                            // complete triggers the clean exit (see maybeIdleBoundary).
                            draining = true;
                            send(writer, ProtoLifecycle.bye(n, true));
                        }
                    }
                    http.stopNow(); // hand the Web UI port to the successor right away
                    return;
                }
                case EngineProtocol.CANCEL_REQUEST -> handleCancelRequest(line, writer);
                default ->
                    sendQuiet(
                            writer, ProtoLifecycle.error(EngineProtocol.ERR_PROTOCOL, "unknown request type: " + type));
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
                jobs.submit(line, verb.toJobRequest(line), new JobTransport.SocketWatch(reader, writer));
                yield true;
            }
            case VerbShape.CacheMaint() -> {
                jobs.submit(line, verb.toJobRequest(line), new JobTransport.SocketWatch(reader, writer));
                yield true;
            }
            case VerbShape.SyncRead() -> {
                verb.run(line, cc.jumpkick.config.Session.defaults().cancel(), writer);
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
            send(writer, ProtoLifecycle.cancelAck(jid, ok, ok ? null : "unknown or already finished jid"));
            return;
        }
        if (dir != null && !dir.isBlank()) {
            int n = jobs.cancelJobsForDir(dir);
            send(
                    writer,
                    ProtoLifecycle.cancelAck(
                            0, n > 0, n > 0 ? ("cancelled " + n + " job(s)") : "no running jobs for dir"));
            return;
        }
        send(writer, ProtoLifecycle.cancelAck(-1, false, "cancel-request requires jid or dir"));
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

    /** Best-effort send: a write failure means the client is gone — nothing more to do for this event. */
    public static void sendQuiet(BufferedWriter writer, String line) {
        try {
            send(writer, line);
        } catch (IOException ignored) {
            // the cancel-watching read loop will notice the same disconnect and cancel the build
        }
    }

    static String redactEnv(String dir, String text) {
        return EventRedaction.redactEnv(dir, text);
    }

    static long jobHeartbeatMs() {
        return JobEnvelope.jobHeartbeatMs();
    }

    static long jobDeadlineMs() {
        return JobEnvelope.jobDeadlineMs();
    }

    static long jobDeadlineGraceMs() {
        return JobEnvelope.jobDeadlineGraceMs();
    }

    private void onConnectionFinished() {
        activeConnections.decrementAndGet();
    }

    private HttpEngineServer httpServer() {
        return http.server();
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
    public void aotTrainerSpawner(Supplier<Process> spawner) {
        aot.spawner(spawner);
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
        int cap = cc.jumpkick.config.Jobs.resolve(cc.jumpkick.config.JkEngineConfig.resolve());
        JvmOptions.planAndApply(HeapPlan.requestedJvms(cap, 1, false, cap));
    }

    static void send(BufferedWriter writer, String line) throws IOException {
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
}
