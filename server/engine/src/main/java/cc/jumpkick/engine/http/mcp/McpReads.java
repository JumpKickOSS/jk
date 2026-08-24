// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.protocol.OutdatedReport;
import cc.jumpkick.engine.protocol.WhyReport;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.runtime.ExplainReport;
import cc.jumpkick.runtime.GraphOps;
import cc.jumpkick.runtime.OutdatedPlans;
import cc.jumpkick.runtime.TaskForecast;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sync reads (why / explain / outdated): the same domain results the wire verbs serve
 * ({@link WhyReport}, {@link ExplainReport}, {@link OutdatedReport}), encoded for MCP
 * {@code structuredContent}. Only encoding lives here — never a second result shaping.
 */
public final class McpReads {

    private McpReads() {}

    public static Map<String, Object> why(String dir, String query) {
        Path root = PathUtil.resolveUserPath(dir);
        return GraphOps.why(root, query).toStructured();
    }

    /**
     * Budgeted explain: per-module dirty/cached summary plus the same ETAs and module edges the
     * wire burst carries. The step-level rows stay wire/CLI-only — an agent that needs them runs
     * {@code jk explain} or reads the CLI JSONL.
     */
    public static Map<String, Object> explain(String dir) {
        Path root = PathUtil.resolveUserPath(dir);
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            var build = JkBuildParser.parse(root.resolve("jk.toml"));
            Path cache = JkDirs.cache();
            Session session = Session.defaults().withWorkingDir(root).withCacheDir(cache);
            ExplainReport report = ExplainReport.compute(root, build, cache, session, ExplainReport.Knobs.defaults());
            if (report.plan().hasErrors()) {
                m.put("errors", report.plan().errors());
                return m;
            }
            List<Map<String, Object>> mods = new ArrayList<>();
            int dirty = 0;
            for (TaskForecast.Module mod : report.plan().modules()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("dir", mod.dir().toString());
                row.put("coord", mod.coord());
                boolean isDirty = mod.dirty();
                if (isDirty) dirty++;
                row.put("dirty", isDirty);
                long cached =
                        mod.steps().stream().filter(TaskForecast.Task::cached).count();
                row.put("cachedSteps", cached);
                row.put("steps", mod.steps().size());
                mods.add(row);
            }
            m.put("modules", mods);
            m.put("dirtyCount", dirty);
            m.put("maxReadyWidth", report.plan().maxReadyWidth());
            m.put("etaMillis", report.etaMillis());
            m.put("fullMillis", report.fullMillis());
            List<Map<String, Object>> edges = new ArrayList<>();
            for (var e : report.plan().edges().entrySet()) {
                for (Path dep : e.getValue()) {
                    Map<String, Object> edge = new LinkedHashMap<>();
                    edge.put("from", e.getKey().toString());
                    edge.put("to", dep.toString());
                    edges.add(edge);
                }
            }
            m.put("edges", edges);
        } catch (Exception e) {
            m.put("error", cc.jumpkick.host.Errors.text(e));
        }
        return m;
    }

    /**
     * Budgeted module/dep graph — the same {@link cc.jumpkick.resolver.DependencyGraphModel}
     * result {@code GET /api/project/graph} serves. Default is members + declared deps
     * (non-transitive); transitive expansion is opt-in and re-capped for the MCP budget below
     * the model's own dashboard caps.
     */
    public static Map<String, Object> graph(String dir, String scopesCsv, boolean transitive) {
        Path root = PathUtil.resolveUserPath(dir);
        Map<String, Object> m = new LinkedHashMap<>();
        cc.jumpkick.resolver.DependencyGraphModel.Graph g;
        try {
            List<cc.jumpkick.model.Scope> scopes = cc.jumpkick.resolver.DependencyGraphModel.parseScopes(scopesCsv);
            g = cc.jumpkick.resolver.DependencyGraphModel.forProjectDir(root, scopes, transitive);
        } catch (Exception e) {
            m.put("error", cc.jumpkick.host.Errors.text(e));
            return m;
        }
        m.put("workspace", g.workspace());
        m.put("scopes", g.scopes());
        m.put("transitive", g.transitive());
        boolean clipped = g.truncated();
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (var n : g.nodes()) {
            if (nodes.size() >= MCP_GRAPH_MAX_NODES) {
                clipped = true;
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", n.id());
            row.put("label", n.label());
            row.put("kind", n.kind());
            if (n.version() != null) row.put("version", n.version());
            if (n.path() != null) row.put("path", n.path());
            nodes.add(row);
        }
        List<Map<String, Object>> edges = new ArrayList<>();
        for (var e : g.edges()) {
            if (edges.size() >= MCP_GRAPH_MAX_EDGES) {
                clipped = true;
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("from", e.from());
            row.put("to", e.to());
            if (e.scope() != null) row.put("scope", e.scope());
            edges.add(row);
        }
        m.put("truncated", clipped);
        m.put("nodes", nodes);
        m.put("edges", edges);
        return m;
    }

    /** MCP budget caps — tighter than the dashboard's force-layout caps. */
    static final int MCP_GRAPH_MAX_NODES = 200;

    static final int MCP_GRAPH_MAX_EDGES = 600;

    /**
     * Full-model export via the same {@link cc.jumpkick.runtime.GenerateOps} generators the wire
     * serves ({@code jk export maven|gradle|bom}). Returns written paths + notes; agents read the
     * files themselves — inlining pom/settings bodies would blow the budget.
     */
    public static Map<String, Object> export(String dir, String format) {
        Path root = PathUtil.resolveUserPath(dir);
        Map<String, Object> m = new LinkedHashMap<>();
        String kind =
                switch (format == null ? "" : format.trim().toLowerCase(Locale.ROOT)) {
                    case "maven" -> "export-maven";
                    case "gradle" -> "export-gradle";
                    case "bom" -> "export-bom";
                    default -> null;
                };
        if (kind == null) {
            m.put("error", "format must be maven | gradle | bom (IDE files: run `jk ide` — generators are CLI-side)");
            return m;
        }
        cc.jumpkick.engine.protocol.GeneratedFiles files;
        try {
            files = cc.jumpkick.runtime.GenerateOps.generate(root, kind, Map.of());
        } catch (RuntimeException e) {
            m.put("error", cc.jumpkick.host.Errors.text(e));
            return m;
        }
        if (files.error() != null && !files.error().isBlank()) {
            m.put("error", files.error());
            return m;
        }
        m.put("format", format.trim().toLowerCase(Locale.ROOT));
        m.put("paths", files.paths());
        if (files.notes() != null && !files.notes().isEmpty()) m.put("notes", files.notes());
        return m;
    }

    public static Map<String, Object> outdated(String dir) {
        Path root = PathUtil.resolveUserPath(dir);
        try {
            Path cache = JkDirs.cache();
            Session session = Session.defaults().withWorkingDir(root).withCacheDir(cache);
            OutdatedReport r = SessionContext.where(session, () -> OutdatedPlans.compute(root, cache, null));
            return r.toStructured();
        } catch (Exception e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("error", cc.jumpkick.host.Errors.text(e));
            return m;
        }
    }
}
