// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.api.Osc;
import cc.jumpkick.cli.theme.Gradient;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.terminal.Ansi;
import cc.jumpkick.terminal.RegionPainter;
import cc.jumpkick.terminal.Style;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;

/**
 * Single-line progress bar widget ({@code ▰…▱… 62%: status}) for long IO (JDK download/install).
 * Active {@link LiveRegion} for Ctrl-C repaint; {@link #update} is synchronized for multi-thread
 * producers. Hides the cursor between {@link #show} and {@link #close}.
 */
public final class SpinnerProgressBar implements AutoCloseable, LiveRegion {

    static final int SEGMENTS = 40;
    static final char FILLED_CHAR = '▰';
    static final char EMPTY_CHAR = '▱';

    private static char filledChar() {
        return Theme.active().isAnsi() ? FILLED_CHAR : Glyphs.BAR_FULL_PLAIN;
    }

    private static char emptyChar() {
        return Theme.active().isAnsi() ? EMPTY_CHAR : Glyphs.BAR_EMPTY_PLAIN;
    }

    /** Right-aligned percent column: " 5%", " 62%", "100%". */
    static final int PERCENT_WIDTH = 4;

    static final String GAP = "  ";
    static final String SEPARATOR = ": ";

    private static final String HIDE_CURSOR = Ansi.HIDE_CURSOR;
    private static final String SHOW_CURSOR = Ansi.SHOW_CURSOR;

    // OSC 9;4 — ConEmu-introduced taskbar/tab progress indicator, now
    // supported by Windows Terminal, WezTerm, ghostty, kitty (≥0.31),
    // and others. Format: ESC ] 9 ; 4 ; <state> [ ; <percent> ] BEL.
    // States: 0 = clear, 1 = normal, 2 = error, 3 = indeterminate,
    // 4 = warning. We use 1 (normal) while the bar advances and 0
    // (clear) on close. BEL terminator is more universally honored
    // than ST in OSC handling. Terminals that don't recognise OSC 9;4
    // silently swallow the sequence in their OSC parser.

    private final PrintStream out;
    private final Style[] segmentColors;
    private final Style[] failColors;

    /** Empty glyphs take the gradient's left-most (darkest) color, not a neutral dim. */
    private final Style emptyStyle;

    private final boolean silent;

    private int lastFilled = 0;
    private String lastPercent = "";
    private String lastStatus = "";
    private boolean closed = false;
    private int lastPercentVal = 0;
    private final RegionPainter painter = new RegionPainter(RegionPainter.Park.IN_ROW);

    private SpinnerProgressBar(PrintStream out, boolean silent) {
        this.out = out;
        this.silent = silent;
        // The fill runs green → bright-green (bright-green pinned at the frontier).
        this.segmentColors = buildGradient(SEGMENTS, Theme.active().progressGradient());
        this.failColors = buildGradient(SEGMENTS, Theme.active().failureGradient());
        this.emptyStyle = segmentColors[0];
    }

    /**
     * Start a new bar on the caller's current line, hiding the cursor. If the resolved config has
     * {@code --no-progress}, returns a silent instance whose {@link #update} / {@link #close} are
     * no-ops.
     */
    public static SpinnerProgressBar show(PrintStream out) {
        boolean silent = SessionContext.current().config().noProgressOr(false);
        SpinnerProgressBar pb = new SpinnerProgressBar(out, silent);
        LiveRegion.setActive(pb);
        if (!silent) {
            out.print(HIDE_CURSOR);
            out.flush();
        }
        return pb;
    }

