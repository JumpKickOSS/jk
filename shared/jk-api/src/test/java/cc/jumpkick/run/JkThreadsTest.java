// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 *: the CPU pool floors at 8 carriers (never capped below the machine's cores), and the
 * IO pool runs every task on a virtual thread — blocking waits (process forks, artifact gates)
 * must be cheap.
 */
class JkThreadsTest {

    @Test
    void cpu_pool_is_cores_with_a_floor_of_eight() {
        assertThat(JkThreads.CPU_THREADS)
                .isEqualTo(Math.max(Runtime.getRuntime().availableProcessors(), 8))
                .isGreaterThanOrEqualTo(8);
    }

    @Test
    void a_managed_wait_on_a_pool_thread_lets_the_pool_run_the_task_queued_behind_it() throws Exception {
        ForkJoinPool pool = new ForkJoinPool(1);
        Object monitor = new Object();
        CountDownLatch waiting = new CountDownLatch(1);
        try {
            pool.execute(() -> {
                synchronized (monitor) {
                    waiting.countDown();
                    try {
                        JkThreads.waitManaged(monitor, 20_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            assertThat(waiting.await(10, TimeUnit.SECONDS)).isTrue();

            CompletableFuture<String> queued = CompletableFuture.supplyAsync(() -> "ran", pool);

            assertThat(queued.get(10, TimeUnit.SECONDS)).isEqualTo("ran");
        } finally {
            synchronized (monitor) {
                monitor.notifyAll();
            }
            pool.shutdownNow();
        }
    }

    @Test
    void io_tasks_run_on_virtual_threads() throws Exception {
        AtomicBoolean virtual = new AtomicBoolean();
        JkThreads.io()
                .submit(() -> virtual.set(Thread.currentThread().isVirtual()))
                .get();
        assertThat(virtual).isTrue();
    }

    @Test
    void platform_runs_each_task_on_a_daemon_platform_thread_with_the_callers_context() throws Exception {
        var name = new AtomicReference<String>();
        var virtual = new AtomicBoolean(true);
        var daemon = new AtomicBoolean(false);
        JkThreads.platform()
                .submit(() -> {
                    virtual.set(Thread.currentThread().isVirtual());
                    daemon.set(Thread.currentThread().isDaemon());
                    name.set(Thread.currentThread().getName());
                })
                .get();
        assertThat(virtual).isFalse();
        assertThat(daemon).isTrue();
        assertThat(name.get()).startsWith("jk-run-");
    }
}
