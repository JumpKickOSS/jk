// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** {@code GRAPH_REPORT.md}: the one-read orientation graphify gives an agent before it queries. */
public final class GraphReport {

    private GraphReport() {}

    /** Communities listed by size before the rest are summarized in one line. */
    static final int MAX_COMMUNITIES = 40;

    /** The report for {@code graph}, communities already assigned; {@code commit} the HEAD built from. */
    public static String write(CodeGraph graph, @Nullable String commit) {
        StringBuilder md = new StringBuilder("# Graph report\n\n## Summary\n\n");
        Map<Integer, List<String>> communities = graph.communities();
        md.append("- ")
                .append(graph.size())
                .append(" nodes, ")
                .append(graph.edges().size())
                .append(" edges, ");
        md.append(communities.size()).append(" communities\n");
        double total = Math.max(graph.edges().size(), 1);
        for (Confidence c : Confidence.values()) {
            long n = graph.edges().stream().filter(e -> e.confidence() == c).count();
            md.append("- ")
                    .append(c)
                    .append(": ")
                    .append(Math.round(n / total * 100))
                    .append("%\n");
        }
        if (commit != null) md.append("- Built from commit `").append(commit).append("`\n");
        md.append("\n## God nodes\n\n");
        int i = 1;
        for (String id : GraphAnalysis.godNodes(graph, 10)) {
            GraphNode n = graph.node(id);
            if (n == null) continue;
            md.append(i++).append(". `").append(n.label()).append("` — ").append(graph.degree(id));
            md.append(" edges (")
                    .append(n.sourceFile())
                    .append(' ')
                    .append(n.sourceLocation())
                    .append(")\n");
        }
        List<GraphAnalysis.Surprise> surprises = GraphAnalysis.surprises(graph, 10);
        if (!surprises.isEmpty()) {
            md.append("\n## Surprising connections\n\n");
            for (GraphAnalysis.Surprise s : surprises) {
                md.append("- `")
                        .append(label(graph, s.edge().source()))
                        .append("` --")
                        .append(s.edge().relation());
                md.append("--> `")
                        .append(label(graph, s.edge().target()))
                        .append("` [")
                        .append(s.edge().confidence());
                md.append("] — ").append(s.why()).append('\n');
            }
        }
        md.append("\n## Communities\n\n");
        int shown = 0;
        for (Map.Entry<Integer, List<String>> c : communities.entrySet()) {
            if (c.getValue().size() < 3) continue;
            if (shown++ == MAX_COMMUNITIES) {
                md.append("- … ")
                        .append(communities.size() - MAX_COMMUNITIES)
                        .append(" smaller communities: `jk graph community <id>`\n");
                break;
            }
            String name = graph.communityName(c.getKey());
            md.append("- **")
                    .append(c.getKey())
                    .append(name == null ? "" : " " + name)
                    .append("** (");
            md.append(c.getValue().size()).append(" nodes): ");
            md.append(String.join(
                    ", ",
                    c.getValue().stream()
                            .sorted((a, b) -> graph.degree(b) - graph.degree(a))
                            .limit(8)
                            .map(id -> "`" + label(graph, id) + "`")
                            .toList()));
            md.append('\n');
        }
        md.append(
                "\nQuery with `jk graph query \"<question>\"`, `jk graph explain <name>` or `jk graph path <a> <b>`.\n");
        return md.toString();
    }

    private static String label(CodeGraph graph, String id) {
        GraphNode n = graph.node(id);
        return n == null ? id : n.label();
    }
}
