// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkScheduleTest {

    @Test
    void serial_is_sum() {
        Path a = Path.of("/a");
        Path b = Path.of("/b");
        long s = WorkSchedule.schedule(
                List.of(new ModuleWorkCost(a, Set.of(), 10, 0), new ModuleWorkCost(b, Set.of(), 20, 0)), 4, true, true);
        assertThat(s).isEqualTo(30);
    }

    @Test
    void first_ready_admission_matches_list_order() {
        // a and b independent; a listed first — both run in parallel under conc=2
        Path a = Path.of("/a");
        Path b = Path.of("/b");
        long s = WorkSchedule.schedule(
                List.of(new ModuleWorkCost(a, Set.of(), 10, 0), new ModuleWorkCost(b, Set.of(), 100, 0)),
                2,
                false,
                true);
        assertThat(s).isEqualTo(100); // long pole
    }

    @Test
    void prereq_serializes() {
        Path a = Path.of("/a");
        Path b = Path.of("/b");
        long s = WorkSchedule.schedule(
                List.of(new ModuleWorkCost(a, Set.of(), 10, 0), new ModuleWorkCost(b, Set.of(a), 20, 0)),
                4,
                false,
                true);
        assertThat(s).isEqualTo(30);
    }

    @Test
    void dependents_start_at_the_upstream_artifact_point() {
        // a's artifact lands at 10 (weight 30, tests 20); b starts there and finishes at 30 —
        // overlapping a's suite. Wall is the long pole (30), not compile+tests+downstream (50).
        Path a = Path.of("/a");
        Path b = Path.of("/b");
        long s = WorkSchedule.schedule(
                List.of(new ModuleWorkCost(a, Set.of(), 30, 20), new ModuleWorkCost(b, Set.of(a), 20, 0)),
                4,
                false,
                true);
        assertThat(s).isEqualTo(30);
    }

    @Test
    void artifact_wake_does_not_free_the_slot() {
        // conc=1: even though a's artifact lands early, b cannot start until a's slot frees.
        Path a = Path.of("/a");
        Path b = Path.of("/b");
        long s = WorkSchedule.schedule(
                List.of(new ModuleWorkCost(a, Set.of(), 30, 20), new ModuleWorkCost(b, Set.of(a), 20, 0)),
                2,
                false,
                true);
        assertThat(s).isEqualTo(30);
        long serialish = WorkSchedule.listSchedule(
                List.of(new ModuleWorkCost(a, Set.of(), 30, 20), new ModuleWorkCost(b, Set.of(a), 20, 0)), 1);
        assertThat(serialish).isEqualTo(50);
    }

    @Test
    void serial_test_floor() {
        Path a = Path.of("/a");
        Path b = Path.of("/b");
        // Parallel modules weight 10 each → schedule 10; test weights 50+50 serial floor 100
        long s = WorkSchedule.schedule(
                List.of(new ModuleWorkCost(a, Set.of(), 10, 50), new ModuleWorkCost(b, Set.of(), 10, 50)),
                2,
                false,
                false);
        assertThat(s).isEqualTo(100);
    }

    /**
     * A module's own wall is its compile prefix plus its <em>longer</em> branch, because
     * {@code run-tests} is a plan leaf and the packaging tail requires only the jar — so
     * {@code BuildPlan} admits them together.
     *
     * <p>Shaped like {@code clients/cli} on the dogfood build: ~6 units of compile, a ~10 unit
     * suite, and a ~37 unit native-image. Pricing that as the sum (53) claims a serialization the
     * executor does not have; the truth is 6 + max(10, 37) = 43, which is what was measured
     * (module span 39.8 s).
     */
    @Test
    void a_module_with_tests_and_a_native_tail_is_not_priced_as_the_sum() {
        Path cli = Path.of("/cli");
        var cost = new ModuleWorkCost(cli, Set.of(), 53, 10, 37);

        assertThat(WorkSchedule.moduleWall(cost)).isEqualTo(43);
        assertThat(WorkSchedule.schedule(List.of(cost), 4, false, true)).isEqualTo(43);
        // -j1 bounds how many MODULES run at once; it does not re-serialize one module's branches.
        assertThat(WorkSchedule.schedule(List.of(cost), 1, false, true)).isEqualTo(43);
    }

    /**
     * The tail is optional. A module that reports none prices as its weight: compile prefix plus
     * the longer of tests versus a zero tail.
     */
    @Test
    void a_module_with_no_tail_prices_exactly_as_its_weight() {
        Path a = Path.of("/a");
        assertThat(WorkSchedule.moduleWall(new ModuleWorkCost(a, Set.of(), 30, 20)))
                .isEqualTo(30);
        assertThat(WorkSchedule.moduleWall(new ModuleWorkCost(a, Set.of(), 30, 20, 0)))
                .isEqualTo(30);
        assertThat(WorkSchedule.moduleWall(new ModuleWorkCost(a, Set.of(), 30, 0)))
                .isEqualTo(30);
    }

    /**
     * Dependents key on the artifact point, which is the compile prefix — before either branch.
     * A downstream module must not wait out an upstream's native-image, which it does not consume.
     */
    @Test
    void a_dependent_starts_at_the_prefix_not_after_the_upstream_tail() {
        Path up = Path.of("/up");
        Path down = Path.of("/down");
        // prefix 6, suite 10, tail 37 → artifact at 6, upstream itself ends at 43.
        long s = WorkSchedule.listSchedule(
                List.of(new ModuleWorkCost(up, Set.of(), 53, 10, 37), new ModuleWorkCost(down, Set.of(up), 5, 0, 0)),
                4);
        // down runs 6..11, entirely inside up's 0..43 — so the wall is up's own 43.
        assertThat(s).isEqualTo(43);
    }

    /** Degenerate inputs must not underflow the prefix into a negative wall. */
    @Test
    void branches_larger_than_the_total_weight_do_not_go_negative() {
        Path a = Path.of("/a");
        assertThat(WorkSchedule.moduleWall(new ModuleWorkCost(a, Set.of(), 5, 10, 10)))
                .isEqualTo(10);
        assertThat(WorkSchedule.moduleWall(new ModuleWorkCost(a, Set.of(), 0, 0, 0)))
                .isZero();
    }
}
