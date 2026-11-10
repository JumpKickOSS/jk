// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.test.TestProgressListener;
import org.jspecify.annotations.Nullable;

/**
 * Keeps the runner's {@code tag-excluded} warning, which names each {@code --class} match the tag
 * filter dropped and the flag that runs it, so an empty {@code --class} run can fail with that cause
 * instead of a generic no-match. Every other event still reaches {@code delegate}.
 *
 * <p>The runner takes any class filter for a selection, and a run that named no class still hands
 * it one when classes are only taken away: {@code [test] exclude-classes}, or the classes a re-run
 * under the same stamp need not run ({@link TestClassLedger}). Such a run named nothing for the tag
 * filter to drop, so its warning is not passed on.
 */
final class TagExcludedCapture implements TestProgressListener {

    /** The runner's warning code; {@code LauncherPath} in the test-runner plugin raises it. */
    static final String CODE = "tag-excluded";

    private final TestProgressListener delegate;
    private final boolean named;
    private volatile @Nullable String message;

    /** @param named whether the run named classes: {@code --class} patterns, or an exact class list */
    TagExcludedCapture(TestProgressListener delegate, boolean named) {
        this.delegate = delegate;
        this.named = named;
    }

    /** The warning's text, or {@code null} when the tag filter dropped no named class. */
    @Nullable
    String message() {
        return message;
    }

    @Override
    public void onWarning(String code, String message) {
        if (CODE.equals(code)) {
            if (!named) return;
            if (message != null && !message.isBlank()) this.message = message;
        }
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
