// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import cc.jumpkick.runtime.EnvReadReport;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The run report's environment section: the variables a suite read that its module does not give
 * it, and a failed suite's reads by a name JumpKick cannot see. A green run lists the first few with
 * the {@code [env]} line that declares each; a failed run, whose libraries may have scanned the whole
 * environment, comes after the failure and folds a long list into one line.
 */
final class JkResultsEnvReads {

    private JkResultsEnvReads() {}

    /**
     * On every verdict, each variable a suite read that its module does not give it, as a {@code W}
     * line with the {@code [env]} declaration as its {@code FIX}: a green run whose test saw
     * {@code null} says why. The first {@link JkResultsAgent#MAX_PROBLEMS} variables, then a count of the rest; a
     * failed suite's {@code env-computed} line after them. The warning the headline already gives
     * as the reason is not repeated.
     */
    static void append(StringBuilder sb, BuildRecord record, @Nullable String headline, boolean failed) {
        if (failed && collapse(sb, record)) {
            appendComputed(sb, record, headline);
            return;
        }
        int shown = 0;
        int more = 0;
        for (BuildRecord.Diag d : record.diagnostics()) {
            if (d == null || !EnvReadReport.UNDECLARED.equals(d.code()) || d.message() == null) continue;
            EnvReadReport.Said said = EnvReadReport.split(JkResultsHints.firstLine(d.message()));
            if (said.fix().isEmpty() || shown >= JkResultsAgent.MAX_PROBLEMS) {
                more += said.variables();
                continue;
            }
            if (said.finding().equals(headline) || d.message().strip().equals(headline)) continue;
            shown++;
            sb.append("W ")
                    .append(JkResultsAgent.one(said.finding(), record.dir()))
                    .append('\n');
            sb.append("FIX ")
                    .append(JkResultsAgent.one(said.fix(), record.dir()))
                    .append('\n');
        }
        if (more > 0) sb.append("W +").append(more).append(" more undeclared variables: jk results\n");
        appendComputed(sb, record, headline);
    }

    /** A failed suite's {@code env-computed} lines, which may be why it failed. */
    private static void appendComputed(StringBuilder sb, BuildRecord record, @Nullable String headline) {
        for (BuildRecord.Diag d : record.diagnostics()) {
            if (d == null || !EnvReadReport.COMPUTED.equals(d.code()) || d.message() == null) continue;
            String line = JkResultsHints.firstLine(d.message());
            if (!line.isEmpty() && !line.equals(headline))
                sb.append("W ").append(JkResultsAgent.one(line, record.dir())).append('\n');
        }
    }

    /**
     * On a failed run whose suites read more undeclared variables than {@link JkResultsAgent#MAX_PROBLEMS}, one
     * line: how many, and the first readers, which are the libraries that scan the environment.
     * False, writing nothing, when there are few enough to list.
     */
    private static boolean collapse(StringBuilder sb, BuildRecord record) {
        int total = 0;
        Set<String> readers = new LinkedHashSet<>();
        for (BuildRecord.Diag d : record.diagnostics()) {
            if (d == null || !EnvReadReport.UNDECLARED.equals(d.code()) || d.message() == null) continue;
            EnvReadReport.Said said = EnvReadReport.split(JkResultsHints.firstLine(d.message()));
            total += said.variables();
            if (said.fix().isEmpty()) continue;
            int read = said.finding().indexOf(" read");
            if (read > 0) readers.add(said.finding().substring(0, read));
        }
        if (total <= JkResultsAgent.MAX_PROBLEMS) return false;
        List<String> named = new ArrayList<>(readers);
        String by = named.isEmpty()
                ? ""
                : " by " + String.join(", ", named.subList(0, Math.min(3, named.size())))
                        + (named.size() > 3 ? ", …" : "");
        sb.append("W ")
                .append(total)
                .append(" undeclared variables read")
                .append(by)
                .append(": jk results\n");
        return true;
    }
}
