// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.model.JkBuild;
import cc.jumpkick.runtime.BuildGraph;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Expand a {@code --modules} / MCP module pick to include transitive build prereqs so a selected
 * member is not scheduled without the siblings it compiles against.
 */
public final class ModuleHints {

    private ModuleHints() {}

    /**
     * {@code selected} plus every in-graph prereq, canonical paths. Empty {@code selected} yields
     * an empty set. Graph errors become {@link IllegalArgumentException}.
     */
    public static Set<Path> withPrereqs(Path entryDir, JkBuild entry, Set<Path> selected) {
        if (selected == null || selected.isEmpty()) return Set.of();
        Set<Path> want = copyCanonical(selected);
        if (entry == null || !entry.isWorkspaceRoot()) return Set.copyOf(want);
        BuildGraph.Result graph;
        try {
            graph = BuildGraph.resolve(entryDir, entry);
        } catch (IOException e) {
            throw new IllegalArgumentException("module selection: cannot resolve the build graph — " + e.getMessage());
        }
        if (graph.hasErrors()) {
            throw new IllegalArgumentException(String.join("; ", graph.errors()));
        }
        return withPrereqs(graph, selected);
    }

    /** Same as {@link #withPrereqs(Path, JkBuild, Set)} against an already-resolved graph. */
    public static Set<Path> withPrereqs(BuildGraph.Result graph, Set<Path> selected) {
        if (selected == null || selected.isEmpty()) return Set.of();
        Set<Path> want = copyCanonical(selected);
        if (graph == null) return Set.copyOf(want);
        var edges = graph.edges();
        ArrayDeque<Path> q = new ArrayDeque<>(want);
        while (!q.isEmpty()) {
            Path d = q.poll();
            for (Path pre : edges.getOrDefault(d, Set.of())) {
                Path n = BuildGraph.canonicalPath(pre);
                if (want.add(n)) q.add(n);
            }
        }
        return Set.copyOf(want);
    }

    private static Set<Path> copyCanonical(Set<Path> selected) {
        Set<Path> want = new LinkedHashSet<>();
        for (Path p : selected) want.add(BuildGraph.canonicalPath(p));
        return want;
    }
}
