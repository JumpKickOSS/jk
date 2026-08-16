// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.theme.JkDarkTheme;
import cc.jumpkick.cli.theme.Theme;
import java.util.ArrayList;
import java.util.List;

/**
 * Sliding ring buffer of recent process / step output for a live plan region. Hidden by default;
 * revealed with Ctrl-O or force-shown on non-zero tool/worker exits. Capacity is hard-capped at
 * {@link #MAX_LINES}; display height is computed at paint time from the terminal and chrome budget.
 *
 * <p>When the pane is open, a full-width dark-gray braille rule ({@link #RULE_GLYPH}) is painted
 * immediately above the live plan wedge as the on-state indicator. When the pane is closed after
 * process lines were committed to scrollback, that rule is replaced by a blank line — never removed
 * without a stand-in — so external output and the wedge stay separated.
 */
public final class OutputWindow {

    /** Hard cap on retained lines (and on lines ever painted in the pane). */
    public static final int MAX_LINES = 200;

    /** Braille dots-25 (U+2812) — the “dotted line” rule above the wedge when peek is on. */
    public static final String RULE_GLYPH = "\u2812"; // ⠒

    /** Center caption on the peek rule (spaces pad the braille fill away from the words). */
    public static final String RULE_LABEL = " \u2191 output \u2191 "; // ↑ output ↑

    private final ArrayList<String> lines = new ArrayList<>();
    private boolean visible;
    /**
     * Process lines committed to terminal scrollback above the live region this plan (open-peek dump
     * + live appends). Used to put one breathing-room blank before the settle chip.
     */
    private int committedScrollbackLines;

    /** Append one logical line; evicts the oldest when over {@link #MAX_LINES}. */
    public synchronized void append(String line) {
        if (line == null) return;
        // Normalize: strip a single trailing CR left by some tools.
        if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
        // Skip pure blank lines — they only create visual gaps before the rule / settle chip.
        if (line.isBlank()) return;
        lines.add(line);
        while (lines.size() > MAX_LINES) lines.remove(0);
    }

    /** Record that {@code n} process lines were written into terminal scrollback. */
    public synchronized void noteCommitted(int n) {
        if (n > 0) committedScrollbackLines += n;
    }

    public synchronized int committedScrollbackLines() {
        return committedScrollbackLines;
    }

    public synchronized void resetCommitted() {
        committedScrollbackLines = 0;
    }

    public synchronized void clear() {
        lines.clear();
    }

    public synchronized int size() {
        return lines.size();
    }

    public synchronized boolean isEmpty() {
        return lines.isEmpty();
    }

    public synchronized boolean visible() {
        return visible;
    }

    /** Force the pane open (failed tool/worker). No-op if already visible. */
    public synchronized void show() {
        visible = true;
    }

    /** Hide the pane; buffer contents are retained. */
    public synchronized void hide() {
        visible = false;
    }

    /** Toggle visibility; returns the new state. */
    public synchronized boolean toggle() {
        visible = !visible;
        return visible;
    }

    /**
     * Newest lines that fit {@code budget} (clamped to {@link #MAX_LINES} and the buffer size).
     * Empty when budget &lt; 1 or the buffer is empty.
     */
    public synchronized List<String> linesForDisplay(int budget) {
        int n = Math.min(lines.size(), Math.min(MAX_LINES, Math.max(0, budget)));
        if (n == 0) return List.of();
        int from = lines.size() - n;
        return List.copyOf(lines.subList(from, lines.size()));
    }

    /**
     * Display budget from terminal geometry: free rows above plan chrome for process lines.
     *
     * <p>Reserves: plan chrome + the on-state rule (1) + <strong>one cursor-park row</strong>. The
     * live region always ends with {@code \n} after its last line; if that parks the cursor past
     * the bottom of the viewport, the terminal scrolls and {@code cursorUp(lastLines)} overshoots
     * — stacking wedge headers into scrollback. Keeping region height ≤ {@code rows - 1} prevents
     * that.
     *
     * @param terminalRows full terminal height
     * @param planChromeRows header + tree (+ completions) lines that form the live plan chrome
     */
    public static int displayBudget(int terminalRows, int planChromeRows) {
        int rows = Math.max(1, terminalRows);
        int chrome = Math.max(0, planChromeRows);
        // rule (1) + cursor park (1) so the final \n after the region never scrolls the viewport
        int free = rows - chrome - 2;
        if (free < 1) return 0;
        return Math.min(MAX_LINES, free);
    }

    /** Max logical lines the live region may occupy (always leave one row for the cursor). */
    public static int maxRegionLines(int terminalRows) {
        return Math.max(1, Math.max(1, terminalRows) - 1);
    }

    /**
     * Full-width rule of {@link #RULE_GLYPH} with centered {@link #RULE_LABEL}, width {@code cols}.
     * Color is {@link Theme#darkGray()} darkened by 35%. Plain/no-ansi themes still return the
     * Unicode form; {@link PlainAscii} rewrites braille and arrows at print time.
     */
    public static String ruleLine(int cols) {
        int n = Math.max(1, cols);
        // Match live plan rows: keep the last column free so DEC auto-wrap does not push the
        // rule onto the next physical row.
        int width = Math.max(1, JkManagerColor.rowColumnBudget(n));
        String body = centeredRuleBody(width);
        // darkGray (bright black) × 0.65 — a step dimmer than the rail gray so the rule reads as
        // a quiet separator under process output.
        return Theme.colorize(body, Theme.active().bright(JkDarkTheme.BRIGHT_BLACK.darker(0.35)));
    }

    /** Visible rule body (no ANSI): braille fill with {@link #RULE_LABEL} centered. */
    static String centeredRuleBody(int width) {
        if (width < 1) width = 1;
        String label = RULE_LABEL;
        if (label.length() >= width) {
            // Too narrow for the caption — fill only, or hard-clip the label.
            if (width <= 2) return RULE_GLYPH.repeat(width);
            return label.substring(0, width);
        }
        int rest = width - label.length();
        int left = rest / 2;
        int right = rest - left;
        return RULE_GLYPH.repeat(left) + label + RULE_GLYPH.repeat(right);
    }
}
