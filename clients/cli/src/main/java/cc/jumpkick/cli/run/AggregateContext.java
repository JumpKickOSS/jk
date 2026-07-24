// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.cli.tui.CommandManager;
import cc.jumpkick.run.PipelineResult;
import cc.jumpkick.runtime.WorkspaceProgressTracker;
import java.util.List;

/**
 * Shared workspace UI sink for the CLI: one {@link CommandManager} plus last errors.
 *
 * <p><b>Dumb client (JK-1121):</b> aggregate bar math lives in the engine's {@link
 * WorkspaceProgressTracker}. This class only applies engine {@link
 * WorkspaceProgressTracker.Snapshot}s to the TUI / {@link LiveProgress}. It does not recompute
 * workspace percent from module ticks.
 */
public final class AggregateContext {

    /** @deprecated Use {@link WorkspaceProgressTracker#PREFLIGHT_UNITS}; kept for test constants. */
    @Deprecated
    public static final long PREFLIGHT_UNITS = WorkspaceProgressTracker.PREFLIGHT_UNITS;

    private final CommandManager cm;
    private volatile List<PipelineResult.Diagnostic> lastErrors = List.of();

    public AggregateContext(CommandManager cm) {
        this.cm = cm;
    }

    public CommandManager view() {
        return cm;
    }

    /**
     * Apply engine workspace aggregate (from {@code workspace-progress} / tracker snapshot). This is
     * the only path that may update the bar / {@link LiveProgress} for multi-module builds.
     */
    public void applySnapshot(WorkspaceProgressTracker.Snapshot snap) {
        if (snap == null) return;
        if (snap.denominator() > 0) cm.progress(snap.numerator(), snap.denominator());
        LiveProgress.get().apply(snap);
    }

    /**
     * Preflight stage labels for the live tree only — does <em>not</em> recompute aggregate % (engine
     * emits {@code workspace-progress} for that).
     */
    public void preflight(String stage, int done, int total, String label) {
        cm.preflight(stage, done, total, label);
    }

    /** @deprecated No-op; engine calibrates. Kept so older call sites compile during migration. */
    @Deprecated
    public void calibrate(long executeWeight) {
        // Engine owns calibration (JK-1120/1121).
    }

    /** @deprecated Always 0 client-side; engine owns totals. */
    @Deprecated
    public long total() {
        return 0;
    }

    /** @deprecated No-op. */
    @Deprecated
    public void growTotal(long delta) {}

    /** @deprecated Always 0 client-side. */
    @Deprecated
    public long completedBase() {
        return 0;
    }

    /** @deprecated No-op. */
    @Deprecated
    public void completeModule(long moduleTicks) {}

    /** @deprecated No-op. */
    @Deprecated
    public void moduleProgress(String module, long advanced) {}

    /** @deprecated No-op. */
    @Deprecated
    public void completeModule(String module, long slice) {}

    public List<PipelineResult.Diagnostic> lastErrors() {
        return lastErrors;
    }

    public void notifyErrors(List<PipelineResult.Diagnostic> errors) {
        this.lastErrors = errors;
    }
}
