// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * JK-2212: the CPU pool floors at 8 carriers (never capped below the machine's cores), and the
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
    void io_tasks_run_on_virtual_threads() throws Exception {
        AtomicBoolean virtual = new AtomicBoolean();
        JkThreads.io()
                .submit(() -> virtual.set(Thread.currentThread().isVirtual()))
                .get();
        assertThat(virtual).isTrue();
    }
}
