// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.cli.tui.CommandManager;
import cc.jumpkick.run.PipelineResult;
import java.util.List;

/**
 * Shared workspace progress sink: one {@link CommandManager} fed by each module's
 * {@link AggregateModuleListener}. With {@link #calibrate}, each module owns a fixed tick slice of
 * the workspace total; without it, the bar grows from live module denominators.
 */
public final class AggregateContext {

    private final CommandManager cm;
    private long completedBase;
    private long total; // fixed aggregate denominator, 0 until calibrated
    // Calibrated path: each still-running module's current contribution to its
    // slice. The aggregate numerator is completedBase + Σ(these), so modules
    // building concurrently sum into the bar instead of clobbering one another
    // (last-writer-wins). A module is removed here when it completes — its slice
    // moves into completedBase, so it's never double-counted.
    private final java.util.Map<String, Long> moduleAdvanced = new java.util.HashMap<>();
    private volatile List<PipelineResult.Diagnostic> lastErrors = List.of();

    public AggregateContext(CommandManager cm) {
        this.cm = cm;
    }

    public CommandManager view() {
        return cm;
    }

    /**
     * Pin the bar's denominator to the workspace's aggregate estimated ticks and paint an empty bar
     * at {@code 0 / total}. Called once before any module runs.
     */
    public synchronized void calibrate(long total) {
        this.total = total;
        cm.progress(0, total);
    }

    /** The aggregate denominator, or 0 when {@link #calibrate} wasn't called. */
    public synchronized long total() {
        return total;
    }

    /** Adjust the aggregate denominator when a module reweights mid-run. */
    public synchronized void growTotal(long delta) {
        total += delta;
    }

    /** Ticks of all modules finished so far. */
    public synchronized long completedBase() {
        return completedBase;
    }

    /** Advance the base past a finished module (slice or live ticks). */
    public synchronized void completeModule(long moduleTicks) {
        completedBase += moduleTicks;
    }

    /** Record one module's advanced ticks and repaint ({@code base + Σ running}). */
    public synchronized void moduleProgress(String module, long advanced) {
        moduleAdvanced.put(module, advanced);
        long sum = completedBase;
        for (long v : moduleAdvanced.values()) sum += v;
        cm.progress(Math.min(sum, total), total);
    }

    /** Fold a module's reserved {@code slice} into the base and drop its running contribution. */
    public synchronized void completeModule(String module, long slice) {
        moduleAdvanced.remove(module);
        completedBase += slice;
        cm.progress(Math.min(completedBase, total), total);
    }

    /** Errors from the most recently failed module pipeline. */
    public List<PipelineResult.Diagnostic> lastErrors() {
        return lastErrors;
    }

    public void notifyErrors(List<PipelineResult.Diagnostic> errors) {
        this.lastErrors = errors;
    }
}
