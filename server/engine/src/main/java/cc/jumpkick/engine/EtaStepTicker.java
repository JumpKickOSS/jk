// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.host.Log;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongPredicate;

/**
 * Once a second, re-price each watched request's step-priced remainder ({@link
 * SsePublisher#tickSteps}). One engine-lifetime daemon thread, started with the first request it
 * watches; a request leaves when its tick says its session is gone.
 */
final class EtaStepTicker {

    private final LongPredicate tick;
    private final Set<Long> watched = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean started = new AtomicBoolean();

    /** {@code tick} re-prices one request and answers false once it is gone. */
    EtaStepTicker(LongPredicate tick) {
        this.tick = tick;
    }

    void watch(long requestId) {
        watched.add(requestId);
        if (!started.compareAndSet(false, true)) return;
        // Engine-lifetime: each tick reads the sessions it names by id, never a request's context.
        ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("jk-eta-step-ticker").factory());
        ticker.scheduleWithFixedDelay(this::tickAll, 1, 1, TimeUnit.SECONDS);
    }

    private void tickAll() {
        for (Long rid : watched) {
            try {
                if (!tick.test(rid)) watched.remove(rid);
            } catch (RuntimeException e) {
                Log.debug("eta step tick for " + rid, e);
            }
        }
    }
}
