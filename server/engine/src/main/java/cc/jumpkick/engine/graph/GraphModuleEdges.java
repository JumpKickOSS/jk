// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.graph;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.UserConfigParseException;
import cc.jumpkick.host.Log;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.runtime.BuildGraph;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which workspace modules each module can see, for the graph worker's reach: the edges of the
 * resolved {@link BuildGraph}, the same edges that order the build (every dependency scope, every
 * {@code [variants]} value, a published POM edge a member serves, {@code order-after}).
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
            List<String> out = new ArrayList<>();
            for (BuildGraph.BuildUnit unit : graph.topoOrder()) {
                Set<String> prereqs = new TreeSet<>();
                for (Path dir : graph.edges().getOrDefault(unit.dir(), Set.of())) prereqs.add(prefix(base, dir));
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

    private static String prefix(Path base, Path dir) {
        String rel = base.relativize(dir).toString().replace('\\', '/');
        return rel.isEmpty() ? "" : rel + "/";
    }
}
