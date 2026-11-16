// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.changes.ChangeJournal;
import cc.jumpkick.config.FrontDoorScope;
import cc.jumpkick.config.JkHttpConfig;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.desktop.WslWindowsDesktop;
import cc.jumpkick.engine.activity.Activity;
import cc.jumpkick.engine.api.LockFloor;
import cc.jumpkick.engine.decisions.DecisionAlerts;
import cc.jumpkick.engine.decisions.DecisionQueue;
import cc.jumpkick.engine.http.BandPort;
import cc.jumpkick.engine.http.CacheSnapshot;
import cc.jumpkick.engine.http.EngineHttpJobs;
import cc.jumpkick.engine.http.EngineIdentity;
import cc.jumpkick.engine.http.FrontDoor;
import cc.jumpkick.engine.http.FrontDoorToken;
import cc.jumpkick.engine.http.GuestPairing;
import cc.jumpkick.engine.http.HttpEngineServer;
import cc.jumpkick.engine.http.HttpEvents;
import cc.jumpkick.engine.http.SelfUpdateControl;
import cc.jumpkick.engine.http.StatusSnapshot;
import cc.jumpkick.engine.jobs.JobEnvelope;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.machine.MachineEnvironments;
import cc.jumpkick.engine.notices.Notices;
import cc.jumpkick.engine.sessions.SessionLog;
import cc.jumpkick.engine.verbs.HostedVerb;
import cc.jumpkick.engine.verbs.VerbRegistry;
import cc.jumpkick.host.Guest;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.ProcessEnv;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.runtime.base.BuildMetrics;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.ProtoSession;
import java.io.IOException;
import java.net.BindException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.LongPredicate;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Embedded HTTP bind + dashboard/MCP job admission. A job kind resolves to its {@link HostedVerb},
 * decodes to the same wire request line the CLI would send, and submits FireAndForget on the one
 * {@link JobEnvelope} — there is no second verb body behind this class.
 */
@RequiredArgsConstructor
public final class EngineHttpFront {

    private final @Nullable JkHttpConfig config;
    private final EnginePaths.Paths paths;
    private final String version;
    private final @Nullable HttpEvents events;
    private final BuildJournal journal;
    private final Supplier<BuildMetrics> metrics;
    private final Consumer<String> log;
    private final Supplier<StatusSnapshot> status;
    private final LiveRuns liveRuns;
    private final AtomicInteger peakActiveConnections;
    private final IntSupplier liveConnections;
    private final JobEnvelope jobs;
    private final VerbRegistry verbs;
    private final LongPredicate cancelJob;
    private final ToIntFunction<String> cancelJobsForDir;
    private final @Nullable ReentrantReadWriteLock cacheGate;
    private final SessionLog sessions;
    private final DecisionQueue decisions;
    private final Notices notices;

    /** The Windows engine's environments, served on every listener it binds; null elsewhere. */
    private final @Nullable MachineEnvironments machine;

    private volatile Supplier<SelfUpdateControl> selfUpdate = () -> SelfUpdateControl.NONE;

    private volatile @Nullable HttpEngineServer server;
    private volatile @Nullable String error;

    /** Set by {@link #stopNow} and {@link #close}: a bind still retrying then lets its server go. */
    private volatile boolean stopped;

    /** Whether this engine takes part in the front door ({@link FrontDoorScope}); read at {@link #start}. */
    private volatile boolean frontDoor;

    /** Test seams: which guest this is, the environment the scope reads, and the guest's pairing. */
    private Supplier<Guest> guest = Guest::detect;

    private Function<String, @Nullable String> env = ProcessEnv::get;

    private Consumer<HttpEngineServer> pairWith = this::pairWithWindows;

    /** Test seam, before {@link #start}: the guest detection, the environment and the guest's pairing. */
    void seams(Supplier<Guest> guest, Function<String, @Nullable String> env, Consumer<HttpEngineServer> pairing) {
        this.guest = guest;
        this.env = env;
        this.pairWith = pairing;
    }

    /** What the dashboard's Update pill reads and drives; before {@link #start}. */
    public void selfUpdate(Supplier<SelfUpdateControl> control) {
        this.selfUpdate = control;
    }

    public @Nullable HttpEngineServer server() {
        return server;
    }

    public @Nullable String error() {
        return error;
    }

