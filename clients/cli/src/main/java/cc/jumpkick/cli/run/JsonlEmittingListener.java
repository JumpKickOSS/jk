// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.BuildPlanView;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.wire.protocol.EngineProtocol;
import java.time.Duration;

/**
 * Base for listeners that render plan events as {@link JsonlShape} lines (progress rider
 * applied). Subclasses supply only the sink via {@link #emit}. {@code immediate} marks semantic
 * boundaries (per-line flush); hot ticks ({@link JsonlShape#HOT_TYPES}) use the heartbeat.
 */
abstract class JsonlEmittingListener implements BuildPlanListener {

    /**
     * True when this listener owns the aggregate {@code progress} rider (single-plan stdout).
     * False for a member of a multi-module workspace run: the engine's {@code workspace-progress}
     * snapshot is the only aggregate truth there — plan-local fractions must not reach {@link
     * LiveProgress}.
     */
    private final boolean aggregateRider;

    JsonlEmittingListener(boolean aggregateRider) {
        this.aggregateRider = aggregateRider;
    }

    /** Sink for one rider-decorated JSONL line. */
    protected abstract void emit(String line, boolean immediate);

    @Override
    public void planStart(BuildPlanView v) {
        line(JsonlShape.planStart(v), EngineProtocol.BUILDPLAN_START);
    }

    @Override
    public void stepStart(String step, String group, int ticks) {
        line(JsonlShape.stepStart(step, wire(group), ticks), EngineProtocol.TASK_START);
    }

    @Override
    public void progress(String step, int delta, BuildPlanView v) {
        // Per-step numerator/denominator on the event; aggregate % via LiveProgress rider.
        if (aggregateRider) LiveProgress.get().update(v.numerator(), v.denominator());
        line(JsonlShape.progress(step, delta, v), EngineProtocol.PROGRESS);
    }

    @Override
    public void tickUpdate(String step, int delta, BuildPlanView v) {
        if (aggregateRider) LiveProgress.get().update(v.numerator(), v.denominator());
        line(JsonlShape.tickUpdate(step, delta, v), EngineProtocol.TICK_UPDATE);
    }

    @Override
    public void label(String step, String label) {
        line(JsonlShape.label(step, label), EngineProtocol.LABEL);
    }

    @Override
    public void output(String step, String out) {
        line(JsonlShape.output(step, out), EngineProtocol.OUTPUT);
    }

    @Override
    public void warn(String step, String code, String msg) {
        line(JsonlShape.warn(step, code, msg), EngineProtocol.WARN);
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
    public void error(String step, String code, String msg, TestFailureInfo failure) {
        line(JsonlShape.error(step, code, msg, failure), "error");
    }

    @Override
    public void stepFinish(String step, String group, TaskStatus s, Duration d, Duration waited) {
        line(JsonlShape.stepFinish(step, wire(group), s, d, waited), EngineProtocol.TASK_FINISH);
    }

    @Override
    public void planFinish(BuildPlanResult r) {
        line(JsonlShape.planFinish(r), EngineProtocol.BUILDPLAN_FINISH);
    }

    private void line(String raw, String type) {
        emit(JsonlShape.withProgress(raw), !JsonlShape.HOT_TYPES.contains(type));
    }

    private static String wire(String group) {
        return group == null ? "" : group;
    }
}
