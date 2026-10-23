// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.BuildPlanView;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.run.TestClassResult;
import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.wire.protocol.EngineProtocol;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

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

    /** The workspace member these events belong to, as {@code group:name}; null for a lone plan. */
    private final @Nullable String module;

    /** Each step's latest label: what a finished step says it did, "up to date" or "cache hit …" included. */
    private final Map<String, String> labels = new ConcurrentHashMap<>();

    JsonlEmittingListener(boolean aggregateRider, @Nullable String module) {
        this.aggregateRider = aggregateRider;
        this.module = module;
    }

    /** Sink for one rider-decorated JSONL line. */
    protected abstract void emit(String line, boolean immediate);

    @Override
    public void planStart(BuildPlanView v) {
        line(JsonlShape.planStart(v), EngineProtocol.BUILDPLAN_START);
    }

    @Override
    public void stepStart(String step, @Nullable String group, int ticks) {
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
        labels.put(step, label);
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
    public void error(String step, String code, String msg, @Nullable String test, String exClass) {
        line(JsonlShape.error(step, code, msg, test, exClass), "error");
    }

    @Override
    public void error(String step, String code, String msg, @Nullable TestFailureInfo failure) {
        line(JsonlShape.error(step, code, msg, failure, JsonlShape.STREAM_TEXTS), "error");
    }

    @Override
    public void testClass(String step, TestClassResult result) {
        line(JsonlShape.classFinish(step, result), EngineProtocol.CLASS_FINISH);
    }

    @Override
    public void stepFinish(String step, @Nullable String group, TaskStatus s, Duration d, Duration waited) {
        String finish = JsonlShape.stepFinish(step, wire(group), s, d, waited);
        String said = labels.remove(step);
        if (said != null) finish = Jsonl.append(finish, "\"label\":" + Jsonl.quote(said));
        line(finish, EngineProtocol.TASK_FINISH);
    }

    @Override
    public void planFinish(BuildPlanResult r) {
        line(JsonlShape.planFinish(r), EngineProtocol.BUILDPLAN_FINISH);
    }

    private void line(String raw, String type) {
        String tagged = module == null ? raw : Jsonl.append(raw, "\"module\":" + Jsonl.quote(module));
        emit(JsonlShape.withProgress(tagged), !JsonlShape.HOT_TYPES.contains(type));
    }

    private static String wire(@Nullable String group) {
        return group == null ? "" : group;
    }
}
