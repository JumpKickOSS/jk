// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import cc.jumpkick.java.compiler.ZincJavaCompiler.Diag;
import java.util.ArrayList;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import sbt.internal.inc.javac.DiagnosticsReporter;
import xsbti.Position;
import xsbti.Problem;
import xsbti.Reporter;
import xsbti.Severity;

/**
 * Zinc's {@link Reporter}, accumulating problems so the worker can answer with a full diagnostic
 * list instead of a stream. Owns the one translation from Zinc's {@link Problem} to jk's
 * {@link Diag} — the failure path in {@code ZincJavaCompiler} needs the same translation for
 * problems that arrive on a thrown {@code CompileFailed}, and two spellings of it would let the two
 * routes disagree about severity or line numbering.
 *
 * <p>A javac diagnostic reported through {@link #report} keeps its {@link Diagnostic#getCode()
 * key} beside the problem: Zinc's {@link DiagnosticsReporter} renders the message and position
 * but drops the key, so the key is taken from the diagnostic before the bridge logs its problem.
 */
final class CollectingReporter implements Reporter {

    /** A logged problem and the javac key it arrived with ({@code ""} when it came without one). */
    private record Keyed(Problem problem, String key) {}

    private final List<Keyed> problems = new ArrayList<>();
    private final DiagnosticsReporter bridge = new DiagnosticsReporter(this);
    private String pendingKey = "";

    /** Log a javac diagnostic: Zinc's rendering of it, with javac's key kept. */
    void report(Diagnostic<? extends JavaFileObject> diagnostic) {
        String code = diagnostic.getCode();
        pendingKey = code == null ? "" : code;
        try {
            bridge.report(diagnostic);
        } finally {
            pendingKey = "";
        }
    }

    static Diag toDiag(Problem p) {
        return toDiag(p, "");
    }

    /**
     * Zinc positions are 1-based lines and a 0-based {@code pointer} column; the diagnostic carries
     * the column 1-based, so a header synthesised from it reads {@code file:line:col} as javac's.
     */
    private static Diag toDiag(Problem p, String key) {
        Position pos = p.position();
        String file = pos.sourcePath().orElse(null);
        long line = pos.line().map(Integer::longValue).orElse(0L);
        long col = pos.pointer().map(c -> c.longValue() + 1).orElse(0L);
        String kind =
                switch (p.severity()) {
                    case Error -> "ERROR";
                    case Warn -> "WARNING";
                    case Info -> "NOTE";
                };
        return new Diag(kind, file, line, col, p.message(), key);
    }

    List<Diag> diagnostics() {
        List<Diag> out = new ArrayList<>();
        for (Keyed k : problems) out.add(toDiag(k.problem(), k.key()));
        return out;
    }

    @Override
    public void reset() {
        problems.clear();
    }

    @Override
    public boolean hasErrors() {
        for (Keyed k : problems) if (k.problem().severity() == Severity.Error) return true;
        return false;
    }

    @Override
    public boolean hasWarnings() {
        for (Keyed k : problems) if (k.problem().severity() == Severity.Warn) return true;
        return false;
    }

    @Override
    public void printSummary() {}

    @Override
    public Problem[] problems() {
        return problems.stream().map(Keyed::problem).toArray(Problem[]::new);
    }

    @Override
    public void log(Problem problem) {
        problems.add(new Keyed(problem, pendingKey));
    }

    @Override
    public void comment(Position pos, String msg) {}
}
