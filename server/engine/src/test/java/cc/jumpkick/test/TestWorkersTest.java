// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TestWorkersTest {

    @Test
    void auto_is_one_for_empty_or_single_class() {
        assertThat(TestWorkers.auto(8, 0)).isEqualTo(1);
        assertThat(TestWorkers.auto(8, 1)).isEqualTo(1);
    }

    @Test
    void auto_is_min_jobs_and_class_count() {
        assertThat(TestWorkers.auto(8, 3)).isEqualTo(3);
        assertThat(TestWorkers.auto(2, 100)).isEqualTo(2);
        assertThat(TestWorkers.auto(1, 50)).isEqualTo(1);
    }

    @Test
    void resolve_explicit_caps_by_class_count() {
        // Heap clamp may shrink further on tiny hosts; floor is at least 1 and at most requested.
        int w = TestWorkers.resolve(4, 2, 8);
        assertThat(w).isBetween(1, 2);
        int serial = TestWorkers.resolve(1, 100, 8);
        assertThat(serial).isEqualTo(1);
    }

    @Test
    void resolve_auto_uses_jobs_and_classes() {
        int w = TestWorkers.resolve(0, 10, 4);
        assertThat(w).isBetween(1, 4);
        assertThat(TestWorkers.resolve(0, 1, 16)).isEqualTo(1);
    }
}
