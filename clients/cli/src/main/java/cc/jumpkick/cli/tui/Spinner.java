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
 * #PULSE_GLYPH}) <em>pulses</em> by lerping its foreground between two colors and back — the same
 * breathing effect as the web dashboard's live indicators (no multi-glyph thrash).
 *
 * <p>Two pulse palettes:
 *
 * <ul>
 *   <li><b>Open</b> (bare terminal, no chip background) — brand blue ↔ almost-black blue
 *       ({@link #buildOpenPulseStyles}).
 *   <li><b>Chip</b> (CommandWedge / pipeline pill with a solid background) — white ↔ chip blue
 *       ({@link #buildChipPulseStyles}), so the glyph stays readable on the colored pill.
 * </ul>
 *
 * <p>Layout: {@code <circle> <message>} on the current line.
 *
 * <p>Cursor hidden between {@link #show} and {@link #close()}. Thread-safe {@link #update}/{@link
 * #close}.
 */
public final class Spinner implements AutoCloseable {

    /** Solid circle used for the pulse animation (U+25CF) — CommandWedge / open pulse. */
    public static final String PULSE_GLYPH = "●";

    /**
     * Filling-circle phases for tree rows under the progress bar (not the CommandWedge). Cycle:
     * white circle → bullseye → fisheye → bullseye, each held for {@link #FILL_HOLD} animator frames,
     * constant blue. Distinct glyphs only — hold is applied in {@link #fillGlyph(int)}, not by
     * repeating entries (the pipeline painter also skips rewriting a tree line when its text is
     * unchanged, so held frames are free).
     *
     * <ul>
     *   <li>U+25CB ○ white circle
     *   <li>U+25CE ◎ bullseye
     *   <li>U+25C9 ◉ fisheye
     * </ul>
     */
    public static final String[] FILL_PHASES = {
        "\u25CB", // ○
        "\u25CE", // ◎
        "\u25C9", // ◉
        "\u25CE", // ◎
    };

    /** Animator frames to hold each {@link #FILL_PHASES} glyph before advancing. */
    public static final int FILL_HOLD = 4;

    /** Frames in one full fill cycle ({@code FILL_PHASES.length * FILL_HOLD}). */
    public static final int FILL_FRAMES = FILL_PHASES.length * FILL_HOLD;

    /** Frames in one full bright→dim→bright cycle (odd so the midpoint lands exactly on dim). */
    static final int PULSE_FRAMES = 25;

    /** Interval between pulse frames (2.0s per full breath at 25 frames). */
    static final long FRAME_MS = cc.jumpkick.runtime.WorkspaceProgressTracker.TTY_FRAME_MS;

    /**
     * Glyph for animator frame {@code i} (wraps). Same glyph for {@link #FILL_HOLD} consecutive
     * frames so the paint path can no-op on tree lines until the phase actually changes.
     */
    public static String fillGlyph(int i) {
        int phase = Math.floorMod(i, FILL_FRAMES) / FILL_HOLD;
        return FILL_PHASES[phase];
    }

    /**
     * Bright end of the open (no-background) pulse — brand run blue ({@code #3D9BFF}, web {@code
     * --run} / Jk Dark primary).
     */
    static final Rgb PULSE_OPEN_BRIGHT = Rgb.hex(0x3D9BFF);

    /**
     * Dim end of the open pulse — almost-black blue in the same family (~10% of primary so it
     * still reads blue, not pure black).
     */
    static final Rgb PULSE_OPEN_DIM = PULSE_OPEN_BRIGHT.scaled(0.10);

    /** White end of the chip pulse (glyph on a solid blue pill). */
    static final Rgb PULSE_CHIP_BRIGHT = Rgb.hex(0xFFFFFF);

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
        // Standalone spinner sits on the terminal background — open blue↔dark-blue pulse.
        this.frameColors = buildOpenPulseStyles(PULSE_FRAMES);
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
     * Open-terminal pulse (no chip background): brand blue at the ends of the cycle, almost-black
     * blue at the midpoint.
     */
    static AttributedStyle[] buildOpenPulseStyles(int n) {
        return buildPulseStyles(n, PULSE_OPEN_BRIGHT, PULSE_OPEN_DIM);
    }

    /**
     * Chip / wedge pulse (glyph painted on a solid colored pill): white at the ends, {@code dim}
     * (typically the chip blue) at the midpoint — same as historical behavior so the glyph stays
     * readable on the blue background.
     */
    static AttributedStyle[] buildChipPulseStyles(int n, Rgb dim) {
        return buildPulseStyles(n, PULSE_CHIP_BRIGHT, dim);
    }

    /** Pulse styles: {@code bright} at the ends of the cycle, {@code dim} at the midpoint. */
    static AttributedStyle[] buildPulseStyles(int n, Rgb bright, Rgb dim) {
        Gradient gradient = new Gradient(bright, dim);
        AttributedStyle[] a = new AttributedStyle[n];
        for (int i = 0; i < n; i++) {
            a[i] = Theme.active().bright(gradient.at(pulseWave(i, n)));
        }
        return a;
    }

    /** 0 at frame 0 and last, 1 at the midpoint — bright→dim→bright when used as gradient {@code t}. */
    static double pulseWave(int frame, int n) {
        if (n <= 1) return 0.0;
        double t = (double) frame / (n - 1); // 0..1
        return t <= 0.5 ? t * 2.0 : (1.0 - t) * 2.0;
    }
}
