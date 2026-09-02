// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.Osc;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.terminal.Ansi;
import java.io.PrintStream;
import java.util.function.Supplier;

/**
 * The mechanics of a one-row live terminal region: decide whether to animate at all, open the
 * command envelope, hide the cursor, repaint a frame in place, drive the OS taskbar, and tear down
 * on completion or Ctrl-C.
 *
 * <p><strong>Why this is a type and not a base class or a copy.</strong> Every one of these steps is
 * easy to get individually right and easy to get collectively wrong, and the failure is invisible
 * until it is on someone's screen: forget the clip and {@code \r} rewinds the wrong physical row so
 * frames stack; forget {@code ERASE_LINE_TO_END} and the previous frame's tail survives
 * under the new one; forget {@link Ansi#SHOW_CURSOR} on the cancel path and the user's terminal is
 * left with no cursor; check {@code silent} before running the caller's teardown and a
 * {@code --no-progress} cancel leaks whatever the operation was mid-way through. {@code
 * JdkDownloadBar} had its own copy of all of it and was wrong about the first two.
 *
 * <p>What stays with the caller is the <em>content</em>: a {@link Frame} for the animating row and a
 * settle line for the cancel. Both are already-composed strings, so geometry and chrome keep their
 * own owners — {@link JkWedge#renderLiveLine} clips, {@link Progress} sizes the bar, {@link
 * JkWedge#cancelled} paints the settle — and this type never decides how anything looks.
 *
 * <p>Three states, decided here rather than by each caller, because they are properties of the
 * output stream and not of the widget:
 *
 * <ul>
 * <li><b>silent</b> — {@code --no-progress} or a machine-consumed stdout. Nothing is written but the
 * command envelope; the same rule already governs {@link Spinner}.
 * <li><b>plain</b> — {@code --no-ansi}. Says what happened, in ASCII chrome, but paints no moving
 *     row: every mechanism a moving row needs is an escape sequence, and writing those into a stream
 * that declared it cannot read them is the invariant broken.
 * <li><b>animating</b> — a real terminal. The full in-place row.
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

    private final PrintStream out;
    private final boolean silent;
    private final boolean animate;
    private final Frame frame;
    private final Runnable onCancel;
    private final Supplier<String> settleLine;

    private int tick;
    private boolean drawn;
    private boolean closed;
    private Thread animator;

    private LiveLine(
            PrintStream out,
            boolean silent,
            boolean animate,
            Frame frame,
            Runnable onCancel,
            Supplier<String> settleLine) {
        this.out = out;
        this.silent = silent;
        this.animate = animate;
        this.frame = frame;
        this.onCancel = onCancel;
        this.settleLine = settleLine;
    }

    /**
     * Take over one row of {@code out}, animating it when the stream can carry it.
     *
     * @param frame the animating row
     * @param onCancel teardown the caller needs on Ctrl-C, run whether or not anything was painted;
     *     may be {@code null}
     * @param settleLine the line to leave behind on Ctrl-C, or {@code null} to just wipe the row
     */
    static LiveLine open(PrintStream out, Frame frame, Runnable onCancel, Supplier<String> settleLine) {
        boolean silent = SessionContext.current().config().noProgressOr(false) || CliOutput.scriptMode();
        // Plain is not the same as silent, and neither is the same as animating. --no-ansi still
        // says what happened; it just may not paint a moving row, because every mechanism a moving
        // row needs — hide the cursor, erase to end of line, drive the OS taskbar — is an escape
        // sequence, and writing those into a stream that declared it cannot read them is the
        // invariant broken. JdkDownloadBar emitted all three under --no-ansi until a test
        // asked; Spinner had always split the two.
        boolean animate = !silent && Theme.active().isAnsi();
        LiveLine line = new LiveLine(out, silent, animate, frame, onCancel, settleLine);
        // Leading blank of the human chrome envelope (idempotent per command).
        CommandWedge.envelopeStart(out);
        LiveRegion.setActive(line);
        if (animate) {
            out.print(Ansi.HIDE_CURSOR);
            out.flush();
            line.startAnimator();
        }
        return line;
    }

    /** Whether a moving row is being painted; callers skip work they would only paint. */
    boolean animating() {
        return animate;
    }

    /** Drive the OS taskbar to {@code percent} (0–100). No-op unless animating. */
    synchronized void progress(int percent) {
        if (closed || !animate) return;
        out.print(Osc.taskbarProgress(Math.max(0, Math.min(100, percent))));
    }

    /**
     * Wipe the row and give the cursor back. The caller prints its own result line afterwards, which
     * takes the cleared row's place on screen.
     */
    synchronized void finish() {
        if (closed) return;
        closed = true;
        stopAnimator();
        LiveRegion.clearActive(this);
        if (!animate) return;
        if (drawn) out.print(Ansi.CLEAR_LINE);
        out.print(Osc.taskbarClear());
        out.print(Ansi.SHOW_CURSOR);
        out.flush();
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

    private void startAnimator() {
        animator = new Thread(
                () -> {
                    while (!closed) {
                        try {
                            Thread.sleep(Spinner.FRAME_MS);
                        } catch (InterruptedException e) {
                            break;
                        }
                        synchronized (this) {
                            if (!closed) {
                                repaint();
                                tick = (tick + 1) % Spinner.PULSE_FRAMES;
                            }
                        }
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
        out.print("\r");
        out.print(frame.render(tick));
        out.print(Ansi.ERASE_LINE_TO_END);
        out.flush();
        drawn = true;
    }
}
