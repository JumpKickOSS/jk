// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.tui.CommandManager;
import cc.jumpkick.run.PipelineResult;
import cc.jumpkick.run.PipelineView;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Workspace progress bar: preflight reservation + calibrated execute weights. Denominator stays
 * fixed after {@link AggregateContext#calibrate}; numerator grows across module boundaries.
 */
class AggregateProgressTest {

    private static final long PF = AggregateContext.PREFLIGHT_UNITS;

    @Test
    void calibrated_bar_keeps_a_fixed_denominator_and_advances_cumulatively() {
        CommandManager cm = newView();
        AggregateContext agg = new AggregateContext(cm);

        // Pre-scan summed two modules' estimates: 40 + 60 = 100 execute units.
        agg.calibrate(100);
        assertThat(agg.total()).isEqualTo(100);
        assertThat(barCount(cm)).isEqualTo(PF + " of " + (PF + 100));

        AggregateModuleListener a = new AggregateModuleListener(agg, "mod-a", List.of(), 40);
        a.pipelineStart(view(0, 40));
        assertThat(barCount(cm)).isEqualTo(PF + " of " + (PF + 100));
        a.progress("compile", 10, view(10, 40)); // 25% of A's slice → +10
        assertThat(barCount(cm)).isEqualTo((PF + 10) + " of " + (PF + 100));
        a.pipelineFinish(success());

        AggregateModuleListener b = new AggregateModuleListener(agg, "mod-b", List.of(), 60);
        b.pipelineStart(view(0, 60));
        assertThat(barCount(cm)).isEqualTo((PF + 40) + " of " + (PF + 100));
        b.progress("compile", 30, view(30, 60));
        assertThat(barCount(cm)).isEqualTo((PF + 70) + " of " + (PF + 100));
    }

    @Test
    void overrun_clamps_to_the_slice_and_never_grows_the_total() {
        CommandManager cm = newView();
        AggregateContext agg = new AggregateContext(cm);
        agg.calibrate(50);

        AggregateModuleListener m = new AggregateModuleListener(agg, "mod", List.of(), 50);
        m.pipelineStart(view(0, 50));
        m.progress("x", 80, view(80, 50));
        assertThat(barCount(cm)).isEqualTo((PF + 50) + " of " + (PF + 50));
    }

    @Test
    void module_boundary_does_not_backtrack_when_a_module_overruns() {
        CommandManager cm = newView();
        AggregateContext agg = new AggregateContext(cm);
        agg.calibrate(100);

        AggregateModuleListener a = new AggregateModuleListener(agg, "mod-a", List.of(), 40);
        a.pipelineStart(view(0, 40));
        a.progress("x", 70, view(70, 40));
        assertThat(barCount(cm)).isEqualTo((PF + 40) + " of " + (PF + 100));
        a.pipelineFinish(success());

        AggregateModuleListener b = new AggregateModuleListener(agg, "mod-b", List.of(), 60);
        b.pipelineStart(view(0, 60));
        assertThat(barCount(cm)).isEqualTo((PF + 40) + " of " + (PF + 100));
    }

    @Test
    void uncalibrated_falls_back_to_the_growing_per_module_total() {
        CommandManager cm = newView();
        AggregateContext agg = new AggregateContext(cm); // no calibrate()

        AggregateModuleListener a = new AggregateModuleListener(agg, "mod-a", List.of());
        a.pipelineStart(view(0, 40));
        assertThat(barCount(cm)).isEqualTo("0 of 40");
        a.progress("x", 10, view(10, 40));
        assertThat(barCount(cm)).isEqualTo("10 of 40");
        a.pipelineFinish(success());

        AggregateModuleListener b = new AggregateModuleListener(agg, "mod-b", List.of());
        b.pipelineStart(view(0, 60));
        assertThat(barCount(cm)).isEqualTo("40 of 100");
    }

    @Test
    void module_reweight_resizes_its_slice_and_the_aggregate_total() {
        CommandManager cm = newView();
        AggregateContext agg = new AggregateContext(cm);
        agg.calibrate(100);

        AggregateModuleListener a = new AggregateModuleListener(agg, "mod-a", List.of(), 40);
        a.pipelineStart(view(0, 40));
        assertThat(agg.total()).isEqualTo(100);

        a.progress("compile", 3, view(3, 3));
        assertThat(agg.total()).isEqualTo(63); // 100 − 37
        a.pipelineFinish(success());
        assertThat(barCount(cm)).isEqualTo((PF + 3) + " of " + (PF + 63));

        AggregateModuleListener b = new AggregateModuleListener(agg, "mod-b", List.of(), 60);
        b.pipelineStart(view(0, 60));
        b.progress("x", 60, view(60, 60));
        assertThat(barCount(cm)).isEqualTo((PF + 63) + " of " + (PF + 63));
    }

    @Test
    void preflight_advances_reserved_units_before_calibrate() {
        CommandManager cm = newView();
        AggregateContext agg = new AggregateContext(cm);
        agg.preflight("plan", 0, 10, "Preparing…");
        assertThat(cm.denominator()).isEqualTo(PF);
        assertThat(cm.numerator()).isGreaterThan(0);
        agg.preflight("plan", 10, 10, "done");
        assertThat(cm.numerator()).isEqualTo(PF);
        agg.calibrate(200);
        assertThat(barCount(cm)).isEqualTo(PF + " of " + (PF + 200));
    }

    private static CommandManager newView() {
        return CommandManager.pipeline(new PrintStream(new ByteArrayOutputStream()), "Build", false);
    }

    private static PipelineView view(long numerator, long denominator) {
        return new PipelineView("module", numerator, denominator, 1, 0, false);
    }

    private static PipelineResult success() {
        return new PipelineResult("module", true, Duration.ZERO, List.of(), List.of(), List.of(), false);
    }

    private static String barCount(CommandManager cm) {
        return cm.numerator() + " of " + cm.denominator();
    }
}
