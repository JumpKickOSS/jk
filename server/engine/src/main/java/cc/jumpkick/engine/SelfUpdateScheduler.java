// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.cache.PreparedUpdate;
import cc.jumpkick.config.EnvValues;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.config.SelfUpdatePolicy;
import cc.jumpkick.config.SelfUpdateState;
import cc.jumpkick.host.Classpaths;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.ProcessEnv;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.plugin.build.TaskExec;
import cc.jumpkick.util.JkDirs;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import org.jspecify.annotations.Nullable;

/**
 * The engine updating jk on its own. A check is due once a day; while one is due, the run starts
 * after {@link #QUIET} with no build in flight, or when the {@link #BUILDS_IN_A_ROW}th build since
 * it fell due finishes without such a gap. The run is check, download, install, one after the
 * other: each step is {@code jk self update} on the PATH client, so the engine and a person run the
 * same code. A build that starts during a step lets the step finish, and the next one waits for the
 * trigger again. Installing starts the new engine, which displaces this one; builds in flight
 * finish here first.
 *
 * <p>Under {@code ask} the run stops after the download and the client offers it at a terminal.
 * Nothing here fails a build: a step that fails is logged and tried again later.
 */
final class SelfUpdateScheduler implements AutoCloseable {

    /** Gap between checks that reached the release site. */
    static final Duration CHECK_EVERY = Duration.ofHours(24);

    /** Gap before a check, or a download, that failed is tried again. */
    static final Duration RETRY_AFTER = Duration.ofHours(1);

    /** How long the engine must go without a build before a step runs on its own. */
    static final Duration QUIET = Duration.ofMinutes(10);

    /** Builds finishing back to back, never {@link #QUIET} apart, after which a step runs anyway. */
    static final int BUILDS_IN_A_ROW = 3;

    /** How old a CI jk home must be before it updates itself: a fresh runner was just installed. */
    static final Duration CI_HOME_AGE = Duration.ofHours(24);

    /** How often the quiet trigger is looked at. */
    static final Duration TICK = Duration.ofMinutes(1);

    /** The steps, each a {@code jk self update} run; a seam for tests. */
    interface Steps {
        /** Read the latest-release pointer into the update state; true when it was read. */
        boolean check();

        /** Download and verify {@code version}; true when the prepared record is written. */
        boolean download(String version);

        /** Install the prepared {@code version} and start its engine; true on success. */
        boolean install(String version);
    }

    /** What the home looks like to the scheduler, read fresh before every step; a seam for tests. */
    interface View {
        SelfUpdatePolicy.Action action();

        SelfUpdateState state();

        Optional<PreparedUpdate> prepared();

        /** The version the install pointer names. */
        Optional<String> installed();

        /** Whether a fresh CI home (a just-installed runner) must not update itself. */
        boolean freshCiHome();

        void recordAttempt(Instant at);
    }

    /** The step due next. */
    sealed interface Next {
        record Check() implements Next {}

        record Download(String version) implements Next {}

        record Install(String version) implements Next {}

        record Nothing() implements Next {}
    }

    private final String version;
    private final IntSupplier activeBuildPlans;
    private final BooleanSupplier stopping;
    private final Steps steps;
    private final View view;
    private final Clock clock;
    private final Consumer<String> log;

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger buildsSinceQuiet = new AtomicInteger();
    private volatile long lastBuildEndMillis;
    private volatile long downloadFailedAtMillis = Long.MIN_VALUE;
    private volatile boolean installed;
    private volatile @Nullable Thread ticker;

    SelfUpdateScheduler(
            String version,
            IntSupplier activeBuildPlans,
            BooleanSupplier stopping,
            Steps steps,
            View view,
            Clock clock,
            Consumer<String> log) {
        this.version = version;
        this.activeBuildPlans = activeBuildPlans;
        this.stopping = stopping;
        this.steps = steps;
        this.view = view;
        this.clock = clock;
        this.log = log;
        this.lastBuildEndMillis = clock.millis();
    }

