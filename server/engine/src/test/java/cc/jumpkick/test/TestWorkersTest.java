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

    @Test
    void outside_a_job_scope_the_plan_share_stands() {
        assertThat(TestWorkers.liveShare(1, 20, 0)).isEqualTo(1);
        assertThat(TestWorkers.liveShare(4, 20, 0)).isEqualTo(4);
        // A standalone jk test / jk build has no job scope; its auto share must stay auto, or the
        // launcher takes the 1 as a pin and runs the whole suite on one JVM.
        assertThat(TestWorkers.liveShare(0, 20, 0)).isEqualTo(0);
    }

    @Test
    void the_last_module_standing_gets_the_machine() {
        // One unit running: the graph has narrowed to this suite, so the whole budget is its share.
        assertThat(TestWorkers.liveShare(1, 20, 1)).isEqualTo(20);
    }

    @Test
    void a_wide_build_keeps_the_plan_share() {
        // Thirteen units in flight is what the plan divided by; the late reading agrees with it.
        assertThat(TestWorkers.liveShare(1, 20, 13)).isEqualTo(1);
        assertThat(TestWorkers.liveShare(1, 20, 38)).isEqualTo(1);
    }

    @Test
    void the_share_widens_as_the_graph_narrows() {
        assertThat(TestWorkers.liveShare(1, 20, 10)).isEqualTo(2);
        assertThat(TestWorkers.liveShare(1, 20, 4)).isEqualTo(5);
        assertThat(TestWorkers.liveShare(1, 20, 2)).isEqualTo(10);
    }

    @Test
    void the_share_never_narrows_below_the_plan() {
        assertThat(TestWorkers.liveShare(8, 20, 38))
                .as("a suite planned wider than the live share keeps its plan")
                .isEqualTo(8);
    }

    @Test
    void the_jobs_budget_is_the_ceiling() {
        assertThat(TestWorkers.liveShare(1, 4, 1)).isEqualTo(4);
    }
}
