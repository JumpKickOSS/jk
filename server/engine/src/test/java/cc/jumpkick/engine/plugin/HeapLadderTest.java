// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/** The retry heaps after a worker runs out of its own, and the failure that names them. */
class HeapLadderTest {

    private static final long MIB = 1L << 20;
    private static final long GIB = 1L << 30;

    @Test
    void each_rung_doubles_the_last_for_three_retries() {
        long budget = 64 * GIB;
        List<Long> ranOut = new ArrayList<>(List.of(128 * MIB));
        List<Long> tried = new ArrayList<>();
        for (Long next = HeapLadder.next(ranOut, budget); next != null; next = HeapLadder.next(ranOut, budget)) {
            tried.add(next);
            ranOut.add(next);
        }
        assertThat(tried).containsExactly(256 * MIB, 512 * MIB, 1024 * MIB);
        assertThat(HeapLadder.ranOut(ranOut)).isEqualTo("ran out of heap at 128 MiB, 256 MiB, 512 MiB and 1.0 GiB");
    }

    @Test
    void the_budget_ends_the_ladder_after_one_rung_at_the_most_it_can_lease() {
        long budget = WorkerLeases.jvmLease(3 * GIB);
        long top = Objects.requireNonNull(HeapLadder.next(List.of(2 * GIB), budget));
        assertThat(top).isGreaterThan(2 * GIB).isLessThanOrEqualTo(3 * GIB);
        assertThat(WorkerLeases.jvmLease(top)).isLessThanOrEqualTo(budget);
        assertThat(HeapLadder.next(List.of(2 * GIB, top), budget))
                .as("nothing larger fits")
                .isNull();
        assertThat(HeapLadder.ranOut(List.of(2 * GIB, top))).endsWith(", the most this host can give one worker");
    }

    @Test
    void no_heap_means_no_ladder() {
        assertThat(HeapLadder.next(List.of(), 64 * GIB)).isNull();
        assertThat(HeapLadder.next(List.of(0L), 64 * GIB)).isNull();
    }
}
