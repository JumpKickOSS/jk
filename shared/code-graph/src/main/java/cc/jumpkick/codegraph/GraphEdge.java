// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * A directed edge {@code source → target}. {@code context} narrows a {@code references} or {@code
 * calls} edge ({@code field}, {@code parameter_type}, {@code call}, …), {@code null} when none.
 * {@code extra} carries every other field verbatim.
 */
public record GraphEdge(
        String source,
        String target,
        String relation,
        Confidence confidence,
        double confidenceScore,
        String sourceFile,
        String sourceLocation,
        double weight,
        @Nullable String context,
        Map<String, Object> extra) {

    public GraphEdge {
        extra = Map.copyOf(new TreeMap<>(extra));
    }

    /** An edge with the confidence's default score, weight 1 and no context. */
    public static GraphEdge of(
            String source, String target, String relation, Confidence confidence, String sourceFile, int line) {
        return new GraphEdge(
                source,
                target,
                relation,
                confidence,
                confidence.defaultScore(),
                sourceFile,
                GraphNode.location(line),
                1.0,
                null,
                Map.of());
    }

    /** This edge with {@code context} set. */
    public GraphEdge withContext(String context) {
        return new GraphEdge(
                source,
                target,
                relation,
                confidence,
                confidenceScore,
                sourceFile,
                sourceLocation,
                weight,
                context,
                extra);
    }

    /** This edge with {@code confidenceScore} set. */
    public GraphEdge withScore(double score) {
        return new GraphEdge(
                source, target, relation, confidence, score, sourceFile, sourceLocation, weight, context, extra);
    }

    /** The {@code extra} key listing the further {@code L<line>} sites of one edge, in line order. */
    public static final String SITES = "sites";

    /**
     * Every {@code L<line>} this edge was found at in its source file, its own location first: two
     * calls from one method (or from overloads, which share a node) to one target are one edge.
     */
    public List<String> sites() {
        List<String> out = new ArrayList<>();
        if (!sourceLocation.isEmpty()) out.add(sourceLocation);
        if (extra.get(SITES) instanceof List<?> l) l.forEach(o -> out.add(String.valueOf(o)));
        return out;
    }

    /** This edge with {@code other}'s sites added when it was found in the same file. */
    public GraphEdge withSitesOf(GraphEdge other) {
        if (!other.sourceFile.equals(sourceFile)) return this;
        TreeSet<Integer> lines = new TreeSet<>();
        for (String s : sites()) lines.add(lineOf(s));
        boolean added = false;
        for (String s : other.sites()) added |= lines.add(lineOf(s));
        if (!added) return this;
        lines.remove(lineOf(sourceLocation));
        List<String> more =
                lines.stream().filter(l -> l > 0).map(GraphNode::location).toList();
        Map<String, Object> next = new TreeMap<>(extra);
        next.put(SITES, more);
        return new GraphEdge(
                source,
                target,
                relation,
                confidence,
                confidenceScore,
                sourceFile,
                sourceLocation,
                weight,
                context,
                next);
    }

    private static int lineOf(String location) {
        try {
            return location.startsWith("L") ? Integer.parseInt(location.substring(1)) : 0;
        } catch (NumberFormatException notALine) {
            return 0;
        }
    }
}
