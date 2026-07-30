// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.cli.tui.CommandManager;
import cc.jumpkick.plugin.build.Phase;
import cc.jumpkick.run.PipelineListener;
import cc.jumpkick.run.PipelineResult;
import cc.jumpkick.run.PipelineView;
import cc.jumpkick.run.Step;
import cc.jumpkick.run.StepStatus;
import java.io.PrintStream;
import java.time.Duration;
import java.util.List;

/**
 * Console listener for pipeline-oriented commands ({@code jk build} and friends): drives a {@link
 * CommandManager} in pipeline mode — a spinner header, an aggregate progress bar, and a dynamic step
 * list. On completion the live region is replaced by a {@code ✓}/{@code ✗} result line built from
 * the {@link ConsoleSpec} mappers.
 *
 * <p>When constructed with a {@code null} {@link ConsoleSpec} the listener uses {@code command} as the
 * display name and calls {@link CommandManager#dismiss()} on completion (the caller owns the result
 * line). This is used by {@link PipelineConsole#run(cc.jumpkick.run.Pipeline, PipelineConsole.Mode,
 * java.nio.file.Path)} to drive the CommandManager spinner for simple pipelines.
 *
 * <p>All steps of this pipeline are attributed to a single {@code module} (the project's {@code
 * group:artifact}). Workspace aggregation across modules feeds one shared {@link CommandManager}
 * from several pipelines; that path is built on the same component.
 */
public final class CommandManagerListener implements PipelineListener {

    private final PrintStream out;
    /** May be {@code null} — use {@link #command} as the display name and dismiss on completion. */
    private final ConsoleSpec spec;

    private final String command;
    private final String module;
    private final List<Step> steps;
    private final boolean animate;
    /**
     * False for one member of a multi-module workspace run: engine {@code workspace-progress} is the
     * only aggregate truth — pipeline-local fractions must not reach {@link LiveProgress} (JK-1121).
     */
    private final boolean aggregateRider;

    private CommandManager cm;
    private CommandManager.OutputScope capture;

    public CommandManagerListener(PrintStream out, ConsoleSpec spec, String module, List<Step> steps, boolean animate) {
        this(out, spec, module, steps, animate, true);
    }

    public CommandManagerListener(
            PrintStream out,
            ConsoleSpec spec,
            String module,
            List<Step> steps,
            boolean animate,
            boolean aggregateRider) {
        this.out = out;
        this.spec = spec;
        this.command = spec != null ? spec.command() : module;
        this.module = module;
        this.steps = steps;
        this.animate = animate;
        this.aggregateRider = aggregateRider;
    }

    /**
     * No-spec constructor: uses {@code command} as the spinner display name and calls {@link
     * CommandManager#dismiss()} on completion so the caller can print its own result line.
     */
    public CommandManagerListener(PrintStream out, String command, String module, List<Step> steps, boolean animate) {
        this.out = out;
        this.spec = null;
        this.command = command;
        this.module = module;
        this.steps = steps;
        this.animate = animate;
        this.aggregateRider = true;
    }

    @Override
    public void pipelineStart(PipelineView view) {
        cm = CommandManager.pipeline(out, command, animate);
        cm.target(module);
        for (Step p : steps) {
            cm.addStepLabeled(module, p.name(), display(p));
        }
        cm.progress(view.numerator(), view.denominator());
        if (aggregateRider) LiveProgress.get().update(view.numerator(), view.denominator());
        // Route step/process output above the pinned region for the pipeline's lifetime.
        capture = cm.captureOutput();
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
        cm.writeAbove(line);
    }

    @Override
    public void error(String step, String code, String message) {
        String brief = message == null || message.isBlank() ? (code != null ? code : "Failed") : message;
        cm.attachPhaseError(module, step, "", brief);
        cm.writeAbove((code != null && !code.isEmpty() ? code + ": " : "") + (message != null ? message : ""));
    }

    @Override
    public void progress(String step, int delta, PipelineView view) {
        cm.progress(view.numerator(), view.denominator());
        if (aggregateRider) LiveProgress.get().update(view.numerator(), view.denominator());
    }

    @Override
    public void tickUpdate(String step, int delta, PipelineView view) {
        cm.progress(view.numerator(), view.denominator());
        if (aggregateRider) LiveProgress.get().update(view.numerator(), view.denominator());
    }

    @Override
    public void stepFinish(String step, Phase phase, StepStatus status, Duration duration) {
        // SKIPPED = cache hit / up-to-date — green terminal, same as SUCCESS (JK-1297).
        boolean ok = status == StepStatus.SUCCESS || status == StepStatus.SKIPPED;
        cm.stepDone(module, step, ok, phase == null ? "" : phase.wireName());
    }

    @Override
    public void pipelineFinish(PipelineResult result) {
        // Restore the real streams before settling so the result line isn't
        // itself routed back above the (closing) region.
        if (capture != null) capture.close();
        if (cm == null) cm = CommandManager.pipeline(out, command, animate);
        // No-spec path: the caller owns the result line — just clean up the live region.
        if (spec == null) {
            cm.dismiss();
            return;
        }
        // Exec commands hand off to a subprocess — the build duration is meaningless there.
        String suffix = spec.exec() ? "" : " " + ConsoleSpec.took(result.duration());
        // All diagnostics print ABOVE the result line (which stays last) — warnings
        // first, then errors nearest the line — so the failure route reads just like
        // the success route and the outcome is the last thing on screen.
        List<String> above = new java.util.ArrayList<>();
        for (PipelineResult.Diagnostic d : result.warnings()) {
            above.add(ConsoleSpec.renderWarning(d));
        }
        for (PipelineResult.Diagnostic d : result.errors()) {
            above.add(ConsoleSpec.renderError(d));
        }
        // A soft failure overrides an otherwise-successful result: the pipeline itself is fine, but the
        // command discovered afterward that it can't proceed (e.g. jk run found no runnable entry
        // point). Rendered as the red failure chip with the caller's exact sentence — no "Failed to
        // <command>" derivation — so a genuine build failure (below) keeps its normal phrasing.
        String soft = spec.softFailure() != null ? spec.softFailure().apply(result) : null;
        if (soft != null) {
            cm.finishPipelineFailureCustom(soft + suffix, above);
        } else if (result.success()) {
            String tail = spec.onSuccess().apply(result) + suffix;
            if (spec.chip() && spec.exec()) cm.finishPipelineExec(tail, above);
            else if (spec.chip()) cm.finishPipelineSuccess(tail, above);
            else cm.finishSuccess(tail, above);
        } else {
            if (spec.chip()) cm.finishPipelineFailure(spec.onFailure().apply(result) + suffix, above);
            else cm.finishFailure(spec.onFailure().apply(result) + suffix, above);
        }
    }

    private static String display(Step p) {
        return p.label() != null && !p.label().isEmpty() ? p.label() : p.name();
    }
}