    /**
     * Update progress and status. {@code percent} is clamped to [0, 100]; {@code status} is rendered
     * after the percent, separated by {@code ": "}.
     */
    public synchronized void update(int percent, String status) {
        if (closed || silent) return;
        int clamped = Math.max(0, Math.min(100, percent));
        int filled = (int) Math.round(clamped * SEGMENTS / 100.0);
        String percentStr = String.format("%3d%%", clamped);
        String statusStr = status == null ? "" : status;

        StringBuilder f = new StringBuilder();
        painter.status(f, Osc.taskbarProgress(clamped));
        painter.paint(f, List.of(row(filled, clamped, percentStr, statusStr)), 0, 0, 0);
        out.print(f);
        out.flush();
        lastFilled = filled;
        lastPercent = percentStr;
        lastPercentVal = clamped;
        lastStatus = statusStr;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        LiveRegion.clearActive(this);
        if (silent) return;
        // Wipe the bar line entirely on the way out. Without the
        // CR + erase-line, a partial bar (e.g. a plan that failed
        // before any progress) would stay in the transcript next to
        // the failure summary that follows.
        wipe();
        out.print(Osc.taskbarClear());
        out.print(SHOW_CURSOR);
        out.flush();
    }

    /**
     * Wipe the progress-bar line and replace it with {@code message} on the same row, then close the
     * bar. Use this when the bar shouldn't linger in the transcript — e.g., a "Download finished"
     * line takes its place. Subsequent {@link #close()} calls are no-ops.
     */
    public synchronized void finish(String message) {
        if (closed) return;
        closed = true;
        LiveRegion.clearActive(this);
        if (silent) {
            // With --no-progress the bar never drew, so we don't need to wipe
            // a line — but the final message is still useful to the user, so
            // emit it as a plain println.
            out.println(message);
            out.flush();
            return;
        }
        out.print(Ansi.CLEAR_LINE); // clear the bar line
        painter.forget();
        out.print(Osc.taskbarClear());
        out.print(SHOW_CURSOR);
        out.println(message);
        out.flush();
    }

    /**
     * Print {@code line} on its own row <em>above</em> the bar, without disturbing the bar's pinned
     * position. Wipes the current bar line, prints the message + newline, then redraws the bar in
     * place. Used by listeners to surface warnings / errors mid-run so they land cleanly in the
     * scroll-back instead of being glued to the end of the carriage-returned bar line. Safe to call
     * from any thread (synchronizes on this).
     *
     * <p>If the bar is silent or already closed, the message passes through as a plain {@code
     * println} on the bar's stream — there's no pinned row to clear.
     */
    public synchronized void writeAbove(String line) {
        if (silent || closed || painter.height() == 0) {
            out.println(line);
            out.flush();
            return;
        }
        wipe();
        out.println(line); // emit the hoisted message
        // The lift forgot the row, so this paint draws the whole bar again under the message.
        StringBuilder f = new StringBuilder();
        painter.paint(f, List.of(row(lastFilled, lastPercentVal, lastPercent, lastStatus)), 0, 0, 0);
        out.print(f);
        out.flush();
    }

    /**
     * Repaint the bar as a final "Failed" line: bold red "Failed" prefix, the bar at its last filled
     * state in normal colors, status struck through. Caller-facing: terminates the line with a
     * newline so the shell prompt that follows doesn't overlay the bar.
     *
     * <p>Distinct from {@link #renderCanceled} which exists for explicit Ctrl-C — that one paints
     * every segment red and leaves the cursor mid-line for a follow-up "Force-cancelled" notice.
     */
    public synchronized void renderFailed() {
        if (closed) return;
        closed = true;
        LiveRegion.clearActive(this);
        if (silent) return;
        Style strikeStyle = Theme.active().dim().crossedOut();
        // Every segment painted with the dark-red→bright-red gradient.
        // We don't care about filled vs empty here — the bar's role at
        // this point is to mark *where* the plan stopped, not to
        // continue showing in-flight progress.
        char[] glyphs = new char[SEGMENTS];
        Arrays.fill(glyphs, emptyChar());
        String failed = Theme.colorize(
                        Glyphs.cross() + " Failed", Theme.active().error().bold())
                + " "
                + paint(glyphs, failColors)
                + GAP
                + Theme.colorize(lastPercent, percentStyle(lastPercentVal))
                + SEPARATOR
                + Theme.colorize(lastStatus, strikeStyle);
        settleRow(failed);
        out.print(Osc.taskbarClear());
        out.print(SHOW_CURSOR);
        out.println();
        out.flush();
    }

