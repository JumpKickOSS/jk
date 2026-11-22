// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.graph.GraphRunner;
import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpError;
import cc.jumpkick.engine.http.mcp.McpRenameCheck;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.wire.protocol.GraphAck;
import cc.jumpkick.wire.protocol.GraphRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The code graph's tools, named like the {@code jk code} subcommands they answer: {@code usages}
 * and {@code rename} on the default list, {@code query}, {@code read}, {@code find}, {@code
 * delete}, {@code move}, {@code pull_up} and {@code extract_interface} on the extended one.
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
     * {@code usages}: who calls a method, or uses a field, a type, an annotation or a constructor,
     * complete, in one call. The one graph question on the default list — measured to answer caller
     * questions in fewer tokens than grep and reads.
     */
    public static McpTool usages() {
        return new CodeGraphTool(
                spec(
                        "usages",
                        "Who calls Type.method or uses a field, type, @Annotation or anchor at file:line, complete."
                                + " transitive=true: all that reach it. Call it first, before any search.",
                        scoped(Map.of(
                                "address",
                                McpSchemas.string(
                                        "Type.method, Type.field, Type, @Annotation, or an anchor, whose answer"
                                                + " adds the uses no compiler checks (scripts, SQL, YAML, front end):"
                                                + " route:GET /x/{}, table:t, column:t.c, env:NAME, config:key; route:* lists"
                                                + " a kind's."),
                                "transitive",
                                McpSchemas.bool(),
                                "max_hops",
                                McpSchemas.integer(),
                                "token_budget",
                                McpSchemas.integer())),
                        List.of("address")),
                "usages",
                in -> List.of(required(in, "address")));
    }

    /**
     * {@code rename}: a method, field (with its accessors), type or package renamed, or a type moved
     * (a qualified {@code to}), in one call: every use a compiler bound is edited, the rest listed
     * with ids that {@code also} applies, and the engine proves the edit (compile, same references)
     * or undoes it. It writes, so it is not read-only.
     */
    public static McpTool rename() {
        return new CodeGraphTool(
                new Spec(
                        "rename",
                        "Rename Type.method, a property (Type.field + get/set), Type or a.pkg to `to` (qualified: moves it);"
                                + " edits, proves, tests: done unless it says otherwise.",
                        McpSchemas.withGoal(
                                props(Map.of(
                                        "address",
                                        McpSchemas.string(),
                                        "to",
                                        McpSchemas.string(),
                                        "also",
                                        McpSchemas.string(
                                                "Apply uses it listed but did not edit: c1,c3 / c2-c5 / declared"
                                                        + " / inferred / all; a judgment one (SQL column, message"
                                                        + " key) only by its id."),
                                        "check",
                                        McpSchemas.bool())),
                                List.of("address", "to"))),
                "rename",
                in -> List.of(required(in, "address"), required(in, "to")));
    }

    /**
     * {@code delete}: a method (every overload), a field or a type removed in one call when nothing
     * uses it, then compiled, or undone whole when it does not compile; with a use left it writes
     * nothing and lists the uses as {@code usages} does. It writes, so it is not read-only.
     */
    public static McpTool delete() {
        return new CodeGraphTool(
                new Spec(
                        "delete",
                        "Delete Type.method (every overload), Type.field or Type if nothing uses it, then compile;"
                                + " else writes nothing and lists each use at file:line.",
                        McpSchemas.object(
                                props(Map.of(
                                        "address",
                                        McpSchemas.string(),
                                        "kind",
                                        McpSchemas.oneOf("method", "field", "type"))),
                                List.of("address"))),
                "delete",
                in -> List.of(required(in, "address")));
    }

    /**
     * {@code move}: a static method (every overload) or static field moved to another class, enum or
     * record in one call, every use javac bound edited, then compiled and proven, or undone whole;
     * what would break or change without a compile error writes nothing and is listed. It writes, so
     * it is not read-only.
     */
    public static McpTool move() {
        return new CodeGraphTool(
                new Spec(
                        "move",
                        "Move static Type.method (every overload) or Type.field to the class, enum or record `to`;"
                                + " edits every use and import, proves it, or writes nothing and lists the hazards.",
                        McpSchemas.object(
                                props(Map.of("address", McpSchemas.string(), "to", McpSchemas.string())),
                                List.of("address", "to"))),
                "move",
                in -> List.of(required(in, "address"), required(in, "to")));
    }

    /**
     * {@code pull_up}: an instance method (every overload) or instance field moved to the class its
     * type extends, named by {@code to} or found when it is left out, as {@code jk code pull-up} does: its uses bind there as written, then it is
     * compiled and proven, or undone whole; what would break or change without a compile error
     * writes nothing and is listed. It writes, so it is not read-only.
     */
    public static McpTool pullUp() {
        return new CodeGraphTool(
                new Spec(
                        "pull_up",
                        "Pull instance Type.method (every overload) or Type.field up to the class Type extends;"
                                + " proves it, or writes nothing and lists the hazards.",
                        McpSchemas.object(
                                props(Map.of(
                                        "address",
                                        McpSchemas.string(),
                                        "to",
                                        McpSchemas.string("The class Type extends; the default."))),
                                List.of("address"))),
                "pull-up",
                in -> {
                    String to = in.str("to");
                    String address = required(in, "address");
                    return to == null || to.isBlank() ? List.of(address) : List.of(address, to);
                });
    }

    /**
     * {@code extract_interface}: an interface declaring chosen public instance methods of a class, as
     * {@code jk code extract-interface} makes it: the class implements it, then it is compiled, or
     * undone whole; the variables that call only those methods are listed as places that could
     * take the interface. It writes, so it is not read-only.
     */
    public static McpTool extractInterface() {
        return new CodeGraphTool(
                new Spec(
                        "extract_interface",
                        "Create interface `to` from public methods of class `address`, which implements it; proves it"
                                + " compiles and lists each variable that could take the interface, at file:line.",
                        McpSchemas.object(
                                props(Map.of(
                                        "address",
                                        McpSchemas.string(),
                                        "to",
                                        McpSchemas.string(),
                                        "methods",
                                        McpSchemas.string("a,b: every overload of each; default every public one that"
                                                + " overrides nothing"))),
                                List.of("address", "to"))),
                "extract-interface",
                in -> {
                    String methods = in.str("methods");
                    return methods == null || methods.isBlank()
                            ? List.of(required(in, "address"), required(in, "to"))
                            : List.of(required(in, "address"), required(in, "to"), methods);
                });
    }

    /** {@code query}: the symbols and connections a question is about, as {@code jk code query} answers it. */
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

    /** {@code read}: one symbol's source or a type's members, as {@code jk code read} answers it. */
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

    /** {@code find}: declarations by kind, visibility, supertype, annotation and name, as {@code jk code find}. */
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
        long started = Clock.SYSTEM.nanos();
        GraphRequest request =
                request(in, PathUtil.resolveUserPath(in.requiredDir()).toString());
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
        String text = ack.note() == null ? ack.text() : ack.text() + "\n\n[i] " + ack.note();
        if ("rename".equals(op) && in.flagOr("check", true)) {
            return McpRenameCheck.after(in, text, started, ack.uncompiled());
        }
        return in.text(text);
    }

    /** The graph request {@code in} makes against {@code dir}, reading only the arguments this operation declares. */
    GraphRequest request(McpCall in, String dir) {
        return new GraphRequest(
                dir,
                op,
                args.apply(in),
                "bfs",
                3,
                declared("token_budget") ? in.count("token_budget", 2000, 100, 100_000) : 2000,
                List.of(),
                false,
                declared("max_hops") ? in.count("max_hops", 8, 1, 50) : 8,
                null,
                false,
                false,
                optional(in, "scope"),
                optional(in, "modules"),
                optional(in, "source_set"),
                options(in));
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

    /** Whether this operation's schema declares {@code key}: an argument it does not declare is never read. */
    private boolean declared(String key) {
        return spec.inputSchema().get("properties") instanceof Map<?, ?> props && props.containsKey(key);
    }

    private @Nullable String optional(McpCall in, String key) {
        return declared(key) ? in.str(key) : null;
    }

    /** The operation's named settings a call carries, as {@code key=value}. */
    private List<String> options(McpCall in) {
        List<String> out = new ArrayList<>();
        for (String key : List.of("view", "kind", "visibility", "extends", "implements", "annotated", "also")) {
            String v = optional(in, key);
            if (v != null && !v.isBlank()) out.add(key + "=" + v);
        }
        if (declared("transitive") && in.flag("transitive")) out.add("transitive=true");
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
