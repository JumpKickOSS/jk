// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.cli.tui.CommandManager;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.plugin.build.Phase;
import cc.jumpkick.run.PipelineListener;
import cc.jumpkick.run.PipelineResult;
import cc.jumpkick.run.PipelineView;
import cc.jumpkick.run.Step;
import cc.jumpkick.run.StepStatus;
import java.time.Duration;
import java.util.List;

/**
 * Feeds one workspace module's step/tree events into the shared {@link AggregateContext}'s {@link
 * CommandManager}. Aggregate bar math is engine-owned (JK-1120/1121) — this listener does not update
 * {@link LiveProgress} or workspace percent.
 */
public final class AggregateModuleListener implements PipelineListener {

    private final AggregateContext agg;
    private final CommandManager cm;
    private final String module;
    private final List<Step> steps;

    /** Parallel-build output buffer; caller flushes when the module finishes. */
    private java.util.List<String> outBuffer;

    /** Route this module's output into {@code buffer} (parallel build); see field doc. */
    public void bufferOutputInto(java.util.List<String> buffer) {
        this.outBuffer = buffer;
    }

    public AggregateModuleListener(AggregateContext agg, String module, List<Step> steps) {
        this(agg, module, steps, 0);
    }

    /**
     * {@code slice} is ignored (engine owns weights); kept for call-site compatibility.
     */
    public AggregateModuleListener(AggregateContext agg, String module, List<Step> steps, long slice) {
        this.agg = agg;
        this.cm = agg.view();
        this.module = module;
        this.steps = steps;
    }

    @Override
    public void pipelineStart(PipelineView view) {
        cm.target(module);
        for (Step p : steps) {
            String display = p.label() != null && !p.label().isEmpty() ? p.label() : p.name();
            cm.addStepLabeled(module, p.name(), display);
        }
    }

    @Override
    public void stepStart(String step, Phase phase, int ticks) {
        cm.stepRunning(module, step, phase == null ? "" : phase.wireName());
    }

    @Override
    public void label(String step, String label) {
        cm.stepMessage(module, step, label);
    }

    @Override
    public void output(String step, String line) {
        emit(line);
    }

    @Override
    public void warn(String step, String code, String message) {
        if (ConsoleSpec.isCompilerCode(code)) {
            emit(ConsoleSpec.compilerWarning(step, message));
        } else {
            emit(ProgressBarListener.renderDiagnostic(
                    Glyphs.BANG + " Warning",
                    cc.jumpkick.cli.theme.Theme.active().warning().bold(),
                    step,
                    code,
                    message));
        }
    }

    @Override
    public void error(String step, String code, String message) {
        String brief = message == null || message.isBlank() ? (code != null ? code : "Failed") : message;
        cm.attachPhaseError(module, step, "", brief);
        emit(ProgressBarListener.renderDiagnostic(
                Glyphs.CROSS + " Error",
                cc.jumpkick.cli.theme.Theme.active().error().bold(),
                step,
                code,
                message));
    }

    private void emit(String line) {
        if (outBuffer != null) {
            synchronized (outBuffer) {
                outBuffer.add(line);
            }
        } else {
            cm.writeAbove(line);
        }
    }

    @Override
    public void progress(String step, int delta, PipelineView view) {
        // Aggregate % comes from engine workspace-progress only.
    }

    @Override
    public void tickUpdate(String step, int delta, PipelineView view) {
        // Aggregate % comes from engine workspace-progress only.
    }

    @Override
    public void stepFinish(String step, Phase phase, StepStatus status, Duration duration) {
        cm.stepDone(module, step, status == StepStatus.SUCCESS, phase == null ? "" : phase.wireName());
    }

    @Override
    public void pipelineFinish(PipelineResult result) {
        if (!result.success()) {
            agg.notifyErrors(result.errors());
        }
    }
}
