// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import java.util.ArrayList;
import java.util.List;

/** An answer's size limit: about three characters per token, cut at a line boundary with a notice. */
public final class Budget {

    private Budget() {}

    static final int CHARS_PER_TOKEN = 3;

    /**
     * A query's symbol lines, then its connection lines, under {@code budget}; the first {@code seeds}
     * symbol lines are never cut.
     */
    static String cut(List<String> nodes, List<String> edges, int seeds, int budget) {
        List<String> lines = new ArrayList<>(nodes);
        lines.addAll(edges);
        String output = String.join("\n", lines);
        int chars = budget * CHARS_PER_TOKEN;
        if (output.length() <= chars) return output;
        int cutAt = output.substring(0, chars).lastIndexOf('\n');
        if (cutAt <= 0) cutAt = chars;
        if (seeds > 0) {
            int seedEnd = nodes.subList(0, seeds).stream()
                            .mapToInt(l -> l.length() + 1)
                            .sum()
                    - 1;
            cutAt = Math.max(cutAt, Math.min(seedEnd, output.length()));
        }
        String kept = output.substring(0, cutAt);
        long keptLines = kept.lines().count();
        long shown = Math.min(keptLines, nodes.size());
        long cut = nodes.size() - shown;
        if (cut == 0) {
            // Every symbol fits: the connections after them are cut at the budget too, nearest the question first.
            long shownEdges = keptLines - nodes.size();
            int full = (output.length() + 2) / CHARS_PER_TOKEN;
            return "[i] All " + nodes.size() + " symbols; " + shownEdges + " of " + edges.size()
                    + " connections shown (~" + budget + " tokens), those nearest the question first. Every"
                    + " connection takes ~" + full + ": --budget " + full + " (MCP token_budget), or narrow with"
                    + " --context or jk graph neighbors.\n\n" + kept + "\n... " + (edges.size() - shownEdges)
                    + " more connections cut";
        }
        int nodeChars = nodes.stream().mapToInt(l -> l.length() + 1).sum();
        int full = (Math.min(nodeChars, output.length()) + 2) / CHARS_PER_TOKEN;
        return "[!] TRUNCATED: showing " + shown + " of " + nodes.size() + " symbols (~" + budget + " tokens). All"
                + " of them take ~" + full + ": --budget " + full + " (MCP token_budget), or ask a narrower"
                + " question.\n\n" + kept + "\n... " + cut + " more symbols cut";
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
