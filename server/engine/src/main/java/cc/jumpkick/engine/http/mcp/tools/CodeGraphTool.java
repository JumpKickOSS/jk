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
 * The code graph's read tools, named like the {@code jk graph} subcommands they answer: {@code
 * callers} on the default list, {@code query}, {@code read} and {@code find} on the extended one.
 * Each refreshes the graph first (a walk and a snapshot load when nothing changed) and answers with
 * {@code Owner.member()} at {@code file:line}.
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

    /** {@code query}: the symbols and connections a question is about, as {@code jk graph query} answers it. */
    public static McpTool query() {
        return new CodeGraphTool(
                spec(
                        "query",
                        "Answer a question about the code in a token budget: the symbols it names and how they "
                                + "connect, each at file:line, instead of grep and reads.",
                        scoped(Map.of("question", McpSchemas.string(), "token_budget", McpSchemas.integer())),
                        List.of("question")),
                "query",
                in -> List.of(required(in, "question")));
    }

    /** {@code read}: one symbol's source or a type's members, as {@code jk graph read} answers it. */
    public static McpTool read() {
        return new CodeGraphTool(
                spec(
                        "read",
                        "A symbol's source instead of whole files: Type, Type.member, Type.*, a path; "
                                + "#line=N,M. The budget picks content, structure or headline.",
                        scoped(Map.of(
                                "address", McpSchemas.string(),
                                "view", McpSchemas.oneOf("content", "structure", "headline"),
                                "token_budget", McpSchemas.integer())),
                        List.of("address")),
                "read",
                in -> List.of(required(in, "address")));
    }

    /** {@code find}: declarations by kind, visibility, supertype, annotation and name, as {@code jk graph find}. */
    public static McpTool find() {
        return new CodeGraphTool(
                spec(
                        "find",
                        "Declarations by kind, visibility, extends, implements, annotated, name glob; each "
                                + "a comma list.",
                        scoped(Map.of(
                                "name", McpSchemas.string(),
                                "kind", McpSchemas.string(),
                                "visibility", McpSchemas.string(),
                                "extends", McpSchemas.string(),
                                "implements", McpSchemas.string(),
                                "annotated", McpSchemas.string(),
                                "token_budget", McpSchemas.integer())),
                        List.of()),
                "find",
                in -> {
                    String name = in.str("name");
                    return name == null || name.isBlank() ? List.of() : List.of(name);
                });
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
                "bfs",
                3,
                in.count("token_budget", 2000, 100, 100_000),
                List.of(),
                false,
                in.count("max_hops", 8, 1, 50),
                null,
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
        if (in.flag("transitive")) out.add("transitive=true");
        return out;
    }

    /** {@code props} plus the optional scope: paths, modules, source set. */
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
}
