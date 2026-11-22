// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.lock.ManifestPaths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Under a failed resolve in the agent report, the conflict it ended on ({@link BuildRecord.Diag#conflict}):
 * each contested coordinate, every version wanted of it and who wants it, what was picked and
 * why, and the {@code why} call that shows the path.
 */
final class JkResultsConflict {

    /** Contested coordinates shown under one failure. */
    static final int MAX_COORDS = 3;

    /** Wanted versions shown under one coordinate. The rest is one {@code +N more} line. */
    static final int MAX_WANTS = 5;

    /** Advertised versions named when none matched. */
    private static final int MAX_AVAILABLE = 5;

    private JkResultsConflict() {}

    /** The lines under the failure, each ending in a newline; empty when {@code conflict} names no coordinate. */
    static String block(@Nullable String conflict, String projectDir) {
        List<Map<?, ?>> facts = facts(conflict);
        if (facts.isEmpty()) return "";
        Map<String, List<Map<?, ?>>> byCoord = new LinkedHashMap<>();
        for (Map<?, ?> f : facts) {
            String pkg = str(f, "package");
            if (!pkg.isEmpty())
                byCoord.computeIfAbsent(pkg, k -> new ArrayList<>()).add(f);
        }
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Map.Entry<String, List<Map<?, ?>>> e : byCoord.entrySet()) {
            if (shown == MAX_COORDS) break;
            String picked = picked(e.getValue());
            if (picked == null) continue;
            shown++;
            sb.append("  conflict ").append(e.getKey()).append('\n');
            appendWants(sb, e.getValue(), projectDir);
            sb.append("    picked none: ")
                    .append(JkResultsAgent.one(picked, projectDir))
                    .append('\n');
            sb.append("    why(coord=").append(e.getKey()).append(")\n");
        }
        return sb.toString();
    }

    /**
     * Why no version of a coordinate was picked, or {@code null} when it is not contested: none
     * matched what was asked, or two edges want different versions and none is in both.
     */
    private static @Nullable String picked(List<Map<?, ?>> facts) {
        Set<String> wanted = new LinkedHashSet<>();
        for (Map<?, ?> f : facts) {
            String versions = str(f, "versions");
            switch (str(f, "kind")) {
                case "no-versions" -> {
                    List<String> available = strs(f, "available");
                    String sample = String.join(", ", available.subList(0, Math.min(MAX_AVAILABLE, available.size())));
                    return "no version matches " + versions + (sample.isEmpty() ? "" : " · available " + sample);
                }
                case "not-found" -> {
                    List<String> notes = strs(f, "notes");
                    return "not found in any repository" + (notes.isEmpty() ? "" : " · " + notes.get(0));
                }
                case "unavailable" -> {
                    return versions + " is unavailable";
                }
                case "dependency", "constraint" -> wanted.add(versions);
                default -> {}
            }
        }
        return wanted.size() > 1 ? "no version satisfies every want" : null;
    }

    /** One line per distinct want: the versions, then who wants them and what pinned them. */
    private static void appendWants(StringBuilder sb, List<Map<?, ?>> facts, String projectDir) {
        Set<String> lines = new LinkedHashSet<>();
        for (Map<?, ?> f : facts) {
            String kind = str(f, "kind");
            if (!kind.equals("dependency") && !kind.equals("constraint")) continue;
            String from = str(f, "from");
            String who = from.isEmpty() ? ManifestPaths.MANIFEST : from + suffix(str(f, "fromVersions"));
            String pin = str(f, "pinnedBy");
            lines.add(str(f, "versions")
                    + (kind.equals("constraint") ? " allowed by " : " wanted by ")
                    + who
                    + (pin.isEmpty() ? "" : ", pinned by " + pin));
        }
        int n = 0;
        for (String line : lines) {
            if (n++ == MAX_WANTS) break;
            sb.append("    ").append(JkResultsAgent.one(line, projectDir)).append('\n');
        }
        if (lines.size() > MAX_WANTS)
            sb.append("    +").append(lines.size() - MAX_WANTS).append(" more\n");
    }

    private static String suffix(String versions) {
        return versions.isEmpty() ? "" : " " + versions;
    }

    private static List<Map<?, ?>> facts(@Nullable String conflict) {
        if (conflict == null || conflict.isEmpty()) return List.of();
        Object parsed;
        try {
            parsed = MiniJson.parse(conflict);
        } catch (RuntimeException notJson) {
            return List.of();
        }
        if (!(parsed instanceof Map<?, ?> map) || !(map.get("facts") instanceof List<?> rows)) return List.of();
        List<Map<?, ?>> out = new ArrayList<>();
        for (Object row : rows) if (row instanceof Map<?, ?> m) out.add(m);
        return out;
    }

    private static String str(Map<?, ?> m, String key) {
        return m.get(key) instanceof String s ? s : "";
    }

    private static List<String> strs(Map<?, ?> m, String key) {
        List<String> out = new ArrayList<>();
        if (m.get(key) instanceof List<?> list) for (Object o : list) if (o instanceof String s) out.add(s);
        return out;
    }
}
