// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.graph;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.ModuleOrder;
import cc.jumpkick.config.UserConfigParseException;
import cc.jumpkick.host.Log;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PathSource;
import cc.jumpkick.model.Variants;
import cc.jumpkick.runtime.BuildGraph;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * Which workspace modules each module can see, for the graph worker's reach: the edges of the
 * resolved {@link BuildGraph} (every dependency scope, a published POM edge a member serves,
 * {@code order-after}), and the siblings a {@code [variants]} value's dependencies or a {@code path}
 * dependency name, which the build graph leaves to the plan.
 */
final class GraphModuleEdges {

    private GraphModuleEdges() {}

    /**
     * {@code prefix \t prereq \t prereq …}, one row per module of the build graph, each a module
     * directory relative to {@code root} ({@code ""} for the root, else ending in {@code /}); none
     * when the graph does not resolve, so the worker counts every module.
     */
    static List<String> rows(Path root) {
        try {
            Path manifest = ManifestPaths.manifestIn(root);
            if (!Files.isRegularFile(manifest)) return List.of();
            BuildGraph.Result graph = BuildGraph.resolve(root, JkBuildParser.parse(manifest));
            if (graph.hasErrors()) return List.of();
            Path base = BuildGraph.canonicalPath(root.toAbsolutePath().normalize());
            Map<String, Path> dirByCoord = new LinkedHashMap<>();
            Map<String, Path> dirByName = new LinkedHashMap<>();
            for (BuildGraph.BuildUnit unit : graph.topoOrder()) {
                dirByCoord.put(unit.coord(), unit.dir());
                dirByName.put(unit.manifest().project().name(), unit.dir());
            }
            List<String> out = new ArrayList<>();
            for (BuildGraph.BuildUnit unit : graph.topoOrder()) {
                Set<String> prereqs = new TreeSet<>();
                for (Path dir : graph.edges().getOrDefault(unit.dir(), Set.of())) prereqs.add(prefix(base, dir));
                for (Dependency d : unplanned(unit.manifest())) {
                    Path dir = sibling(unit.dir(), d, dirByCoord, dirByName, graph);
                    if (dir != null && !dir.equals(unit.dir())) prereqs.add(prefix(base, dir));
                }
                StringBuilder row = new StringBuilder(prefix(base, unit.dir()));
                for (String p : prereqs) row.append('\t').append(p);
                out.add(row.toString());
            }
            return out;
        } catch (UserConfigParseException unparsed) {
            throw unparsed; // config.toml's own error, with its position
        } catch (IOException | RuntimeException unresolved) {
            Log.debug("graph: no module edges for " + root, unresolved);
            return List.of();
        }
    }

    /** The dependencies the build graph does not draw an edge for: every variant value's, and every path dependency. */
    private static List<Dependency> unplanned(JkBuild m) {
        List<Dependency> out = new ArrayList<>();
        for (Variants.Dimension dimension : m.variants().dimensions()) {
            for (Variants.Value value : dimension.values().values()) {
                value.dependencies().values().forEach(out::addAll);
            }
        }
        for (Collection<Dependency> deps : m.dependencies().byScope().values()) {
            for (Dependency d : deps) if (d.isPath()) out.add(d);
        }
        return out;
    }

    private static @Nullable Path sibling(
            Path moduleDir,
            Dependency d,
            Map<String, Path> dirByCoord,
            Map<String, Path> dirByName,
            BuildGraph.Result graph) {
        PathSource source = d.pathSource();
        if (source != null) {
            Path path = BuildGraph.canonicalPath(
                    moduleDir.resolve(source.rawPath()).toAbsolutePath().normalize());
            if (graph.edges().containsKey(path)) return path;
        }
        return ModuleOrder.resolveSibling(d, dirByCoord, dirByName);
    }

    private static String prefix(Path base, Path dir) {
        String rel = base.relativize(dir).toString().replace('\\', '/');
        return rel.isEmpty() ? "" : rel + "/";
    }
}
