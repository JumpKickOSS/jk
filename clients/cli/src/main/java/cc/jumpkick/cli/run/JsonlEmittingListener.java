// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.BuildPlanView;
import cc.jumpkick.run.TaskStatus;
import java.time.Duration;

/**
 * Base for listeners that render pipeline events as {@link JsonlShape} lines (progress rider
 * applied). Subclasses supply only the sink via {@link #emit}. {@code immediate} marks semantic
 * boundaries (per-line flush); hot ticks ({@link JsonlShape#HOT_TYPES}) use the heartbeat.
 */
abstract class JsonlEmittingListener implements BuildPlanListener {

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
    public void pipelineStart(BuildPlanView v) {
        line(JsonlShape.pipelineStart(v), "buildplan-start");
    }

    @Override
    public void stepStart(String step, String group, int ticks) {
        line(JsonlShape.stepStart(step, wire(group), ticks), "task-start");
    }

    @Override
    public void progress(String step, int delta, BuildPlanView v) {
        // Per-step numerator/denominator on the event; aggregate % via LiveProgress rider.
        if (aggregateRider) LiveProgress.get().update(v.numerator(), v.denominator());
        line(JsonlShape.progress(step, delta, v), "progress");
    }

    @Override
    public void tickUpdate(String step, int delta, BuildPlanView v) {
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
    public void stepFinish(String step, String group, TaskStatus s, Duration d) {
        line(JsonlShape.stepFinish(step, wire(group), s, d), "task-finish");
    }

    @Override
    public void pipelineFinish(BuildPlanResult r) {
        line(JsonlShape.pipelineFinish(r), "buildplan-finish");
    }

    private void line(String raw, String type) {
        emit(JsonlShape.withProgress(raw), !JsonlShape.HOT_TYPES.contains(type));
    }

    private static String wire(String group) {
        return group == null ? "" : group;
    }
}
