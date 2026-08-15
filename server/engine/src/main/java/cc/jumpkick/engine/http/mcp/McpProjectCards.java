// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.builds.ProjectBuilds;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.lock.LockFreshness;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.protocol.MiniJson;
import cc.jumpkick.util.PathUtil;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Compact project card for {@code jk_bind} / {@code jk_project}. */
public final class McpProjectCards {

    private McpProjectCards() {}

    public static Map<String, Object> card(String dir, List<String> historyRaw) {
        Map<String, Object> m = new LinkedHashMap<>();
        Path root;
        try {
            root = PathUtil.resolveUserPath(dir);
        } catch (RuntimeException e) {
            m.put("dir", dir);
            return m;
        }
        String abs = root.toString();
        m.put("dir", abs);
        try {
            JkBuild build = JkBuildParser.parse(root.resolve("jk.toml"));
            var p = build.project();
            m.put("coord", p.group() + ":" + p.name());
            if (p.description() != null) m.put("description", p.description());
            m.put("version", p.version());
            int java = p.javaRelease();
            if (java > 0) m.put("java", java);
            if (p.jdk() != null) m.put("jdk", p.jdk());
            m.put("members", members(root, build));
        } catch (Exception ignored) {
            // missing/unparseable jk.toml — still return dir + id if we can
        }
        try {
            String id = ProjectBuilds.key(root);
            if (id != null && !id.isBlank()) m.put("projectId", id);
        } catch (RuntimeException ignored) {
            // no identity yet
        }
        try {
            m.put("lockStale", LockFreshness.needsRefresh(root));
        } catch (RuntimeException e) {
            m.put("lockStale", true);
        }
        Map<String, Object> last = lastRun(abs, historyRaw);
        if (last != null) m.put("lastRun", last);
        return m;
    }

    private static List<Map<String, Object>> members(Path root, JkBuild build) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!build.isWorkspaceRoot() || build.workspace() == null) return out;
        for (String rel : build.workspace().modules()) {
            Map<String, Object> row = new LinkedHashMap<>();
            Path moduleDir = root.resolve(rel).normalize();
            row.put("dir", moduleDir.toString());
            try {
                var p = JkBuildParser.parseLocal(moduleDir.resolve("jk.toml")).project();
                row.put("coord", p.group() + ":" + p.name());
            } catch (Exception ignored) {
                // path-only member
            }
            out.add(row);
        }
        return out;
    }

    private static @Nullable Map<String, Object> lastRun(String absDir, List<String> historyRaw) {
        if (historyRaw == null) return null;
        String want = McpHistoryViews.normalizeDir(absDir);
        for (String raw : historyRaw) {
            Map<String, Object> rec = parseObj(raw);
            if (rec == null) continue;
            String have = McpHistoryViews.normalizeDir(McpHistoryViews.str(rec, "dir"));
            if (!have.equals(want) && !have.startsWith(want + "/")) continue;
            Map<String, Object> sum = McpHistoryViews.summarize(rec);
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("id", sum.get("id"));
            one.put("success", sum.get("success"));
            one.put("exitCode", sum.get("exitCode"));
            one.put("kind", sum.get("kind"));
            one.put("failedModules", sum.get("failedModules"));
            return one;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Map<String, Object> parseObj(String raw) {
        try {
            Object o = MiniJson.parse(raw);
            return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
