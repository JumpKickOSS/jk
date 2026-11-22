// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.cache.PathClients;
import cc.jumpkick.cache.PreparedUpdate;
import cc.jumpkick.config.EnvValues;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.config.SelfUpdatePolicy;
import cc.jumpkick.config.SelfUpdateState;
import cc.jumpkick.config.UpdateOffer;
import cc.jumpkick.engine.http.SelfUpdateControl;
import cc.jumpkick.host.Classpaths;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.ProcessEnv;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.plugin.build.TaskExec;
import cc.jumpkick.updates.UpdateManifest;
import cc.jumpkick.version.Versions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import org.jspecify.annotations.Nullable;

/**
 * The engine updating jk on its own, to the release the signed update manifest names ({@link
 * UpdateManifest#release}, which the engine's {@link UpdateCheck} keeps current). A newer release
 * is downloaded once no build has finished for {@link #DOWNLOAD_QUIET}, or once it has waited
 * {@link #DOWNLOAD_WAIT}; it is installed only when the engine is idle: no build for {@link #IDLE}
 * and no session connected. Installing starts the new engine, which displaces this one. Each step
 * is {@code jk self update} on the PATH client, so the engine and a person run the same code, and
 * the download is held to the manifest's checksums.
 *
 * <p>Nothing runs with a build in flight, on CI (jk checks there but never replaces itself
 * mid-run), or when {@code [updates] check = false}. Under {@code ask} the run stops after the
 * download and the client offers it at a terminal. Nothing here fails a build: a step that fails
 * is logged and tried again later.
 */
final class SelfUpdateScheduler implements AutoCloseable, SelfUpdateControl {

    /** How long no build must have finished before a newer release is downloaded. */
    static final Duration DOWNLOAD_QUIET = UpdateScheduler.QUIET;

    /** How long a newer release waits for that quiet before it is downloaded anyway, between builds. */
    static final Duration DOWNLOAD_WAIT = UpdateScheduler.APPLY_WAIT;

    /** How long no build must have finished, with no session connected, before an install. */
    static final Duration IDLE = Duration.ofMinutes(10);

    /** Gap before a download that failed is tried again. */
    static final Duration RETRY_AFTER = Duration.ofHours(1);

    /** How often the triggers are looked at. */
    static final Duration TICK = Duration.ofMinutes(1);

    /** The steps, each a {@code jk self update} run; a seam for tests. */
    interface Steps {
        /** Download and verify {@code version}; true when the prepared record is written. */
        boolean download(String version);

        /** Install the prepared {@code version} and start its engine; true on success. */
        boolean install(String version);
    }

    /** What the home looks like to the scheduler, read fresh before every step; a seam for tests. */
    interface View {
        SelfUpdatePolicy.Action action();

        SelfUpdateState state();

        /** Whether the update check runs ({@code [updates] check}). */
        boolean enabled();

        /** Whether this is a CI run, which never replaces its own jk. */
        boolean ci();

        /** The release the held update manifest names. */
        Optional<String> offered();

        Optional<PreparedUpdate> prepared();

        /** The version the install pointer names. */
        Optional<String> installed();

        /** "Not now" to {@code version} at {@code at}, on every surface ({@link UpdateOffer#snooze}). */
        void snooze(String version, Instant at);
    }

    /** The step due next. */
    sealed interface Next {
        record Download(String version) implements Next {}

        record Install(String version) implements Next {}

        record Nothing() implements Next {}
    }

    private final String version;
    private final IntSupplier activeBuildPlans;
    private final IntSupplier connectedSessions;
    private final BooleanSupplier stopping;
    private final Steps steps;
    private final View view;
    private final Clock clock;
    private final Consumer<String> log;

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean installingNow = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile long lastBuildEndMillis;
    private volatile long downloadFailedAtMillis = Long.MIN_VALUE;
    private volatile boolean installed;
    private volatile @Nullable Thread ticker;

    /** The newer release last seen offered, and since when; null before one. */
    private @Nullable String waiting;

    private Instant waitingSince = Instant.EPOCH;

    SelfUpdateScheduler(
            String version,
            IntSupplier activeBuildPlans,
            IntSupplier connectedSessions,
            BooleanSupplier stopping,
            Steps steps,
            View view,
            Clock clock,
            Consumer<String> log) {
        this.version = version;
        this.activeBuildPlans = activeBuildPlans;
        this.connectedSessions = connectedSessions;
        this.stopping = stopping;
        this.steps = steps;
        this.view = view;
        this.clock = clock;
        this.log = log;
        this.lastBuildEndMillis = clock.millis();
    }

