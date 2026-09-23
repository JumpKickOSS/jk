// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.host.Log;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.RepoSource;
import cc.jumpkick.model.PackageId;
import cc.jumpkick.repo.EffectivePom;
import cc.jumpkick.repo.EffectivePomBuilder;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.Pom;
import cc.jumpkick.repo.RepoGroup;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The selector a lock row's POM declares for one of its edges, for {@code jk why}. The lock pins
 * one version per edge; what the parent asked for is read from its effective POM, which the store
 * holds from the resolve that wrote the lock.
 */
public final class EdgeSelectors {

    private final EffectivePomBuilder poms;

    /**
     * Reads POMs through {@code repos}, then through every repository a row of {@code lock} names as
     * its source that {@code repos} does not already hold, so a row an override repository served
     * is found where the lock says it came from.
     */
    public EdgeSelectors(RepoGroup repos, Lockfile lock) {
        this.poms = new EffectivePomBuilder(repos.withReposAppended(lockSources(repos, lock)));
    }

    /**
     * The version {@code parent}'s POM declares for its edge onto {@code child}'s package, as
     * written: a version, a Maven range, or {@code LATEST}/{@code RELEASE}. Null when the parent has
     * no Maven POM, declares no such edge, or its POM cannot be read.
     */
    public @Nullable String declared(Lockfile.Artifact parent, Lockfile.Artifact child) {
        if (!PackageId.isMavenPackageKey(parent.packageKey()) || !PackageId.isMavenPackageKey(child.packageKey())) {
            return null;
        }
        EffectivePom pom;
        try {
            pom = poms.build(PackageId.parse(parent.packageKey()).withVersion(parent.version()));
        } catch (Exception e) {
            Log.debug("jk why: no POM for " + parent.packageKey() + "@" + parent.version(), e);
            return null;
        }
        String childKey = PackageId.parse(child.packageKey()).key();
        String childGa = child.moduleGroup() + ":" + child.moduleArtifact();
        String byGa = null;
        for (Pom.Dep d : pom.dependencies()) {
            if (d.version() == null || d.version().isBlank()) continue;
            if (MavenPackageSource.packageKey(d).equals(childKey))
                return d.version().trim();
            if (byGa == null && d.module().equals(childGa)) byGa = d.version().trim();
        }
        return byGa;
    }

    /** One repository per lock source {@code repos} does not already serve; synthetic sources are skipped. */
    private static List<MavenRepo> lockSources(RepoGroup repos, Lockfile lock) {
        if (repos.repos().isEmpty()) return List.of();
        Set<String> known = new LinkedHashSet<>();
        for (MavenRepo r : repos.repos()) known.add(r.baseUrl().toString());
        MavenRepo template = repos.repos().getFirst();
        List<MavenRepo> extra = new ArrayList<>();
        for (Lockfile.Artifact row : lock.artifacts()) {
            RepoSource source = RepoSource.parse(row.source());
            String name = source.name();
            String url = source.url();
            if (name == null || !(url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file:"))) {
                continue;
            }
            if (!known.add(url)) continue;
            try {
                extra.add(template.declaredByPom(name, URI.create(url), true, true));
            } catch (IllegalArgumentException e) {
                Log.debug("jk why: lock source is not a URL: " + row.source(), e);
            }
        }
        return extra;
    }
}
