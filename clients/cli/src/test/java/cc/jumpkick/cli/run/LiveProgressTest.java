// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.runtime.WorkspaceProgressTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LiveProgressTest {

    @AfterEach
    void clear() {
        LiveProgress.get().clear();
    }

    @Test
    void update_computes_percent_clamped() {
        LiveProgress p = LiveProgress.get();
        p.update(0, 100);
        assertThat(p.percent()).isEqualTo(0.0);
        p.update(50, 100);
        assertThat(p.percent()).isEqualTo(50.0);
        p.update(200, 100);
        assertThat(p.percent()).isEqualTo(100.0);
        p.update(-10, 100);
        assertThat(p.percent()).isEqualTo(0.0);
    }

    @Test
    void update_ignores_zero_denominator() {
        LiveProgress p = LiveProgress.get();
        p.update(10, 0);
        assertThat(p.percent()).isNull();
        p.update(1, 3);
        assertThat(p.percent()).isEqualTo(33.3);
        assertThat(p.jsonToken()).isEqualTo("33.3");
    }

    @Test
    void json_token_formats_whole_numbers() {
        LiveProgress p = LiveProgress.get();
        p.setPercent(100.0);
        assertThat(p.jsonToken()).isEqualTo("100");
        p.clear();
        assertThat(p.jsonToken()).isEqualTo("null");
    }

    @Test
    void apply_snapshot_uses_units_then_falls_back_to_percent() {
        LiveProgress p = LiveProgress.get();
        p.apply(new WorkspaceProgressTracker.Snapshot(50, 200, 25.0, "execute", 0, 4));
        assertThat(p.percent()).isEqualTo(25.0);
        // Percent-only snapshot (denominator 0) still lands.
        p.apply(new WorkspaceProgressTracker.Snapshot(0, 0, 60.0, "execute", 2, 4));
        assertThat(p.percent()).isEqualTo(60.0);
    }

    @Test
    void aggregate_context_applies_engine_snapshot_to_live_progress() {
        LiveProgress.get().clear(); // parallel forks / prior class must not leak static %
        var cm = cc.jumpkick.cli.tui.CommandManager.pipeline(
                new java.io.PrintStream(new java.io.ByteArrayOutputStream()), "Build", false);
        var agg = new AggregateContext(cm);
        // Preflight labels alone do not invent a percent (engine owns aggregate).
        agg.preflight("plan", 5, 10, "Preparing…");
        assertThat(LiveProgress.get().percent()).isNull();
        long pf = WorkspaceProgressTracker.PREFLIGHT_UNITS;
        agg.applySnapshot(new WorkspaceProgressTracker.Snapshot(pf, pf + 100, 50.0, "execute", 0, 2));
        assertThat(LiveProgress.get().percent()).isEqualTo(50.0);
    }
}

