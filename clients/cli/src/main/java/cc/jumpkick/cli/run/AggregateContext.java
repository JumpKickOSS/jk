// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.cli.tui.JkManager;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.wire.runtime.WorkspaceProgressTracker;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared workspace UI sink for the CLI: one {@link JkManager} plus last errors.
 *
 * <p><b>Dumb client</b> aggregate bar math lives in the engine's {@link
 * WorkspaceProgressTracker}. This class only applies engine {@link
 * WorkspaceProgressTracker.Snapshot}s to the TUI / {@link LiveProgress}. It does not recompute
 * workspace percent from module ticks.
 */
public final class AggregateContext {

    private final JkManager cm;

    /**
     * Every failed module's plan-finish errors, in the order the modules finished. Keyed per module
     * because a workspace can fail more than one — {@code --continue}, or two parallel modules dying
     * together — and a single "last errors" slot kept only whichever finished last, dropping the
     * earlier module's diagnostics from both the settle and the transcript.
     */
    private final Map<String, List<BuildPlanResult.Diagnostic>> errorsByModule =
            Collections.synchronizedMap(new LinkedHashMap<>());
    /** Diagnostics a module listener already rendered from the live stream, keyed with their module. */
    private final Set<String> streamed = ConcurrentHashMap.newKeySet();

    public AggregateContext(JkManager cm) {
        this.cm = cm;
    }

    public JkManager view() {
        return cm;
    }

    /**
     * Apply engine workspace aggregate (from {@code workspace-progress} / tracker snapshot). This is
     * the only path that may update the bar / {@link LiveProgress} for multi-module builds.
     */
    public void applySnapshot(WorkspaceProgressTracker.Snapshot snap) {
        if (snap == null) return;
        if (snap.denominator() > 0) cm.progress(snap.numerator(), snap.denominator());
        // Residual RemainingWork: adaptive clock bar + countdown re-anchor (ends on time with R(t)).
        if (snap.remainingMs() >= 0) cm.setBarResidualRemaining(snap.remainingMs());
        // run-wide module remaining next to the wall-clock ETA.
        if (snap.modulesTotal() > 0) {
            cm.setModuleProgress(snap.modulesComplete(), snap.modulesTotal());
        }
        // Prefer engine strategy percent (includes adaptive clock) for JSONL riders.
        if (snap.hasPercent()) LiveProgress.get().setPercent(snap.percent());
        else LiveProgress.get().apply(snap);
    }

    /**
     * Preflight stage labels for the live tree only — does <em>not</em> recompute aggregate % (engine
     * emits {@code workspace-progress} for that).
     */
    public void preflight(String stage, int done, int total, String label) {
        cm.preflight(stage, done, total, label);
    }

    /** Every failed module's errors, module by module in finish order — the transcript's view. */
    public List<BuildPlanResult.Diagnostic> lastErrors() {
        List<BuildPlanResult.Diagnostic> out = new ArrayList<>();
        synchronized (errorsByModule) {
            for (List<BuildPlanResult.Diagnostic> errors : errorsByModule.values()) out.addAll(errors);
        }
        return out;
    }

    /** {@code module}'s plan finished with {@code errors}; a second finish for the same module replaces the first. */
    public void notifyErrors(String module, List<BuildPlanResult.Diagnostic> errors) {
        errorsByModule.put(module, List.copyOf(errors));
    }

    /** {@code module}'s listener rendered this diagnostic live; the workspace settle must not render it again. */
    public void markStreamed(String module, String step, String code, String message) {
        streamed.add(ConsoleSpec.diagnosticKey(module, step, code, message));
    }

    /** {@link #lastErrors} minus what each module already rendered — the settle's share of the errors. */
    public List<BuildPlanResult.Diagnostic> unstreamedErrors() {
        List<BuildPlanResult.Diagnostic> out = new ArrayList<>();
        synchronized (errorsByModule) {
            for (Map.Entry<String, List<BuildPlanResult.Diagnostic>> e : errorsByModule.entrySet()) {
                out.addAll(ConsoleSpec.withoutStreamed(e.getKey(), e.getValue(), streamed));
            }
        }
        return out;
    }
}
