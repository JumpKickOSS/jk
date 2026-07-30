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
    private List<String> outBuffer;

    /** Route this module's output into {@code buffer} (parallel build); see field doc. */
    public void bufferOutputInto(List<String> buffer) {
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
            emit(renderDiagnostic(
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
        emit(renderDiagnostic(
                Glyphs.CROSS + " Error",
                cc.jumpkick.cli.theme.Theme.active().error().bold(),
                step,
                code,
                message));
    }

    /** Styled {@code ! Warning [step/code]: Summary — detail} diagnostic line. */
    static String renderDiagnostic(
            String prefix, org.jline.utils.AttributedStyle prefixStyle, String step, String code, String message) {
        String summary = message == null ? "" : message;
        String detail = null;
        int sep = summary.indexOf(" — ");
        if (sep >= 0) {
            detail = capitalize(summary.substring(sep + 3));
            summary = summary.substring(0, sep);
        }
        // Only capitalize when the summary looks like a sentence start (first char
        // is a plain letter not followed by a hyphen — artifact names like
        // "jk-audit-runner" should stay lowercase).
        if (!summary.isEmpty()
                && Character.isLowerCase(summary.charAt(0))
                && (summary.length() < 2 || summary.charAt(1) != '-')) {
            summary = capitalize(summary);
        }
        var sb = new org.jline.utils.AttributedStringBuilder();
        sb.append(prefix, prefixStyle);
        // Omit [step/code] when code is absent — keeps simple informational
        // warnings (e.g. missing worker jars) uncluttered.
        if (code != null && !code.isBlank()) {
            sb.append(" [").append(step).append("/").append(code).append("]");
        }
        sb.append(": ");
        sb.append(summary, cc.jumpkick.cli.theme.Theme.active().focused());
        if (detail != null)
            sb.append(" — ").append(detail, cc.jumpkick.cli.theme.Theme.active().activeStep());
        return sb.toAnsi();
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s == null ? "" : s;
        char first = s.charAt(0);
        return Character.isLowerCase(first) ? Character.toUpperCase(first) + s.substring(1) : s;
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
        // SKIPPED = cache hit / up-to-date — still a green terminal (matches Pipeline.isOk).
        // Treating it as failure painted the live tree red with "Failed" while the build
        // succeeded (JK-1297).
        boolean ok = status == StepStatus.SUCCESS || status == StepStatus.SKIPPED;
        cm.stepDone(module, step, ok, phase == null ? "" : phase.wireName());
    }

    @Override
    public void pipelineFinish(PipelineResult result) {
        if (!result.success()) {
            agg.notifyErrors(result.errors());
        }
    }
}
