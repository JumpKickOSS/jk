// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.compile.CompileResult;
import cc.jumpkick.model.Scope;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.task.CompileWarnings;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
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

    /** The tool kotlinc's diagnostics are keyed on. */
    public static final String KOTLINC = "kotlinc";

    /** The tool groovyc's diagnostics are keyed on. */
    public static final String GROOVYC = "groovyc";

    private JavacDiagnostics() {}

    /**
     * Report every diagnostic; true when at least one was an error. {@code classpath} is the
     * compile's own classpath, so a missing-package provider is judged against what this step saw,
     * and {@code scopes} the lock scopes it read, so only a file-less row among them is a suspect.
     */
    public static boolean report(
            TaskContext ctx, List<Path> classpath, Set<Scope> scopes, List<CompileResult.Diagnostic> diagnostics) {
        return report(ctx, classpath, scopes, diagnostics, List.of());
    }

    /**
     * As above, with the {@code replayed} warnings of sources this run did not compile said again
     * among them, in source order, as one compile of every source would say them.
     */
    public static boolean report(
            TaskContext ctx,
            List<Path> classpath,
            Set<Scope> scopes,
            List<CompileResult.Diagnostic> diagnostics,
            List<CompileResult.Diagnostic> replayed) {
        return sayInOrder(ctx, TOOL, PackageProviders.enrich(ctx, classpath, scopes, diagnostics), replayed);
    }

    /**
     * The warnings of a compile that succeeded, keyed on {@code tool}: its own {@code fresh} ones and
     * the {@code replayed} ones of sources it did not compile, in source order.
     */
    public static void reportWarnings(
            TaskContext ctx,
            String tool,
            List<CompileResult.Diagnostic> fresh,
            List<CompileResult.Diagnostic> replayed) {
        sayInOrder(ctx, tool, fresh, replayed);
    }

    private static boolean sayInOrder(
            TaskContext ctx,
            String tool,
            List<CompileResult.Diagnostic> fresh,
            List<CompileResult.Diagnostic> replayed) {
        if (replayed.isEmpty()) {
            boolean errored = false;
            for (CompileResult.Diagnostic d : fresh) errored |= say(ctx, tool, d, false);
            return errored;
        }
        record Said(CompileResult.Diagnostic d, boolean replayed) {}
        List<Said> all = new ArrayList<>();
        for (CompileResult.Diagnostic d : fresh) all.add(new Said(d, false));
        for (CompileResult.Diagnostic d : replayed) all.add(new Said(d, true));
        all.sort(Comparator.comparing(Said::d, CompileWarnings.ORDER));
        boolean errored = false;
        for (Said s : all) errored |= say(ctx, tool, s.d(), s.replayed());
        return errored;
    }

    /** Say {@code d} by its severity; true when it was an error. */
    private static boolean say(TaskContext ctx, String tool, CompileResult.Diagnostic d, boolean replayed) {
        if (d.severity() == CompileResult.Severity.ERROR) {
            ctx.keyedError(tool, d.key(), d.describe());
            return true;
        }
        if (replayed) ctx.replayedWarn(tool, d.key(), d.describe());
        else ctx.keyedWarn(tool, d.key(), d.describe());
        return false;
    }
}
