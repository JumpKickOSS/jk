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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The code graph's read tools, under graphify's names and arguments so an agent's graphify habits
 * carry over, plus {@code callers}: {@code query_graph}, {@code get_node}, {@code get_neighbors}, {@code get_community},
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
                                "Answer a question about the code in a token budget, with file:line, instead of "
                                        + "grep and reads. scope (path globs, ! excludes), modules, source_set narrow it.",
                                scoped(Map.of(
                                        "question", McpSchemas.string(),
                                        "mode", McpSchemas.oneOf("bfs", "dfs"),
                                        "depth", McpSchemas.integer(),
                                        "token_budget", McpSchemas.integer(),
                                        "context_filter", McpSchemas.strings("call, field, import, …"))),
                                List.of("question")),
                        "query",
                        in -> List.of(required(in, "question"))),
                callers(),
                new CodeGraphTool(
                        spec(
                                "read_symbol",
                                "A symbol's source instead of whole files: Type, Type.member, Type.*, a path; "
                                        + "#line=N,M. The budget picks content, structure or headline.",
                                scoped(Map.of(
                                        "address", McpSchemas.string(),
                                        "view", McpSchemas.oneOf("content", "structure", "headline"),
                                        "token_budget", McpSchemas.integer())),
                                List.of("address")),
                        "read",
                        in -> List.of(required(in, "address"))),
                new CodeGraphTool(
                        spec(
                                "find_symbols",
                                "Declarations by kind, visibility, extends, implements, annotated, name glob; each "
                                        + "a comma list.",
                                scoped(Map.of(
                                        "name", McpSchemas.string(),
                                        "kind", McpSchemas.string(),
                                        "visibility", McpSchemas.string(),
                                        "extends", McpSchemas.string(),
                                        "implements", McpSchemas.string(),
                                        "annotated", McpSchemas.string())),
                                List.of()),
                        "find",
                        in -> {
                            String name = in.str("name");
                            return name == null || name.isBlank() ? List.of() : List.of(name);
                        }),
                new CodeGraphTool(
                        spec(
                                "rules_for",
                                "Before editing a file: the guard rules that govern it (what is banned, the "
                                        + "sanctioned alternative, why) and its NOTE:/WHY: comments.",
                                props(Map.of("path", McpSchemas.string())),
                                List.of("path")),
                        "rules",
                        in -> List.of(required(in, "path"))),
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
                                "A symbol's direct connections with relation and file:line.",
                                props(Map.of(
                                        "label", McpSchemas.string(),
                                        "node_id", McpSchemas.string(),
                                        "relation_filter", McpSchemas.string("calls, imports, …"),
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
                                scoped(Map.of("top_n", McpSchemas.integer("How many (default 10)"))),
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
                                "How two symbols connect: the shortest path along edge direction unless undirected.",
                                props(Map.of(
                                        "source", McpSchemas.string(),
                                        "target", McpSchemas.string(),
                                        "max_hops", McpSchemas.integer(),
                                        "undirected", McpSchemas.bool())),
                                List.of("source", "target")),
                        "path",
                        in -> List.of(required(in, "source"), required(in, "target"))));
    }

    /**
     * {@code callers}: who calls a method, complete, in one call. The one graph tool on the default
     * list — measured to answer caller questions in fewer tokens than grep and reads.
     */
    public static McpTool callers() {
        return new CodeGraphTool(
                spec(
                        "callers",
                        "Who calls Type.method (every overload), complete from the compiled classes: Owner.method at "
                                + "file:line. transitive=true: all that reach it. Use it, not grep.",
                        scoped(Map.of(
                                "address", McpSchemas.string(),
                                "transitive", McpSchemas.bool(),
                                "max_hops", McpSchemas.integer(),
                                "token_budget", McpSchemas.integer())),
                        List.of("address")),
                "callers",
                in -> List.of(required(in, "address")));
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
                false,
                in.str("scope"),
                in.str("modules"),
                in.str("source_set"),
                options(in));
        GraphAck ack;
        try {
            ack = GraphRunner.run(request, progress -> {});
        } catch (IOException e) {
            return in.error(e.getMessage() == null ? "graph unavailable" : e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return in.error("graph request interrupted");
        }
        if (!ack.ok()) return in.error(String.valueOf(ack.error()));
        return in.text(ack.note() == null ? ack.text() : ack.text() + "\n\n[i] " + ack.note());
    }

    private static Spec spec(String name, String description, Map<String, Object> props, List<String> required) {
        return new Spec(name, description, McpSchemas.object(props, required), McpSchemas.READ_ONLY);
    }

    /** {@code props} plus the {@code dir} every tool takes. */
    private static Map<String, Object> props(Map<String, Object> props) {
        Map<String, Object> out = new LinkedHashMap<>(props);
        out.put("dir", McpSchemas.string());
        return out;
    }

    /** The operation's named settings a call carries, as {@code key=value}. */
    private static List<String> options(McpCall in) {
        List<String> out = new ArrayList<>();
        for (String key : List.of("view", "kind", "visibility", "extends", "implements", "annotated")) {
            String v = in.str(key);
            if (v != null && !v.isBlank()) out.add(key + "=" + v);
        }
        if (in.flag("libraries")) out.add("libraries=true");
        if (in.flag("transitive")) out.add("transitive=true");
        return out;
    }

    /** {@code props} plus the optional scope a graphify caller never passes: paths, modules, source set. */
    private static Map<String, Object> scoped(Map<String, Object> props) {
        Map<String, Object> out = new LinkedHashMap<>(props);
        out.put("scope", McpSchemas.string());
        out.put("modules", McpSchemas.string());
        out.put("source_set", McpSchemas.oneOf("main", "test"));
        return props(out);
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
