// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Terminal result of a {@link BuildPlan#run}. Lists every step that ran (with status + duration) plus
 * the accumulated warnings and errors emitted across all steps.
 */
public record BuildPlanResult(
        String planName,
        boolean success,
        Duration duration,
        List<StepReport> steps,
        List<Diagnostic> warnings,
        List<Diagnostic> errors,
        boolean cancelled,
        boolean userCancelled) {

    public BuildPlanResult {
        Objects.requireNonNull(planName, "planName");
        Objects.requireNonNull(duration, "duration");
        steps = List.copyOf(steps);
        warnings = List.copyOf(warnings);
        errors = List.copyOf(errors);
    }

    /**
     * 7-arg compatibility constructor. {@code cancelled=true} is presumed user-initiated when no
     * separate flag is supplied — the older callers all came from the SIGINT bridge.
     */
    public BuildPlanResult(
            String planName,
            boolean success,
            Duration duration,
            List<StepReport> steps,
            List<Diagnostic> warnings,
            List<Diagnostic> errors,
            boolean cancelled) {
        this(planName, success, duration, steps, warnings, errors, cancelled, cancelled);
    }

    /**
     * One row in the report's per-step breakdown. {@code requires} carries the step's dependency
     * edges (from {@link Task#requires()}) so downstream consumers can reconstruct the step DAG —
     * the edges are otherwise lost once {@link BuildPlan#run} returns. Used by the engine's
     * critical-path cache-benefit metric.
     */
    public record StepReport(String name, TaskStatus status, Duration duration, List<String> requires) {
        public StepReport {
            requires = requires == null ? List.of() : List.copyOf(requires);
        }

        /** Compatibility constructor for callers that don't track dependency edges. */
        public StepReport(String name, TaskStatus status, Duration duration) {
            this(name, status, duration, List.of());
        }
    }

    /**
     * Structured diagnostic from {@link TaskContext#warn} / {@link TaskContext#error}.
     *
     * <p>Test failures carry split identity ({@code module}, {@code engine}, {@code className},
     * {@code method}), {@code exceptionClass}, and full {@code stack}. Non-test diagnostics leave
     * those empty. {@code test} is kept empty for new emits (prefer {@code module} +
     * {@code method}).
     *
     * <p>{@code code} names the tool ({@code javac}, {@code kotlinc}, a guard rule); {@code key} is
     * that tool's own name for the diagnostic ({@code compiler.err.cant.resolve.location}), {@code
     * ""} when the tool reports text only.
     */
    public record Diagnostic(
            String step,
            String code,
            @Nullable String message,
            @Nullable String test,
            @Nullable String exceptionClass,
            @Nullable String module,
            @Nullable String engine,
            @Nullable String className,
            @Nullable String method,
            @Nullable String stack,
            @Nullable String file,
            int line,
            int snippetStart,
            List<String> snippet,
            int worker,
            String key) {

        /** Diagnostic with no test identity — the common case (javac, resolver, …). */
        public Diagnostic(String step, String code, String message) {
            this(step, code, message, "", "", "", "", "", "", "", "", 0, 0, List.of(), 0);
        }

        /** Every field but the tool's key, which is {@code ""}. */
        public Diagnostic(
                String step,
                String code,
                @Nullable String message,
                @Nullable String test,
                @Nullable String exceptionClass,
                @Nullable String module,
                @Nullable String engine,
                @Nullable String className,
                @Nullable String method,
                @Nullable String stack,
                @Nullable String file,
                int line,
                int snippetStart,
                List<String> snippet,
                int worker) {
            this(
                    step,
                    code,
                    message,
                    test,
                    exceptionClass,
                    module,
                    engine,
                    className,
                    method,
                    stack,
                    file,
                    line,
                    snippetStart,
                    snippet,
                    worker,
                    "");
        }

        /** This diagnostic with the tool's key for it. */
        public Diagnostic withKey(String key) {
            return new Diagnostic(
                    step,
                    code,
                    message,
                    test,
                    exceptionClass,
                    module,
                    engine,
                    className,
                    method,
                    stack,
                    file,
                    line,
                    snippetStart,
                    snippet,
                    worker,
                    key);
        }

        /** Two-field test failure (display label + exception class). */
        public Diagnostic(
                String step,
                String code,
                @Nullable String message,
                @Nullable String test,
                @Nullable String exceptionClass) {
            this(
                    step,
                    code,
                    message,
                    test == null ? "" : test,
                    exceptionClass == null ? "" : exceptionClass,
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    0,
                    0,
                    List.of(),
                    0);
        }

        /** Full structured test failure (incl. optional source snippet). */
        public Diagnostic(String step, String code, @Nullable String message, @Nullable TestFailureInfo failure) {
            this(
                    step,
                    code,
                    message == null ? (failure == null ? "" : failure.message()) : message,
                    "",
                    failure == null ? "" : failure.exceptionClass(),
                    failure == null ? "" : failure.module(),
                    failure == null ? "" : failure.engine(),
                    failure == null ? "" : failure.className(),
                    failure == null ? "" : failure.method(),
                    failure == null ? "" : failure.stack(),
                    failure == null ? "" : failure.file(),
                    failure == null ? 0 : failure.line(),
                    failure == null ? 0 : failure.snippetStart(),
                    failure == null ? List.of() : failure.snippet(),
                    failure == null ? 0 : failure.worker());
        }

        public Diagnostic {
            if (snippet == null) snippet = List.of();
            else snippet = List.copyOf(snippet);
            key = key == null ? "" : key;
        }

        public @Nullable TestFailureInfo testFailure() {
            if ((module == null || module.isEmpty())
                    && (className == null || className.isEmpty())
                    && (method == null || method.isEmpty())
                    && (exceptionClass == null || exceptionClass.isEmpty())
                    && (stack == null || stack.isEmpty())
                    && (file == null || file.isEmpty())
                    && worker <= 0) {
                return null;
            }
            return new TestFailureInfo(
                    empty(module),
                    empty(engine),
                    empty(className),
                    empty(method),
                    empty(exceptionClass),
                    empty(message),
                    empty(stack),
                    worker,
                    empty(file),
                    line,
                    snippetStart,
                    snippet);
        }

        private static String empty(@Nullable String value) {
            return value == null ? "" : value;
        }
    }
}
