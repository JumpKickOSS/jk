// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.theme.Gradient;
import cc.jumpkick.cli.theme.Rgb;
import cc.jumpkick.cli.theme.Theme;
import org.jline.utils.AttributedStyle;

/**
 * Embeddable segmented progress bar string renderer (no cursor/terminal state). Filled blocks use a
 * moving gradient; fractional eighth-blocks model sub-cell progress. For the single-line widget see
 * {@link SpinnerProgressBar}.
 */
public final class ProgressBar {

    public static final int SEGMENTS = 40;

    /** Solid cell for the filled run. */
    static final char FULL_BLOCK = '█';

    // Legacy medium-square glyphs, kept for the suffix-less {@link #renderBar} used
    // by static utilization tables (e.g. {@code jk cache}).
    static final char FILLED_CHAR = '▰';
    static final char EMPTY_CHAR = '▱';

    private final Gradient gradient;
    private final AttributedStyle[] fillColors;

    /** Bar in the default green → bright-green progress gradient. */
    public ProgressBar() {
        this(Theme.active().progressGradient());
    }

    private static volatile ProgressBar shared;

    /**
     * Cached default-gradient instance. The constructor precomputes a {@value #SEGMENTS}-style
     * gradient — too heavy to rebuild on every 80ms animation frame (JK-1893). Instances are
     * immutable, so sharing is safe; the cache refreshes if the active theme's gradient changes.
     */
    public static ProgressBar shared() {
        Gradient g = Theme.active().progressGradient();
        ProgressBar s = shared;
        if (s == null || s.gradient != g) {
            s = new ProgressBar(g);
            shared = s;
        }
        return s;
    }

    /** Bar in an explicit gradient (e.g. the failure gradient for a stopped run). */
    public ProgressBar(Gradient gradient) {
        this.gradient = gradient;
        this.fillColors = buildGradient(SEGMENTS, gradient);
    }

    /** Clamp {@code numerator / denominator} to {@code [0.0, 1.0]}. */
    public static double fraction(long numerator, long denominator) {
        if (denominator <= 0) return 0.0;
        double f = (double) numerator / (double) denominator;
        return f < 0.0 ? 0.0 : Math.min(f, 1.0);
    }

    /** Number of filled segments for the given ratio. */
    public static int filled(long numerator, long denominator) {
        return (int) Math.round(fraction(numerator, denominator) * SEGMENTS);
    }

    /** Rounded percent for the given ratio. */
    public static int percent(long numerator, long denominator) {
        return (int) Math.round(fraction(numerator, denominator) * 100);
    }

    /**
     * Render one bar line for {@code (numerator, denominator)} — the underlined block bar followed by
     * a plain white {@code NN%} trailing the bar.
     */
    public String render(long numerator, long denominator) {
        StringBuilder sb = new StringBuilder();
        appendBar(sb, numerator, denominator);
        int pct = percent(numerator, denominator);
        sb.append(' ');
        sb.append(Theme.colorize(pct + "%", Theme.active().brightWhite()));
        return sb.toString();
    }

    /**
     * Append the {@link #SEGMENTS}-wide underlined bar: solid blocks for the whole cells, one
     * eighth-block at the fractional frontier, and brightest-color underlined spaces for the
     * unreached cells. Every cell is underlined.
     *
     * <p>Plain ({@code --no-ansi}): ASCII {@code #} filled / {@code -} empty, no underline SGR.
     */
    private void appendBar(StringBuilder sb, long numerator, long denominator) {
        int[] cells = cells(numerator, denominator);
        int full = cells[0], eighths = cells[1], fill = cells[2];
        if (!Theme.active().isAnsi()) {
            int filled = full + (eighths > 0 ? 1 : 0);
            for (int i = 0; i < SEGMENTS; i++) {
                sb.append(i < filled ? Glyphs.BAR_FULL_PLAIN : Glyphs.BAR_EMPTY_PLAIN);
            }
            return;
        }
        AttributedStyle brightest = fillColors[SEGMENTS - 1];
        for (int i = 0; i < SEGMENTS; i++) {
            char c;
            AttributedStyle color;
            if (i < full) { // whole cell
                c = FULL_BLOCK;
                color = fillColors[SEGMENTS - fill + i];
            } else if (i == full && eighths > 0) { // fractional frontier
                c = (char) (0x2590 - eighths); // ▏ (1/8) … ▉ (7/8)
                color = fillColors[SEGMENTS - fill + i]; // == brightest (the frontier)
            } else { // unreached
                c = ' ';
                color = brightest;
            }
            sb.append(Theme.colorize(String.valueOf(c), color.underline()));
        }
    }

