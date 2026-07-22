// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import java.util.List;

/** Aggregate test outcome: counts plus failures for exit codes and UI. */
public record TestSummary(long total, long succeeded, long failed, long skipped, List<Failure> failures) {

    public TestSummary {
        failures = List.copyOf(failures);
    }

    public boolean allPassed() {
        return failed == 0;
    }

    /**
     * One failed test. {@code exceptionClass} and {@code message} are the failure's throwable split
     * into discrete fields; either may be empty when the failure carries no throwable (e.g. runner
     * exited N). {@code details} is the full stack (empty when none).
     *
     * <p>{@code module} / {@code className} / {@code workerId} enrich multi-module / multi-worker
     * failure lines (JK-1094). Empty module/class and {@code workerId <= 0} mean unknown / single
     * worker.
     */
    public record Failure(
            String testName,
            String exceptionClass,
            String message,
            String details,
            String module,
            String className,
            int workerId) {

        public Failure {
            if (testName == null) testName = "";
            if (exceptionClass == null) exceptionClass = "";
            if (message == null) message = "";
            if (details == null) details = "";
            if (module == null) module = "";
            if (className == null) className = "";
        }

        /** Compat ctor without module / class / worker. */
        public Failure(String testName, String exceptionClass, String message, String details) {
            this(testName, exceptionClass, message, details, "", "", 0);
        }

        /**
         * One-line label for console: {@code module :: display [wN]}. Module and worker omitted when
         * unknown / serial.
         */
        public String headline() {
            StringBuilder sb = new StringBuilder();
            if (!module.isBlank()) {
                sb.append(module).append(" :: ");
            }
            sb.append(testName);
            if (workerId > 0) {
                sb.append("  [w").append(workerId).append(']');
            }
            return sb.toString();
        }
    }
}
