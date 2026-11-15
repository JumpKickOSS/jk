// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.power;

import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.config.KeepAwake;
import cc.jumpkick.run.JkThreads;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Keeps the machine from idle sleep while a job runs ({@code [engine] keep-awake}): held from the
 * first job's start to the last job's end, never through the idle linger after it, and only on A/C
 * under the default {@link KeepAwake#AC}, so a power change to battery mid-build drops it and a
 * change back takes it again. A closed lid is never blocked: closing it is the user's word, and a
 * laptop building in a bag is a heat hazard; the run then reads as suspended ({@link
 * cc.jumpkick.host.time.Suspensions}).
 *
 * <p>Off until the engine process enables it ({@link #enable}), so a test that runs jobs in-process
 * holds nothing.
 */
public final class Awake {

    /** How often a held hold looks at the power source again. */
    static final Duration RECHECK = Duration.ofSeconds(15);

    private static final Awake SYSTEM =
            new Awake(() -> JkEngineConfig.resolve().keepAwake(), PowerHost::forThisHost, loop -> JkThreads.platform()
                    .execute(loop));

    /** The engine's. */
    public static Awake system() {
        return SYSTEM;
    }

    private final Supplier<KeepAwake> mode;
    private final Supplier<PowerHost> hostFactory;
    private final @Nullable Consumer<Runnable> loopStarter;
    private final Set<Long> jobs = ConcurrentHashMap.newKeySet();

    private volatile Consumer<String> log = s -> {};
    private volatile boolean enabled;
    private @Nullable PowerHost host;
    private PowerHost.@Nullable Hold hold;
    private boolean looping;
    private boolean saidCannot;

    /**
     * A policy over {@code mode}, the host {@code hostFactory} makes on first use, and {@code
     * loopStarter}, which runs the re-check loop on a platform thread (null: no loop, a test calls
     * {@link #check}).
     */
    Awake(Supplier<KeepAwake> mode, Supplier<PowerHost> hostFactory, @Nullable Consumer<Runnable> loopStarter) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.hostFactory = Objects.requireNonNull(hostFactory, "hostFactory");
        this.loopStarter = loopStarter;
    }

    /** The engine process turns it on, with where its decisions are logged. */
    public void enable(Consumer<String> log) {
        this.log = Objects.requireNonNull(log, "log");
        enabled = true;
    }

    /**
     * A job started: the hold is taken if the mode and the power source want it, by the re-check
     * loop and never on the job's thread, since starting a helper waits to see that it holds.
     */
    public void jobStarted(long requestId) {
        jobs.add(requestId);
        if (!enabled) return;
        if (loopStarter == null) {
            check();
            return;
        }
        boolean start;
        synchronized (this) {
            start = !looping;
            if (start) looping = true;
        }
        if (start) loopStarter.accept(this::loop);
    }

    /** The job {@link #jobStarted} named ended: the last one's end drops the hold at once. */
    public void jobEnded(long requestId) {
        jobs.remove(requestId);
        if (enabled && jobs.isEmpty()) check();
    }

    /** Whether the machine is being held awake now. */
    public synchronized boolean holding() {
        return hold != null && hold.alive();
    }

    /** One look: take, keep or drop the hold for the jobs running now, the mode and the power source. */
    synchronized void check() {
        if (jobs.isEmpty() || !enabled) {
            release("no job runs");
            return;
        }
        PowerHost h = host();
        KeepAwake m = mode.get();
        PowerHost.Source source = m == KeepAwake.AC ? h.source() : PowerHost.Source.UNKNOWN;
        if (!wanted(m, source)) {
            release(m == KeepAwake.NEVER ? "keep-awake = never" : "the machine runs on " + spell(source));
            return;
        }
        if (hold != null && hold.alive()) return;
        hold = h.hold();
        if (hold != null) {
            log.accept("jk engine: keeping the machine awake while jobs run (keep-awake = " + m.spelling() + ")");
        } else if (!saidCannot) {
            saidCannot = true;
            log.accept("jk engine: cannot keep the machine awake here"
                    + (h instanceof PowerHost.DetectOnly d ? " (" + d.why() + ")" : "")
                    + "; a run it sleeps through reads as suspended");
        }
    }

    /** Whether {@code mode} holds the machine awake on {@code source}. */
    static boolean wanted(KeepAwake mode, PowerHost.Source source) {
        return switch (mode) {
            case NEVER -> false;
            case ALWAYS -> true;
            case AC -> source == PowerHost.Source.AC;
        };
    }

    private void release(String why) {
        PowerHost.Hold h = hold;
        if (h == null) return;
        hold = null;
        h.close();
        log.accept("jk engine: stopped keeping the machine awake: " + why);
    }

    private PowerHost host() {
        PowerHost h = host;
        if (h == null) host = h = hostFactory.get();
        return h;
    }

    private static String spell(PowerHost.Source s) {
        return switch (s) {
            case AC -> "A/C";
            case BATTERY -> "battery";
            case UNKNOWN -> "an unknown power source";
        };
    }

    private void loop() {
        try {
            check();
            while (true) {
                Thread.sleep(RECHECK);
                synchronized (this) {
                    if (jobs.isEmpty()) {
                        looping = false;
                        return;
                    }
                }
                check();
            }
        } catch (InterruptedException e) {
            synchronized (this) {
                looping = false;
            }
            Thread.currentThread().interrupt();
        }
    }
}
