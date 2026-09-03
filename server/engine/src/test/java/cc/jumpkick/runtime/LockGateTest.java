// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * One monitor per lock dir. {@link LockFlow} remains the sole acquisition owner, and
 * {@link WorkspaceLock} enters it rather than adding another workspace lifecycle lock.
 */
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
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                        try {
                            assertThat(start.await(30, TimeUnit.SECONDS)).isTrue();
                            synchronized (LockGate.monitorFor(tmp)) {
                                int now = inside.incrementAndGet();
                                maxInside.accumulateAndGet(now, Math::max);
                                // Hold the monitor: a second holder is only observable as overlap.
                                Thread.sleep(5);
                                inside.decrementAndGet();
                            }
                        } catch (Throwable e) {
                            failures.add(e); // nothing else would ever see it
                        } finally {
                            done.countDown();
                        }
                    })
                    .start();
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(failures).isEmpty();
        assertThat(maxInside.get()).isEqualTo(1);
    }
}
