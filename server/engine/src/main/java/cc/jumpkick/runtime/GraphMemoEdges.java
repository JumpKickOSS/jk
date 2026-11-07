// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The graph memo's edge lines: {@code <kind>\t<from>\t<to>}, one per prerequisite, where {@code kind}
 * is {@code edge} for a build-order edge and {@code test} for a test edge, and both ends are
 * workspace-relative unit keys.
 */
final class GraphMemoEdges {

    private GraphMemoEdges() {}

    /** Append one {@code kind} line per edge of {@code edges}, keyed relative to {@code root}. */
    static void append(StringBuilder sb, String kind, Path root, Map<Path, Set<Path>> edges) {
        for (var e : edges.entrySet()) {
            String from = PreflightMemo.relKey(root, e.getKey().toAbsolutePath().normalize());
            for (Path prereq : e.getValue()) {
                String to = PreflightMemo.relKey(root, prereq.toAbsolutePath().normalize());
                sb.append(kind)
                        .append('\t')
                        .append(from)
                        .append('\t')
                        .append(to)
                        .append('\n');
            }
        }
    }

    /** Fill {@code into} from {@code rels}; false when a rel names no unit. */
    static boolean resolve(Map<String, Set<String>> rels, Map<String, Path> dirByRel, Map<Path, Set<Path>> into) {
        for (var e : rels.entrySet()) {
            Path from = dirByRel.get(e.getKey());
            if (from == null) return false;
            for (String toRel : e.getValue()) {
                Path to = dirByRel.get(toRel);
                if (to == null) return false;
                into.computeIfAbsent(from, k -> new LinkedHashSet<>()).add(to);
            }
        }
        return true;
    }
}
