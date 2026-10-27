// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.codegraph.Budget;
import cc.jumpkick.codegraph.CodeGraph;
import cc.jumpkick.codegraph.GraphJson;
import cc.jumpkick.codegraph.GraphQuery;
import cc.jumpkick.codegraph.GraphReport;
import cc.jumpkick.codegraph.GraphSnapshot;
import cc.jumpkick.codegraph.GraphTools;
import cc.jumpkick.codegraph.Modules;
import cc.jumpkick.codegraph.Scope;
import cc.jumpkick.codegraph.SymbolFinder;
import cc.jumpkick.codegraph.SymbolReader;
import cc.jumpkick.config.EnvValues;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One graph operation: refresh the graph (unless the caller asked not to), then answer. Every read
 * goes through the refresh, so an answer never comes from a stale graph; with nothing changed the
 * refresh is one walk and a snapshot load.
 */
public final class GraphService {

    private GraphService() {}

    /** The operations {@link #run} answers. */
    public static final List<String> OPS = List.of(
            "build",
            "query",
            "explain",
            "path",
            "node",
            "neighbors",
            "community",
            "god-nodes",
            "stats",
            "report",
            "read",
            "find",
            "rules");

    /**
     * One operation's arguments, as the CLI and MCP surfaces collect them. {@code modulePrefixes} are
     * the selected modules' directories, already resolved by the engine; {@code options} are the
     * operation's named settings.
     */
    public record Request(
            String op,
            List<String> args,
            String mode,
            int depth,
            int budget,
            List<String> contexts,
            boolean all,
            boolean undirected,
            int maxHops,
            int topN,
            @Nullable String relation,
            boolean rebuild,
            boolean noRefresh,
            @Nullable String scope,
            List<String> modulePrefixes,
            @Nullable String sourceSet,
            Map<String, String> options) {

        public Request {
            modulePrefixes = List.copyOf(modulePrefixes);
            options = Map.copyOf(options);
        }

        /** The same request with no scope, settings or module selection. */
        public Request(
                String op,
                List<String> args,
                String mode,
                int depth,
                int budget,
                List<String> contexts,
                boolean all,
                boolean undirected,
                int maxHops,
                int topN,
                @Nullable String relation,
                boolean rebuild,
                boolean noRefresh) {
            this(
                    op,
                    args,
                    mode,
                    depth,
                    budget,
                    contexts,
                    all,
                    undirected,
                    maxHops,
                    topN,
                    relation,
                    rebuild,
                    noRefresh,
                    null,
                    List.of(),
                    null,
                    Map.of());
        }
    }

    /**
     * What the engine knows about the workspace: its root, where its graph lives, every module's
     * class facts index, its modules, the script reader, whether {@code graphify-out/} is kept
     * current, the {@code [graph.aliases]} (term → outside words), for {@code rules} the guard rules
     * the engine matched to the file (one JSON object each), and for {@code read} and {@code find} the
     * locked jars and where their API indexes are kept.
     */
    public record Workspace(
            Path root,
            Path out,
            List<GraphBuild.ClassIndex> indexes,
            Modules modules,
            ScriptReader scripts,
            boolean graphify,
            Map<String, List<String>> aliases,
            List<String> guardRules,
            List<LibraryApi.Jar> libraries,
            @Nullable Path apiCache) {

        public Workspace {
            indexes = List.copyOf(indexes);
            aliases = Map.copyOf(aliases);
            guardRules = List.copyOf(guardRules);
            libraries = List.copyOf(libraries);
        }
    }

    /** An operation's text, the refresh it was answered from, and a note for the user or {@code null}. */
    public record Answer(
            String text, GraphBuild.Result build, @Nullable String note) {}

    public static Answer run(Workspace w, Request r, GraphBuild.Progress progress) throws IOException {
        if (!OPS.contains(r.op())) throw new IllegalArgumentException("unknown graph operation '" + r.op() + "'");
        GraphBuild.Result build = r.noRefresh() ? stored(w.out()) : null;
        if (r.noRefresh() && build == null) {
            // -n answers from what was written; with nothing written there is nothing to answer
            // from, and building one here would store a graph with no compiled facts in it.
            throw new IllegalArgumentException("no graph has been built here yet: run jk graph without -n first");
        }
        if (build == null) {
            build = GraphBuild.refresh(w.root(), w.out(), w.indexes(), w.modules(), w.scripts(), r.rebuild(), progress);
        }
        String note = w.graphify() ? GraphifyMirror.sync(w.root(), w.out(), build.changed()) : null;
        return new Answer(text(w, build, r), build, note);
    }

