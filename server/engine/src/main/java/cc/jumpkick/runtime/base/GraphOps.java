// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.Errors;
import cc.jumpkick.host.Log;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.lock.MemberRows;
import cc.jumpkick.model.FeatureSelection;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.resolver.DependencyTree;
import cc.jumpkick.resolver.DependencyTreeStyle;
import cc.jumpkick.resolver.EdgeSelectors;
import cc.jumpkick.resolver.LockGraph;
import cc.jumpkick.resolver.Provenance;
import cc.jumpkick.wire.protocol.WhyReport;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Engine-hosted {@code jk tree} / {@code jk why} (thin-client contract): both need the parsed
 * project + lock (and tree walks path-dep composites, re-parsing module tomls), so the graph
 * reasoning runs engine-side. The tree renders with {@link DependencyTreeStyle.Styling#markers()} —
 * the client, which owns the Theme, substitutes the marker tags with its real stylers.
 */
public final class GraphOps {

    private GraphOps() {}

    /** The marker-tagged tree, ready for {@link DependencyTreeStyle#applyStyling} client-side. */
    public static String treeRender(
            Path dir, int maxDepth, boolean flatten, boolean stack, List<String> scopeNames, FeatureSelection selection)
            throws IOException {
        JkBuild project = JkBuildParser.parse(ManifestPaths.manifestIn(dir));
        Path lockFile = LockPaths.lockFile(dir);
        Lockfile lock = MemberRows.view(LockfileReader.read(lockFile), lockFile, dir);
        List<Scope> scopes = scopeNames.isEmpty()
                ? null
                : scopeNames.stream().map(Scope::fromCanonical).toList();
        return DependencyTree.render(
                project, lock, dir, maxDepth, DependencyTreeStyle.Styling.markers(), flatten, scopes, stack, selection);
    }

    /**
     * The provenance of every lock row matching {@code query}. {@code repositories} builds the
     * project's repository group, through which each step's parent POM is read for the selector it
     * declared.
     */
    public static WhyReport why(
            Path dir, @Nullable String query, FeatureSelection selection, Function<JkBuild, RepoGroup> repositories) {
        try {
            JkBuild project = JkBuildParser.parse(ManifestPaths.manifestIn(dir));
            Lockfile lock = LockfileReader.read(LockPaths.lockFile(dir));
            // One LockGraph per request: a fuzzy query with many matches must not rebuild the
            // whole reverse adjacency per match.
            LockGraph graph = LockGraph.of(project, lock, dir, selection);
            EdgeSelectors edgeSelectors = edgeSelectors(project, lock, repositories);
            List<Lockfile.Artifact> matches = lock.artifacts().stream()
                    .filter(p -> matchesQuery(p.name(), query))
                    .toList();
            List<String> names = new ArrayList<>(matches.size());
            List<String> versions = new ArrayList<>(matches.size());
            List<String> members = new ArrayList<>(matches.size());
            List<String> pinnedBy = new ArrayList<>(matches.size());
            List<String> owners = new ArrayList<>();
            List<String> paths = new ArrayList<>();
            List<String> selectors = new ArrayList<>();
            List<String> roots = new ArrayList<>();
            for (int i = 0; i < matches.size(); i++) {
                Lockfile.Artifact target = matches.get(i);
                // Display GA form to users (not g:a:jar:).
                names.add(ga(target.packageKey()));
                versions.add(target.version());
                members.add(String.join(",", target.members()));
                pinnedBy.add(target.pinnedBy() == null ? "" : target.pinnedBy());
                for (Provenance.Path path : Provenance.pathsTo(graph, target.packageKey())) {
                    owners.add(Integer.toString(i));
                    // The walk ends at the coordinate, so the last step is this row: a partition
                    // row shows its own version there, the workspace's row its own.
                    List<String> steps = new ArrayList<>(path.steps().size());
                    for (int k = 0; k < path.steps().size(); k++) {
                        var s = path.steps().get(k);
                        String v = k == path.steps().size() - 1 ? target.version() : s.version();
                        steps.add(ga(s.module()) + "@" + v);
                    }
                    paths.add(String.join(">", steps));
                    roots.add(path.rootUnit() == null ? "" : path.rootUnit());
                    selectors.add(String.join(
                            WhyReport.STEP_SELECTOR_SEPARATOR, stepSelectors(path, target, graph, edgeSelectors)));
                }
            }
            return new WhyReport(
                    null,
                    names,
                    versions,
                    members,
                    pinnedBy,
                    owners,
                    paths,
                    selectors,
                    roots,
                    prunedEdges(lock, query));
        } catch (IOException | RuntimeException e) {
            return WhyReport.error(Errors.text(e));
        }
    }

    /**
     * What each step of {@code path} was declared with: the manifest's selector for the root, the
     * previous step's POM edge after; {@code ""} where neither says. The last step is {@code target}
     * itself, so a partition row's own edge is the one read.
     */
    private static List<String> stepSelectors(
            Provenance.Path path, Lockfile.Artifact target, LockGraph graph, @Nullable EdgeSelectors edges) {
        List<Provenance.Task> steps = path.steps();
        List<String> out = new ArrayList<>(steps.size());
        for (int k = 0; k < steps.size(); k++) {
            String selector = null;
            if (k == 0) {
                selector = graph.rootSelector(steps.get(k).module());
            } else if (edges != null) {
                Lockfile.Artifact parent = graph.artifact(steps.get(k - 1).module());
                Lockfile.Artifact child = k == steps.size() - 1
                        ? target
                        : graph.artifact(steps.get(k).module());
                if (parent != null && child != null) selector = edges.declared(parent, child);
            }
            out.add(selector == null ? "" : selector);
        }
        return out;
    }

    /** The POM reader for {@code lock}'s edges, or null when the project's repositories cannot be built. */
    private static @Nullable EdgeSelectors edgeSelectors(
            JkBuild project, Lockfile lock, Function<JkBuild, RepoGroup> repositories) {
        try {
            return new EdgeSelectors(repositories.apply(project), lock);
        } catch (RuntimeException e) {
            Log.debug("jk why: repositories unavailable, steps print without their selectors", e);
            return null;
        }
    }

    /** Every {@code excluded-by} line in the lock whose pruned child matches {@code query}, as wire fields. */
    private static List<String> prunedEdges(Lockfile lock, @Nullable String query) {
        List<String> out = new ArrayList<>();
        for (Lockfile.Artifact row : lock.artifacts()) {
            for (String line : row.excludedBy()) {
                int sep = line.indexOf(Lockfile.EXCLUSION_ORIGIN_SEPARATOR);
                String child = sep < 0 ? line : line.substring(0, sep);
                String origin = sep < 0 ? "" : line.substring(sep + Lockfile.EXCLUSION_ORIGIN_SEPARATOR.length());
                if (!matchesQuery(child, query)) continue;
                out.add(String.join(
                        WhyReport.EXCLUSION_FIELD_SEPARATOR,
                        child,
                        origin,
                        ga(row.packageKey()) + "@" + row.version()));
            }
        }
        return out;
    }

    /**
     * Match a lockfile package name/key against a user query. Exact match, GA match (query
     * {@code g:a} vs lock {@code g:a:jar:}), artifact-only match, or substring.
     */
    private static boolean matchesQuery(String name, @Nullable String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery;
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

    private static String ga(@Nullable String nameOrKey) {
        return LockGraph.ga(nameOrKey);
    }
}
