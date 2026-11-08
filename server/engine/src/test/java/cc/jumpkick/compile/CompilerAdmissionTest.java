// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** A shared compiler worker admits a compile only while its heap has room for it. */
class CompilerAdmissionTest {

    private static final long GIB = 1L << 30;

    private static CompilerWorker.HeapReading heap(long usedAfterGc, long max, double gcFrac) {
        return new CompilerWorker.HeapReading(usedAfterGc, max, max, gcFrac, 0, 0);
    }

    @Test
    void an_idle_worker_takes_any_compile_however_large() {
        assertThat(CompilerPool.heapAdmits(0, 0, 40 * GIB, heap(1 * GIB, 12 * GIB, 0.5)))
                .isTrue();
    }

    @Test
    void compiles_are_admitted_while_their_estimates_fit_in_the_heap() {
        CompilerWorker.HeapReading calm = heap(2 * GIB, 10 * GIB, 0.01);
        assertThat(CompilerPool.heapAdmits(3, 5 * GIB, 1 * GIB, calm)).isTrue();
        assertThat(CompilerPool.heapAdmits(3, 6 * GIB, 2 * GIB, calm))
                .as("over 70% of the ceiling")
                .isFalse();
    }

    @Test
    void a_heap_under_pressure_admits_nothing_more() {
        assertThat(CompilerPool.heapAdmits(2, 1 * GIB, 1 * GIB, heap(8 * GIB, 10 * GIB, 0.01)))
                .as("left over 70% full after a collection")
                .isFalse();
        assertThat(CompilerPool.heapAdmits(2, 1 * GIB, 1 * GIB, heap(1 * GIB, 10 * GIB, 0.2)))
                .as("collecting a fifth of the time")
                .isFalse();
    }

    @Test
    void before_the_first_heap_report_credits_alone_decide() {
        assertThat(CompilerPool.heapAdmits(5, 50 * GIB, 50 * GIB, null)).isTrue();
    }
}
