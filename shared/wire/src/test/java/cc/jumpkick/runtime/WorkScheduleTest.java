// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

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
        // JK-2210/2211: a's artifact lands at 10 (weight 30, tests 20); b starts there and
        // finishes at 30 — overlapping a's suite. The old full-completion gate priced 50.
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
}
