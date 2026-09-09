// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.plugin.PluginSlots;
import org.junit.jupiter.api.AfterEach;
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

    @AfterEach
    void reopenTheGate() {
        PluginSlots.configure(0);
    }

    @Test
    void an_unbounded_gate_leaves_the_plan_share_alone() {
        PluginSlots.configure(0);
        assertThat(TestWorkers.liveShare(1, 20)).isEqualTo(1);
        assertThat(TestWorkers.liveShare(4, 20)).isEqualTo(4);
    }

    @Test
    void a_drained_build_lets_the_last_suite_widen() {
        PluginSlots.configure(16);
        // Nothing else is forking: the suite may take what the gate would actually give it.
        assertThat(TestWorkers.liveShare(1, 20)).isEqualTo(16);
    }

    @Test
    void the_share_tracks_what_the_gate_still_has() {
        PluginSlots.configure(16);
        try (var held = PluginSlots.acquire()) {
            // One permit is out, so fifteen is what this suite could actually fork right now.
            assertThat(TestWorkers.liveShare(1, 20)).isEqualTo(15);
        }
    }

    @Test
    void the_share_never_narrows_below_the_plan() {
        PluginSlots.configure(2);
        assertThat(TestWorkers.liveShare(8, 20))
                .as("a suite planned wider than the free permits keeps its plan")
                .isEqualTo(8);
    }

    @Test
    void the_jobs_budget_is_the_ceiling() {
        PluginSlots.configure(32);
        assertThat(TestWorkers.liveShare(1, 4)).isEqualTo(4);
    }
}