    /**
     * The last graph written, without looking at the tree: the one in memory while it is current,
     * else {@code graph.bin}, else {@code graph.json}.
     */
    private static GraphBuild.@Nullable Result stored(Path out) {
        GraphMemory.Graph held = GraphMemory.graph(out);
        if (held != null) return storedResult(held.graph(), held.key(), out);
        try {
            Path bin = out.resolve(GraphOutputs.SNAPSHOT);
            if (Files.isRegularFile(bin)) {
                try (InputStream in = Files.newInputStream(bin)) {
                    GraphSnapshot.Loaded loaded = GraphSnapshot.read(in);
                    if (loaded != null) {
                        GraphMemory.sawGraph(out, loaded.graph(), loaded.key());
                        return storedResult(loaded.graph(), loaded.key(), out);
                    }
                }
            }
            Path json = out.resolve(GraphOutputs.JSON);
            if (!Files.isRegularFile(json)) return null;
            return new GraphBuild.Result(GraphJson.read(Files.readString(json)), 0, 0, 0, out, 0, false, null);
        } catch (IOException | IllegalArgumentException unreadable) {
            return null;
        }
    }

    private static GraphBuild.Result storedResult(CodeGraph graph, GraphSnapshot.Key key, Path out) {
        return new GraphBuild.Result(graph, key.files(), 0, 0, out, 0, false, key.commit());
    }

    static String text(Workspace w, GraphBuild.Result build, Request r) {
        CodeGraph g = build.graph();
        Scope scope = Scope.of(r.scope(), r.modulePrefixes(), r.sourceSet(), g.modules());
        GraphTools tools = new GraphTools(g, scope, rel -> source(w.root(), rel));
        List<String> a = r.args();
        return switch (r.op()) {
            case "build" -> "";
            case "query" ->
                new GraphQuery(g, scope, w.aliases())
                        .query(String.join(" ", a), mode(r.mode()), r.depth(), r.budget(), r.contexts());
            case "explain" -> tools.explain(arg(a, 0, "a node name"), r.all());
            case "path" ->
                tools.shortestPath(arg(a, 0, "a source"), arg(a, 1, "a target"), r.maxHops(), r.undirected());
            case "node" -> tools.node(arg(a, 0, "a node name"));
            case "neighbors" ->
                tools.neighbors(arg(a, 0, "a node name"), r.relation() == null ? "" : r.relation(), r.budget());
            case "community" -> tools.community(community(arg(a, 0, "a community id")), r.budget());
            case "god-nodes" -> tools.godNodes(r.topN());
            case "stats" -> tools.stats(build.commit());
            case "report" -> GraphReport.write(g, build.commit());
            case "rules" -> RulesFor.answer(g, w.root(), arg(a, 0, "a file"), w.guardRules());
            case "find" -> find(w, g, scope, r, a);
            case "read" ->
                new SymbolReader(
                                g,
                                scope,
                                rel -> source(w.root(), rel),
                                new LibraryApi(w.libraries(), w.apiCache())::describe)
                        .read(
                                String.join(";", a.isEmpty() ? List.of(arg(a, 0, "an address")) : a),
                                SymbolReader.View.of(r.options().get("view")),
                                r.budget());
            default -> throw new IllegalArgumentException("unknown graph operation '" + r.op() + "'");
        };
    }

    /** Workspace declarations, or with {@code libraries=true} the locked jars' types by name glob and kind. */
    private static String find(Workspace w, CodeGraph g, Scope scope, Request r, List<String> a) {
        String name = a.isEmpty() ? null : String.join(" ", a);
        if (!EnvValues.parseBool(r.options().get("libraries")).orElse(false)) {
            return SymbolFinder.of(r.options(), name).find(g, scope, r.budget());
        }
        if (name == null) throw new IllegalArgumentException("a library search needs a name glob: *Mapper");
        String kinds = r.options().getOrDefault("kind", "");
        List<String> wanted = kinds.isBlank()
                ? List.of()
                : List.of(kinds.toLowerCase(Locale.ROOT).replace(" ", "").split(","));
        List<String> rows = new LibraryApi(w.libraries(), w.apiCache()).find(SymbolFinder.namePattern(name), wanted);
        List<String> lines = new ArrayList<>();
        lines.add(rows.size() == 1 ? "1 library type" : rows.size() + " library types");
        lines.addAll(rows);
        return Budget.lines(lines, r.budget(), "Narrow the name glob or add a kind");
    }

    /** The text of the file at {@code rel} under {@code root}; {@code null} when it cannot be read. */
    private static @Nullable String source(Path root, String rel) {
        try {
            Path file = root.resolve(rel).normalize();
            if (!file.startsWith(root.normalize()) || !Files.isRegularFile(file)) return null;
            return Files.readString(file);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private static GraphQuery.Mode mode(String mode) {
        return mode.toLowerCase(Locale.ROOT).equals("dfs") ? GraphQuery.Mode.DFS : GraphQuery.Mode.BFS;
    }

    private static int community(String id) {
        try {
            return Integer.parseInt(id.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("community id must be a number, got '" + id + "'");
        }
    }

    private static String arg(List<String> args, int i, String what) {
        if (i >= args.size() || args.get(i).isBlank()) throw new IllegalArgumentException("needs " + what);
        return args.get(i);
    }
}
