// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import java.util.List;

/** graphify's output budget: about three characters per token, cut at a line boundary with a notice. */
public final class Budget {

    private Budget() {}

    static final int CHARS_PER_TOKEN = 3;

    /** {@code NODE}/{@code EDGE} lines under {@code budget}; the first {@code seeds} lines are never cut. */
    static String cut(List<String> lines, int seeds, int budget) {
        String output = String.join("\n", lines);
        int chars = budget * CHARS_PER_TOKEN;
        if (output.length() <= chars) return output;
        int cutAt = output.substring(0, chars).lastIndexOf('\n');
        if (cutAt <= 0) cutAt = chars;
        if (seeds > 0) {
            int seedEnd = lines.subList(0, seeds).stream()
                            .mapToInt(l -> l.length() + 1)
                            .sum()
                    - 1;
            cutAt = Math.max(cutAt, Math.min(seedEnd, output.length()));
        }
        long total = lines.stream().filter(l -> l.startsWith("NODE ")).count();
        String kept = output.substring(0, cutAt);
        long shown = kept.lines().filter(l -> l.startsWith("NODE ")).count();
        long cut = total - shown;
        if (cut == 0) {
            // Every node fits: the edges after them are cut at the budget too, nearest the question first.
            long edges = lines.stream().filter(l -> l.startsWith("EDGE ")).count();
            long shownEdges = kept.lines().filter(l -> l.startsWith("EDGE ")).count();
            int full = (output.length() + 2) / CHARS_PER_TOKEN;
            return "[i] All " + total + " nodes; " + shownEdges + " of " + edges + " edges shown (~" + budget
                    + " tokens), those nearest the question first. Every edge takes ~" + full + ": --budget " + full
                    + " (MCP token_budget), or narrow with context_filter or get_neighbors.\n\n" + kept + "\n... "
                    + (edges - shownEdges) + " more edges cut";
        }
        int nodeChars = lines.stream()
                .filter(l -> l.startsWith("NODE "))
                .mapToInt(l -> l.length() + 1)
                .sum();
        int full = (Math.min(nodeChars, output.length()) + 2) / CHARS_PER_TOKEN;
        return "[!] TRUNCATED: showing " + shown + " of " + total + " nodes (~" + budget + " tokens). All of them"
                + " take ~" + full + ": --budget " + full + " (MCP token_budget), or narrow with context_filter or"
                + " get_node.\n\n" + kept + "\n... " + cut + " more nodes cut";
    }

    /** Plain lines under {@code budget}, cut with a count and {@code hint}. */
    public static String lines(List<String> lines, int budget, String hint) {
        String output = String.join("\n", lines);
        int chars = budget * CHARS_PER_TOKEN;
        if (output.length() <= chars) return output;
        int cutAt = output.substring(0, chars).lastIndexOf('\n');
        if (cutAt <= 0) cutAt = chars;
        String kept = output.substring(0, cutAt);
        long shown = kept.lines().count();
        int full = (output.length() + 2) / CHARS_PER_TOKEN;
        return "[!] TRUNCATED: showing " + shown + " of " + lines.size() + " lines (~" + budget
                + "-token budget). Raise the token budget to " + full + " to get all " + lines.size() + ". " + hint
                + "\n\n" + kept + "\n... (truncated: " + (lines.size() - shown) + " more lines. " + hint + ")";
    }
}
