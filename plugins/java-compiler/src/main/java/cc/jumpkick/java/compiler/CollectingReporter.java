// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import cc.jumpkick.java.compiler.ZincJavaCompiler.Diag;
import java.util.ArrayList;
import java.util.List;
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
 */
final class CollectingReporter implements Reporter {

    private final List<Problem> problems = new ArrayList<>();

    static Diag toDiag(Problem p) {
        Position pos = p.position();
        String file = pos.sourcePath().orElse(null);
        long line = pos.line().map(Integer::longValue).orElse(0L);
        String kind =
                switch (p.severity()) {
                    case Error -> "ERROR";
                    case Warn -> "WARNING";
                    case Info -> "NOTE";
                };
        return new Diag(kind, file, line, 0, p.message());
    }

    List<Diag> diagnostics() {
        List<Diag> out = new ArrayList<>();
        for (Problem p : problems) out.add(toDiag(p));
        return out;
    }

    @Override
    public void reset() {
        problems.clear();
    }

    @Override
    public boolean hasErrors() {
        for (Problem p : problems) if (p.severity() == Severity.Error) return true;
        return false;
    }

    @Override
    public boolean hasWarnings() {
        for (Problem p : problems) if (p.severity() == Severity.Warn) return true;
        return false;
    }

    @Override
    public void printSummary() {}

    @Override
    public Problem[] problems() {
        return problems.toArray(Problem[]::new);
    }

    @Override
    public void log(Problem problem) {
        problems.add(problem);
    }

    @Override
    public void comment(Position pos, String msg) {}
}
