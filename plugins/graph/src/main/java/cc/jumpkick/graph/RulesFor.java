// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.codegraph.CodeGraph;
import cc.jumpkick.codegraph.Decl;
import cc.jumpkick.codegraph.GraphNode;
import cc.jumpkick.codegraph.Modules;
import cc.jumpkick.codegraph.Scope;
import cc.jumpkick.jsonl.MiniJson;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk graph rules <file>}: what governs a file before an agent edits it. The guard rules whose
 * reach covers it (the engine has already matched module scope and {@code allow} entries; this
 * narrows by source set, {@code files} globs, languages and a generated block's target) and the
 * {@code NOTE:}/{@code WHY:} rationale comments the graph holds for it.
 */
final class RulesFor {

    private RulesFor() {}

    /** Kinds that read text, which reach files outside the source sets too. */
    private static final Set<String> TEXT_KINDS = Set.of("text", "metric", "generated", "vocabulary");

    private static final Map<String, String> LANGUAGES = Map.of(
            "java", "java", "kt", "kotlin", "kts", "kotlin", "groovy", "groovy", "gradle", "groovy", "scala", "scala");

    static String answer(CodeGraph graph, Path root, String path, List<String> rows) {
        Path p = Path.of(path);
        String rel = (p.isAbsolute() ? root.relativize(p.normalize()) : p.normalize())
                .toString()
                .replace('\\', '/');
        Modules.Module module = graph.modules().of(rel);
        Modules.SourceSet set = graph.modules().sourceSet(rel);
        List<String> lines = new ArrayList<>();
        for (String row : rows) {
            if (!(MiniJson.parse(row) instanceof Map<?, ?> r)) continue;
            String kind = String.valueOf(r.get("kind"));
            if (!reaches(r, kind, rel, set, module)) continue;
            Object instead = r.get("instead");
            lines.add("  " + r.get("id") + " [" + kind + "] " + r.get("why")
                    + (instead == null ? "" : " Instead: " + instead));
        }
        List<GraphNode> notes = graph.nodes().stream()
                .filter(n -> n.fileType().equals("rationale") && n.sourceFile().equals(rel))
                .sorted(Comparator.comparingInt(Decl::line))
                .toList();
        if (lines.isEmpty() && notes.isEmpty()) return "No guard rules or notes govern " + rel + ".";
        List<String> out = new ArrayList<>();
        String where = (module == null || module.prefix().isEmpty() ? "root module" : "module " + module.prefix())
                + ", " + set.name().toLowerCase(Locale.ROOT);
        out.add("Rules for " + rel + " (" + where + "):");
        if (lines.isEmpty()) out.add("  none");
        out.addAll(lines);
        if (!notes.isEmpty()) {
            out.add("Notes in this file:");
            for (GraphNode n : notes) out.add("  L" + Decl.line(n) + " " + n.label());
        }
        return String.join("\n", out);
    }

    private static boolean reaches(
            Map<?, ?> r, String kind, String rel, Modules.SourceSet set, Modules.@Nullable Module module) {
        boolean text = TEXT_KINDS.contains(kind);
        if (kind.equals("generated")) {
            return r.get("into") instanceof String into && (rel.equals(into) || rel.endsWith("/" + into));
        }
        if (!text) {
            String wanted = String.valueOf(r.get("set"));
            if (set == Modules.SourceSet.OTHER) return false;
            if (!wanted.equals("all") && !wanted.equals(set.name().toLowerCase(Locale.ROOT))) return false;
        }
        List<String> files = strings(r.get("files"));
        if (!files.isEmpty()) {
            String inModule =
                    module == null ? rel : rel.substring(module.prefix().length());
            Scope globs = Scope.of(String.join(";", files), List.of(), null, Modules.NONE);
            if (!globs.containsPath(rel) && !globs.containsPath(inModule)) return false;
        } else if (text && set == Modules.SourceSet.OTHER) {
            return false;
        }
        List<String> languages = strings(r.get("languages"));
        if (!languages.isEmpty()) {
            String ext = rel.substring(rel.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            return languages.contains(LANGUAGES.getOrDefault(ext, ext));
        }
        return true;
    }

    private static List<String> strings(@Nullable Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) l.forEach(x -> out.add(String.valueOf(x)));
        return out;
    }
}
