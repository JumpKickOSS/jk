// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.protocol.OutdatedReport;
import cc.jumpkick.engine.protocol.WhyReport;
import cc.jumpkick.runtime.ExplainReport;
import cc.jumpkick.runtime.GraphOps;
import cc.jumpkick.runtime.OutdatedPlans;
import cc.jumpkick.runtime.TaskForecast;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.util.PathUtil;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
            m.put("error", String.valueOf(e.getMessage()));
        }
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
            m.put("error", String.valueOf(e.getMessage()));
            return m;
        }
    }
}
