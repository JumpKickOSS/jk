// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.codegraph.CodeGraph;
import cc.jumpkick.codegraph.GraphJson;
import cc.jumpkick.codegraph.GraphQuery;
import cc.jumpkick.codegraph.GraphReport;
import cc.jumpkick.codegraph.GraphSnapshot;
import cc.jumpkick.codegraph.GraphTools;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
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
            "build", "query", "explain", "path", "node", "neighbors", "community", "god-nodes", "stats", "report");

    /** One operation's arguments, as the CLI and MCP surfaces collect them. */
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
            boolean noRefresh) {}

    /** An operation's text and the refresh it was answered from. */
    public record Answer(String text, GraphBuild.Result build) {}

    public static Answer run(
            Path root,
            Path out,
            List<GraphBuild.ClassIndex> indexes,
            ScriptReader scripts,
            Request r,
            GraphBuild.Progress progress)
            throws IOException {
        if (!OPS.contains(r.op())) throw new IllegalArgumentException("unknown graph operation '" + r.op() + "'");
        GraphBuild.Result build = r.noRefresh() ? stored(out) : null;
        if (build == null) build = GraphBuild.refresh(root, out, indexes, scripts, r.rebuild(), progress);
        return new Answer(text(build, r), build);
    }

    /** The last graph written, without looking at the tree: {@code graph.bin}, else {@code graph.json}. */
    private static GraphBuild.@Nullable Result stored(Path out) {
        try {
            Path bin = out.resolve(GraphOutputs.SNAPSHOT);
            if (Files.isRegularFile(bin)) {
                try (InputStream in = Files.newInputStream(bin)) {
                    GraphSnapshot.Loaded loaded = GraphSnapshot.read(in);
                    if (loaded != null) {
                        return new GraphBuild.Result(
                                loaded.graph(),
                                loaded.key().files(),
                                0,
                                0,
                                out,
                                0,
                                false,
                                loaded.key().commit());
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

    static String text(GraphBuild.Result build, Request r) {
        CodeGraph g = build.graph();
        GraphTools tools = new GraphTools(g);
        List<String> a = r.args();
        return switch (r.op()) {
            case "build" -> "";
            case "query" ->
                new GraphQuery(g).query(String.join(" ", a), mode(r.mode()), r.depth(), r.budget(), r.contexts());
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
            default -> throw new IllegalArgumentException("unknown graph operation '" + r.op() + "'");
        };
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