    /**
     * Repaint the bar as canceled: every segment in bright red, status struck through. Used by the
     * global SIGINT handler to mark the in-flight work before the process halts. Leaves the cursor at
     * the end of the bar line with no trailing newline so the caller can emit a follow-up line.
     */
    public synchronized boolean renderCanceled() {
        if (closed) return false;
        closed = true;
        LiveRegion.clearActive(this);
        if (silent) return false;
        Style redStyle = Theme.active().error();
        Style strikeStyle = Theme.active().dim().crossedOut();
        char[] glyphs = new char[SEGMENTS];
        Style[] styles = new Style[SEGMENTS];
        for (int i = 0; i < SEGMENTS; i++) {
            glyphs[i] = i < lastFilled ? filledChar() : emptyChar();
            styles[i] = redStyle;
        }
        settleRow(paint(glyphs, styles)
                + GAP
                + Theme.colorize(lastPercent, percentStyle(lastPercentVal))
                + SEPARATOR
                + Theme.colorize(lastStatus, strikeStyle));
        out.print(Osc.taskbarClear());
        out.print(SHOW_CURSOR);
        out.flush();
        // Cursor is left mid-line with no newline; GlobalCancel prints the
        // "‼ Canceled by user" notice on the next line.
        return false;
    }

    /** The bar's row: segments, percent, status. */
    private String row(int filled, int percent, String percentStr, String status) {
        return segments(filled)
                + GAP
                + Theme.colorize(percentStr, percentStyle(percent))
                + SEPARATOR
                + Theme.colorize(status, Theme.active().dim());
    }

    /** Paint {@code row} over the bar as its last state; only the cells that differ are written. */
    private void settleRow(String row) {
        StringBuilder f = new StringBuilder();
        painter.paint(f, List.of(row), 0, 0, 0);
        out.print(f);
    }

    /** Erase the bar's row, leaving the cursor at its start. */
    private void wipe() {
        StringBuilder f = new StringBuilder();
        painter.lift(f, 0, 0);
        out.print(f);
    }

    private String segments(int filled) {
        char[] glyphs = new char[SEGMENTS];
        Style[] styles = new Style[SEGMENTS];
        for (int i = 0; i < SEGMENTS; i++) {
            boolean isFilled = i < filled;
            glyphs[i] = isFilled ? filledChar() : emptyChar();
            styles[i] = isFilled ? filledColor(i, filled) : emptyStyle;
        }
        return paint(glyphs, styles);
    }

    /** Styled runs under ANSI; the plain rewrite of the glyphs under {@code --no-ansi}. */
    private static String paint(char[] glyphs, Style[] styles) {
        if (!Theme.active().isAnsi()) return Theme.paint(new String(glyphs), Style.EMPTY);
        return Style.renderRuns(glyphs, styles);
    }

    /**
     * Color for the filled glyph at zero-based position {@code i} when {@code filled} glyphs are lit.
     * The right-most filled glyph ({@code i == filled - 1}) maps to the last pre-computed color (the
     * gradient end, orange); each glyph to the left steps one entry back toward the gradient start
     * (magenta). With {@code filled} small only the orange tail of the gradient shows; the magenta
     * head appears as the bar fills, so the band looks pushed rightward by the frontier.
     */
    private Style filledColor(int i, int filled) {
        return segmentColors[SEGMENTS - filled + i];
    }

    static Style[] buildGradient(int n) {
        return buildGradient(n, Theme.active().progressGradient());
    }

    static Style[] buildGradient(int n, Gradient gradient) {
        Style[] a = new Style[n];
        for (int i = 0; i < n; i++) {
            double t = n <= 1 ? 0.0 : (double) i / (n - 1);
            a[i] = Theme.active().bright(gradient.at(t));
        }
        return a;
    }

    private Style percentStyle(int pct) {
        double t = Math.max(0.0, Math.min(1.0, (double) pct / 100.0));
        return Theme.active().bright(Theme.active().progressGradient().at(t)).bold();
    }
}
