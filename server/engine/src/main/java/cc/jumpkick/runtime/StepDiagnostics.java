// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.compile.CompileResult;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.task.ActionCache;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * What one plugin step or packager says as it runs, said and kept: each {@code diagnostic} reply
 * its worker sends is {@linkplain PlannerPlugin#forwardStepDiagnostic forwarded} as it arrives and
 * kept for the action record the run stores, and a cache hit {@linkplain #replay replays} the
 * record's through the same forward. A restored step therefore says what its run said, in the
 * words its run said it: a notice stays on every verdict while what caused it is unchanged, not
 * only on the build that ran the step.
 */
final class StepDiagnostics {

    private final TaskContext ctx;
    private final Path moduleDir;
    private final String module;
    private final String step;
    private final List<String> said = new ArrayList<>();

    /** The diagnostics of {@code step} of the module {@code module} at {@code moduleDir}. */
    StepDiagnostics(TaskContext ctx, Path moduleDir, String module, String step) {
        this.ctx = ctx;
        this.moduleDir = moduleDir;
        this.module = module;
        this.step = step;
    }

    /** One {@code diagnostic} reply from the worker: said now, and kept for the record. */
    void forward(String line) {
        synchronized (said) {
            said.add(line);
        }
        PlannerPlugin.forwardStepDiagnostic(ctx, moduleDir, module, step, line);
    }

    /** Every reply {@link #forward} has seen, in order: the record's {@link ActionCache.ActionRecord#said()}. */
    List<String> said() {
        synchronized (said) {
            return List.copyOf(said);
        }
    }

    /** Say again what {@code hit}'s run said, as that run said it. */
    void replay(ActionCache.ActionRecord hit) {
        for (String line : hit.said()) PlannerPlugin.forwardStepDiagnostic(ctx, moduleDir, module, step, line);
    }

    /**
     * Mirror the javac diagnostic loop for worker compilers: one {@code ctx.error}/{@code warn}
     * per diagnostic so each keeps its own {@code path:line[:col]:} header for journal locus
     * parsing and CLI snippets, instead of one joined blob whose first header wins.
     */
    static void forwardWorkerDiagnostics(
            TaskContext ctx, String code, List<CompileResult.Diagnostic> diagnostics, String emptyFallback) {
        boolean errored = false;
        for (CompileResult.Diagnostic d : diagnostics) {
            if (d.severity() == CompileResult.Severity.ERROR) {
                ctx.error(code, d.describe());
                errored = true;
            } else {
                ctx.warn(code, d.describe());
            }
        }
        // Never fail silently: a crash that produced no ERROR diagnostic still explains itself.
        if (!errored) ctx.error(code, emptyFallback);
    }
}
