// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.compile.CompileResult;
import cc.jumpkick.model.Scope;
import cc.jumpkick.run.TaskContext;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Forwards a javac run's diagnostics to the step's context by severity, {@code javac} as the tool
 * and the compiler's own key beside each: errors fail the build, warnings and notes are surfaced
 * and do not. A missing-package error first gains the coordinate that provides the package, when
 * the lock or the catalog knows one ({@link PackageProviders}). One spelling for compile-main,
 * compile-test, fixtures and the guard suite.
 */
public final class JavacDiagnostics {

    /** The tool name every javac diagnostic is keyed on downstream. */
    public static final String TOOL = "javac";

    private JavacDiagnostics() {}

    /**
     * Report every diagnostic; true when at least one was an error. {@code classpath} is the
     * compile's own classpath, so a missing-package provider is judged against what this step saw,
     * and {@code scopes} the lock scopes it read, so only a file-less row among them is a suspect.
     */
    public static boolean report(
            TaskContext ctx, List<Path> classpath, Set<Scope> scopes, List<CompileResult.Diagnostic> diagnostics) {
        boolean errored = false;
        for (CompileResult.Diagnostic d : PackageProviders.enrich(ctx, classpath, scopes, diagnostics)) {
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
