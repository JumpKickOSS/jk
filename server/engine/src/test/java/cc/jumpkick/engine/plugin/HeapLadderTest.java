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
        assertThat(HeapLadder.ranOut(List.of(2 * GIB, top), budget, 0))
                .endsWith(", the most the worker budget can lease");
    }

    /**
     * A ceiling bounds the heap, not the lease: on a 31.8 GiB host whose test JVMs may reach a
     * quarter of it, the ladder once stopped at 7.1 GiB, the heap whose lease fits 7.95 GiB.
     */
    @Test
    void the_ceiling_ends_the_ladder_at_the_ceiling_and_says_so() {
        long ceiling = 34_144_990_003L / 4;
        long budget = 24 * GIB;
        List<Long> ranOut = new ArrayList<>(List.of(2700 * MIB));
        for (Long next = HeapLadder.next(ranOut, budget, ceiling);
                next != null;
                next = HeapLadder.next(ranOut, budget, ceiling)) {
            ranOut.add(next);
        }
        assertThat(ranOut).containsExactly(2700 * MIB, 5400 * MIB, ceiling / MIB * MIB);
        assertThat(HeapLadder.ranOut(ranOut, budget, ceiling))
                .isEqualTo("ran out of heap at 2.6 GiB, 5.3 GiB and 7.9 GiB, a quarter of this host's RAM");
    }

    @Test
    void the_budget_is_named_when_it_ends_the_ladder_below_the_ceiling() {
        long budget = WorkerLeases.jvmLease(6 * GIB);
        long ceiling = 8 * GIB;
        List<Long> ranOut = new ArrayList<>(List.of(4 * GIB));
        Long top = Objects.requireNonNull(HeapLadder.next(ranOut, budget, ceiling));
        assertThat(top).isGreaterThan(4 * GIB).isLessThanOrEqualTo(6 * GIB);
        ranOut.add(top);
        assertThat(HeapLadder.next(ranOut, budget, ceiling)).isNull();
        assertThat(HeapLadder.ranOut(ranOut, budget, ceiling)).endsWith(", the most the worker budget can lease");
    }

    @Test
    void spent_retries_name_no_bound() {
        assertThat(HeapLadder.ranOut(List.of(GIB, 2 * GIB, 4 * GIB, 8 * GIB), 64 * GIB, 16 * GIB))
                .isEqualTo("ran out of heap at 1.0 GiB, 2.0 GiB, 4.0 GiB and 8.0 GiB");
    }

    @Test
    void no_heap_means_no_ladder() {
        assertThat(HeapLadder.next(List.of(), 64 * GIB)).isNull();
        assertThat(HeapLadder.next(List.of(0L), 64 * GIB)).isNull();
    }
}
