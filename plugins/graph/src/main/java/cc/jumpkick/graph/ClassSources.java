// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.cache.GraphCacheKeys;
import cc.jumpkick.codegraph.Fragment;
import cc.jumpkick.codegraph.GraphEdge;
import cc.jumpkick.codegraph.GraphNode;
import cc.jumpkick.codegraph.Mention;
import cc.jumpkick.codegraph.Modules;
import cc.jumpkick.codegraph.extract.Extractors;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.host.Log;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Which source each compiled class came from: a class set's compiled facts cut into one fragment
 * per source file. The action cache keeps that mapping under the set's compile keys and {@link
 * GraphCacheKeys#VERSION}, so a change here moves the version ({@code ExtractorVersionTest}).
 */
final class ClassSources {

    private ClassSources() {}

    /** The JVM sources among {@code sources}, by file name: where a class's source file is looked for. */
    static Map<String, List<String>> byName(List<GraphSources.Source> sources) {
        Map<String, List<String>> byName = new HashMap<>();
        for (GraphSources.Source s : sources) {
            if (Extractors.isJvmSource(s.rel())) {
                byName.computeIfAbsent(s.rel().substring(s.rel().lastIndexOf('/') + 1), k -> new ArrayList<>())
                        .add(s.rel());
            }
        }
        return byName;
    }

    /**
     * Class set {@code c}'s compiled facts, one fragment per source file its classes came from. A
     * class maps only to a source of its own module and source set, the sources its compile keys
     * cover, so a cached mapping holds wherever the key does.
     */
    static Map<String, Fragment> map(
            FactsIndex facts, Map<String, List<String>> byName, Modules modules, GraphBuild.ClassIndex c) {
        Predicate<String> own = own(modules, c.modulePrefix(), c.sourceSet());
        return split(JvmFacts.map(facts, (pkg, file) -> find(byName, own, pkg, file)));
    }

    /**
     * {@code f} cut into one fragment per source file: a node and an edge by the file they name, a
     * mention by the file of the node it comes from.
     */
    static Map<String, Fragment> split(Fragment f) {
        Map<String, String> fileOf = new HashMap<>();
        Map<String, List<GraphNode>> nodes = new LinkedHashMap<>();
        Map<String, List<GraphEdge>> edges = new HashMap<>();
        Map<String, List<Mention>> mentions = new HashMap<>();
        for (GraphNode n : f.nodes()) {
            fileOf.put(n.id(), n.sourceFile());
            nodes.computeIfAbsent(n.sourceFile(), k -> new ArrayList<>()).add(n);
        }
        for (GraphEdge e : f.edges()) {
            edges.computeIfAbsent(e.sourceFile(), k -> new ArrayList<>()).add(e);
        }
        for (Mention m : f.mentions()) {
            String file = fileOf.get(m.source());
            if (file == null) {
                Log.debug("graph: a compiled mention from " + m.source() + " names no file; left out");
                continue;
            }
            mentions.computeIfAbsent(file, k -> new ArrayList<>()).add(m);
        }
        Set<String> files = new TreeSet<>(nodes.keySet());
        files.addAll(edges.keySet());
        files.addAll(mentions.keySet());
        Map<String, Fragment> out = new LinkedHashMap<>();
        for (String file : files) {
            out.put(
                    file,
                    new Fragment(
                            nodes.getOrDefault(file, List.of()),
                            edges.getOrDefault(file, List.of()),
                            mentions.getOrDefault(file, List.of())));
        }
        return out;
    }

    /** Whether a workspace-relative source is one of module {@code prefix}'s {@code sourceSet} sources. */
    static Predicate<String> own(Modules modules, String prefix, String sourceSet) {
        return rel -> {
            Modules.Module m = modules.of(rel);
            return (m == null ? "" : m.prefix()).equals(prefix) && sourceSet.equals(set(modules, rel));
        };
    }

    /**
     * The class set a workspace-relative source compiles into: {@code main}, {@code guard} for the
     * guard suite ({@code src/guard/}, a compact module's {@code guard/src/}), whose classes sit
     * apart, {@code test} for every other test suite; {@code null} for a file of no source set.
     */
    static @Nullable String set(Modules modules, String rel) {
        Modules.SourceSet s = modules.sourceSet(rel);
        if (s == Modules.SourceSet.OTHER) return null;
        if (s == Modules.SourceSet.MAIN) return "main";
        Modules.Module m = modules.of(rel);
        String inside = m == null
                ? rel.substring(Math.max(0, rel.indexOf("src/")))
                : rel.substring(m.prefix().length());
        boolean compact = m != null && m.compact();
        return inside.startsWith(compact ? "guard/src/" : "src/guard/") ? "guard" : "test";
    }

    /**
     * The source a class came from, among those {@code own} accepts (its module's, of its source
     * set): in its package directory. A Java source must sit there; a Kotlin or Groovy one may sit
     * anywhere, so the lone such source of that name answers for it — never a Java file of another
     * package, whose facts it would take on.
     */
    static @Nullable String find(Map<String, List<String>> byName, Predicate<String> own, String pkg, String file) {
        List<String> candidates =
                byName.getOrDefault(file, List.of()).stream().filter(own).toList();
        String suffix = pkg.isEmpty() ? "/" + file : "/" + pkg + "/" + file;
        for (String c : candidates) {
            if (("/" + c).endsWith(suffix)) return c;
        }
        if (file.endsWith(".java")) return null;
        return candidates.size() == 1 ? candidates.getFirst() : null;
    }
}
