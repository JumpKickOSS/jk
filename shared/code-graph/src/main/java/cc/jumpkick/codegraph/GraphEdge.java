// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import java.util.Map;
import java.util.TreeMap;
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
}
