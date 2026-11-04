// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.FileNotFoundException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
        CountDownLatch cancelled = new CountDownLatch(1);
        List<ArtifactFanOut.Fetch<String>> fetches = List.of(
                () -> {
                    throw new FileNotFoundException("gone");
                },
                () -> {
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

    private static boolean await(CountDownLatch latch) throws InterruptedException {
        return latch.await(5, TimeUnit.SECONDS);
    }
}
