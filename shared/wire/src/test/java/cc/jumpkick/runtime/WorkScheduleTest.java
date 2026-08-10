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
                List.of(new ModuleWorkCost(a, Set.of(), 10, 0), new ModuleWorkCost(b, Set.of(), 20, 0)),
                4,
                true,
                true);
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
