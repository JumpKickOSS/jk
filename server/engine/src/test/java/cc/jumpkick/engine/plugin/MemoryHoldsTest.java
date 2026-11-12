// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** A build on hold for memory (JK-VYJ4X): one hold per build however many of its forks wait. */
class MemoryHoldsTest {

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private final List<String> heard = new ArrayList<>();

    @AfterEach
    void detach() {
        MemoryHolds.listen(null);
        MemoryHolds.useClock(Clock.SYSTEM);
    }

    private void listen() {
        MemoryHolds.useClock(new Clock() {
            @Override
            public long millis() {
                return nanos.get() / 1_000_000L;
            }

            @Override
            public long nanos() {
                return nanos.get();
            }
        });
        MemoryHolds.listen(new MemoryHolds.Listener() {
            @Override
            public void held(long requestId, long needBytes, long freeBytes) {
                heard.add("held " + requestId + " " + needBytes + " " + freeBytes);
            }

            @Override
            public void resumed(long requestId, long heldMillis) {
                heard.add("resumed " + requestId + " " + heldMillis);
            }
        });
    }

    @Test
    void the_hold_starts_with_the_first_waiting_fork_and_ends_with_the_last() {
        listen();
        long rid = 9_100_001L;

        MemoryHolds.waiting(rid, 512, 128);
        MemoryHolds.waiting(rid, 256, 64);
        nanos.addAndGet(4_000_000_000L);
        assertThat(MemoryHolds.onHold(rid)).isTrue();
        assertThat(MemoryHolds.heldMillis(rid)).isEqualTo(4_000);
        assertThat(MemoryHolds.holdNeed(rid)).containsExactly(512, 128);

        MemoryHolds.done(rid);
        assertThat(MemoryHolds.onHold(rid)).as("one fork still waits").isTrue();
        nanos.addAndGet(1_000_000_000L);
        MemoryHolds.done(rid);

        assertThat(MemoryHolds.onHold(rid)).isFalse();
        assertThat(heard).containsExactly("held " + rid + " 512 128", "resumed " + rid + " 5000");
        assertThat(MemoryHolds.totalHeldMillis(rid)).isEqualTo(5_000);
    }

    @Test
    void a_fork_with_no_build_and_a_done_without_a_wait_change_nothing() {
        listen();
        MemoryHolds.waiting(null, 1, 1);
        MemoryHolds.done(9_100_002L);
        assertThat(heard).isEmpty();
        assertThat(MemoryHolds.totalHeldMillis(9_100_002L)).isZero();
    }
}
