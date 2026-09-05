// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.cli.tui.JkManager;
import cc.jumpkick.cli.tui.OutputPane;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.BuildPlanView;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskStatus;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Feeds one workspace module's step/tree events into the shared {@link AggregateContext}'s {@link
 * JkManager}. Aggregate bar math is engine-owned — this listener does not update
 * {@link LiveProgress} or workspace percent.
 */
public final class AggregateModuleListener implements BuildPlanListener {

    private final AggregateContext agg;
    private final JkManager cm;
    private final String module;
    private final List<Task> steps;

    /** Parallel-build output buffer; caller flushes when the module finishes. */
    private List<String> outBuffer;

    /** True while painting a {@link TestFailureHighlight} block from run-tests output. */
    private boolean inTestFailure;

    private final TestFailureHighlight.Stream testFailStream = new TestFailureHighlight.Stream();
    private final DiagnosticReport.CompilerHeaderRun compilerHeaders = new DiagnosticReport.CompilerHeaderRun();

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
    public void planStart(BuildPlanView view) {
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
        // Test-failure blocks: keep the curated path (raw lines in outBuffer or stream paint).
        // Do not feed the Ctrl-O peek ring (and never force-show for tests).
        if (TestFailureHighlight.isHeader(line) || inTestFailure) {
            if (TestFailureHighlight.isHeader(line)) {
                flushBufferedFailure();
                inTestFailure = true;
                testFailStream.reset();
            }
            if (outBuffer != null) {
                synchronized (outBuffer) {
                    outBuffer.add(line);
                }
                if (line != null && TestFailureHighlight.FOOTER_SENTINEL.equals(line.strip())) {
                    inTestFailure = false;
                }
                return;
            }
            emit(paintOutputLine(line));
            return;
        }

        // Tool/process chatter (native-image, compilers, …). Animating: feed the live peek ring
        // so Ctrl-O works mid-step — and do NOT also park lines in outBuffer (that list is
        // settled as a bulk dump and would re-print the whole Graal log after a successful
        // native-image). Non-animating with a buffer: buffer ONLY — writeAbove prints
        // immediately in that mode, and the module-finish block prints the buffer again, so
        // doing both showed every line twice.
        String painted = StackTraceHighlight.line(line);
        if (outBuffer != null && !cm.animating()) {
            synchronized (outBuffer) {
                outBuffer.add(line);
            }
        } else {
            cm.writeProcessOutput(painted);
        }
    }

    private @Nullable String paintOutputLine(String line) {
        if (TestFailureHighlight.isHeader(line)) {
            // A second header must not reset() away an un-flushed first block.
            flushBufferedFailure();
            inTestFailure = true;
            testFailStream.reset();
            testFailStream.line(line);
            return null; // flushed on stepFinish / footer
        }
        if (inTestFailure) {
            testFailStream.line(line);
            return null;
        }
        return StackTraceHighlight.line(line);
    }

    @Override
    public void warn(String step, String code, String message) {
        emit(ConsoleSpec.renderWarning(step, code, message, module));
    }

    @Override
    public void error(String step, String code, String message) {
        String brief = message == null || message.isBlank() ? (code != null ? code : "Failed") : message;
        cm.attachPhaseError(module, step, "", brief);
        // Per-test failures are fully rendered by run-tests output (styled "Test Failure" block).
        // Do not also print a second report — keep the diagnostic for JSON.
        if ("test-failure".equals(code)) return;
        // Header suppression stacks consecutive same-key compiler reports — safe only on the
        // buffered path, where the module's block prints contiguously at finish. On the live
        // (animating) path, prints from parallel modules interleave in the merged stream, and a
        // headerless body from module A can land directly under module B's output, reading as
        // B's continuation — always repeat the pill there.
        boolean grouped = outBuffer != null && !cm.animating();
        boolean showHeader = !grouped || compilerHeaders.show(step, code, module);
        String report = ConsoleSpec.renderError(step, code, message, module, showHeader);
        if (report != null && !report.isEmpty()) {
            agg.markStreamed(step, code, message);
            // Same buffer-XOR-print rule as output(): the settled block is the single printing
            // path when not animating.
            if (outBuffer != null && !cm.animating()) {
                synchronized (outBuffer) {
                    outBuffer.add(report);
                }
            } else {
                cm.writeAbove(report);
            }
        }
        if (OutputPane.forceShowOnStepFailure(step)) {
            cm.showProcessFailureOutput();
        }
    }

    private void emit(String line) {
        if (line == null) return; // source-snippet buffer mid-stream
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
        cm.notePlainTestTick(module, step, delta);
    }

    @Override
    public void tickUpdate(String step, int delta, BuildPlanView view) {
        // Aggregate % comes from engine workspace-progress only.
        cm.notePlainTestTick(module, step, delta);
    }

    @Override
    public void stepFinish(String step, String group, TaskStatus status, Duration duration, Duration waited) {
        flushBufferedFailure();
        // SKIPPED = cache hit / up-to-date — still a green terminal (matches BuildPlan.isOk).
        // Treating it as failure painted the live tree red with "Failed" while the build
        // succeeded.
        boolean ok = status == TaskStatus.SUCCESS || status == TaskStatus.SKIPPED;
        cm.stepDone(module, step, ok, group == null ? "" : group);
        if (!ok && OutputPane.forceShowOnStepFailure(step)) {
            cm.showProcessFailureOutput();
        }
    }

    /** Paint any buffered failure block now — already-received lines must not be dropped. */
    private void flushBufferedFailure() {
        if (inTestFailure && outBuffer == null) {
            for (String painted : testFailStream.finish()) emit(painted);
        }
        inTestFailure = false;
        testFailStream.reset();
    }

    @Override
    public void planFinish(BuildPlanResult result) {
        flushBufferedFailure();
        // Clear leftover ACTIVE rows (e.g. a lost task-finish for ensure-jdk) so the module
        // does not linger in the live tree after its plan is done.
        cm.finishModule(module, result.success());
        if (!result.success()) {
            agg.notifyErrors(result.errors());
        }
    }
}
