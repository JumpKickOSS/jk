// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.plugin.build.Phase;
import cc.jumpkick.run.PipelineListener;
import cc.jumpkick.run.PipelineResult;
import cc.jumpkick.run.PipelineView;
import cc.jumpkick.run.StepStatus;
import java.time.Duration;

/**
 * Base for listeners that render pipeline events as {@link JsonlShape} lines (progress rider
 * applied). Subclasses supply only the sink via {@link #emit}. {@code immediate} marks semantic
 * boundaries (per-line flush); hot ticks ({@link JsonlShape#HOT_TYPES}) use the heartbeat.
 */
abstract class JsonlEmittingListener implements PipelineListener {

    /**
     * True when this listener owns the aggregate {@code progress} rider (single-pipeline stdout).
     * False for a member of a multi-module workspace run: the engine's {@code workspace-progress}
     * snapshot is the only aggregate truth there — pipeline-local fractions must not reach {@link
     * LiveProgress}.
     */
    private final boolean aggregateRider;

    JsonlEmittingListener(boolean aggregateRider) {
        this.aggregateRider = aggregateRider;
    }

    /** Sink for one rider-decorated JSONL line. */
    protected abstract void emit(String line, boolean immediate);

    @Override
    public void pipelineStart(PipelineView v) {
        line(JsonlShape.pipelineStart(v), "pipeline-start");
    }

    @Override
    public void stepStart(String step, Phase phase, int ticks) {
        line(JsonlShape.stepStart(step, wire(phase), ticks), "step-start");
    }

    @Override
    public void progress(String step, int delta, PipelineView v) {
        // Per-step numerator/denominator on the event; aggregate % via LiveProgress rider.
        if (aggregateRider) LiveProgress.get().update(v.numerator(), v.denominator());
        line(JsonlShape.progress(step, delta, v), "progress");
    }

    @Override
    public void tickUpdate(String step, int delta, PipelineView v) {
        if (aggregateRider) LiveProgress.get().update(v.numerator(), v.denominator());
        line(JsonlShape.tickUpdate(step, delta, v), "tick-update");
    }

    @Override
    public void label(String step, String label) {
        line(JsonlShape.label(step, label), "label");
    }

    @Override
    public void output(String step, String out) {
        line(JsonlShape.output(step, out), "output");
    }

    @Override
    public void warn(String step, String code, String msg) {
        line(JsonlShape.warn(step, code, msg), "warn");
    }

    @Override
    public void error(String step, String code, String msg) {
        line(JsonlShape.error(step, code, msg), "error");
    }

    @Override
    public void error(String step, String code, String msg, String test, String exClass) {
        line(JsonlShape.error(step, code, msg, test, exClass), "error");
    }

    @Override
    public void stepFinish(String step, Phase phase, StepStatus s, Duration d) {
        line(JsonlShape.stepFinish(step, wire(phase), s, d), "step-finish");
    }

    @Override
    public void pipelineFinish(PipelineResult r) {
        line(JsonlShape.pipelineFinish(r), "pipeline-finish");
    }

    private void line(String raw, String type) {
        emit(JsonlShape.withProgress(raw), !JsonlShape.HOT_TYPES.contains(type));
    }

    private static String wire(Phase phase) {
        return phase == null ? "" : phase.wireName();
    }
}
