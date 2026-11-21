// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import cc.jumpkick.diagnostic.CompilerLocus;
import cc.jumpkick.engine.listen.EventRedaction;
import cc.jumpkick.run.BuildPlanResult;

/** A plan's diagnostics as the journal keeps them. */
final class PlanDiags {

    private PlanDiags() {}

    /**
     * Copy a plan diagnostic into the journal, filling {@code file}/{@code line}/{@code col} from
     * a javac/kotlinc/groovyc header (and caret) when the plan row left them empty.
     */
    static BuildRecord.Diag of(String severity, String dir, String redactDir, BuildPlanResult.Diagnostic d) {
        String redacted = EventRedaction.redactEnv(redactDir, d.message());
        String message = redacted == null ? "" : redacted;
        String conflict = EventRedaction.redactEnv(redactDir, d.conflict());
        String file = d.file() == null ? "" : d.file();
        int line = d.line();
        int col = 0;
        CompilerLocus loc = CompilerLocus.parse(message);
        if (loc != null) {
            // The parsed column belongs with the parsed file/line: a diag whose file/line came
            // from elsewhere (test identity) must not adopt a column from a locus its message
            // merely quotes.
            boolean fromMessage = file.isEmpty() && line <= 0;
            if (file.isEmpty()) file = loc.file();
            if (line <= 0) line = loc.line();
            if (fromMessage) col = loc.col();
        }
        return new BuildRecord.Diag(
                severity,
                dir,
                d.step(),
                d.code(),
                message,
                d.test(),
                d.exceptionClass(),
                d.module(),
                d.engine(),
                d.className(),
                d.method(),
                EventRedaction.redactEnv(redactDir, d.stack()),
                file,
                line,
                col,
                d.snippetStart(),
                d.snippet(),
                d.worker(),
                d.key(),
                d.suite(),
                conflict == null ? "" : conflict);
    }
}
