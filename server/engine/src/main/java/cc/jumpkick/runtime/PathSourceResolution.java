// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PathSource;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoArtifactResolver;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.resolver.CrossPackageFeatures;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pre-solve path-source bridge (analogue of {@link GitSourceResolution}): materialize each
 * {@code path = "..."} dep into a {@code file://} Maven repo, augment {@link RepoGroup}, and
 * rewrite to an exact coordinate pin for PubGrub. No path deps → no-op.
 */
public final class PathSourceResolution {

    private PathSourceResolution() {}

    /**
     * The result of preparing a build: the dependency-rewritten project, the augmented repos, and the
     * cross-package features activated on each path library, keyed by the coordinate its lock row
     * carries — the selection itself travels no further than the rewrite.
     */
    public record Prepared(JkBuild project, RepoGroup repos, Map<String, List<String>> activatedFeatures) {}

    /**
     * Materialize every path dependency in {@code effective} (resolving each against {@code
     * lockRootDir} — the directory of the consuming {@code jk.toml}), augment {@code baseRepos} with
     * each artifact's {@code file://} repo, and rewrite path deps to coordinate pins.
     */
    public static Prepared prepare(
            JkBuild effective, RepoGroup baseRepos, Cas cas, Path lockRootDir, Path javaHome, String jkVersion)
            throws IOException, InterruptedException {
        Map<Scope, List<Dependency>> byScope = effective.dependencies().byScope();
        // A feature selection on a path library pulls that library's optional deps into the
        // consumer's main graph. Expanded here, before the rewrite: the exact coordinate pin that
        // replaces a path dep carries no selection, so this is the last place that can see it.
        CrossPackageFeatures.Result cross =
                CrossPackageFeatures.expand(lockRootDir, byScope.getOrDefault(Scope.MAIN, List.of()));
        boolean anyPath = byScope.values().stream().flatMap(List::stream).anyMatch(Dependency::isPath);
        if (!anyPath) {
            return new Prepared(effective, baseRepos, Map.of());
        }

        PathSourceMaterializer materializer =
                new PathSourceMaterializer(lockRootDir, cas, baseRepos, javaHome, jkVersion);

        // Materialize once per unique target directory; a target appearing in several scopes
        // (main + test) is built and published only once.
        Map<String, PathSourceMaterializer.Materialized> bySource = new LinkedHashMap<>();
        List<MavenRepo> extraRepos = new ArrayList<>();
        for (List<Dependency> list : byScope.values()) {
            for (Dependency d : list) {
                PathSource path = d.pathSource();
                if (path == null) continue;
                String key = path.rawPath();
                if (bySource.containsKey(key)) continue;
                PathSourceMaterializer.Materialized m = materializer.materialize(path);
                bySource.put(key, m);
                extraRepos.add(new MavenRepo(
                        RepoArtifactResolver.GIT_SOURCE_PREFIX + m.coordinate() + ":" + m.version(),
                        m.repoUrl(),
                        new Http(),
                        cas));
            }
        }

        // Rewrite each path dep into an exact pin on the materialized coordinate.
        EnumMap<Scope, List<Dependency>> rewritten = new EnumMap<>(Scope.class);
        Map<String, List<String>> activated = new LinkedHashMap<>();
        byScope.forEach((scope, list) -> {
            List<Dependency> out = new ArrayList<>(list.size());
            for (Dependency d : list) {
                PathSource path = d.pathSource();
                if (path == null) {
                    out.add(d);
                    continue;
                }
                // Every path dep was materialized by the loop above, keyed on this same raw path.
                PathSourceMaterializer.Materialized m = Objects.requireNonNull(bySource.get(path.rawPath()));
                out.add(Dependency.of(d.library(), m.coordinate(), VersionSelector.parse("=" + m.version())));
                List<String> features = cross.activatedFeaturesByModule().get(d.module());
                if (features != null && !features.isEmpty()) activated.put(m.coordinate(), features);
            }
            rewritten.put(scope, out);
        });
        // The library's activated optional deps root in the consumer's main graph as ordinary deps.
        List<Dependency> main = new ArrayList<>(rewritten.getOrDefault(Scope.MAIN, List.of()));
        for (Dependency extra : cross.extrasList()) {
            if (main.stream().noneMatch(x -> x.packageKey().equals(extra.packageKey()))) main.add(extra);
        }
        rewritten.put(Scope.MAIN, main);

        JkBuild project = JkBuild.builder(effective.project())
                .dependencies(new JkBuild.Dependencies(rewritten))
                .repositories(effective.repositories())
                .profiles(effective.profiles())
                .features(effective.features())
                .workspace(effective.workspace())
                .manifest(effective.manifest())
                .build();

        // Path artifact repos first: the pinned coordinate is built locally, so the file:// repo
        // answers before any remote is consulted. Preserve exclusive group bindings on baseRepos
        // (JumpKick first-party) — rebuilding without them re-opens jumpkick→404→central for every
        // Central GAV.
        return new Prepared(project, baseRepos.withReposPrepended(extraRepos), Map.copyOf(activated));
    }
}
