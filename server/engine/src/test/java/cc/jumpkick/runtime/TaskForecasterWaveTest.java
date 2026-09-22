// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** A cancelled forecast lane stops the other lanes of its wave. */
class TaskForecasterWaveTest {

    @Test
    void a_cancelled_lane_cancels_the_rest_of_the_wave() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean();
        Future<?> cancelled = CompletableFuture.failedFuture(new CancellationException("stop"));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> other = pool.submit(() -> {
                started.countDown();
                try {
                    Thread.sleep(30_000);
                    finished.set(true);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> TaskForecaster.joinWave(List.of(cancelled, other)))
                    .isInstanceOf(CancellationException.class);
            assertThat(other.isCancelled() || other.isDone()).isTrue();
            Thread.sleep(50);
            assertThat(finished).isFalse();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void a_forecaster_failure_is_not_swallowed() {
        Future<?> failed = CompletableFuture.failedFuture(new IllegalStateException("forecast broke"));
        assertThatThrownBy(() -> TaskForecaster.joinWave(List.of(failed)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("forecast broke");
    }
}
