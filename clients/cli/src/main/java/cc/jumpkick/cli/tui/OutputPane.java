// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.run.TaskNames;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;

/**
 * The sliding process-output pane under the live region, its Ctrl-O key, and the stdout/stderr
 * swap that feeds it. Two pieces of process-global state live here and are named rather than
 * hidden: {@link OutputCapture} swaps the process's {@code System.out} and {@code System.err} for
 * the region's life, and {@link PeekKeys} rewrites the controlling terminal's attributes for the key
 * listener. Receives the manager's monitor and takes it exactly where the manager did, because
 * opening or closing the pane repaints the region.
 */
public final class OutputPane {

    private final JkManager m;
    private final Object lock;

    /** Always present; only plan+animate installs the key listener. */
    private final OutputWindow window = new OutputWindow();

    private final OutputCapture capture;

    /**
     * Ctrl-O peek listener (plan mode on an interactive TTY only); {@code null} when the controlling
     * terminal is unavailable. Written by the plan-starting thread, read by whichever thread settles
     * — often the SIGINT handler. {@link PeekKeys} owns the terminal attributes and the atomicity of
     * giving them back.
     */
    private volatile @Nullable PeekKeys keys;

    OutputPane(JkManager m, Object lock) {
        this.m = m;
        this.lock = lock;
        this.capture = new OutputCapture(m::writeProcessOutput);
    }

    /** The sliding buffer itself (tests / force-show). */
    OutputWindow window() {
        return window;
    }

    /** Install the Ctrl-O listener; {@code closed} tells it when the region is gone. */
    void attachPeekKey(BooleanSupplier closed) {
        keys = PeekKeys.attach(this::toggle, closed);
    }

    /** Give the terminal its attributes back. Idempotent. */
    void releasePeekKey() {
        PeekKeys k = keys;
        if (k != null) k.close();
    }

    /** Flush a captured partial line that has gone quiet; called outside the lock by the frame loop. */
    void flushStale(long quietMs) {
        capture.flushStale(quietMs);
    }

    /**
     * Force-open the process-output pane (non-zero tool/worker exit). No-op when not animating a
     * plan. Does not run for test failures — callers must not invoke this for run-tests.
     */
    void showOnFailure() {
        if (!m.planMode) return;
        synchronized (lock) {
            if (m.done) return;
            boolean wasOpen = window.visible();
            window.show();
            if (m.animate && Theme.active().isAnsi() && !wasOpen) {
                m.view.openPeekPaint();
            } else if (m.animate && Theme.active().isAnsi()) {
                m.view.requestFullRepaint();
                m.view.paintBuildPlan();
                m.out.flush();
            } else if (!Theme.active().isAnsi()
                    && !SessionContext.current().config().verboseOr(false)) {
                // Plain mode buffers tool stdout (suppressed unless -v); a crash is the one
                // moment it must surface — verbose already printed it live.
                m.plain.dumpProcessOutput();
            }
        }
    }

    /** Toggle the process-output pane (Ctrl-O). */
    void toggle() {
        if (!m.planMode) return;
        synchronized (lock) {
            if (m.done) return;
            if (window.visible()) {
                window.hide();
                if (m.animate && Theme.active().isAnsi()) m.view.closePeekPaint();
            } else {
                window.show();
                if (m.animate && Theme.active().isAnsi()) m.view.openPeekPaint();
            }
        }
    }

    /**
     * Peek close for settle/cancel: process lines are already permanent scrollback above the live
     * region — hide the pane so wipe clears separator+wedge (not a re-dump of the log). The settle
     * path then prints one blank between that scrollback and the settle chip when any lines were
     * committed.
     */
    void flushVisibleToScrollback() {
        synchronized (lock) {
            if (!window.visible()) return;
            // Lines were committed above the region as they arrived; leave them in scrollback.
            window.hide();
        }
    }

    /**
     * Redirect {@code System.out}/{@code System.err} so any process/step output is line-buffered and
     * printed <em>above</em> the live region via {@link #writeAbove}, keeping the region pinned to
     * the bottom. The region itself keeps painting to the original (captured) stdout, so there's no
     * recursion. Close the returned scope (try-with-resources) to restore the streams and flush any
     * trailing partial line. No-op when not animating.
     */
    JkManager.OutputScope captureOutput() {
        if (!m.animate || !capture.start()) return () -> {};
        return capture::restore;
    }

    /** Hand the real streams back, flushing a trailing partial line. Idempotent. */
    void restoreStreams() {
        capture.restore();
    }

    /** A failed tool/worker step force-opens the process-output pane; a curated test failure does not. */
    public static boolean forceShowOnStepFailure(String step) {
        return !isCuratedTestStep(step);
    }

    /**
     * The curated test-runner step ({@code run-tests} + forks) — keyed on step identity, never the
     * step's group: compile-test failures are group Test too and must force-open (tool output).
     */
    static boolean isCuratedTestStep(String stepKey) {
        return stepKey != null && stepKey.startsWith(TaskNames.RUN_TESTS);
    }
}