    public int liveEventStreams() {
        HttpEngineServer h = server;
        return h == null ? 0 : h.liveEventStreams();
    }

    /**
     * Start when {@code [http]} is present; bind failure is advisory only. A named port is bound as
     * named. Otherwise the host engine binds 8910, and a WSL or container guest its sticky band port
     * ({@link BandPort}), moving to the next free one when another process holds it.
     */
    public void start() {
        // The hub and the config are created together: no [http] table, neither of them.
        HttpEvents hub = events;
        if (config == null || hub == null) return;
        EngineIdentity id = EngineIdentity.current(version, guest.get());
        frontDoor = FrontDoorScope.active(env, JkDirs.home());
        if (!frontDoor) log.accept("jk engine: a test sandbox, private home or CI: no front-door duty");
        Path bandFile =
                id.guest().guest() && !JkHttpConfig.portNamed() ? paths.dir().resolve(BandPort.FILE) : null;
        JkHttpConfig first = bandFile == null ? config : config.withPort(BandPort.preferred(bandFile, id.id()));
        if (frontDoor && !id.guest().guest()) FrontDoorToken.align(paths.dir(), paths.httpToken());
        HttpEngineServer candidate = candidate(first, hub, id);
        try {
            candidate.start(1);
            serve(candidate, bandFile);
        } catch (BindException busy) {
            // Someone holds the port: a predecessor still letting go, or another engine. The bind
            // retries for seconds, and engine startup — the first build's wait — must not.
            error = "port " + first.port() + " busy, retrying";
            // Startup chore; reads no session.
            Thread.ofVirtual().name("jk-engine-http-bind").start(() -> bindLater(candidate, first, hub, id, bandFile));
        } catch (IOException | RuntimeException e) {
            failed(candidate, e);
        }
    }

    /**
     * The retrying bind, off the startup path; a stop that lands meanwhile lets the port go. A guest
     * whose band port stays taken tries the rest of the band and keeps the one it gets.
     */
    private void bindLater(
            HttpEngineServer candidate,
            JkHttpConfig first,
            HttpEvents hub,
            EngineIdentity id,
            @Nullable Path bandFile) {
        try {
            // A WSL guest holding 8910 while no host ran gives it back; the bind retries for 5 s.
            FrontDoor door = candidate.frontDoor();
            if (door != null && door.askToStepAside(first.port())) {
                log.accept("jk engine: asked the WSL engine holding port " + first.port() + " to give it back");
            }
            candidate.start();
            serve(candidate, bandFile);
            return;
        } catch (BindException taken) {
            candidate.close();
            if (bandFile == null) {
                failed(candidate, taken);
                return;
            }
        } catch (IOException | RuntimeException e) {
            failed(candidate, e);
            return;
        }
        for (int port = BandPort.next(first.port()); port != first.port() && !stopped; port = BandPort.next(port)) {
            HttpEngineServer next = candidate(first.withPort(port), hub, id);
            try {
                next.start(1);
                serve(next, bandFile);
                return;
            } catch (BindException taken) {
                next.close();
            } catch (IOException | RuntimeException e) {
                failed(next, e);
                return;
            }
        }
        error = "no free port in " + BandPort.FIRST + "-" + BandPort.LAST;
        log.accept("jk engine: http failed to start (" + error + ") — continuing without http");
    }

