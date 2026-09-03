// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.Errors;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.resolver.DependencyTree;
import cc.jumpkick.resolver.DependencyTreeStyle;
import cc.jumpkick.resolver.LockGraph;
import cc.jumpkick.resolver.Provenance;
import cc.jumpkick.wire.protocol.WhyReport;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Engine-hosted {@code jk tree} / {@code jk why} (thin-client contract): both need the parsed
 * project + lock (and tree walks path-dep composites, re-parsing module tomls), so the graph
 * reasoning runs engine-side. The tree renders with {@link DependencyTreeStyle.Styling#markers()} —
 * the client, which owns the Theme, substitutes the marker tags with its real stylers.
 */
public final class GraphOps {

    private GraphOps() {}

    /** The marker-tagged tree, ready for {@link DependencyTreeStyle#applyStyling} client-side. */
    public static String treeRender(Path dir, int maxDepth, boolean flatten, boolean stack, List<String> scopeNames)
            throws IOException {
        JkBuild project = JkBuildParser.parse(dir.resolve(ManifestPaths.MANIFEST));
        Lockfile lock = LockfileReader.read(LockPaths.lockFile(dir));
        List<Scope> scopes = scopeNames.isEmpty()
                ? null
                : scopeNames.stream().map(Scope::fromCanonical).toList();
        return DependencyTree.render(
                project, lock, dir, maxDepth, DependencyTreeStyle.Styling.markers(), flatten, scopes, stack);
    }

    public static WhyReport why(Path dir, String query) {
        try {
            JkBuild project = JkBuildParser.parse(dir.resolve(ManifestPaths.MANIFEST));
            Lockfile lock = LockfileReader.read(LockPaths.lockFile(dir));
            // One LockGraph per request: a fuzzy query with many matches used to rebuild the
            // whole reverse adjacency per match.
            LockGraph graph = LockGraph.of(project, lock, dir);
            List<Lockfile.Artifact> matches = lock.artifacts().stream()
                    .filter(p -> matchesQuery(p.name(), query))
                    .toList();
            List<String> names = new ArrayList<>(matches.size());
            List<String> versions = new ArrayList<>(matches.size());
            List<String> owners = new ArrayList<>();
            List<String> paths = new ArrayList<>();
            for (int i = 0; i < matches.size(); i++) {
                Lockfile.Artifact target = matches.get(i);
                // Display GA form to users (not g:a:jar:).
                names.add(ga(target.packageKey()));
                versions.add(target.version());
                for (Provenance.Path path : Provenance.pathsTo(graph, target.packageKey())) {
                    owners.add(Integer.toString(i));
                    paths.add(path.steps().stream()
                            .map(s -> ga(s.module()) + "@" + s.version())
                            .collect(Collectors.joining(">")));
                }
            }
            return new WhyReport(null, names, versions, owners, paths);
        } catch (IOException | RuntimeException e) {
            return WhyReport.error(Errors.text(e));
        }
    }

    /**
     * Match a lockfile package name/key against a user query. Exact match, GA match (query
     * {@code g:a} vs lock {@code g:a:jar:}), artifact-only match, or substring.
     */
    private static boolean matchesQuery(String name, String query) {
        if (name.equals(query)) return true;
        String nameGa = ga(name);
        String queryGa = ga(query);
        if (nameGa.equals(queryGa)) return true;
        if (!query.contains(":")) {
            // artifact-only: match last GA segment or full package key tail
            if (nameGa.endsWith(":" + query)) return true;
            if (name.endsWith(":" + query) || name.contains(query)) return true;
            return nameGa.contains(query);
        }
        return name.contains(query) || nameGa.contains(query);
    }

    private static String ga(String nameOrKey) {
        return LockGraph.ga(nameOrKey);
    }
}
