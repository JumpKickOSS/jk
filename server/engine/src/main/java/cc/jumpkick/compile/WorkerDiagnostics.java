// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.diagnostic.CompilerLocus;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Rebuilds worker JSONL diagnostics as {@link CompileResult.Diagnostic}s whose message text
 * carries a javac-style {@code path:line[:col]: severity:} header. Every downstream consumer —
 * journal locus parsing, CLI snippet rendering, dashboard code links — scrapes the message, so
 * a bare {@code SEV: msg} (or a prefixed header) makes the locus unreadable end to end.
 */
final class WorkerDiagnostics {

    private WorkerDiagnostics() {}

    /**
     * Structured worker diagnostic (groovyc, javac): when the worker supplied a file+line and the
     * message does not already start with its own header, synthesize one.
     */
    static CompileResult.Diagnostic located(String sev, String file, long line, long col, String msg) {
        CompileResult.Severity severity = CompileResult.Severity.fromName(sev);
        String text = msg == null ? "" : msg;
        if (file != null && !file.isBlank() && line > 0 && CompilerLocus.parse(text) == null) {
            StringBuilder sb = new StringBuilder(file).append(':').append(line);
            if (col > 0) sb.append(':').append(col);
            sb.append(": ")
                    .append(severity.name().toLowerCase(Locale.ROOT))
                    .append(": ")
                    .append(text);
            text = sb.toString();
        }
        return new CompileResult.Diagnostic(
                severity, file == null || file.isBlank() ? null : Path.of(file), line, col, text);
    }

    /**
     * Unstructured worker diagnostic (kotlinc's BTA logger): located messages carry their own
     * {@code path:line:col:} header and must pass verbatim — a severity prefix would be absorbed
     * into the parsed file name. Only headerless messages keep the severity label.
     */
    static CompileResult.Diagnostic text(String sev, String msg) {
        CompileResult.Severity severity =
                "INFO".equals(sev) ? CompileResult.Severity.NOTE : CompileResult.Severity.fromName(sev);
        String text = msg == null ? "" : msg;
        if (CompilerLocus.parse(text) == null && sev != null && !sev.isBlank()) {
            text = sev.toLowerCase(Locale.ROOT) + ": " + text;
        }
        return new CompileResult.Diagnostic(severity, null, 0, 0, text);
    }
}