    /** A server bound to {@code cfg}'s port, wired to this engine's surfaces. */
    private HttpEngineServer candidate(JkHttpConfig cfg, HttpEvents hub, EngineIdentity id) {
        HttpEngineServer candidate = new HttpEngineServer(
                cfg,
                JkDirs.home(),
                paths.httpToken(),
                paths.log(),
                version,
                status,
                hub,
                httpJobs(),
                journal,
                () -> metrics.get().entries(),
                // Single-flight + 30s TTL: dashboard SSE reconnect + GET /api/cache must not each
                // exclusive-walk multi-GiB stores (SerialGC balloons committed heap ~90 MiB).
                CacheSnapshot.memoizing(JkDirs.cache(), () -> status.get().activeBuildPlans() == 0),
                log);
        // Hard-refresh mid-build: history rows carry live requestId/progress/phases; SSE connect
        // delivers one compact run-snapshot per job to the new subscription only.
        candidate.setLiveRunSupport(liveRuns::snapshot, liveRuns::isLive, liveRuns::rehydrate);
        // Combined-connection peak observed at every admission point (UDS accept bumps it too) —
        // not only when a status snapshot happens to run.
        candidate.setOnSseAdmitted(() -> peakActiveConnections.accumulateAndGet(liveConnections.getAsInt(), Math::max));
        if (cacheGate != null) candidate.setCacheGate(cacheGate);
        candidate.setSessions(sessions);
        candidate.setDecisions(decisions);
        candidate.setNotices(notices);
        candidate.setSelfUpdate(selfUpdate);
        candidate.setActivity(new Activity(ChangeJournal::current, decisions, notices));
        candidate.setIdentity(id);
        if (frontDoor && !id.guest().guest()) {
            candidate.setFrontDoor(
                    new FrontDoor(() -> id, candidate::port, paths.dir().resolve(GuestPairing.DIR), FrontDoor.band()));
            if (machine != null) candidate.setMachine(machine);
        }
        return candidate;
    }

    /** Serve {@code candidate}; a guest's band port is kept for the next start. */
    private void serve(HttpEngineServer candidate, @Nullable Path bandFile) throws IOException {
        if (stopped) {
            candidate.close();
            return;
        }
        if (bandFile != null) BandPort.remember(bandFile, candidate.port());
        if (frontDoor) pairWith.accept(candidate);
        Files.writeString(paths.http(), candidate.url());
        server = candidate;
        error = null;
        if (stopped) {
            close();
            return;
        }
        log.accept("jk engine: http listening on " + candidate.url());
        if (candidate.frontDoor() != null) {
            FrontDoorToken.align(paths.dir(), paths.httpToken());
            duty = FrontDoorDuty.host(() -> server, log);
        }
    }

    /** What this engine does for the front door while it serves; null before it serves. */
    private volatile @Nullable FrontDoorDuty duty;

    /**
     * The 8910 listener a WSL guest serves the front door on while it holds the role: the front
     * door's token from the Windows engine's directory, the Windows pairings, and the root page sent
     * to this guest's own dashboard under its prefix.
     */
    private HttpEngineServer frontListener(Path windowsEngineDir, EngineIdentity id) {
        HttpEvents hub = Objects.requireNonNull(events);
        JkHttpConfig cfg = Objects.requireNonNull(config).withPort(JkHttpConfig.DEFAULT_PORT);
        HttpEngineServer front = new HttpEngineServer(
                cfg,
                JkDirs.home(),
                FrontDoorToken.file(windowsEngineDir),
                paths.log(),
                version,
                status,
                hub,
                httpJobs(),
                journal,
                () -> metrics.get().entries(),
                CacheSnapshot.memoizing(JkDirs.cache(), () -> status.get().activeBuildPlans() == 0),
                log);
        front.setIdentity(id);
        front.setFrontDoorHome(id.id());
        front.setFrontDoor(
                new FrontDoor(() -> id, front::port, windowsEngineDir.resolve(GuestPairing.DIR), FrontDoor.band()));
        return front;
    }

    private void failed(HttpEngineServer candidate, Exception e) {
        candidate.close();
        error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        log.accept("jk engine: http failed to start (" + error + ") — continuing without http");
    }

    /** This WSL guest's pairing with the Windows front door: its directory and id; null until written. */
    private record Paired(Path dir, String id) {}

    private volatile @Nullable Paired pairing;

