// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * What a compile returned: the outcome plus one entry per compiler diagnostic. One type for every
 * language jk compiles — {@code success} mirrors {@code javac}'s exit-zero for {@link JavacRunner},
 * and the worker's {@code exit == 0 && status == COMPILATION_SUCCESS} for
 * {@link WorkerCompileDriver}'s kotlinc and groovyc forks. {@code KotlincResult} and
 * {@code GroovycResult} were byte-identical copies of this record and of each other; the spec was
 * the same in all three, so the type is.
 */
public record CompileResult(boolean success, List<Diagnostic> diagnostics) {

    public CompileResult {
        Objects.requireNonNull(diagnostics, "diagnostics");
        diagnostics = List.copyOf(diagnostics);
    }

    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(d -> d.severity() == Severity.ERROR);
    }

    /** Joined form for logs and exception messages. */
    public String output() {
        return diagnostics.stream().map(Diagnostic::describe).collect(Collectors.joining("\n"));
    }

    /**
     * One diagnostic. {@code key} is the compiler's own name for it — javac's {@code
     * compiler.err.cant.resolve.location} — and {@code ""} when the compiler reported text only
     * (kotlinc, groovyc, a forked javac's stderr).
     */
    public record Diagnostic(
            Severity severity, @Nullable Path source, long line, long column, String message, String key) {

        public Diagnostic {
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(message, "message");
            key = key == null ? "" : key;
        }

        /** A diagnostic the compiler gave no key for. */
        public Diagnostic(Severity severity, @Nullable Path source, long line, long column, String message) {
            this(severity, source, line, column, message, "");
        }

        /**
         * The diagnostic as it should reach the console: the compiler's full verbatim block (header
         * {@code src/Foo.java:12: error: …} plus any source snippet, caret, and {@code symbol:}/{@code
         * location:} lines). The CLI relativizes paths and adds color on top, so this stays a faithful
         * copy of what javac/kotlinc emitted.
         */
        public String describe() {
            return message;
        }

        /** Human-friendly one-line form: {@code error: src/Foo.java:12: ...}. */
        public String render() {
            StringBuilder sb = new StringBuilder();
            sb.append(severity.name().toLowerCase(Locale.ROOT)).append(": ");
            if (source != null) {
                sb.append(source);
                if (line > 0) {
                    sb.append(':').append(line);
                    if (column > 0) sb.append(':').append(column);
                }
                sb.append(": ");
            }
            sb.append(message);
            return sb.toString();
        }
    }

    public enum Severity {
        ERROR,
        WARNING,
        NOTE,
        OTHER;

        public static Severity fromJavacKind(javax.tools.Diagnostic.Kind kind) {
            return switch (kind) {
                case ERROR -> ERROR;
                case WARNING, MANDATORY_WARNING -> WARNING;
                case NOTE -> NOTE;
                case OTHER -> OTHER;
                default -> OTHER;
            };
        }

        /** Map a {@code javax.tools.Diagnostic.Kind} name (plugin JSONL) to a severity. */
        public static Severity fromName(@Nullable String kind) {
            if (kind == null) return OTHER;
            return switch (kind) {
                case "ERROR" -> ERROR;
                case "WARNING", "MANDATORY_WARNING" -> WARNING;
                case "NOTE" -> NOTE;
                default -> OTHER;
            };
        }
    }
}
