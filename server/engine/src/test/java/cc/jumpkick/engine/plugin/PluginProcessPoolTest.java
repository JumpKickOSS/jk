// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** A step waiting on its worker does not hold its CPU-pool thread from the steps queued behind it. */
@EnabledOnOs({OS.LINUX, OS.MAC})
class PluginProcessPoolTest {

    @Test
    void a_pool_thread_waiting_on_its_worker_lets_the_pool_run_the_task_queued_behind_it() throws Exception {
        ForkJoinPool pool = new ForkJoinPool(1);
        CompletableFuture<Process> forked = new CompletableFuture<>();
        try {
            CompletableFuture<Integer> step = CompletableFuture.supplyAsync(
                    () -> {
                        try {
                            return PluginProcess.converseOnChannel(
                                            List.of("/bin/sh", "-c", "sleep 30"),
                                            WorkerEnv.strict(),
                                            (pb, heap) -> {
                                                Process p = pb.start();
                                                forked.complete(p);
                                                return p;
                                            },
                                            convo -> {},
                                            frame -> {},
                                            null)
                                    .code();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    },
                    pool);
            forked.get(10, TimeUnit.SECONDS);

            CompletableFuture<String> queued = CompletableFuture.supplyAsync(() -> "ran", pool);

            assertThat(queued.get(10, TimeUnit.SECONDS)).isEqualTo("ran");
            assertThat(step).isNotDone();
        } finally {
            if (forked.isDone()) forked.get().destroyForcibly();
            pool.shutdownNow();
        }
    }
}