    /**
     * A WSL guest with interop pairs with the Windows front door: its port and token go into the
     * Windows user's JumpKick home ({@link GuestPairing}). Off the start path, since it asks interop.
     */
    private void pairWithWindows(HttpEngineServer candidate) {
        EngineIdentity id = candidate.identity();
        if (id == null || id.guest().kind() != Guest.Kind.WSL || !id.guest().interop()) return;
        SessionContext.startVirtual("jk-engine-guest-pairing", () -> {
            // The Windows engine is the front door: make sure it runs (JK-2JFBT).
            long asked = Clock.SYSTEM.millis();
            WindowsHostEngine.Outcome host = WindowsHostEngine.ensure(
                    id.guest(),
                    WindowsHostEngine.Mode.read(JkDirs.userConfigFile()),
                    name -> {
                        String v = ProcessEnv.get(name);
                        return v != null ? v : System.getProperty("jk.env." + name);
                    },
                    WindowsHostEngine.SHELL);
            if (host.state() != WindowsHostEngine.State.SKIPPED) {
                log.accept("jk engine: " + host.line() + "; " + (Clock.SYSTEM.millis() - asked) + " ms in all");
            }
            Path dir = GuestPairing.windowsDir();
            if (dir == null) {
                log.accept("jk engine: could not reach the Windows home to pair with its engine");
                return;
            }
            try {
                String token = Files.readString(paths.httpToken()).strip();
                GuestPairing.write(dir, new GuestPairing.Pair(id.id(), candidate.port(), token));
                pairing = new Paired(dir, id.id());
                // Stopped while interop answered: close() found no pairing to remove.
                if (stopped) {
                    unpair();
                    return;
                }
                WslWindowsDesktop windows = DecisionAlerts.wslDesktop(id.guest());
                String mismatch = windows == null
                        ? null
                        : WindowsHostEngine.mismatch(
                                FrontDoorDuty.probeWindows(windows, JkHttpConfig.DEFAULT_PORT), FrontDoor.PROTOCOL);
                if (mismatch != null) {
                    // Serve this engine's own dashboard on its band port; the front door cannot show it.
                    log.accept("jk engine: not pairing with the Windows engine: " + mismatch);
                    unpair();
                    return;
                }
                Path engineDir = dir.getParent();
                if (engineDir != null && duty == null) {
                    duty = FrontDoorDuty.guest(
                            candidate,
                            id.id(),
                            dir,
                            () -> frontListener(engineDir, id),
                            DecisionAlerts.wslDesktop(id.guest()),
                            log);
                    // Stopped while this pairing ran: close() found no duty to end, so its loop
                    // would run, tick and probe Windows for the rest of the process's life.
                    if (stopped) endDuty();
                }
            } catch (IOException | RuntimeException e) {
                log.accept("jk engine: could not pair with the Windows engine (" + e.getMessage() + ")");
            }
        });
    }

    /** Says the front door is leaving when this engine is it, and gives 8910 up. */
    private void endDuty() {
        FrontDoorDuty d = duty;
        duty = null;
        if (d != null) d.close();
    }

    private void unpair() {
        Paired p = pairing;
        pairing = null;
        if (p != null) GuestPairing.remove(p.dir(), p.id());
    }

    /** Hand the Web UI port to a successor immediately (idempotent). */
    public void stopNow() {
        stopped = true;
        endDuty();
        unpair();
        HttpEngineServer h = server;
        if (h != null) h.stopNow();
    }

    public void close() {
        stopped = true;
        endDuty();
        unpair();
        HttpEngineServer h = server;
        if (h != null) h.close();
        server = null;
    }

    private EngineHttpJobs httpJobs() {
        return new EngineHttpJobs() {
            @Override
            public long trigger(JobSpec spec) {
                return EngineHttpFront.this.trigger(spec);
            }

            @Override
            public boolean cancel(long requestId) {
                return cancelJob.test(requestId);
            }

            @Override
            public int cancelDir(String dir) {
                return cancelJobsForDir.applyAsInt(dir);
            }
        };
    }

    /**
     * {@code POST /api/build} / MCP {@code run}: resolve the verb, decode the spec into its
     * wire request line, stamp the origin that asked, submit FireAndForget on the one envelope.
     */
    private long trigger(JobSpec spec) {
        Path entryDir = requireProject(spec.dir());
        HostedVerb verb = verbs.forJobKind(spec.kind());
        if (verb == null) throw new IllegalArgumentException("kind not hosted: " + spec.kind());
        LockFloor.refuseIfBelow(entryDir, verb.wireType(), version);
        String line = ProtoSession.withOrigin(
                verb.decodeJob(spec.withDir(entryDir.toString())),
                spec.origin().trigger(),
                spec.origin().session());
        return jobs.submit(line, verb.toJobRequest(line), spec.transport());
    }

    private static Path requireProject(String dirStr) {
        Path entryDir = PathUtil.resolveUserPath(dirStr);
        if (!Files.isRegularFile(ManifestPaths.manifestIn(entryDir))) {
            throw new IllegalArgumentException("no jk.toml in " + entryDir);
        }
        return entryDir;
    }
}
