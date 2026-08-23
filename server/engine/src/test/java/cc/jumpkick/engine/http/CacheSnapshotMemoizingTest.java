// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CacheSnapshotMemoizingTest {

    private static final CacheSnapshot SNAP =
            new CacheSnapshot(1, 100, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 20L << 30, 1L << 30, 0);

    @Test
    void memoizing_single_flights_concurrent_gets() throws Exception {
        AtomicInteger walks = new AtomicInteger();
        CacheSnapshot.Memoizing memo = CacheSnapshot.memoizing(
                () -> {
                    walks.incrementAndGet();
                    try {
                        Thread.sleep(80);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return SNAP;
                },
                60_000);

        int n = 8;
        CyclicBarrier start = new CyclicBarrier(n);
        CountDownLatch done = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    start.await(2, TimeUnit.SECONDS);
                    assertThat(memo.get()).isSameAs(SNAP);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            });
        }
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(walks.get()).isEqualTo(1);
        memo.get();
        assertThat(walks.get()).isEqualTo(1);
    }

    @Test
    void invalidate_forces_a_fresh_walk() {
        AtomicInteger walks = new AtomicInteger();
        CacheSnapshot.Memoizing memo = CacheSnapshot.memoizing(
                () -> {
                    walks.incrementAndGet();
                    return SNAP;
                },
                60_000);

        memo.get();
        memo.get();
        assertThat(walks.get()).isEqualTo(1);
        memo.invalidate();
        memo.get();
        assertThat(walks.get()).isEqualTo(2);
    }

    @Test
    void render_reads_captured_maven_stats_instead_of_walking() {
        // JK-2293: toJson/toThinJson must read the captured Maven-local stats, not walk ~/.m2 on
        // the render / SSE connect path. A snapshot carrying known values must render exactly those.
        CacheSnapshot snap =
                new CacheSnapshot(1, 100, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 20L << 30, 1L << 30, 0, 42L, 424242L);
        assertThat(snap.mavenLocalBytes()).isEqualTo(424242L);
        assertThat(snap.mavenLocalCount()).isEqualTo(42L);
        assertThat(snap.toJson().toString()).contains("\"mavenLocalBytes\":424242", "\"mavenLocalCount\":42");
        assertThat(snap.toThinJson().toString()).contains("\"mavenLocalBytes\":424242");
    }
}
