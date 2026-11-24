// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.time.Clock;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

/** The heap-pressure trim: one at a time, at most once per cooldown, and armed on the JVM's heap pools. */
class HeapPressureTest {

    @Test
    void pressure_trims_once_per_cooldown_and_never_two_at_once() throws Exception {
        AtomicLong now = new AtomicLong(1_000_000);
        AtomicInteger trims = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        List<String> log = new CopyOnWriteArrayList<>();
        HeapPressure pressure = new HeapPressure(
                () -> {
                    trims.incrementAndGet();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "memos dropped: manifests 3";
                },
                () -> 200L << 20,
                log::add,
                now::get);

        assertThat(pressure.pressed()).isTrue();
        assertThat(pressure.pressed()).as("a trim is still running").isFalse();
        release.countDown();
        awaitLog(log, 1);
        assertThat(log).containsExactly("jk engine: heap pressure at 200 MiB: memos dropped: manifests 3");

        now.addAndGet(HeapPressure.COOLDOWN.toMillis() - 1);
        assertThat(pressure.pressed()).as("inside the cooldown").isFalse();
        now.addAndGet(1);
        assertThat(pressure.pressed()).isTrue();
        awaitLog(log, 2);
        assertThat(trims).hasValue(2);

        pressure.close();
        now.addAndGet(HeapPressure.COOLDOWN.toMillis());
        assertThat(pressure.pressed()).as("closed").isFalse();
    }

    @Test
    void start_arms_every_heap_pool_that_reports_its_usage_after_a_collection() {
        try (HeapPressure ignored = HeapPressure.start(line -> {})) {
            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                long max = pool.getUsage().getMax();
                if (pool.getType() != MemoryType.HEAP || !pool.isCollectionUsageThresholdSupported() || max <= 0)
                    continue;
                assertThat(pool.getCollectionUsageThreshold())
                        .as(pool.getName())
                        .isEqualTo((long) (max * HeapPressure.THRESHOLD));
            }
        }
    }

    private static void awaitLog(List<String> log, int lines) {
        long until = Clock.SYSTEM.nanos() + TimeUnit.SECONDS.toNanos(10);
        while (log.size() < lines && Clock.SYSTEM.nanos() < until)
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        assertThat(log).hasSize(lines);
    }
}
