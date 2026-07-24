// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.plugin.build.Phase;
import cc.jumpkick.run.PipelineListener;
import cc.jumpkick.run.PipelineResult;
import cc.jumpkick.run.PipelineView;
import cc.jumpkick.run.StepStatus;
import java.io.PrintStream;
import java.time.Duration;

/**
 * Emit one JSON object per event to stdout. Triggered by {@code --output json} or {@code jsonl}
 * (identical). Consumed by agents, CI, and tooling. Wire format: {@link JsonlShape} — see {@code
 * docs/machine-output.md}. Each line carries the aggregate {@code progress} rider (JK-1117) and is
 * dual-written to the active {@link CliSessionTranscript} when present (JK-1116).
 */
public final class JsonlListener implements PipelineListener {

    private final PrintStream out;
    /**
     * False for one member of a multi-module workspace run: the engine's {@code workspace-progress}
     * snapshot is the only aggregate truth there — pipeline-local fractions must not reach {@link
     * LiveProgress} (JK-1121).
     */
    private final boolean aggregateRider;

    public JsonlListener(PrintStream out) {
        this(out, true);
    }

    public JsonlListener(PrintStream out, boolean aggregateRider) {
        this.out = out;
        this.aggregateRider = aggregateRider;
    }

    @Override
    public void pipelineStart(PipelineView v) {
        emit(JsonlShape.pipelineStart(v), true);
    }

    @Override
    public void stepStart(String step, Phase phase, int ticks) {
        emit(JsonlShape.stepStart(step, phase == null ? "" : phase.wireName(), ticks), true);
    }

    @Override
    public void progress(String step, int delta, PipelineView v) {
        // Per-step numerator/denominator on the event; aggregate % via LiveProgress rider.
        if (aggregateRider) LiveProgress.get().update(v.numerator(), v.denominator());
        emit(JsonlShape.progress(step, delta, v), false);
    }

    @Override
    public void tickUpdate(String step, int delta, PipelineView v) {
        if (aggregateRider) LiveProgress.get().update(v.numerator(), v.denominator());
        emit(JsonlShape.tickUpdate(step, delta, v), false);
    }

    @Override
    public void label(String step, String label) {
        emit(JsonlShape.label(step, label), false);
    }

    @Override
    public void output(String step, String line) {
        emit(JsonlShape.output(step, line), false);
    }

    @Override
    public void warn(String step, String code, String msg) {
        emit(JsonlShape.warn(step, code, msg), true);
    }

    @Override
    public void error(String step, String code, String msg) {
        emit(JsonlShape.error(step, code, msg), true);
    }

    @Override
    public void error(String step, String code, String msg, String test, String exClass) {
        emit(JsonlShape.error(step, code, msg, test, exClass), true);
    }

    @Override
    public void stepFinish(String step, Phase phase, StepStatus s, Duration d) {
        emit(JsonlShape.stepFinish(step, phase == null ? "" : phase.wireName(), s, d), true);
    }

    @Override
    public void pipelineFinish(PipelineResult r) {
        emit(JsonlShape.pipelineFinish(r), true);
    }

    private void emit(String line, boolean immediateSessionFlush) {
        String decorated = JsonlShape.withProgress(line);
        synchronized (out) {
            out.println(decorated);
            out.flush();
        }
        CliSessionTranscript session = CliSessionTranscript.active();
        if (session != null) session.appendRaw(decorated, immediateSessionFlush);
    }
}