    /**
     * The scheduler of this engine's home, or empty for an engine that never updates itself: a
     * {@code -SNAPSHOT}, or one not running the jar its home installed (a checkout, a test). {@code
     * connectedSessions} counts the clients and agents connected now.
     */
    static Optional<SelfUpdateScheduler> forEngine(
            String version,
            IntSupplier activeBuildPlans,
            IntSupplier connectedSessions,
            BooleanSupplier stopping,
            Consumer<String> log) {
        if (Versions.isSnapshot(version) || !runsInstalledJar(System.getProperty("java.class.path", ""))) {
            return Optional.empty();
        }
        Path client = PathClients.pathClient(JkDirs.binDir());
        return Optional.of(new SelfUpdateScheduler(
                version,
                activeBuildPlans,
                connectedSessions,
                stopping,
                new ClientSteps(client, JkDirs.state().resolve("self-update.log")),
                new HomeView(version),
                Clock.SYSTEM,
                log));
    }

    /** Whether {@code classPath} is the one engine jar this home's install pointer names. */
    static boolean runsInstalledJar(String classPath) {
        if (classPath.isBlank() || classPath.contains(Classpaths.SEPARATOR)) return false;
        Optional<Path> live = EngineInstall.current().currentInstall().map(EngineInstall.Materialized::engineJar);
        if (live.isEmpty()) return false;
        try {
            return Files.isSameFile(Path.of(classPath), live.get());
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Look at the triggers every {@link #TICK} on a daemon thread. */
    void start() {
        // Engine-lifetime update ticker; reads no session.
        ticker = Thread.ofVirtual().name("jk-self-update").start(() -> {
            while (!closed.get()) {
                try {
                    Thread.sleep(TICK);
                } catch (InterruptedException e) {
                    return;
                }
                try {
                    tick();
                } catch (RuntimeException e) {
                    Log.debug("SelfUpdateScheduler: tick failed", e);
                }
            }
        });
    }

    /** A build plan finished: the quiet and idle spells start again. */
    void buildFinished() {
        lastBuildEndMillis = clock.millis();
    }

    /** Run the steps that are due, back to back, while nothing builds; never with a build in flight. */
    void tick() {
        if (closed.get() || installed || stopping.getAsBoolean() || building()) return;
        if (!running.compareAndSet(false, true)) return;
        try {
            runSteps();
        } finally {
            running.set(false);
        }
    }

    private void runSteps() {
        while (!closed.get() && !installed && !stopping.getAsBoolean() && !building()) {
            Instant now = clock.instant();
            switch (next(now)) {
                case Next.Nothing n -> {
                    return;
                }
                case Next.Download d -> {
                    if (!downloadDue(d.version(), now) || !run(d)) return;
                }
                case Next.Install i -> {
                    if (idle() && run(i)) installed = true;
                    return;
                }
            }
        }
    }

    private boolean building() {
        return activeBuildPlans.getAsInt() != 0;
    }

    private long sinceLastBuild() {
        return clock.millis() - lastBuildEndMillis;
    }

    /** Quiet since the last build, or the release has waited long enough for that. */
    private boolean downloadDue(String release, Instant now) {
        if (!release.equals(waiting)) {
            waiting = release;
            waitingSince = now;
        }
        return sinceLastBuild() >= DOWNLOAD_QUIET.toMillis() || !now.isBefore(waitingSince.plus(DOWNLOAD_WAIT));
    }

    /** No build for {@link #IDLE} and no session connected: nothing runs on this engine to be cut off. */
    private boolean idle() {
        return !building() && sinceLastBuild() >= IDLE.toMillis() && connectedSessions.getAsInt() == 0;
    }

    private boolean run(Next next) {
        return switch (next) {
            case Next.Download d -> {
                log.accept("jk engine: self-update: downloading jk " + d.version());
                boolean ok = steps.download(d.version());
                if (!ok) {
                    downloadFailedAtMillis = clock.millis();
                    log.accept(
                            "jk engine: self-update: download of JumpKick " + d.version() + " failed; retrying later");
                }
                yield ok;
            }
            case Next.Install i -> {
                log.accept("jk engine: self-update: installing jk " + i.version() + "; its engine takes over");
                boolean ok = steps.install(i.version());
                if (!ok) log.accept("jk engine: self-update: installing jk " + i.version() + " failed");
                yield ok;
            }
            case Next.Nothing n -> false;
        };
    }

    /** The step due at {@code now}, read fresh from the home. */
    Next next(Instant now) {
        if (!view.enabled()) return new Next.Nothing();
        SelfUpdatePolicy.Action action = view.action();
        if (action == SelfUpdatePolicy.Action.OFF) return new Next.Nothing();
        // A newer install already exists: its engine is the one to update from.
        if (view.installed().filter(v -> Versions.compare(v, version) > 0).isPresent()) return new Next.Nothing();
        if (view.ci()) return new Next.Nothing();
        Optional<PreparedUpdate> prepared = view.prepared().filter(p -> p.newerThan(version));
        if (prepared.isPresent()) {
            return action == SelfUpdatePolicy.Action.AUTO
                    ? new Next.Install(prepared.get().version())
                    : new Next.Nothing();
        }
        Optional<String> offered = view.offered().filter(v -> Versions.compare(v, version) > 0);
        if (offered.isPresent() && downloadRetryDue(now)) return new Next.Download(offered.get());
        return new Next.Nothing();
    }

    private boolean downloadRetryDue(Instant now) {
        long failed = downloadFailedAtMillis;
        return failed == Long.MIN_VALUE || now.toEpochMilli() - failed >= RETRY_AFTER.toMillis();
    }

    /** The downloaded release a person may be offered now: newer, not snoozed, updating not off. */
    @Override
    public Optional<String> offer() {
        if (installed || view.action() == SelfUpdatePolicy.Action.OFF) return Optional.empty();
        String staged = view.prepared().map(PreparedUpdate::version).orElse(null);
        return UpdateOffer.due(view.state(), staged, version, clock.instant());
    }

    @Override
    public boolean installing() {
        return installingNow.get();
    }

    /**
     * A person asked to install the downloaded release now: it installs as soon as no other step
     * is running, whatever the quiet trigger says. False when nothing newer is downloaded.
     */
    @Override
    public boolean installNow() {
        if (closed.get() || installed || stopping.getAsBoolean()) return false;
        Optional<String> staged =
                view.prepared().filter(p -> p.newerThan(version)).map(PreparedUpdate::version);
        if (staged.isEmpty()) return false;
        if (!installingNow.compareAndSet(false, true)) return true;
        // A person's click; reads no session.
        Thread.ofVirtual().name("jk-self-update-now").start(() -> runInstallNow(staged.get()));
        return true;
    }

    /** The install {@link #installNow} started, once no other step is running; called directly by tests. */
    void runInstallNow(String target) {
        try {
            while (!running.compareAndSet(false, true)) {
                if (closed.get()) return;
                Thread.sleep(1000);
            }
            try {
                if (run(new Next.Install(target))) installed = true;
            } finally {
                running.set(false);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            installingNow.set(false);
        }
    }

    @Override
    public boolean snooze() {
        Optional<String> offered = offer();
        if (offered.isEmpty()) return false;
        view.snooze(offered.get(), clock.instant());
        return true;
    }

    @Override
    public void close() {
        closed.set(true);
        Thread t = ticker;
        if (t != null) t.interrupt();
    }

    /** The live home: policy, state and install read from disk on every call. */
    static final class HomeView implements View {
        private final String version;

        HomeView(String version) {
            this.version = version;
        }

        @Override
        public SelfUpdatePolicy.Action action() {
            SelfUpdateState state = state();
            return SelfUpdatePolicy.decide(
                            version,
                            ProcessEnv.get(SelfUpdatePolicy.ENV),
                            GlobalConfig.selfUpdate().orElse(null),
                            state,
                            SelfUpdatePolicy.attended(state, Clock.SYSTEM.instant(), ci()))
                    .action();
        }

        @Override
        public SelfUpdateState state() {
            return SelfUpdateState.read();
        }

        @Override
        public boolean enabled() {
            return GlobalConfig.updatesCheck();
        }

        @Override
        public boolean ci() {
            return EnvValues.isCi(ProcessEnv::get);
        }

        @Override
        public Optional<String> offered() {
            return UpdateManifest.held().flatMap(UpdateManifest::release).map(UpdateManifest.Release::version);
        }

        @Override
        public Optional<PreparedUpdate> prepared() {
            return PreparedUpdate.read(EngineInstall.current().engineHome());
        }

        @Override
        public Optional<String> installed() {
            return EngineInstall.current().currentInstall().map(EngineInstall.Materialized::version);
        }

        @Override
        public void snooze(String version, Instant at) {
            try {
                SelfUpdateState.update(s -> UpdateOffer.snooze(s, version, at));
            } catch (IOException e) {
                Log.debug("SelfUpdateScheduler: snooze not recorded", e);
            }
        }
    }

    /**
     * Each step as {@code jk self update} on the PATH client, in this engine's own environment, its
     * output appended to a log beside the update state. The install step outlives this engine: it
     * starts the successor that displaces it.
     */
    static final class ClientSteps implements Steps {

        /** Ceiling for one step; a download on a slow link is the long one. */
        static final Duration STEP_TIMEOUT = Duration.ofMinutes(20);

        private final Path client;
        private final Path logFile;

        ClientSteps(Path client, Path logFile) {
            this.client = client;
            this.logFile = logFile;
        }

        @Override
        public boolean download(String version) {
            return run("self", "update", "--download", version);
        }

        @Override
        public boolean install(String version) {
            return run("self", "update", version);
        }

        private boolean run(String... args) {
            if (!Files.isRegularFile(client)) return false;
            try {
                Files.createDirectories(logFile.getParent());
                Process p = new TaskExec.ToolRun(client)
                        .args(List.of(args))
                        .env("JK_NONINTERACTIVE", "1")
                        .start(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
                p.getOutputStream().close();
                if (!p.waitFor(STEP_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    return false;
                }
                return p.exitValue() == 0;
            } catch (IOException e) {
                Log.debug("SelfUpdateScheduler: " + String.join(" ", args) + " did not start", e);
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
