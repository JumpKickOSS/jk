// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** A build is on hold for memory only while fully stalled, and one hold per stall however its forks wait. */
class MemoryHoldsTest {

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private final List<String> heard = new ArrayList<>();

    @AfterEach
    void detach() {
        MemoryHolds.listen(null);
        MemoryHolds.useClock(Clock.SYSTEM);
        MemoryHolds.settleWith(null);
    }

    private MemoryHolds.Listener listen() {
        MemoryHolds.settleWith(() -> {});
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
        MemoryHolds.Listener l = new MemoryHolds.Listener() {
            @Override
            public void held(long requestId, long needBytes, long freeBytes) {
                heard.add("held " + requestId + " " + needBytes + " " + freeBytes);
            }

            @Override
            public void resumed(long requestId, long heldMillis) {
                heard.add("resumed " + requestId + " " + heldMillis);
            }
        };
        MemoryHolds.listen(l);
        return l;
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
        nanos.addAndGet(MemoryHolds.MERGE_NANOS);
        MemoryHolds.settle();

        assertThat(MemoryHolds.onHold(rid)).isFalse();
        assertThat(heard).containsExactly("held " + rid + " 512 128", "resumed " + rid + " 5000");
        assertThat(MemoryHolds.totalHeldMillis(rid)).isEqualTo(5_000);
    }

    @Test
    void a_stopped_engine_detaches_its_listener_and_leaves_a_successors() {
        List<String> stopped = new ArrayList<>();
        MemoryHolds.Listener first = new MemoryHolds.Listener() {
            @Override
            public void held(long requestId, long needBytes, long freeBytes) {
                stopped.add("held " + requestId);
            }

            @Override
            public void resumed(long requestId, long heldMillis) {}
        };
        MemoryHolds.listen(first);
        MemoryHolds.Listener successor = listen();
        MemoryHolds.detach(first);
        long rid = 9_100_009L;

        MemoryHolds.waiting(rid, 512, 128);

        assertThat(heard).as("the successor's listener stays").containsExactly("held " + rid + " 512 128");
        MemoryHolds.done(rid);
        nanos.addAndGet(MemoryHolds.MERGE_NANOS);
        MemoryHolds.settle();
        heard.clear();

        MemoryHolds.detach(successor);
        MemoryHolds.waiting(rid + 1, 512, 128);
        assertThat(heard).as("the engine's own listener detached").isEmpty();
        assertThat(stopped).isEmpty();
        MemoryHolds.done(rid + 1);
        nanos.addAndGet(MemoryHolds.MERGE_NANOS);
        MemoryHolds.settle();
    }

    @Test
    void a_build_with_a_fork_running_is_not_on_hold_until_the_last_one_exits() {
        listen();
        long rid = 9_100_003L;

        MemoryHolds.started(rid);
        MemoryHolds.waiting(rid, 512, 128);
        nanos.addAndGet(10_000_000_000L);
        assertThat(MemoryHolds.onHold(rid)).as("a fork still works").isFalse();

        MemoryHolds.ended(rid);
        assertThat(MemoryHolds.onHold(rid)).as("fully stalled").isTrue();
        assertThat(heard).containsExactly("held " + rid + " 512 128");
        MemoryHolds.started(rid);
        MemoryHolds.done(rid);
        nanos.addAndGet(MemoryHolds.MERGE_NANOS);
        MemoryHolds.settle();
        MemoryHolds.ended(rid);
        assertThat(heard).last().isEqualTo("resumed " + rid + " 0");
    }

    @Test
    void two_holds_less_than_three_seconds_apart_are_one_hold() {
        listen();
        long rid = 9_100_004L;

        MemoryHolds.waiting(rid, 512, 128);
        nanos.addAndGet(1_000_000_000L);
        MemoryHolds.done(rid);
        nanos.addAndGet(2_000_000_000L);
        MemoryHolds.settle();
        assertThat(MemoryHolds.onHold(rid)).as("inside the merge window").isTrue();
        MemoryHolds.waiting(rid, 512, 128);
        nanos.addAndGet(1_000_000_000L);
        MemoryHolds.done(rid);
        nanos.addAndGet(MemoryHolds.MERGE_NANOS);
        MemoryHolds.settle();

        assertThat(heard).containsExactly("held " + rid + " 512 128", "resumed " + rid + " 4000");

        MemoryHolds.waiting(rid, 512, 128);
        nanos.addAndGet(1_000_000_000L);
        MemoryHolds.done(rid);
        nanos.addAndGet(MemoryHolds.MERGE_NANOS);
        MemoryHolds.settle();
        assertThat(heard).as("three seconds apart is a new hold").hasSize(4);
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
