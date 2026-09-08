// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.runtime.base.ProjectCard;
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
        ProjectCard card = ProjectCard.of(root);
        m.put("dir", card.dir());
        if (card.coord() != null) m.put("coord", card.coord());
        if (card.description() != null) m.put("description", card.description());
        if (card.version() != null) m.put("version", card.version());
        if (card.javaRelease() > 0) m.put("java", card.javaRelease());
        if (card.jdk() != null) m.put("jdk", card.jdk());
        if (card.coord() != null) m.put("members", members(card));
        if (card.projectId() != null) m.put("projectId", card.projectId());
        m.put("lockStale", card.lockStale());
        Map<String, Object> last = lastRun(card.dir(), historyRaw);
        if (last != null) m.put("lastRun", last);
        return m;
    }

    private static List<Map<String, Object>> members(ProjectCard card) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ProjectCard.Member member : card.members()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("dir", member.dir());
            if (member.coord() != null) row.put("coord", member.coord());
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
