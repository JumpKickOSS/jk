// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.engine.protocol.OutdatedReport;
import cc.jumpkick.engine.protocol.WhyReport;
import cc.jumpkick.runtime.BuildService;
import cc.jumpkick.runtime.ExplainPlan;
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

/** Sync read verbs: why / explain / outdated. */
public final class McpReads {

    private McpReads() {}

    public static Map<String, Object> why(String dir, String query) {
        Path root = PathUtil.resolveUserPath(dir);
        WhyReport r = GraphOps.why(root, query);
        Map<String, Object> m = new LinkedHashMap<>();
        if (r.error() != null) {
            m.put("error", r.error());
            return m;
        }
        List<Map<String, Object>> matches = new ArrayList<>();
        for (int i = 0; i < r.matchNames().size(); i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", r.matchNames().get(i));
            row.put("version", i < r.matchVersions().size() ? r.matchVersions().get(i) : "");
            List<String> paths = new ArrayList<>();
            String idx = Integer.toString(i);
            for (int p = 0; p < r.paths().size(); p++) {
                if (idx.equals(r.pathOwners().get(p))) paths.add(r.paths().get(p));
            }
            row.put("paths", paths);
            matches.add(row);
        }
        m.put("matches", matches);
        return m;
    }

    public static Map<String, Object> explain(String dir) {
        Path root = PathUtil.resolveUserPath(dir);
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            var build = JkBuildParser.parse(root.resolve("jk.toml"));
            ExplainPlan plan = BuildService.explain(root, build, JkDirs.cache());
            if (plan.hasErrors()) {
                m.put("errors", plan.errors());
                return m;
            }
            List<Map<String, Object>> mods = new ArrayList<>();
            int dirty = 0;
            for (TaskForecast.Module mod : plan.modules()) {
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
            m.put("maxReadyWidth", plan.maxReadyWidth());
        } catch (Exception e) {
            m.put("error", String.valueOf(e.getMessage()));
        }
        return m;
    }

    public static Map<String, Object> outdated(String dir) {
        Path root = PathUtil.resolveUserPath(dir);
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            OutdatedReport r = OutdatedPlans.compute(root, JkDirs.cache(), null);
            if (r.error() != null) {
                m.put("error", r.error());
                return m;
            }
            m.put("workspace", r.workspace());
            List<Map<String, Object>> rows = new ArrayList<>();
            for (OutdatedReport.Row row : r.rows()) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("module", row.moduleLabel());
                o.put("coordinate", row.coordinate());
                o.put("current", row.current());
                o.put("compatible", row.compatible());
                o.put("latest", row.latest());
                if (row.tip() != null && !row.tip().isBlank()) o.put("tip", row.tip());
                rows.add(o);
            }
            m.put("rows", rows);
        } catch (Exception e) {
            m.put("error", String.valueOf(e.getMessage()));
        }
        return m;
    }
}
