// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.graph.GraphRunner;
import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpError;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.wire.protocol.GraphAck;
import cc.jumpkick.wire.protocol.GraphRequest;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The code graph's read tools, under graphify's names and arguments so an agent's graphify habits
 * carry over: {@code query_graph}, {@code get_node}, {@code get_neighbors}, {@code get_community},
 * {@code god_nodes}, {@code graph_stats}, {@code shortest_path}. Each refreshes the graph first (a
 * walk and a snapshot load when nothing changed) and answers in graphify's text.
 */
public final class CodeGraphTool implements McpTool {

    private final Spec spec;
    private final String op;
    private final Function<McpCall, List<String>> args;

    private CodeGraphTool(Spec spec, String op, Function<McpCall, List<String>> args) {
        this.spec = spec;
        this.op = op;
        this.args = args;
    }

    /** Every graph tool, in the order {@code tools/list} shows them. */
    public static List<McpTool> all() {
        return List.of(
                new CodeGraphTool(
                        spec(
                                "query_graph",
                                "Answer a question about the code within a token budget instead of grepping and "
                                        + "reading files. Same as jk graph query.",
                                props(Map.of(
                                        "question", McpSchemas.string("Words or symbol names"),
                                        "mode", McpSchemas.oneOf("bfs", "dfs"),
                                        "depth", McpSchemas.integer("Hops from the matched nodes (default 3, max 6)"),
                                        "token_budget", McpSchemas.integer("Answer size in tokens (default 2000)"),
                                        "context_filter",
                                                McpSchemas.strings("Edge contexts to follow: call, field, import, …"))),
                                List.of("question")),
                        "query",
                        in -> List.of(required(in, "question"))),
                new CodeGraphTool(
                        spec(
                                "get_node",
                                "One node's details: source file and line, type, community, degree. Same as jk graph node.",
                                props(Map.of("label", McpSchemas.string(), "node_id", McpSchemas.string())),
                                List.of()),
                        "node",
                        in -> List.of(name(in))),
                new CodeGraphTool(
                        spec(
                                "get_neighbors",
                                "A node's direct connections with relation, confidence and file:line. Same as jk graph neighbors.",
                                props(Map.of(
                                        "label", McpSchemas.string(),
                                        "node_id", McpSchemas.string(),
                                        "relation_filter",
                                                McpSchemas.string("Only relations containing this: calls, imports, …"),
                                        "token_budget", McpSchemas.integer())),
                                List.of()),
                        "neighbors",
                        in -> List.of(name(in))),
                new CodeGraphTool(
                        spec(
                                "get_community",
                                "The members of one community (a cluster of closely connected code). Same as jk graph community.",
                                props(Map.of(
                                        "community_id", McpSchemas.integer(), "token_budget", McpSchemas.integer())),
                                List.of("community_id")),
                        "community",
                        in -> List.of(String.valueOf(in.num("community_id")))),
                new CodeGraphTool(
                        spec(
                                "god_nodes",
                                "The most-connected entities: where to start reading. Same as jk graph god-nodes.",
                                props(Map.of("top_n", McpSchemas.integer("How many (default 10)"))),
                                List.of()),
                        "god-nodes",
                        in -> List.of()),
                new CodeGraphTool(
                        spec(
                                "graph_stats",
                                "Node, edge and community counts and the confidence mix. Same as jk graph stats.",
                                props(Map.of()),
                                List.of()),
                        "stats",
                        in -> List.of()),
                new CodeGraphTool(
                        spec(
                                "shortest_path",
                                "How two things connect: the shortest path, following edge direction unless "
                                        + "undirected. Same as jk graph path.",
                                props(Map.of(
                                        "source", McpSchemas.string(),
                                        "target", McpSchemas.string(),
                                        "max_hops", McpSchemas.integer("Longest path to report (default 8)"),
                                        "undirected", McpSchemas.bool())),
                                List.of("source", "target")),
                        "path",
                        in -> List.of(required(in, "source"), required(in, "target"))));
    }

    @Override
    public Spec spec() {
        return spec;
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        GraphRequest request = new GraphRequest(
                PathUtil.resolveUserPath(in.requiredDir()).toString(),
                op,
                args.apply(in),
                "dfs".equals(in.str("mode")) ? "dfs" : "bfs",
                in.count("depth", 3, 1, 6),
                in.count("token_budget", 2000, 100, 100_000),
                in.strings("context_filter"),
                false,
                in.flag("undirected"),
                in.count("max_hops", 8, 1, 50),
                in.count("top_n", 10, 1, 200),
                in.str("relation_filter"),
                false,
                false);
        GraphAck ack;
        try {
            ack = GraphRunner.run(request, progress -> {});
        } catch (IOException e) {
            return in.error(e.getMessage() == null ? "graph unavailable" : e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return in.error("graph request interrupted");
        }
        return ack.ok() ? in.text(ack.text()) : in.error(String.valueOf(ack.error()));
    }

    private static Spec spec(String name, String description, Map<String, Object> props, List<String> required) {
        return new Spec(name, description, McpSchemas.object(props, required), McpSchemas.READ_ONLY);
    }

    /** {@code props} plus the {@code dir} every tool takes. */
    private static Map<String, Object> props(Map<String, Object> props) {
        Map<String, Object> out = new LinkedHashMap<>(props);
        out.put("dir", McpSchemas.string(McpSchemas.BOUND_ROOT));
        return out;
    }

    private static String required(McpCall in, String key) {
        String v = in.str(key);
        if (v == null || v.isBlank()) throw new McpError(-32602, "requires arguments." + key);
        return v;
    }

    private static String name(McpCall in) {
        String v = in.str("node_id");
        if (v == null || v.isBlank()) v = in.str("label");
        if (v == null || v.isBlank()) throw new McpError(-32602, "requires arguments.label or arguments.node_id");
        return v;
    }
}
