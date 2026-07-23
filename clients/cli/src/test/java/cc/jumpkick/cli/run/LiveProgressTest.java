// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import static org.assertj.core.api.Assertions.assertThat;

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
    void aggregate_context_feeds_live_progress() {
        var cm = cc.jumpkick.cli.tui.CommandManager.pipeline(
                new java.io.PrintStream(new java.io.ByteArrayOutputStream()), "Build", false);
        var agg = new AggregateContext(cm);
        agg.preflight("plan", 5, 10, "Preparing…");
        assertThat(LiveProgress.get().percent()).isNotNull();
        assertThat(LiveProgress.get().percent()).isGreaterThan(0);
        agg.calibrate(100);
        // End of preflight = PREFLIGHT_UNITS / (PREFLIGHT_UNITS + 100)
        double expected = 100.0 * AggregateContext.PREFLIGHT_UNITS / (AggregateContext.PREFLIGHT_UNITS + 100);
        assertThat(LiveProgress.get().percent()).isEqualTo(Math.round(expected * 10.0) / 10.0);
    }
}
