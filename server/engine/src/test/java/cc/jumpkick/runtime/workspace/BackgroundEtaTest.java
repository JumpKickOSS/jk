// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The build's ETA is priced beside the build: published when ready, never after the build ends. */
class BackgroundEtaTest {

    private static BuildService.EtaModel model(long ms) {
        return new BuildService.EtaModel(ms, List.of(), 1, true, ms);
    }

    @Test
    void publishes_the_estimate_once_it_is_priced() throws Exception {
        List<BuildService.EtaModel> heard = new CopyOnWriteArrayList<>();
        CountDownLatch published = new CountDownLatch(1);
        BackgroundEta eta = BackgroundEta.start(() -> model(1234), m -> {
            heard.add(m);
            published.countDown();
        });
        assertThat(published.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(heard).extracting(BuildService.EtaModel::etaMs).containsExactly(1234L);
        assertThat(eta.close()).map(BuildService.EtaModel::etaMs).contains(1234L);
    }

    @Test
    void a_build_that_ends_first_cancels_the_pricing_and_hears_nothing() throws Exception {
        List<BuildService.EtaModel> heard = new CopyOnWriteArrayList<>();
        CountDownLatch pricing = new CountDownLatch(1);
        CountDownLatch never = new CountDownLatch(1);
        BackgroundEta eta = BackgroundEta.start(
                () -> {
                    pricing.countDown();
                    try {
                        never.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("cancelled", e);
                    }
                    return model(1);
                },
                heard::add);
        assertThat(pricing.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(eta.close()).isEmpty();
        never.countDown();
        Thread.sleep(50);
        assertThat(heard).isEmpty();
    }

    @Test
    void a_failed_pricing_leaves_the_build_without_an_estimate() throws Exception {
        List<BuildService.EtaModel> heard = new CopyOnWriteArrayList<>();
        CountDownLatch failing = new CountDownLatch(1);
        BackgroundEta eta = BackgroundEta.start(
                () -> {
                    failing.countDown();
                    throw new IllegalStateException("forecast failed");
                },
                heard::add);
        assertThat(failing.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(eta.close()).isEmpty();
        assertThat(heard).isEmpty();
    }

    @Test
    void an_estimate_in_hand_is_published_at_once() {
        List<BuildService.EtaModel> heard = new CopyOnWriteArrayList<>();
        BackgroundEta eta = BackgroundEta.of(model(7), heard::add);
        assertThat(heard).hasSize(1);
        assertThat(eta.close()).isPresent();
    }

    /** A deferred estimate prices nothing until the build has prepared its modules. */
    @Test
    void a_deferred_estimate_waits_for_begin_and_a_closed_one_never_starts() throws Exception {
        AtomicInteger priced = new AtomicInteger();
        List<BuildService.EtaModel> heard = new CopyOnWriteArrayList<>();
        BackgroundEta waiting = BackgroundEta.deferred(
                () -> {
                    priced.incrementAndGet();
                    return model(5);
                },
                heard::add);
        Thread.sleep(50);
        assertThat(priced).hasValue(0);
        waiting.begin();
        waiting.begin();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (heard.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(heard).hasSize(1);
        assertThat(priced).as("begin twice prices once").hasValue(1);

        BackgroundEta closed = BackgroundEta.deferred(
                () -> {
                    priced.incrementAndGet();
                    return model(6);
                },
                heard::add);
        closed.close();
        closed.begin();
        Thread.sleep(50);
        assertThat(priced).hasValue(1);
    }
}
