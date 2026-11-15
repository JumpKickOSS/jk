// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** A worker's wait returns only once the work its exit started (lease, learned heap) is done. */
class SettledProcessTest {

    @Test
    void a_wait_returns_after_the_exit_work_not_before() throws Exception {
        Process exited = new ProcessBuilder("true").start();
        exited.waitFor();
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean recorded = new AtomicBoolean();
        CompletableFuture<Void> work = CompletableFuture.runAsync(() -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            recorded.set(true);
        });
        SettledProcess process = new SettledProcess(exited, work);

        assertThat(process.waitFor(200, TimeUnit.MILLISECONDS))
                .as("the process is gone but its exit work is not done")
                .isFalse();
        release.countDown();
        assertThat(process.waitFor()).isZero();
        assertThat(recorded)
                .as("what the exit records is there when the wait returns")
                .isTrue();
        assertThat(process.onExit().get(5, TimeUnit.SECONDS)).isSameAs(process);
    }

    @Test
    void exit_work_that_fails_still_ends_the_wait() throws Exception {
        Process exited = new ProcessBuilder("true").start();
        SettledProcess process =
                new SettledProcess(exited, CompletableFuture.failedFuture(new IllegalStateException("gc log")));
        assertThat(process.waitFor()).isZero();
        assertThat(process.waitFor(1, TimeUnit.SECONDS)).isTrue();
    }
}
