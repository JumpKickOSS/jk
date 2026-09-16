// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.compile.CompileResult;
import cc.jumpkick.run.TaskContext;
import java.util.List;

/**
 * Forwards a javac run's diagnostics to the step's context by severity, {@code javac} as the tool
 * and the compiler's own key beside each: errors fail the build, warnings and notes are surfaced
 * and do not. One spelling for compile-main, compile-test, fixtures and the guard suite.
 */
public final class JavacDiagnostics {

    /** The tool name every javac diagnostic is keyed on downstream. */
    public static final String TOOL = "javac";

    private JavacDiagnostics() {}

    /** Report every diagnostic; true when at least one was an error. */
    public static boolean report(TaskContext ctx, List<CompileResult.Diagnostic> diagnostics) {
        boolean errored = false;
        for (CompileResult.Diagnostic d : diagnostics) {
            if (d.severity() == CompileResult.Severity.ERROR) {
                ctx.keyedError(TOOL, d.key(), d.describe());
                errored = true;
            } else {
                ctx.keyedWarn(TOOL, d.key(), d.describe());
            }
        }
        return errored;
    }
}
