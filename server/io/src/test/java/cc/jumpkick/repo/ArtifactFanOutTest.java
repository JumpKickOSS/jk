// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ArtifactFanOutTest {

    @Test
    void answers_come_back_in_the_order_asked() throws Exception {
        List<ArtifactFanOut.Fetch<String>> fetches = List.of(
                () -> {
                    Thread.sleep(50);
                    return "slow first";
                },
                () -> "fast second");

        assertThat(ArtifactFanOut.concurrently(fetches)).containsExactly("slow first", "fast second");
    }

    @Test
    void a_failure_cancels_the_rest_instead_of_waiting_for_them() throws Exception {
        CountDownLatch never = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        // The failure waits for the long fetch to be running: one cancelled before it starts never
        // runs at all, and the point here is the one in flight.
        List<ArtifactFanOut.Fetch<String>> fetches = List.of(
                () -> {
                    running.await();
                    throw new FileNotFoundException("gone");
                },
                () -> {
                    running.countDown();
                    try {
                        never.await();
                        return "never";
                    } catch (InterruptedException e) {
                        cancelled.countDown();
                        throw e;
                    }
                });

        assertThatThrownBy(() -> ArtifactFanOut.concurrently(fetches)).isInstanceOf(FileNotFoundException.class);
        assertThat(await(cancelled)).as("the long fetch was interrupted").isTrue();
    }

    @Test
    void an_interrupted_caller_cancels_every_fetch_and_returns_at_once() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> caller = pool.submit(() -> ArtifactFanOut.concurrently(List.<ArtifactFanOut.Fetch<String>>of(
                    () -> {
                        started.countDown();
                        try {
                            new CountDownLatch(1).await();
                            return "never";
                        } catch (InterruptedException e) {
                            cancelled.countDown();
                            throw e;
                        }
                    },
                    () -> {
                        new CountDownLatch(1).await();
                        return "never";
                    })));
            assertThat(await(started)).isTrue();
            caller.cancel(true);

            assertThat(await(cancelled))
                    .as("the fetch in flight was interrupted")
                    .isTrue();
        }
    }

    @Test
    void fetches_cancelled_before_they_ran_do_not_hold_the_call_for_its_grace() {
        int n = 200;
        List<AtomicBoolean> claimed = new ArrayList<>();
        List<Future<?>> pending = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            claimed.add(new AtomicBoolean());
            pending.add(new CompletableFuture<>()); // never scheduled
        }
        CountDownLatch stopped = new CountDownLatch(n);
        long start = System.nanoTime();

        ArtifactFanOut.cancelAndWait(pending, claimed, stopped);

        assertThat(stopped.getCount()).isZero();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
                .as("not the %s grace", ArtifactFanOut.CANCEL_GRACE)
                .isLessThan(1_000);
    }

    private static boolean await(CountDownLatch latch) throws InterruptedException {
        return latch.await(5, TimeUnit.SECONDS);
    }
}
