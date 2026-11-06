// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.codegraph.CodeGraph;
import cc.jumpkick.codegraph.GraphNode;
import cc.jumpkick.codegraph.Modules;
import cc.jumpkick.codegraph.extract.Extractors;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * Which source sets of the modules a rename's method can be called from ({@link ModuleReach}) have
 * no class index, so their uses are read from the sources.
 */
final class RenameReach {

    private RenameReach() {}

    /**
     * The module source sets ({@code prefix set}) that hold JVM sources but no compiled classes,
     * among {@code reaching} (module prefixes; {@code null} for every module).
     */
    static List<String> unbuilt(CodeGraph graph, List<GraphBuild.ClassIndex> indexes, @Nullable Set<String> reaching) {
        Set<String> have = new TreeSet<>();
        for (GraphBuild.ClassIndex c : indexes) {
            have.add(c.modulePrefix() + " " + c.sourceSet());
        }
        Modules modules = graph.modules();
        Set<String> missing = new TreeSet<>();
        for (GraphNode n : graph.nodes()) {
            String rel = n.sourceFile();
            if (rel.isEmpty() || !Extractors.isJvmSource(rel) || rel.contains("/resources/")) continue;
            String set = ClassSources.set(modules, rel);
            if (set == null) continue;
            Modules.Module m = modules.of(rel);
            String prefix = m == null ? "" : m.prefix();
            if (reaching != null && !reaching.contains(prefix)) continue;
            String key = prefix + " " + set;
            if (!have.contains(key)) missing.add(key);
        }
        return List.copyOf(missing);
    }

    /** Whether {@code rel} is one of the sources of {@code set}, an {@link #unbuilt} {@code prefix set}. */
    static boolean inModule(Modules modules, String rel, String set) {
        String prefix = set.substring(0, set.lastIndexOf(' '));
        String sourceSet = set.substring(set.lastIndexOf(' ') + 1);
        Modules.Module m = modules.of(rel);
        return (m == null ? "" : m.prefix()).equals(prefix) && sourceSet.equals(ClassSources.set(modules, rel));
    }
}
