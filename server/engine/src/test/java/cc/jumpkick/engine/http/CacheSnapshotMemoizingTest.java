// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.Await;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class CacheSnapshotMemoizingTest {

    private static final CacheSnapshot SNAP =
            new CacheSnapshot(1, 100, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1L << 30, 0, 0, 0, 0, 0, 0, 0);

    @Test
    void memoizing_single_flights_concurrent_gets() throws Exception {
        int n = 8;
        AtomicInteger walks = new AtomicInteger();
        CountDownLatch callersArrived = new CountDownLatch(n);
        List<Thread> callers = Collections.synchronizedList(new ArrayList<>());
        CacheSnapshot.Memoizing memo = CacheSnapshot.memoizing(
                () -> {
                    walks.incrementAndGet();
                    try {
                        // Coalescing is only exercised while the walk is in flight, so hold it until
                        // every caller is provably contending. "Reached get()" is not that: a caller
                        // counts down and is then briefly RUNNABLE before it parks on the memo's
                        // lock, and this used to cover that gap with a bare Thread.sleep(50) — a
                        // guess about scheduling on this machine. Thread state IS
                        // observable, so wait for the followers to be off the CPU instead.
                        assertThat(callersArrived.await(30, TimeUnit.SECONDS))
                                .as("all callers reach get() before the walk returns")
                                .isTrue();
                        Thread walker = Thread.currentThread();
                        Await.until(
                                Duration.ofSeconds(30),
                                () -> callers.stream()
                                        .filter(t -> t != walker)
                                        .allMatch(t -> t.getState() != Thread.State.RUNNABLE),
                                () -> "followers never parked on the memo lock: " + states(callers, walker));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return SNAP;
                },
                60_000);

        CyclicBarrier start = new CyclicBarrier(n);
        CountDownLatch done = new CountDownLatch(n);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < n; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    start.await(30, TimeUnit.SECONDS);
                    callers.add(Thread.currentThread());
                    callersArrived.countDown();
                    assertThat(memo.get()).isSameAs(SNAP);
                } catch (Throwable e) {
                    failures.add(e); // a throw here would die in the virtual thread unseen
                } finally {
                    done.countDown();
                }
            });
        }
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        assertThat(failures).isEmpty();
        assertThat(walks.get()).isEqualTo(1);
        memo.get();
        assertThat(walks.get()).isEqualTo(1);
    }

    /** Follower thread states, for the poll's timeout message. */
    private static String states(List<Thread> callers, Thread walker) {
        return callers.stream()
                .filter(t -> t != walker)
                .map(t -> t.getName() + "=" + t.getState())
                .collect(Collectors.joining(", "));
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
        // : toJson/toThinJson must read the captured Maven-local stats, not walk ~/.m2 on
        // the render / SSE connect path. A snapshot carrying known values must render exactly those.
        CacheSnapshot snap =
                new CacheSnapshot(1, 100, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1L << 30, 0, 42L, 424242L, 0, 0, 0, 0);
        assertThat(snap.mavenLocalBytes()).isEqualTo(424242L);
        assertThat(snap.mavenLocalCount()).isEqualTo(42L);
        assertThat(snap.toJson().toString()).contains("\"mavenLocalBytes\":424242", "\"mavenLocalCount\":42");
        assertThat(snap.toThinJson().toString()).contains("\"mavenLocalBytes\":424242");
    }
}
