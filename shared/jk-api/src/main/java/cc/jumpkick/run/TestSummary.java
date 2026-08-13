// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import java.util.List;
import java.util.Map;

/**
 * Aggregate test outcome: counts plus failures for exit codes and UI. {@code classes} is the
 * distinct executed test-class count when the runner could derive it, else 0 (unknown).
 * {@code classWallMs} maps FQCN → wall-ms for successfully finished class containers (ETA training).
 */
public record TestSummary(
        long total,
        long succeeded,
        long failed,
        long skipped,
        long classes,
        List<Failure> failures,
        Map<String, Long> classWallMs) {

    public TestSummary {
        failures = List.copyOf(failures);
        classWallMs = classWallMs == null || classWallMs.isEmpty() ? Map.of() : Map.copyOf(classWallMs);
    }

    /** Classes unknown; no class walls. */
    public TestSummary(long total, long succeeded, long failed, long skipped, List<Failure> failures) {
        this(total, succeeded, failed, skipped, 0, failures, Map.of());
    }

    /** Class count known; no class walls. */
    public TestSummary(long total, long succeeded, long failed, long skipped, long classes, List<Failure> failures) {
        this(total, succeeded, failed, skipped, classes, failures, Map.of());
    }

    public boolean allPassed() {
        return failed == 0;
    }

    /**
     * One failed test. Identity comes from the runner's split uniqueId ({@code testEngine} /
     * {@code className} / {@code method}); {@code exceptionClass} + {@code message} + {@code stack}
     * are the throwable. Empty module/class and {@code workerId <= 0} mean unknown / serial.
     *
     * <p>{@code testName} is a short label for progress/headlines (usually the method segment), not a
     * Jupiter display-name decision from the plugin.
     */
    public record Failure(
            String testName,
            String exceptionClass,
            String message,
            String stack,
            String module,
            String className,
            int workerId,
            String testEngine,
            String method) {

        public Failure {
            if (testName == null) testName = "";
            if (exceptionClass == null) exceptionClass = "";
            if (message == null) message = "";
            if (stack == null) stack = "";
            if (module == null) module = "";
            if (className == null) className = "";
            if (testEngine == null) testEngine = "";
            if (method == null) method = "";
        }

        /** Compat: no module / class / worker / engine. */
        public Failure(String testName, String exceptionClass, String message, String stack) {
            this(testName, exceptionClass, message, stack, "", "", 0, "", "");
        }

        /** Compat: module/class/worker without engine/method. */
        public Failure(
                String testName,
                String exceptionClass,
                String message,
                String stack,
                String module,
                String className,
                int workerId) {
            this(testName, exceptionClass, message, stack, module, className, workerId, "", "");
        }

        /** @deprecated use {@link #stack()} — kept as an alias for older call sites. */
        @Deprecated
        public String details() {
            return stack;
        }

        /**
         * One-line label for console: {@code module :: method [wN]}. Module and worker omitted when
         * unknown / serial.
         */
        public String headline() {
            StringBuilder sb = new StringBuilder();
            if (!module.isBlank()) {
                sb.append(module).append(" :: ");
            }
            String label = !method.isBlank() ? method : (!testName.isBlank() ? testName : className);
            sb.append(label);
            if (workerId > 0) {
                sb.append("  [w").append(workerId).append(']');
            }
            return sb.toString();
        }

        /** Structured form for diagnostics / client wire (no source snippet). */
        public TestFailureInfo toInfo() {
            return new TestFailureInfo(
                    module,
                    testEngine,
                    className,
                    method.isBlank() ? testName : method,
                    exceptionClass,
                    message,
                    stack,
                    workerId);
        }
    }
}
