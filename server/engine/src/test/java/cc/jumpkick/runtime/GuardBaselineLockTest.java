// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.baseline.Baseline;
import cc.jumpkick.guard.eval.LaneRun;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.guard.schema.Lane;
import cc.jumpkick.util.FileLocks;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A lane's baseline tightening waits for whoever holds the baseline lock, a freeze among them. */
class GuardBaselineLockTest {

    @Test
    void a_tightening_waits_for_the_baseline_lock(@TempDir Path root) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = Thread.ofPlatform().start(() -> {
            try {
                FileLocks.withLock(GuardsPresence.baselineLock(root), () -> {
                    held.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Void> merge = CompletableFuture.runAsync(() -> {
            try {
                PlannerGuards.mergeTightened(root, new LaneRun.Result(Lane.MODULE, List.of(), Baseline.EMPTY, 0));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        Thread.sleep(500);
        assertThat(merge).as("the merge waits while the lock is held").isNotDone();
        release.countDown();
        merge.get(30, TimeUnit.SECONDS);
        assertThat(merge).isCompleted();
        holder.join();
    }
}
