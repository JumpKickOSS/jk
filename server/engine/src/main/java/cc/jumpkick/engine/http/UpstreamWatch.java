// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.host.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Watches the engine a proxied request waits on. Once the engine has sent nothing for {@code
 * silence}, it is asked whether it is still there; an engine that does not answer has stopped, and
 * the waiting thread is interrupted, which ends its wait for the answer or for the answer's next
 * bytes. An engine that answers is waited on for as long as its answer takes, as a direct client
 * would wait.
 */
final class UpstreamWatch {

    private final long silenceNanos;
    private final Supplier<CompletableFuture<Boolean>> alive;
    private final Thread waiter;
    private volatile long heard;
    private @Nullable ScheduledFuture<?> tick;
    private boolean asking;
    private boolean stopped;
    private boolean done;

    private UpstreamWatch(Duration silence, Supplier<CompletableFuture<Boolean>> alive) {
        this.silenceNanos = silence.toNanos();
        this.alive = alive;
        this.waiter = Thread.currentThread();
        this.heard = Clock.SYSTEM.nanos();
    }

    /**
     * Starts watching for the calling thread, which must be the one that waits and must {@link
     * #end} the watch. {@code alive} answers whether the engine is still there; it must not block.
     */
    static UpstreamWatch start(Duration silence, Supplier<CompletableFuture<Boolean>> alive) {
        UpstreamWatch watch = new UpstreamWatch(silence, alive);
        ScheduledFuture<?> tick = WriteDeadline.WATCHDOG.scheduleWithFixedDelay(
                watch::tick, watch.silenceNanos, watch.silenceNanos, TimeUnit.NANOSECONDS);
        synchronized (watch) {
            watch.tick = tick;
        }
        return watch;
    }

    /** The engine sent something. */
    void heard() {
        heard = Clock.SYSTEM.nanos();
    }

    private void tick() {
        if (Clock.SYSTEM.nanos() - heard < silenceNanos) return;
        synchronized (this) {
            if (asking || done) return;
            asking = true;
        }
        alive.get().whenComplete((answered, failed) -> {
            if (failed == null && Boolean.TRUE.equals(answered)) {
                synchronized (this) {
                    asking = false;
                }
            } else {
                stop();
            }
        });
    }

    private synchronized void stop() {
        asking = false;
        if (done || stopped) return;
        stopped = true;
        waiter.interrupt();
    }

    /** Stops watching; true when the engine had stopped, the waiter's interrupt then cleared. */
    boolean end() {
        boolean wasStopped;
        synchronized (this) {
            done = true;
            if (tick != null) tick.cancel(false);
            wasStopped = stopped;
        }
        if (wasStopped) Thread.interrupted();
        return wasStopped;
    }
}
