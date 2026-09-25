// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TestWorkersTest {

    @Test
    void no_history_is_a_modest_default_capped_by_share_and_class_count() {
        // Unknown class count does not cap; one class cannot use a second JVM.
        assertThat(TestWorkers.uncapped(24, new long[0])).isEqualTo(2);
        assertThat(TestWorkers.uncapped(24, new long[1])).isEqualTo(1);
        assertThat(TestWorkers.uncapped(24, new long[17])).isEqualTo(2);
        assertThat(TestWorkers.uncapped(1, new long[17])).isEqualTo(1);
        assertThat(TestWorkers.resolve(0, 1, 16)).isEqualTo(1);
    }

    /**
     * spring-petclinic, one module: the longest class is ~18.5 s and the other nineteen sum to ~6.5 s,
     * so {@code ceil(Σ / max)} is 2 even when the share is every core.
     */
    @Test
    void a_long_tail_suite_forks_two_workers() {
        long[] petclinic = {18_501, 4_094, 544, 494, 317, 208, 208, 173, 113, 98, 84, 84, 22, 13, 10, 4, 4, 2, 2, 1};
        assertThat(TestWorkers.uncapped(24, petclinic)).isEqualTo(2);
        Map<String, Long> recorded = new LinkedHashMap<>();
        for (int i = 0; i < petclinic.length; i++) recorded.put("org.example.T" + i, petclinic[i]);
        assertThat(TestWorkers.autoCount(24, recorded, List.of(), 0)).isEqualTo(TestWorkers.clampByHeap(2));
    }

    @Test
    void even_classes_shard_up_to_the_share() {
        long[] even = new long[8];
        Arrays.fill(even, 1_000);
        assertThat(TestWorkers.uncapped(24, even)).isEqualTo(8);
        assertThat(TestWorkers.uncapped(3, even)).isEqualTo(3);
    }

    @Test
    void a_class_without_a_wall_counts_as_the_median() {
        // Known 10s and 30s → median 20s. The unrecorded class takes 20s: sum 60s, max 30s → 2.
        Map<String, Long> recorded = Map.of("a.Slow", 30_000L, "a.Fast", 10_000L);
        assertThat(TestWorkers.uncapped(8, walls(recorded, "a.Slow", "a.Fast", "a.New")))
                .isEqualTo(2);
        // A name only the ledger would have sanitized still matches.
        Map<String, Long> nested = Map.of("a.Outer_Inner", 5_000L);
        assertThat(TestWorkers.autoCount(4, nested, List.of("a.Outer$Inner"), 0))
                .isEqualTo(TestWorkers.clampByHeap(1));
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
    void resolve_auto_without_history_stays_within_the_modest_default() {
        int w = TestWorkers.resolve(0, 10, 4);
        assertThat(w).isBetween(1, 2);
        assertThat(TestWorkers.resolve(0, 1, 16)).isEqualTo(1);
    }

    private static long[] walls(Map<String, Long> recorded, String... names) {
        long[] out = new long[names.length];
        for (int i = 0; i < names.length; i++) {
            Long v = recorded.get(names[i]);
            out[i] = v == null ? 0 : v;
        }
        return out;
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
