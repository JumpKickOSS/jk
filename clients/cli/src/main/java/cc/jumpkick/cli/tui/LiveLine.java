// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.Osc;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.terminal.Ansi;
import java.io.PrintStream;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * The mechanics of a one-row live terminal region: decide whether to animate at all, open the
 * command envelope, hide the cursor, repaint a frame in place, drive the OS taskbar, and tear down
 * on completion or Ctrl-C.
 *
 * <p><strong>Why this is a type and not a base class or a copy.</strong> Every one of these steps is
 * easy to get individually right and easy to get collectively wrong, and the failure is invisible
 * until it is on someone's screen: forget the clip and {@code \r} rewinds the wrong physical row so
 * frames stack; forget {@code ERASE_LINE_TO_END} and the previous frame's tail survives under the
 * new one; forget {@link Ansi#SHOW_CURSOR} on the cancel path and the user's terminal is left with
 * no cursor; check {@code silent} before running the caller's teardown and a {@code --no-progress}
 * cancel leaks whatever the operation was mid-way through.
 *
 * <p>What stays with the caller is the <em>content</em>: a {@link Frame} for the animating row, an
 * optional plain-mode {@linkplain Builder#heartbeat heartbeat}, and a settle line for the cancel.
 * The row is an already-composed, already-clipped string, so geometry and chrome keep their own
 * owners — {@link JkWedge#renderLiveLine} clips, {@link Progress} sizes the bar, {@link
 * JkWedge#cancelled} paints the settle — and this type never decides how anything looks.
 *
 * <p>Three states, decided here rather than by each caller, because they are properties of the
 * output stream and not of the widget:
 *
 * <ul>
 * <li><b>silent</b> — {@code --no-progress} or a machine-consumed stdout. Nothing is written but the
 *     command envelope.
 * <li><b>plain</b> — {@code --no-ansi}. Says what happened, in ASCII chrome, but paints no moving
 *     row: every mechanism a moving row needs is an escape sequence, and writing those into a stream
 *     that declared it cannot read them is the invariant broken. The animator thread still runs,
 *     and calls the caller's heartbeat instead of painting; the caller decides what a plain
 *     "still working" looks like and how often to say it.
 * <li><b>animating</b> — a real terminal. The full in-place row, one frame every {@link
 *     Spinner#FRAME_MS}, the taskbar told the region's state on every frame.
 * </ul>
 *
 * <p>A silent or plain region still registers as the active {@link LiveRegion} and still runs the
 * caller's cancel teardown — bytes on disk do not care whether anything was painted.
 */
final class LiveLine implements AutoCloseable, LiveRegion {

    /** The row for animator frame {@code frame}: one line, already clipped to the terminal. */
    interface Frame {
        String render(int frame);
    }

    /** Poll interval of the plain-mode heartbeat; the caller's hook decides whether to say anything. */
    static final long PLAIN_TICK_MS = Math.min(Spinner.FRAME_MS * 10, 5_000L);

    /** Taskbar state before any progress has been reported. */
    private static final int INDETERMINATE = -1;

    private final PrintStream out;
    private final boolean silent;
    private final boolean animate;
    private final Frame frame;
    private final @Nullable Runnable heartbeat;
    private final @Nullable Runnable onCancel;
    private final @Nullable Supplier<String> settleLine;

    private int tick;
    private int percent = INDETERMINATE;
    private boolean drawn;
    private boolean closed;
    private @Nullable Thread animator;

    private LiveLine(Builder b) {
        this.out = b.out;
        this.frame = b.frame;
        this.heartbeat = b.heartbeat;
        this.onCancel = b.onCancel;
        this.settleLine = b.settleLine;
        this.silent = SessionContext.current().config().noProgressOr(false) || CliOutput.scriptMode();
        this.animate = !silent && Theme.active().isAnsi();
    }

    /** Describe a region over one row of {@code out}; {@link Builder#open()} takes the row. */
    static Builder of(PrintStream out, Frame frame) {
        return new Builder(out, frame);
    }

    /** What a caller may add to a region before opening it; every part is optional. */
    static final class Builder {
        private final PrintStream out;
        private final Frame frame;
        private @Nullable Runnable heartbeat;
        private @Nullable Runnable onCancel;
        private @Nullable Supplier<String> settleLine;

        private Builder(PrintStream out, Frame frame) {
            this.out = out;
            this.frame = frame;
        }

        /**
         * Called every {@link #PLAIN_TICK_MS} in plain mode instead of painting a frame; the hook
         * owns its own cadence. Never called when silent or animating.
         */
        Builder heartbeat(Runnable heartbeat) {
            this.heartbeat = heartbeat;
            return this;
        }

        /** Teardown the caller needs on Ctrl-C, run whether or not anything was painted. */
        Builder onCancel(Runnable onCancel) {
            this.onCancel = onCancel;
            return this;
        }

        /** The line to leave behind on Ctrl-C; without one the row is wiped and nothing replaces it. */
        Builder settle(Supplier<String> settleLine) {
            this.settleLine = settleLine;
            return this;
        }

        /**
         * Take over the row: open the envelope, register for Ctrl-C, and — when the stream can carry
         * it — hide the cursor, tell the taskbar, and start the animator.
         */
        LiveLine open() {
            LiveLine line = new LiveLine(this);
            // Leading blank of the human chrome envelope (idempotent per command).
            CommandWedge.envelopeStart(out);
            LiveRegion.setActive(line);
            if (line.animate) {
                out.print(Ansi.HIDE_CURSOR);
                out.print(line.taskbar());
                out.flush();
            }
            line.startAnimator();
            return line;
        }

        /**
         * The region without the terminal: registered for Ctrl-C, nothing written, no animator. The
         * owner drives it through {@link #step()} and {@link #finish()}. A test seam.
         */
        LiveLine still() {
            LiveLine line = new LiveLine(this);
            LiveRegion.setActive(line);
            return line;
        }
    }

    /** Whether a moving row is being painted; callers skip work they would only paint. */
    boolean animating() {
        return animate;
    }

    /** Whether this region says what happened in ASCII lines: not silent, not animating. */
    boolean plain() {
        return !silent && !animate;
    }

    /** Drive the OS taskbar to {@code percent} (0–100). No-op unless animating. */
    synchronized void progress(int percent) {
        this.percent = Math.max(0, Math.min(100, percent));
        if (closed || !animate) return;
        out.print(taskbar());
    }

    /**
     * One beat of the region, as the animator would take it: paint the next frame when animating,
     * run the heartbeat when plain, nothing when silent or finished.
     */
    synchronized void step() {
        if (closed) return;
        if (animate) {
            repaint();
            tick = (tick + 1) % Spinner.PULSE_FRAMES;
        } else if (heartbeat != null && !silent) {
            heartbeat.run();
        }
    }

    /**
     * Wipe the row and give the cursor back. The caller prints its own result line afterwards, which
     * takes the cleared row's place on screen.
     *
     * @return {@code true} when this call closed the region; {@code false} when it was already closed
     */
    synchronized boolean finish() {
        if (closed) return false;
        closed = true;
        stopAnimator();
        LiveRegion.clearActive(this);
        if (!animate) return true;
        if (drawn) out.print(Ansi.CLEAR_LINE);
        out.print(Osc.taskbarClear());
        out.print(Ansi.SHOW_CURSOR);
        out.flush();
        return true;
    }

    @Override
    public void close() {
        finish();
    }

    @Override
    public synchronized boolean renderCanceled() {
        if (closed) return false;
        closed = true;
        stopAnimator();
        LiveRegion.clearActive(this);
        // Before the silent check: --no-progress and script mode cancel too, and Ctrl-C ends in
        // Runtime.halt, which runs no shutdown hook and unwinds no stack — so this is the caller's
        // only chance to clean up whatever it had in flight.
        if (onCancel != null) onCancel.run();
        if (silent) return false;
        String settle = settleLine == null ? null : settleLine.get();
        if (animate) {
            // Wipe the row the frames were using, then settle in its place.
            out.print("\r");
            out.print(Ansi.ERASE_LINE_TO_END);
        }
        if (settle != null) out.println(settle);
        if (animate) {
            out.print(Osc.taskbarClear());
            out.print(Ansi.SHOW_CURSOR);
        }
        out.flush();
        return settle != null;
    }

    /** The animator: frames when animating, heartbeats when plain, no thread at all when silent. */
    private void startAnimator() {
        if (silent || (!animate && heartbeat == null)) return;
        long interval = animate ? Spinner.FRAME_MS : PLAIN_TICK_MS;
        animator = new Thread(
                () -> {
                    while (!closed) {
                        try {
                            Thread.sleep(interval);
                        } catch (InterruptedException e) {
                            break;
                        }
                        step();
                    }
                },
                "jk-live-line");
        animator.setDaemon(true);
        animator.start();
    }

    private void stopAnimator() {
        if (animator != null) {
            animator.interrupt();
            animator = null;
        }
    }

    private void repaint() {
        out.print(taskbar());
        out.print("\r");
        out.print(frame.render(tick));
        out.print(Ansi.ERASE_LINE_TO_END);
        out.flush();
        drawn = true;
    }

    /** The taskbar told what the region knows: a percentage once one was reported, busy until then. */
    private String taskbar() {
        return percent == INDETERMINATE ? Osc.taskbarIndeterminate() : Osc.taskbarProgress(percent);
    }
}
