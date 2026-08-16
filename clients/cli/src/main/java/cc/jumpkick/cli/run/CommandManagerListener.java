// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.cli.tui.JkManager;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.BuildPlanView;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskStatus;
import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Console listener for plan-oriented commands ({@code jk build} and friends): drives a {@link
 * JkManager} in plan mode — a spinner header, an aggregate progress bar, and a dynamic step
 * list. On completion the live region is replaced by a {@code ✓}/{@code ✗} result line built from
 * the {@link ConsoleSpec} mappers.
 *
 * <p>When constructed with a {@code null} {@link ConsoleSpec} the listener uses {@code command} as the
 * display name and calls {@link JkManager#dismiss} on completion (the caller owns the result
 * line). This is used by {@link BuildPlanConsole#run(cc.jumpkick.run.BuildPlan, BuildPlanConsole.Mode,
 * java.nio.file.Path)} to drive the JkManager spinner for simple plans.
 *
 * <p>All steps of this plan are attributed to a single {@code module} (the project's {@code
 * group:artifact}). Workspace aggregation across modules feeds one shared {@link JkManager}
 * from several plans; that path is built on the same component.
 */
public final class CommandManagerListener implements BuildPlanListener {

    private final PrintStream out;
    /** May be {@code null} — use {@link #command} as the display name and dismiss on completion. */
    private final ConsoleSpec spec;

    private final String command;
    private final String module;
    private final List<Task> steps;
    private final boolean animate;
    /**
     * False for one member of a multi-module workspace run: engine {@code workspace-progress} is the
     * only aggregate truth — plan-local fractions must not reach {@link LiveProgress}.
     */
    private final boolean aggregateRider;

    private JkManager cm;
    private JkManager.OutputScope capture;

    public CommandManagerListener(PrintStream out, ConsoleSpec spec, String module, List<Task> steps, boolean animate) {
        this(out, spec, module, steps, animate, true);
    }

