// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RemainingWorkTest {

    @Test
    void residual_tracks_in_flight_and_complete() {
        Path a = Path.of("/a");
        Path b = Path.of("/b");
        List<ModuleWorkCost> costs =
                List.of(new ModuleWorkCost(a, Set.of(), 100, 0), new ModuleWorkCost(b, Set.of(), 100, 0));
        // Serial: R0 = 200 weight units. Seed R0ms = 200 so weightToMs = 1.
        RemainingWork rw = RemainingWork.seed(costs, 200, 1, true, true);
        assertThat(rw.R0()).isEqualTo(200);
        assertThat(rw.remaining()).isEqualTo(200);

        rw.moduleProgress(a, 0.5);
        assertThat(rw.remaining()).isEqualTo(150); // 50 + 100

        rw.moduleComplete(a);
        assertThat(rw.remaining()).isEqualTo(100);

        rw.moduleComplete(b);
        assertThat(rw.remaining()).isEqualTo(0);
    }

    @Test
    void history_floor_scale_applies_to_residual() {
        Path a = Path.of("/a");
        List<ModuleWorkCost> costs = List.of(new ModuleWorkCost(a, Set.of(), 100, 0));
        // Ideal schedule = 100; R0 = 200 → scale 2
        RemainingWork rw = RemainingWork.seed(costs, 200, 1, true, true);
        assertThat(rw.remaining()).isEqualTo(200);
        rw.moduleProgress(a, 0.5);
        assertThat(rw.remaining()).isEqualTo(100); // 50 weights * 2
    }

    @Test
    void parallel_schedule_is_not_serial_sum() {
        Path a = Path.of("/a");
        Path b = Path.of("/b");
        List<ModuleWorkCost> costs =
                List.of(new ModuleWorkCost(a, Set.of(), 100, 0), new ModuleWorkCost(b, Set.of(), 100, 0));
        // concurrency 2, independent: schedule = 100
        RemainingWork rw = RemainingWork.seed(costs, 100, 2, false, true);
        assertThat(rw.remaining()).isEqualTo(100);
        rw.moduleProgress(a, 0.5);
        rw.moduleProgress(b, 0.5);
        // residual 50+50 parallel → 50
        assertThat(rw.remaining()).isEqualTo(50);
    }

    @Test
    void history_only_seed_reports_r0_and_drains_on_completes() {
        // Zero-weight costs with R0 > 0 (history-only seed): remaining() must report R0 and
        // drain per module — not filter every residual to 0 and peg the clock bar.
        Path a = Path.of("/a");
        Path b = Path.of("/b");
        List<ModuleWorkCost> costs =
                List.of(new ModuleWorkCost(a, Set.of(), 0, 0), new ModuleWorkCost(b, Set.of(), 0, 0));
        RemainingWork rw = RemainingWork.seed(costs, 60_000, 1, true, true);
        assertThat(rw.R0()).isEqualTo(60_000);
        assertThat(rw.remaining()).isEqualTo(60_000);
        rw.moduleComplete(a);
        assertThat(rw.remaining()).isEqualTo(30_000);
        rw.moduleComplete(b);
        assertThat(rw.remaining()).isEqualTo(0);
    }

    @Test
    void history_only_seed_with_no_costs_holds_r0_until_finish() {
        RemainingWork rw = RemainingWork.seed(List.of(), 45_000, 1, true, true);
        assertThat(rw.R0()).isEqualTo(45_000);
        assertThat(rw.remaining()).isEqualTo(45_000);
    }
}
