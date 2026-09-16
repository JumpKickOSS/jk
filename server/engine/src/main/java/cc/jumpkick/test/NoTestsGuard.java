// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.run.TestSummary;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Turns the runner's {@code no-tests-discovered} warning into a failed step. The runner emits it
 * when test classes exist and discovery — with and without the tag filters — found no test in
 * them: a Platform that dropped the framework's engine, a framework classloader that failed. A
 * summary of zero tests and zero failures under that warning is not a green run.
 */
final class NoTestsGuard implements TestProgressListener {

    /** The runner's warning code; {@code LauncherPath.NO_TESTS_DISCOVERED} in the test-runner plugin. */
    static final String CODE = "no-tests-discovered";

    private final TestProgressListener delegate;
    private @Nullable String message;

    NoTestsGuard(TestProgressListener delegate) {
        this.delegate = delegate;
    }

    /** {@code summary} unless the runner reported an empty discovery over a suite that ran nothing. */
    TestSummary verdict(TestSummary summary, String moduleLabel) {
        if (message == null || summary.total() != 0 || summary.failed() != 0) return summary;
        return new TestSummary(
                        1, 0, 1, 0, List.of(new TestFailureInfo(moduleLabel, "", "", "(test run)", "", message, "")))
                .withWorkers(summary.workers());
    }

    @Override
    public void onWarning(String code, String message) {
        if (CODE.equals(code)) this.message = message;
        delegate.onWarning(code, message);
    }

    @Override
    public void onDiscoveryTotal(int classes, int tests) {
        delegate.onDiscoveryTotal(classes, tests);
    }

    @Override
    public void onTestStarted(String id, String display, boolean isTest, int workerId) {
        delegate.onTestStarted(id, display, isTest, workerId);
    }

    @Override
    public void onTestFinished(
            String id,
            String display,
            String status,
            boolean isTest,
            boolean wasStatic,
            long durationMs,
            int workerId) {
        delegate.onTestFinished(id, display, status, isTest, wasStatic, durationMs, workerId);
    }

    @Override
    public void onTestSkipped(
            String id, String display, String reason, boolean isTest, boolean wasStatic, int workerId) {
        delegate.onTestSkipped(id, display, reason, isTest, wasStatic, workerId);
    }

    @Override
    public void onUserOutput(int workerId, String line) {
        delegate.onUserOutput(workerId, line);
    }

    @Override
    public void onFailure(
            String id,
            String label,
            String exClass,
            String message,
            String stack,
            String engine,
            String className,
            String method,
            int workerId) {
        delegate.onFailure(id, label, exClass, message, stack, engine, className, method, workerId);
    }
}
