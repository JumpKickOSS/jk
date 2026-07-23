// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.cli.tui.CommandManager;
import cc.jumpkick.run.PipelineResult;
import java.util.List;

/**
 * Shared workspace progress sink: one {@link CommandManager} fed by preflight events and each
 * module's {@link AggregateModuleListener}.
 *
 * <p>Bar model: a fixed preflight reservation ({@link #PREFLIGHT_UNITS}) for Checking/Lock/Graph/Plan,
 * then execute weights from {@link #calibrate}. Numerator is always monotonic: preflight progress
 * fills 0…{@code PREFLIGHT_UNITS}, then execute adds on top.
 */
public final class AggregateContext {

    /** Reserved progress units for all preflight work (before module pipelines run). */
    public static final long PREFLIGHT_UNITS = 100;

    private final CommandManager cm;
    private long completedBase;
    private long total; // execute aggregate denominator, 0 until calibrated
    private long preflightNum; // 0..PREFLIGHT_UNITS
    private boolean executeCalibrated;
    private final java.util.Map<String, Long> moduleAdvanced = new java.util.HashMap<>();
    private volatile List<PipelineResult.Diagnostic> lastErrors = List.of();

    public AggregateContext(CommandManager cm) {
        this.cm = cm;
    }

    public CommandManager view() {
        return cm;
    }

    /**
     * Drive preflight bar + phase pills. {@code stageFraction} is 0..1 of the preflight reservation
     * (Checking→Plan map into successive bands). Stage-local {@code done}/{@code total} refine the
     * Plan band when {@code total > 0}.
     */
    public synchronized void preflight(String stage, int done, int total, String label) {
        cm.preflight(stage, done, total, label);
        preflightNum = Math.min(PREFLIGHT_UNITS, Math.round(preflightFraction(stage, done, total) * PREFLIGHT_UNITS));
        paint();
    }

    /** Mark preflight complete (all PREFLIGHT_UNITS earned) without execute calibrate yet. */
    public synchronized void preflightComplete() {
        preflightNum = PREFLIGHT_UNITS;
        paint();
    }

    /**
     * Pin execute weight and paint at end-of-preflight. {@code executeWeight} is Σ module pipeline
     * weights (ticks).
     */
    public synchronized void calibrate(long executeWeight) {
        this.total = Math.max(0, executeWeight);
        this.executeCalibrated = true;
        this.preflightNum = PREFLIGHT_UNITS;
        paint();
    }

    public synchronized long total() {
        return total;
    }

    public synchronized void growTotal(long delta) {
        total += delta;
        paint();
    }

    public synchronized long completedBase() {
        return completedBase;
    }

    public synchronized void completeModule(long moduleTicks) {
        completedBase += moduleTicks;
        paint();
    }

    public synchronized void moduleProgress(String module, long advanced) {
        moduleAdvanced.put(module, advanced);
        paint();
    }

    public synchronized void completeModule(String module, long slice) {
        moduleAdvanced.remove(module);
        completedBase += slice;
        paint();
    }

    public List<PipelineResult.Diagnostic> lastErrors() {
        return lastErrors;
    }

    public void notifyErrors(List<PipelineResult.Diagnostic> errors) {
        this.lastErrors = errors;
    }

    private void paint() {
        long execSum = completedBase;
        for (long v : moduleAdvanced.values()) execSum += v;
        long num;
        long den;
        if (executeCalibrated) {
            num = preflightNum + Math.min(execSum, total);
            den = PREFLIGHT_UNITS + total;
            if (den <= 0) den = PREFLIGHT_UNITS;
        } else {
            num = preflightNum;
            den = PREFLIGHT_UNITS;
        }
        cm.progress(num, den);
        // Same units as the TUI bar — agents read percent only (JK-1117).
        LiveProgress.get().update(num, den);
    }

    /**
     * Map preflight stages onto 0..1 of the reserved band: Checking 0–15%, Lock 15–30%, Graph
     * 30–40%, Plan 40–100% (Plan uses done/total when known).
     */
    static double preflightFraction(String stage, int done, int total) {
        if (stage == null) stage = "";
        return switch (stage) {
            case "checking" -> 0.08;
            case "lock" -> total > 0 && done >= total ? 0.30 : 0.20;
            case "graph" -> total > 0 && done >= total ? 0.40 : 0.35;
            case "plan" -> {
                if (total <= 0) yield 0.45;
                double within = Math.min(1.0, Math.max(0.0, (double) done / (double) total));
                yield 0.40 + 0.60 * within;
            }
            default -> 0.10;
        };
    }
}