    /**
     * The bar's first-cell color — the lead color the plan-header's powerline cap blends into.
     * Mirrors {@link #appendBar}'s coloring for cell 0.
     */
    public Rgb leadColor(long numerator, long denominator) {
        int fill = cells(numerator, denominator)[2];
        int idx = fill > 0 ? SEGMENTS - fill : SEGMENTS - 1; // cell 0's gradient index
        double t = SEGMENTS <= 1 ? 0.0 : (double) idx / (SEGMENTS - 1);
        return gradient.at(t);
    }

    /**
     * Decompose a ratio into {@code {full, eighths, fill}}: whole filled cells, the fractional
     * frontier in eighths (0–7), and the count of non-empty cells.
     */
    private static int[] cells(long numerator, long denominator) {
        double exact = fraction(numerator, denominator) * SEGMENTS;
        int full = (int) Math.floor(exact);
        int eighths = (int) Math.round((exact - full) * 8);
        if (eighths == 8) { // rounded up to a whole cell
            full++;
            eighths = 0;
        }
        if (full >= SEGMENTS) { // clamp at 100%
            full = SEGMENTS;
            eighths = 0;
        }
        return new int[] {full, eighths, full + (eighths > 0 ? 1 : 0)};
    }

    /**
     * Just the colored segment bar at an explicit width — no percent and no {@code [n of d]} suffix.
     * For embedding a fixed-width bar inside another widget (e.g. a boxed table's utilization row).
     * The gradient is rebuilt at the requested width so the moving-fill look is preserved at any
     * size.
     */
    public String renderBar(long numerator, long denominator, int segments) {
        if (segments <= 0) return "";
        AttributedStyle[] colors = segments == SEGMENTS ? fillColors : buildGradient(segments, gradient);
        int fill = (int) Math.round(fraction(numerator, denominator) * segments);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments; i++) {
            boolean isFilled = i < fill;
            char c = isFilled ? FILLED_CHAR : EMPTY_CHAR;
            AttributedStyle style = isFilled ? colors[segments - fill + i] : colors[0];
            sb.append(Theme.colorize(String.valueOf(c), style));
        }
        return sb.toString();
    }

    /**
     * Flat two-color variant of {@link #renderBar}: filled cells use {@code fillStyle}, empty cells
     * use {@code emptyStyle}. For contexts where a gradient isn't appropriate (e.g. utilization rows
     * that want a solid fill color and a distinct track color).
     */
    public static String renderBar(
            long numerator, long denominator, int segments, AttributedStyle fillStyle, AttributedStyle emptyStyle) {
        if (segments <= 0) return "";
        int fill = (int) Math.round(fraction(numerator, denominator) * segments);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments; i++) {
            char c = i < fill ? FILLED_CHAR : EMPTY_CHAR;
            sb.append(Theme.colorize(String.valueOf(c), i < fill ? fillStyle : emptyStyle));
        }
        return sb.toString();
    }

    private static AttributedStyle[] buildGradient(int n, Gradient gradient) {
        AttributedStyle[] a = new AttributedStyle[n];
        for (int i = 0; i < n; i++) {
            double t = n <= 1 ? 0.0 : (double) i / (n - 1);
            a[i] = Theme.active().bright(gradient.at(t));
        }
        return a;
    }

    private AttributedStyle percentStyle(int pct) {
        double t = Math.max(0.0, Math.min(1.0, (double) pct / 100.0));
        return Theme.active().bright(gradient.at(t)).bold();
    }
}
