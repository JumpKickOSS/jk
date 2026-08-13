// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

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
     * those empty. Legacy {@code test} is kept empty for new emits (prefer {@code module} +
     * {@code method}).
     */
    public record Diagnostic(
            String step,
            String code,
            String message,
            String test,
            String exceptionClass,
            String module,
            String engine,
            String className,
            String method,
            String stack,
            String file,
            int line,
            int snippetStart,
            java.util.List<String> snippet,
            int worker) {

        /** Diagnostic with no test identity — the common case (javac, resolver, …). */
        public Diagnostic(String step, String code, String message) {
            this(step, code, message, "", "", "", "", "", "", "", "", 0, 0, java.util.List.of(), 0);
        }

        /** Legacy two-field test failure (display label + exception class). */
        public Diagnostic(String step, String code, String message, String test, String exceptionClass) {
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
                    java.util.List.of(),
                    0);
        }

        /** Full structured test failure (incl. optional source snippet). */
        public Diagnostic(String step, String code, String message, TestFailureInfo failure) {
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
                    failure == null ? java.util.List.of() : failure.snippet(),
                    failure == null ? 0 : failure.worker());
        }

        public Diagnostic {
            if (snippet == null) snippet = java.util.List.of();
            else snippet = java.util.List.copyOf(snippet);
            if (file == null) file = "";
        }

        public TestFailureInfo testFailure() {
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
                    module,
                    engine,
                    className,
                    method,
                    exceptionClass,
                    message,
                    stack,
                    worker,
                    file,
                    line,
                    snippetStart,
                    snippet);
        }
    }
}
