// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** JK-1356: one monitor per lock dir — concurrent re-locks of one checkout serialize. */
class LockGateTest {

    @Test
    void same_dir_resolves_to_the_same_monitor(@TempDir Path tmp) {
        assertThat(LockGate.monitorFor(tmp)).isSameAs(LockGate.monitorFor(tmp.resolve("x/..")));
        assertThat(LockGate.monitorFor(tmp)).isNotSameAs(LockGate.monitorFor(tmp.resolve("other")));
    }

    @Test
    void concurrent_holders_of_one_dir_never_overlap(@TempDir Path tmp) throws Exception {
        int threads = 8;
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxInside = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                        try {
                            start.await();
                            synchronized (LockGate.monitorFor(tmp)) {
                                int now = inside.incrementAndGet();
                                maxInside.accumulateAndGet(now, Math::max);
                                Thread.sleep(5);
                                inside.decrementAndGet();
                            }
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    })
                    .start();
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(maxInside.get()).isEqualTo(1);
    }
}
