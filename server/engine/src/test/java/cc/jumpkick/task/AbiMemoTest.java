// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Eviction ranking of the ABI memo: a snapshot of use ticks with a total order. */
class AbiMemoTest {

    /**
     * Eviction ranks one snapshot of the use ticks, oldest first: the extremes of the range and a
     * tick shared by several entries order totally, the tie in key order.
     */
    @Test
    void eviction_ranks_a_snapshot_of_use_ticks_and_breaks_a_tie_on_the_key() {
        Map<String, AbiMemo.Entry> entries = new HashMap<>();
        long[] ticks = {5, Long.MIN_VALUE, 5, 0, Long.MAX_VALUE, -1, 5, 3};
        for (int i = 0; i < ticks.length; i++) {
            AbiMemo.Entry e = new AbiMemo.Entry("t" + i);
            e.used = ticks[i];
            entries.put("k" + i, e);
        }
        assertThat(AbiMemo.victims(entries, 3))
                .extracting(Map.Entry::getKey)
                .containsExactly("k1", "k5", "k3", "k7", "k0");
        assertThat(AbiMemo.victims(entries, ticks.length)).isEmpty();
        assertThat(AbiMemo.victims(entries, ticks.length + 1)).isEmpty();
    }

    /**
     * Hits move the use ticks while an eviction sorts. The ranking reads each tick once, so no sort
     * ever sees a key change under it; a ranking on the live field is the one TimSort refuses.
     */
    @Test
    void hits_that_move_use_ticks_during_an_eviction_never_break_its_order() throws Exception {
        Map<String, AbiMemo.Entry> entries = new ConcurrentHashMap<>();
        List<AbiMemo.Entry> all = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            AbiMemo.Entry e = new AbiMemo.Entry("t");
            entries.put("k" + i, e);
            all.add(e);
        }
        AtomicBoolean stop = new AtomicBoolean();
        AtomicLong tick = new AtomicLong();
        List<Thread> hitters = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            Thread h = new Thread(() -> {
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                while (!stop.get()) all.get(rnd.nextInt(all.size())).used = tick.incrementAndGet();
            });
            h.setDaemon(true);
            h.start();
            hitters.add(h);
        }
        try {
            for (int round = 0; round < 40; round++) {
                assertThat(AbiMemo.victims(entries, 10_000)).hasSize(10_000);
            }
        } finally {
            stop.set(true);
            for (Thread h : hitters) h.join();
        }
    }
}
