// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * When the engine runs its {@link UpdateCheck}. The first check comes after the first build
 * finishes, or {@link #FIRST_WAIT} after the engine starts when no build came; then one every
 * {@link #EVERY}. Every check is pushed back by up to {@link #JITTER}, so engines started together
 * do not ask together. What a check finds is applied once the engine is quiet (no build for {@link
 * #QUIET}) or has waited {@link #APPLY_WAIT}; a kind that fails to apply waits for the next check.
 * Nothing runs while a build is in flight, nor on an
 * engine that is draining, nor when {@code [updates] check = false}.
 */
final class UpdateScheduler implements AutoCloseable {

    static final Duration FIRST_WAIT = Duration.ofMinutes(2);
    static final Duration EVERY = Duration.ofHours(4);
    static final Duration JITTER = Duration.ofMinutes(5);
    static final Duration QUIET = Duration.ofMinutes(2);
    static final Duration APPLY_WAIT = Duration.ofMinutes(30);

    /** How often the triggers are looked at. */
    static final Duration TICK = Duration.ofSeconds(30);

    /** The check and the apply; a seam for tests. */
    interface Work {
        /** Ask for the manifest; true when a kind waits to be applied. */
        boolean check();

        /** Apply what waits. */
        void apply();
    }

    private final IntSupplier activeBuildPlans;
    private final BooleanSupplier stopping;
    private final BooleanSupplier enabled;
    private final Work work;
    private final Clock clock;
    private final LongSupplier jitterMillis;

    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile @Nullable Thread ticker;

    private Instant dueAt;
    private boolean checkedOnce;
    private Instant lastBuildEnd = Instant.EPOCH;
    private @Nullable Instant pendingSince;

    UpdateScheduler(
            IntSupplier activeBuildPlans,
            BooleanSupplier stopping,
            BooleanSupplier enabled,
            Work work,
            Clock clock,
            LongSupplier jitterMillis) {
        this.activeBuildPlans = activeBuildPlans;
        this.stopping = stopping;
        this.enabled = enabled;
        this.work = work;
        this.clock = clock;
        this.jitterMillis = jitterMillis;
        this.dueAt = clock.instant().plus(FIRST_WAIT).plusMillis(jitter());
    }

    /** The scheduler of this engine's home. */
    static UpdateScheduler forEngine(IntSupplier activeBuildPlans, BooleanSupplier stopping, Consumer<String> log) {
        UpdateCheck check = UpdateCheck.forEngine(log);
        return new UpdateScheduler(
                activeBuildPlans,
                stopping,
                GlobalConfig::updatesCheck,
                new Work() {
                    @Override
                    public boolean check() {
                        return check.check();
                    }

                    @Override
                    public void apply() {
                        check.apply();
                    }
                },
                Clock.SYSTEM,
                () -> ThreadLocalRandom.current().nextLong(JITTER.toMillis()));
    }

    /** Look at the triggers every {@link #TICK} on a daemon thread. */
    void start() {
        // Engine-lifetime update ticker; reads no session.
        ticker = Thread.ofVirtual().name("jk-update-check").start(() -> {
            while (!closed.get()) {
                try {
                    Thread.sleep(TICK);
                } catch (InterruptedException e) {
                    return;
                }
                try {
                    tick();
                } catch (RuntimeException e) {
                    Log.debug("UpdateScheduler: tick failed", e);
                }
            }
        });
    }

    /** A build plan finished. Before the first check, that check is due once this build's jitter has passed. */
    synchronized void buildFinished() {
        Instant now = clock.instant();
        lastBuildEnd = now;
        if (!checkedOnce) {
            Instant afterBuild = now.plusMillis(jitter());
            if (afterBuild.isBefore(dueAt)) dueAt = afterBuild;
        }
    }

    /** Check when one is due and apply what waits when that is allowed; never with a build in flight. */
    synchronized void tick() {
        if (closed.get() || stopping.getAsBoolean() || activeBuildPlans.getAsInt() != 0) return;
        if (!enabled.getAsBoolean()) return;
        Instant now = clock.instant();
        if (!now.isBefore(dueAt)) {
            checkedOnce = true;
            dueAt = now.plus(EVERY).plusMillis(jitter());
            if (work.check()) {
                if (pendingSince == null) pendingSince = now;
            } else {
                pendingSince = null;
            }
        }
        Instant waiting = pendingSince;
        if (waiting == null || activeBuildPlans.getAsInt() != 0) return;
        boolean quiet = !now.isBefore(lastBuildEnd.plus(QUIET));
        boolean waitedLongEnough = !now.isBefore(waiting.plus(APPLY_WAIT));
        if (quiet || waitedLongEnough) {
            // One attempt per check: a kind that failed to apply is noted again by the next one.
            pendingSince = null;
            work.apply();
        }
    }

    private long jitter() {
        return Math.max(0, jitterMillis.getAsLong());
    }

    @Override
    public void close() {
        closed.set(true);
        Thread t = ticker;
        if (t != null) t.interrupt();
    }
}