    /**
     * The scheduler of this engine's home, or empty for an engine that never updates itself: a
     * {@code -SNAPSHOT}, or one not running the jar its home installed (a checkout, a test).
     */
    static Optional<SelfUpdateScheduler> forEngine(
            String version, IntSupplier activeBuildPlans, BooleanSupplier stopping, Consumer<String> log) {
        if (Versions.isSnapshot(version) || !runsInstalledJar(System.getProperty("java.class.path", ""))) {
            return Optional.empty();
        }
        Path client = EngineInstall.pathClient(JkDirs.binDir());
        return Optional.of(new SelfUpdateScheduler(
                version,
                activeBuildPlans,
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

    /** Look at the quiet trigger every {@link #TICK} on a daemon thread. */
    void start() {
        // Engine-lifetime update ticker; reads no session.
        ticker = Thread.ofVirtual().name("jk-self-update").start(() -> {
            while (!closed.get()) {
                try {
                    Thread.sleep(TICK);
                } catch (InterruptedException e) {
                    return;
                }
                tick();
            }
        });
    }

    /** A build plan finished. The {@link #BUILDS_IN_A_ROW}th in a row without a quiet gap runs a step. */
    void buildFinished() {
        long now = clock.millis();
        if (now - lastBuildEndMillis >= QUIET.toMillis()) buildsSinceQuiet.set(0);
        lastBuildEndMillis = now;
        if (buildsSinceQuiet.incrementAndGet() >= BUILDS_IN_A_ROW) kick(true);
    }

    /** The quiet trigger: no build in flight and none finished for {@link #QUIET}. */
    void tick() {
        if (quiet()) kick(false);
    }

    private boolean quiet() {
        return activeBuildPlans.getAsInt() == 0 && clock.millis() - lastBuildEndMillis >= QUIET.toMillis();
    }

    private void kick(boolean afterBuilds) {
        if (closed.get() || installed || stopping.getAsBoolean()) return;
        if (!running.compareAndSet(false, true)) return;
        // Update steps run beside builds and read no session.
        Thread.ofVirtual().name("jk-self-update-run").start(() -> {
            try {
                runSteps(afterBuilds);
            } catch (RuntimeException e) {
                Log.debug("SelfUpdateScheduler: run failed", e);
            } finally {
                running.set(false);
            }
        });
    }

    /**
     * Steps back to back while nothing interrupts them. {@code afterBuilds} is the build trigger:
     * its first step runs even with other builds in flight; any later one waits for quiet again.
     */
    void runSteps(boolean afterBuilds) {
        boolean go = afterBuilds;
        while (!closed.get() && !installed && !stopping.getAsBoolean()) {
            Next next = next(clock.instant());
            if (next instanceof Next.Nothing) return;
            if (!go && !quiet()) return;
            long startedAt = clock.millis();
            boolean ok = run(next);
            if (next instanceof Next.Install && ok) {
                installed = true;
                return;
            }
            if (!ok) return;
            boolean interrupted = lastBuildEndMillis > startedAt || activeBuildPlans.getAsInt() > 0;
            if (interrupted) {
                // A build came in: the next step waits for a quiet spell or a fresh run of builds.
                buildsSinceQuiet.set(0);
                return;
            }
            go = false;
        }
    }

    private boolean run(Next next) {
        return switch (next) {
            case Next.Check c -> {
                view.recordAttempt(clock.instant());
                log.accept("jk engine: self-update: checking for a newer jk");
                yield steps.check();
            }
            case Next.Download d -> {
                log.accept("jk engine: self-update: downloading jk " + d.version());
                boolean ok = steps.download(d.version());
                if (!ok) {
                    downloadFailedAtMillis = clock.millis();
                    log.accept("jk engine: self-update: download of jk " + d.version() + " failed; retrying later");
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
        SelfUpdatePolicy.Action action = view.action();
        if (action == SelfUpdatePolicy.Action.OFF) return new Next.Nothing();
        // A newer install already exists: its engine is the one to update from.
        if (view.installed().filter(v -> Versions.compare(v, version) > 0).isPresent()) return new Next.Nothing();
        if (view.freshCiHome()) return new Next.Nothing();
        Optional<PreparedUpdate> prepared = view.prepared().filter(p -> p.newerThan(version));
        if (prepared.isPresent()) {
            return action == SelfUpdatePolicy.Action.AUTO
                    ? new Next.Install(prepared.get().version())
                    : new Next.Nothing();
        }
        SelfUpdateState state = view.state();
        if (checkDue(state, now)) return new Next.Check();
        String latest = state.latest();
        if (latest != null && Versions.compare(latest, version) > 0 && downloadRetryDue(now)) {
            return new Next.Download(latest);
        }
        return new Next.Nothing();
    }

    private static boolean checkDue(SelfUpdateState state, Instant now) {
        if (state.lastCheck() != null && state.lastCheck().plus(CHECK_EVERY).isAfter(now)) return false;
        return state.lastAttempt() == null
                || !state.lastAttempt().plus(RETRY_AFTER).isAfter(now);
    }

    private boolean downloadRetryDue(Instant now) {
        long failed = downloadFailedAtMillis;
        return failed == Long.MIN_VALUE || now.toEpochMilli() - failed >= RETRY_AFTER.toMillis();
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
        public Optional<PreparedUpdate> prepared() {
            return PreparedUpdate.read(EngineInstall.current().engineHome());
        }

        @Override
        public Optional<String> installed() {
            return EngineInstall.current().currentInstall().map(EngineInstall.Materialized::version);
        }

        @Override
        public boolean freshCiHome() {
            if (!ci()) return false;
            Instant now = Clock.SYSTEM.instant();
            SelfUpdateState state = state();
            if (state.firstSeen() == null) {
                try {
                    SelfUpdateState.update(s -> s.firstSeen() == null ? s.withFirstSeen(now) : s);
                } catch (IOException e) {
                    Log.debug("SelfUpdateScheduler: first-seen not recorded", e);
                }
                return true;
            }
            return state.firstSeen().plus(CI_HOME_AGE).isAfter(now);
        }

        @Override
        public void recordAttempt(Instant at) {
            try {
                SelfUpdateState.update(s -> s.withAttempt(at));
            } catch (IOException e) {
                Log.debug("SelfUpdateScheduler: attempt not recorded", e);
            }
        }

        private static boolean ci() {
            return EnvValues.isCi(ProcessEnv::get);
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
        public boolean check() {
            return run("self", "update", "--check");
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
