// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SingleFlightTest {

    private final SingleFlight<String> flights = new SingleFlight<>(t -> t instanceof AbortedHere);

    static final class AbortedHere extends IOException {}

    /** Until the second caller is parked on the leader's fetch, not merely submitted. */
    private void awaitWaiter() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (flights.waiters("k") < 1) {
            assertThat(System.nanoTime()).as("the second caller never waited").isLessThan(deadline);
            Thread.onSpinWait();
        }
    }

    @Test
    void callers_of_one_key_share_one_fetch() throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch leading = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> first = pool.submit(() -> flights.run("k", () -> {
                fetches.incrementAndGet();
                leading.countDown();
                release.await();
                return "body";
            }));
            assertThat(leading.await(5, TimeUnit.SECONDS)).isTrue();
            Future<String> second = pool.submit(() -> flights.run("k", () -> {
                fetches.incrementAndGet();
                return "again";
            }));
            awaitWaiter();
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("body");
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("body");
        }
        assertThat(fetches).hasValue(1);
        assertThat(flights.inFlight()).isZero();
    }

    @Test
    void a_finished_fetch_is_not_remembered() throws Exception {
        assertThat(flights.run("k", () -> "one")).isEqualTo("one");
        assertThat(flights.run("k", () -> "two")).isEqualTo("two");
    }

    @Test
    void a_waiter_shares_a_real_failure() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch leading = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> first = pool.submit(() -> flights.run("k", () -> {
                leading.countDown();
                release.await();
                throw new FileNotFoundException("missing");
            }));
            assertThat(leading.await(5, TimeUnit.SECONDS)).isTrue();
            Future<String> second = pool.submit(() -> flights.run("k", () -> "never asked"));
            awaitWaiter();
            release.countDown();
            assertThatThrownBy(() -> first.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(FileNotFoundException.class);
            assertThatThrownBy(() -> second.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(FileNotFoundException.class);
        }
    }

    @Test
    void a_waiter_fetches_itself_when_the_leader_gave_up_for_its_own_reasons() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch leading = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> first = pool.submit(() -> flights.run("k", () -> {
                leading.countDown();
                release.await();
                throw new AbortedHere();
            }));
            assertThat(leading.await(5, TimeUnit.SECONDS)).isTrue();
            Future<String> second = pool.submit(() -> flights.run("k", () -> "mine"));
            awaitWaiter();
            release.countDown();
            assertThatThrownBy(() -> first.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(AbortedHere.class);
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("mine");
        }
    }
}
