// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import java.util.List;

/**
 * Structured test failure for plan diagnostics / client wire / details.jsonl. Built by the engine
 * from the test-runner's split identity + throwable; UIs must not re-parse a glued label.
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

    private static String empty(String s) {
        return s == null ? "" : s;
    }
}
