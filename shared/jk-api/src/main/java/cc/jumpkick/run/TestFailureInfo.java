// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The one structured test failure — plan diagnostics, client wire, {@code details.jsonl} and
 * {@link TestSummary#failures()} all carry this record. Built by the engine from the test-runner's
 * split identity + throwable; UIs must not re-parse a glued label.
 *
 * <p>{@code method} is the failure's display identity: the method segment when the runner reported
 * one, else the container/run label the runner used ({@code "Foo (container)"}, {@code "(test run)"},
 * {@code "(worker 2)"}). There is deliberately no second "short name" component — a duplicate
 * {@code testName} field is what let the two failure records drift apart.
 *
 * <p>{@code stack} is the full {@code printStackTrace} text (single string, newlines preserved).
 * {@code file}/{@code line}/{@code snippet} are optional source context resolved from the stack.
 */
public record TestFailureInfo(
        String module,
        String engine,
        String className,
        String method,
        String exceptionClass,
        String message,
        String stack,
        int worker,
        String file,
        int line,
        int snippetStart,
        List<String> snippet) {

    public TestFailureInfo {
        module = empty(module);
        engine = empty(engine);
        className = empty(className);
        method = empty(method);
        exceptionClass = empty(exceptionClass);
        message = empty(message);
        stack = empty(stack);
        file = empty(file);
        snippet = snippet == null || snippet.isEmpty() ? List.of() : List.copyOf(snippet);
    }

    /** No worker / source snippet. */
    public TestFailureInfo(
            String module,
            String engine,
            String className,
            String method,
            String exceptionClass,
            String message,
            String stack) {
        this(module, engine, className, method, exceptionClass, message, stack, 0, "", 0, 0, List.of());
    }

    /** Worker, no source snippet. */
    public TestFailureInfo(
            String module,
            String engine,
            String className,
            String method,
            String exceptionClass,
            String message,
            String stack,
            int worker) {
        this(module, engine, className, method, exceptionClass, message, stack, worker, "", 0, 0, List.of());
    }

    /**
     * The one {@code module :: test  [wN]} label in jk. Every surface that qualifies a test with the
     * module it ran in — plan diagnostics, the live progress line, the results markdown — renders it
     * here, so the separator and the worker suffix cannot drift between them. A blank module or a
     * non-positive {@code workerId} drops that part; the result is never null.
     */
    /** The one {@code module :: test} separator — written by {@link #label}, undone by {@link #stripLabel}. */
    public static final String SEPARATOR = " :: ";

    public static String label(@Nullable String module, @Nullable String test, int workerId) {
        StringBuilder sb = new StringBuilder();
        if (module != null && !module.isBlank()) {
            sb.append(module).append(SEPARATOR);
        }
        sb.append(test == null ? "" : test);
        if (workerId > 0) {
            sb.append("  [w").append(workerId).append(']');
        }
        return sb.toString();
    }

    /** This failure's {@code module :: method  [wN]} label; falls back to the class when unnamed. */
    public String label() {
        return label(module, method.isBlank() ? className : method, worker);
    }

    /**
     * Inverse of {@link #label}: the display text after the leading {@code "module :: "} segment.
     * With a non-null {@code module}, only that exact prefix is stripped — a label written for a
     * different module comes back untouched. With {@code null}, whatever precedes the first
     * separator is stripped. Neither form touches the worker suffix, and a display string that
     * itself contains the separator keeps it (only the leading segment goes).
     */
    public static String stripLabel(@Nullable String module, @Nullable String label) {
        if (label == null || label.isBlank()) return "";
        String s = label.trim();
        if (module == null) {
            int sep = s.indexOf(SEPARATOR);
            return sep > 0 ? s.substring(sep + SEPARATOR.length()).trim() : s;
        }
        String mod = module.trim();
        if (!mod.isEmpty() && s.startsWith(mod + SEPARATOR)) {
            return s.substring(mod.length() + SEPARATOR.length()).trim();
        }
        return s;
    }

    private static String empty(@Nullable String s) {
        return s == null ? "" : s;
    }
}
