// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import java.util.ArrayList;
import java.util.List;

/**
 * Sliding ring buffer of recent process / step output for a live plan region. Hidden by default;
 * revealed with Ctrl-O or force-shown on non-zero tool/worker exits. Capacity is hard-capped at
 * {@link #MAX_LINES}; display height is computed at paint time from the terminal and chrome budget.
 */
public final class OutputWindow {

    /** Hard cap on retained lines (and on lines ever painted in the pane). */
    public static final int MAX_LINES = 200;

    private final ArrayList<String> lines = new ArrayList<>();
    private boolean visible;

    /** Append one logical line; evicts the oldest when over {@link #MAX_LINES}. */
    public synchronized void append(String line) {
        if (line == null) return;
        // Normalize: strip a single trailing CR left by some tools.
        if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
        lines.add(line);
        while (lines.size() > MAX_LINES) lines.remove(0);
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
     * Display budget from terminal geometry: free rows above plan chrome, minus one for the
     * padding blank, clamped to {@code [0, MAX_LINES]}.
     *
     * @param terminalRows full terminal height
     * @param planChromeRows header + tree (+ completions) lines that form the live plan chrome
     */
    public static int displayBudget(int terminalRows, int planChromeRows) {
        int rows = Math.max(1, terminalRows);
        int chrome = Math.max(0, planChromeRows);
        // Leave room for chrome + one padding blank between pane and wedge.
        int free = rows - chrome - 1;
        if (free < 1) return 0;
        return Math.min(MAX_LINES, free);
    }
}
