// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.Ansi;
import cc.jumpkick.cli.theme.Gradient;
import cc.jumpkick.cli.theme.Rgb;
import cc.jumpkick.cli.theme.Theme;
import java.io.PrintStream;
import org.jline.utils.AttributedStyle;

/**
 * Single-line animated spinner for indeterminate CLI work. A solid circle glyph ({@value
 * #PULSE_GLYPH}) <em>pulses</em> by lerping its foreground from white to a dim end color and back —
 * the same breathing effect as the web dashboard's live indicators (no multi-glyph thrash).
 *
 * <p>Layout: {@code <circle> <message>} on the current line.
 *
 * <p>Cursor hidden between {@link #show} and {@link #close()}. Thread-safe {@link #update}/{@link
 * #close}.
 */
public final class Spinner implements AutoCloseable {

    /** Solid circle used for the pulse animation (U+25CF). */
    public static final String PULSE_GLYPH = "●";

    /**
     * @deprecated Use {@link #PULSE_GLYPH}; kept as a single-frame array for older tests that index
     *     {@code FRAMES[0]}.
     */
    @Deprecated
    static final String[] FRAMES = {PULSE_GLYPH};

    /** Frames in one full white→dim→white cycle (odd so the midpoint lands exactly on dim). */
    static final int PULSE_FRAMES = 25;

    /** Interval between pulse frames (~1.9s per full breath at 24 frames). */
    static final long FRAME_MS = 80L;

    /** Dim end of the pulse when the spinner sits on the terminal (not on a chip). */
    static final Rgb PULSE_DIM = Rgb.hex(0x090C11); // web --bg

    private static final String HIDE_CURSOR = Ansi.HIDE_CURSOR;
    private static final String SHOW_CURSOR = Ansi.SHOW_CURSOR;
    private static final String CLEAR_LINE = Ansi.CLEAR_LINE;

    static final String OSC_INDETERMINATE = Ansi.TASKBAR_INDETERMINATE;
    static final String OSC_CLEAR = Ansi.TASKBAR_CLEAR;

    private final PrintStream out;
    private final AttributedStyle[] frameColors;
    private final Object lock = new Object();
    private final boolean silent;

    private volatile String message;
    private int frame = 0;
    private String lastMessage = "";
    private volatile boolean closed = false;
    private Thread animator;

    public static Spinner show(PrintStream out, String message) {
        Spinner s = new Spinner(out, message);
        s.start();
        return s;
    }

    Spinner(PrintStream out, String message) {
        this.out = out;
        this.message = message == null ? "" : message;
        this.frameColors = buildPulseStyles(PULSE_FRAMES, PULSE_DIM);
        this.silent = cc.jumpkick.config.SessionContext.current().config().noProgressOr(false);
    }

    private void start() {
        if (silent) return;
        out.print(HIDE_CURSOR);
        out.print(OSC_INDETERMINATE);
        out.flush();
        animator = new Thread(this::loop, "jk-spinner");
        animator.setDaemon(true);
        animator.start();
    }

    private void loop() {
        while (!closed) {
            step();
            try {
                Thread.sleep(FRAME_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    public void update(String message) {
        this.message = message == null ? "" : message;
    }

    void step() {
        synchronized (lock) {
            if (closed || silent) return;
            String currentMsg = message;
            out.print(OSC_INDETERMINATE);
            out.print("\r");
            out.print(Theme.colorize(PULSE_GLYPH, frameColors[frame]));
            out.print(" ");
            out.print(currentMsg);
            int shrink = lastMessage.length() - currentMsg.length();
            if (shrink > 0) out.print(" ".repeat(shrink));
            out.flush();
            lastMessage = currentMsg;
            frame = (frame + 1) % PULSE_FRAMES;
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (animator != null) animator.interrupt();
        if (silent) return;
        synchronized (lock) {
            out.print(CLEAR_LINE);
            out.print(OSC_CLEAR);
            out.print(SHOW_CURSOR);
            out.flush();
        }
    }

    /**
     * Pulse styles: white at the ends of the cycle, {@code dim} at the midpoint (triangle wave on a
     * white→dim gradient).
     */
    static AttributedStyle[] buildPulseStyles(int n, Rgb dim) {
        Gradient gradient = new Gradient(Rgb.hex(0xFFFFFF), dim);
        AttributedStyle[] a = new AttributedStyle[n];
        for (int i = 0; i < n; i++) {
            a[i] = Theme.active().bright(gradient.at(pulseWave(i, n)));
        }
        return a;
    }

    /** 0 at frame 0 and last, 1 at the midpoint — white→dim→white when used as gradient {@code t}. */
    static double pulseWave(int frame, int n) {
        if (n <= 1) return 0.0;
        double t = (double) frame / (n - 1); // 0..1
        return t <= 0.5 ? t * 2.0 : (1.0 - t) * 2.0;
    }

    /**
     * @deprecated Prefer {@link #buildPulseStyles}; retained for callers that still expect a linear
     *     gradient of length {@code n}.
     */
    @Deprecated
    static AttributedStyle[] buildGradient(int n) {
        return buildPulseStyles(n, PULSE_DIM);
    }
}