    public CommandManagerListener(
            PrintStream out,
            ConsoleSpec spec,
            String module,
            List<Task> steps,
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
     * JkManager#dismiss} on completion so the caller can print its own result line.
     */
    public CommandManagerListener(PrintStream out, String command, String module, List<Task> steps, boolean animate) {
        this.out = out;
        this.spec = null;
        this.command = command;
        this.module = module;
        this.steps = steps;
        this.animate = animate;
        this.aggregateRider = true;
    }

    @Override
    public void planStart(BuildPlanView view) {
        cm = JkManager.plan(out, command, animate);
        cm.target(module);
        for (Task p : steps) {
            cm.addTaskLabeled(module, p.name(), display(p));
        }
        cm.progress(view.numerator(), view.denominator());
        if (aggregateRider) LiveProgress.get().update(view.numerator(), view.denominator());
        // Route step/process output above the pinned region for the plan's lifetime.
        capture = cm.captureOutput();
    }

    @Override
    public void stepStart(String step, String group, int ticks) {
        cm.stepRunning(module, step, group == null ? "" : group);
    }

    @Override
    public void label(String step, String label) {
        cm.stepMessage(module, step, label);
    }

    /** True while buffering a {@link TestFailureHighlight} block from run-tests output. */
    private boolean inTestFailure;

    private final TestFailureHighlight.Stream testFailStream = new TestFailureHighlight.Stream();

    @Override
    public void output(String step, String line) {
        if (TestFailureHighlight.isHeader(line)) {
            // A second header must not reset() away an un-flushed first block.
            flushBufferedFailure();
            inTestFailure = true;
            testFailStream.reset();
            testFailStream.line(line);
            return;
        }
        if (inTestFailure) {
            testFailStream.line(line);
            return;
        }
        cm.writeAbove(StackTraceHighlight.line(line));
    }

    /** Paint any buffered failure block now — already-received lines must not be dropped. */
    private void flushBufferedFailure() {
        if (!inTestFailure) return;
        for (String painted : testFailStream.finish()) {
            if (painted != null) cm.writeAbove(painted);
        }
        inTestFailure = false;
        testFailStream.reset();
    }

    @Override
    public void error(String step, String code, String message) {
        String brief = message == null || message.isBlank() ? (code != null ? code : "Failed") : message;
        cm.attachPhaseError(module, step, "", brief);
        // Styled "Test Failure" block already covers per-test failures; keep JSON diagnostics only.
        if ("test-failure".equals(code)) return;
        String report = ConsoleSpec.renderError(step, code, message, module);
        if (report != null && !report.isEmpty()) cm.writeAbove(report);
        // Non-test diagnostic: treat as tool/worker failure — force-open the process-output pane.
        if (JkManager.forceShowOnStepFailure(step, null)) {
            cm.showProcessFailureOutput();
        }
    }

    @Override
    public void progress(String step, int delta, BuildPlanView view) {
        cm.progress(view.numerator(), view.denominator());
        if (aggregateRider) LiveProgress.get().update(view.numerator(), view.denominator());
    }

    @Override
    public void tickUpdate(String step, int delta, BuildPlanView view) {
        cm.progress(view.numerator(), view.denominator());
        if (aggregateRider) LiveProgress.get().update(view.numerator(), view.denominator());
    }

    @Override
    public void stepFinish(String step, String group, TaskStatus status, Duration duration) {
        flushBufferedFailure();
        // SKIPPED = cache hit / up-to-date — green terminal, same as SUCCESS.
        boolean ok = status == TaskStatus.SUCCESS || status == TaskStatus.SKIPPED;
        cm.stepDone(module, step, ok, group == null ? "" : group);
        // Failed tool/worker (e.g. native-image): force-open the process-output peek. Test-runner
        // failures keep curated chrome and do not force-open.
        if (!ok && JkManager.forceShowOnStepFailure(step, group)) {
            cm.showProcessFailureOutput();
        }
    }

    @Override
    public void planFinish(BuildPlanResult result) {
        // A cancel/disconnect between a block's lines and stepFinish must still show what
        // already arrived.
        flushBufferedFailure();
        if (cm != null) cm.finishModule(module, result.success());
        // Restore the real streams before settling so the result line isn't
        // itself routed back above the (closing) region.
        if (capture != null) capture.close();
        if (cm == null) cm = JkManager.plan(out, command, animate);
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
        List<String> above = new ArrayList<>();
        for (BuildPlanResult.Diagnostic d : result.warnings()) {
            above.add(ConsoleSpec.renderWarning(d));
        }
        for (BuildPlanResult.Diagnostic d : result.errors()) {
            String rendered = ConsoleSpec.renderError(d);
            if (rendered != null && !rendered.isEmpty()) above.add(rendered);
        }
        // A soft failure overrides an otherwise-successful result: the plan itself is fine, but the
        // command discovered afterward that it can't proceed (e.g. jk run found no runnable entry
        // point). Rendered as the red failure chip with the caller's exact sentence — no "Failed to
        // <command>" derivation — so a genuine build failure (below) keeps its normal phrasing.
        // Only probe softFailure when the build succeeded: on a failed plan, execPlan
        // / entry-point scans can emit red diagnostics that flash under the live region before the
        // real failure settle.
        String soft = null;
        if (result.success() && spec.softFailure() != null) {
            soft = spec.softFailure().apply(result);
        }
        if (soft != null) {
            cm.finishBuildPlanFailureCustom(soft + suffix, above);
        } else if (result.success()) {
            String tail = spec.onSuccess().apply(result) + suffix;
            if (spec.chip() && spec.exec()) cm.finishBuildPlanExec(tail, above);
            else if (spec.chip()) cm.finishBuildPlanSuccess(tail, above);
            else cm.finishSuccess(tail, above);
        } else {
            if (spec.chip()) cm.finishBuildPlanFailure(spec.onFailure().apply(result) + suffix, above);
            else cm.finishFailure(spec.onFailure().apply(result) + suffix, above);
        }
    }

    private static String display(Task p) {
        return p.label() != null && !p.label().isEmpty() ? p.label() : p.name();
    }
}
