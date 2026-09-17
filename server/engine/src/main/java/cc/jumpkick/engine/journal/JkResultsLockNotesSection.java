// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import java.util.ArrayList;
import java.util.List;

/**
 * The lock's notes in {@code jk-results.md}: the {@code ## Lock notes} section, one line per note
 * the resolve raised under the {@code lock-note} code — a member that reads its own rows, a
 * repository a POM declared that served a row, a checksum verified against a weak sidecar. They
 * are listed in full, apart from the warnings cap, so a run with many override warnings still
 * shows them; nothing is written for a run that raised none.
 */
public final class JkResultsLockNotesSection {

    /** The diagnostic code the resolve raises its notes under. */
    public static final String CODE = "lock-note";

    private JkResultsLockNotesSection() {}

    /** True for a diagnostic the resolve raised as a lock note. */
    static boolean isLockNote(BuildRecord.Diag d) {
        return d != null && CODE.equals(d.code());
    }

    /** The {@code Diagnostics:} line's own count of lock notes, or {@code 0}. */
    static int count(BuildRecord r) {
        int n = 0;
        for (BuildRecord.Diag d : r.diagnostics()) if (isLockNote(d)) n++;
        return n;
    }

    static void append(StringBuilder sb, BuildRecord r) {
        List<BuildRecord.Diag> notes = new ArrayList<>();
        for (BuildRecord.Diag d : r.diagnostics()) if (isLockNote(d)) notes.add(d);
        if (notes.isEmpty()) return;
        sb.append("## Lock notes\n\n");
        for (BuildRecord.Diag d : notes) {
            sb.append("- ")
                    .append(JkResultsMarkdown.clipOneLine(d.message(), 600))
                    .append('\n');
        }
        sb.append('\n');
    }
}
