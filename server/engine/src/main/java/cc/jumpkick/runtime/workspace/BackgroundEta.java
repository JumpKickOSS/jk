// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.host.Log;
import cc.jumpkick.run.JkThreads;
import cc.jumpkick.task.JavaCompile;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The build's ETA and work model, priced beside the build instead of ahead of it. Pricing walks the
 * forecast of every scheduled module, which on a 223-module workspace is seconds of hashing and
 * planning; no module needs the answer to start, so the build starts at once and the estimate is
 * published to the listener when it is ready.
 *
 * <p>The walk asks no compiler worker for a plan ({@link JavaCompile.PlanWorkers#NONE}) and prices
 * each compile from its prior record: the build's own compiles run on those workers, and a cold
 * build of a 223-module workspace queued one plan per module ahead of them, adding a third to the
 * time spent compiling, for a progress bar. {@code jk explain} asks any worker, so where this walk
 * meets a compile with an analysis and no cache hit its verdict, and the seed priced from it, can
 * differ from explain's; the pricing of one plan never does ({@link BuildService#estimateEtaMillis}).
 *
 * <p>{@link #close()} ends it before the build reports completion: an estimate still being priced
 * is cancelled and never published, so the listener never hears of a work model after the build
 * finished.
 */
@NullMarked
final class BackgroundEta {

    private final Object lock = new Object();
    private final Consumer<BuildService.EtaModel> publish;
    private volatile @Nullable Future<BuildService.EtaModel> pricing;
    private boolean open = true;
    private boolean published;

    private BackgroundEta(Consumer<BuildService.EtaModel> publish) {
        this.publish = publish;
    }

    /**
     * Price {@code estimate} on a background thread, handing the result to {@code publish} unless
     * the build closed first. The platform pool carries the session, its cancel token and the request
     * scope; the scoped plan-worker limit does not ride the hop, so it is bound here.
     */
    static BackgroundEta start(Supplier<BuildService.EtaModel> estimate, Consumer<BuildService.EtaModel> publish) {
        BackgroundEta eta = deferred(estimate, publish);
        eta.begin();
        return eta;
    }

    /**
     * An estimate priced only once {@link #begin} is called: the build starts it when its modules
     * are prepared. Preparing and pricing both load most of the planner's classes, and on a fresh
     * engine the two loading at once could leave a pricing thread holding one class-loading lock
     * while every virtual-thread carrier waited on another.
     */
    static BackgroundEta deferred(Supplier<BuildService.EtaModel> estimate, Consumer<BuildService.EtaModel> publish) {
        BackgroundEta eta = new BackgroundEta(publish);
        eta.estimate = estimate;
        return eta;
    }

    private volatile @Nullable Supplier<BuildService.EtaModel> estimate;

    /** Start pricing; a second call, or one after {@link #close}, does nothing. */
    void begin() {
        Supplier<BuildService.EtaModel> what;
        synchronized (lock) {
            what = estimate;
            estimate = null;
            if (what == null || !open) return;
        }
        BackgroundEta eta = this;
        Supplier<BuildService.EtaModel> priced = what;
        eta.pricing = JkThreads.platform().submit(() -> {
            BuildService.EtaModel model = ScopedValue.where(JavaCompile.PLAN_WORKERS, JavaCompile.PlanWorkers.NONE)
                    .call(priced::get);
            eta.publish(model);
            return model;
        });
    }

    /** An estimate already in hand, published at once: for callers that priced synchronously, and tests. */
    static BackgroundEta of(BuildService.EtaModel model, Consumer<BuildService.EtaModel> publish) {
        BackgroundEta eta = new BackgroundEta(publish);
        eta.pricing = CompletableFuture.completedFuture(model);
        eta.publish(model);
        return eta;
    }

    private void publish(BuildService.EtaModel model) {
        synchronized (lock) {
            if (!open || published) return;
            published = true;
            publish.accept(model);
        }
    }

    /** The estimate if pricing finished and succeeded; empty while it runs or after it failed. */
    Optional<BuildService.EtaModel> settled() {
        Future<BuildService.EtaModel> f = pricing;
        if (f == null || !f.isDone() || f.isCancelled()) return Optional.empty();
        try {
            return Optional.of(f.get());
        } catch (ExecutionException e) {
            Log.debug("build eta failed; the build ran without one", e.getCause() != null ? e.getCause() : e);
            return Optional.empty();
        } catch (CancellationException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /**
     * Stop publishing and cancel a pricing still running. Returns the estimate when it had already
     * settled, which is what the build learns its schedule bias from.
     */
    Optional<BuildService.EtaModel> close() {
        synchronized (lock) {
            open = false;
        }
        Future<BuildService.EtaModel> f = pricing;
        if (f != null && !f.isDone()) f.cancel(true);
        return settled();
    }
}
