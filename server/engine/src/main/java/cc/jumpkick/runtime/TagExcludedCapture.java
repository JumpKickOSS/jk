// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.test.TestProgressListener;
import org.jspecify.annotations.Nullable;

/**
 * Keeps the runner's {@code tag-excluded} warning, which names each {@code --class} match the tag
 * filter dropped and the flag that runs it, so an empty {@code --class} run can fail with that cause
 * instead of a generic no-match. Every event still reaches {@code delegate}.
 */
final class TagExcludedCapture implements TestProgressListener {

    /** The runner's warning code; {@code LauncherPath} in the test-runner plugin raises it. */
    static final String CODE = "tag-excluded";

    private final TestProgressListener delegate;
    private volatile @Nullable String message;

    TagExcludedCapture(TestProgressListener delegate) {
        this.delegate = delegate;
    }

    /** The warning's text, or {@code null} when the tag filter dropped no named class. */
    @Nullable
    String message() {
        return message;
    }

    @Override
    public void onWarning(String code, String message) {
        if (CODE.equals(code) && message != null && !message.isBlank()) this.message = message;
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
