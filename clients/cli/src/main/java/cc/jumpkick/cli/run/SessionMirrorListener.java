// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.plugin.build.Phase;
import cc.jumpkick.run.PipelineListener;
import cc.jumpkick.run.PipelineResult;
import cc.jumpkick.run.PipelineView;
import cc.jumpkick.run.StepStatus;
import java.time.Duration;

/**
 * Mirrors pipeline events into the active {@link CliSessionTranscript} as {@link JsonlShape} lines
 * (JK-1116). Used when stdout is not already JSONL (TTY/verbose) so {@code details.jsonl} still
 * carries the live stream. {@link JsonlListener} dual-writes itself; this listener is skipped in
 * JSON mode to avoid duplicate lines.
 */
public final class SessionMirrorListener implements PipelineListener {

    private final CliSessionTranscript session;

    public SessionMirrorListener(CliSessionTranscript session) {
        this.session = session;
    }

    @Override
    public void pipelineStart(PipelineView v) {
        LiveProgress.get().update(v.numerator(), v.denominator());
        session.append(JsonlShape.pipelineStart(v), true);
    }

    @Override
    public void stepStart(String step, Phase phase, int ticks) {
        session.append(JsonlShape.stepStart(step, phase == null ? "" : phase.wireName(), ticks), true);
    }

    @Override
    public void progress(String step, int delta, PipelineView v) {
        LiveProgress.get().update(v.numerator(), v.denominator());
        session.append(JsonlShape.progress(step, delta, v), false);
    }

    @Override
    public void tickUpdate(String step, int delta, PipelineView v) {
        LiveProgress.get().update(v.numerator(), v.denominator());
        session.append(JsonlShape.tickUpdate(step, delta, v), false);
    }

    @Override
    public void label(String step, String label) {
        session.append(JsonlShape.label(step, label), false);
    }

    @Override
    public void output(String step, String line) {
        session.append(JsonlShape.output(step, line), false);
    }

    @Override
    public void warn(String step, String code, String msg) {
        session.append(JsonlShape.warn(step, code, msg), true);
    }

    @Override
    public void error(String step, String code, String msg) {
        session.append(JsonlShape.error(step, code, msg), true);
    }

    @Override
    public void error(String step, String code, String msg, String test, String exClass) {
        session.append(JsonlShape.error(step, code, msg, test, exClass), true);
    }

    @Override
    public void stepFinish(String step, Phase phase, StepStatus s, Duration d) {
        session.append(JsonlShape.stepFinish(step, phase == null ? "" : phase.wireName(), s, d), true);
    }

    @Override
    public void pipelineFinish(PipelineResult r) {
        session.append(JsonlShape.pipelineFinish(r), true);
    }
}
