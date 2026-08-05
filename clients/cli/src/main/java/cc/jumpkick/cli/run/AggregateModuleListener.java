// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.cli.tui.CommandManager;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.BuildPlanView;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskStatus;
import java.time.Duration;
import java.util.List;

/**
 * Feeds one workspace module's step/tree events into the shared {@link AggregateContext}'s {@link
 * CommandManager}. Aggregate bar math is engine-owned — this listener does not update
 * {@link LiveProgress} or workspace percent.
 */
public final class AggregateModuleListener implements BuildPlanListener {

    private final AggregateContext agg;
    private final CommandManager cm;
    private final String module;
    private final List<Task> steps;

    /** Parallel-build output buffer; caller flushes when the module finishes. */
    private List<String> outBuffer;

    /** Route this module's output into {@code buffer} (parallel build); see field doc. */
    public void bufferOutputInto(List<String> buffer) {
        this.outBuffer = buffer;
    }

    public AggregateModuleListener(AggregateContext agg, String module, List<Task> steps) {
        this(agg, module, steps, 0);
    }

    /**
     * {@code slice} is ignored (engine owns weights); kept for call-site compatibility.
     */
    public AggregateModuleListener(AggregateContext agg, String module, List<Task> steps, long slice) {
        this.agg = agg;
        this.cm = agg.view();
        this.module = module;
        this.steps = steps;
    }

    @Override
    public void pipelineStart(BuildPlanView view) {
        cm.target(module);
        for (Task p : steps) {
            String display = p.label() != null && !p.label().isEmpty() ? p.label() : p.name();
            cm.addTaskLabeled(module, p.name(), display);
        }
    }

    @Override
    public void stepStart(String step, String group, int ticks) {
        cm.stepRunning(module, step, group == null ? "" : group);
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
    public void progress(String step, int delta, BuildPlanView view) {
        // Aggregate % comes from engine workspace-progress only.
    }

    @Override
    public void tickUpdate(String step, int delta, BuildPlanView view) {
        // Aggregate % comes from engine workspace-progress only.
    }

    @Override
    public void stepFinish(String step, String group, TaskStatus status, Duration duration) {
        // SKIPPED = cache hit / up-to-date — still a green terminal (matches BuildPlan.isOk).
        // Treating it as failure painted the live tree red with "Failed" while the build
        // succeeded.
        boolean ok = status == TaskStatus.SUCCESS || status == TaskStatus.SKIPPED;
        cm.stepDone(module, step, ok, group == null ? "" : group);
    }

    @Override
    public void pipelineFinish(BuildPlanResult result) {
        if (!result.success()) {
            agg.notifyErrors(result.errors());
        }
    }
}
