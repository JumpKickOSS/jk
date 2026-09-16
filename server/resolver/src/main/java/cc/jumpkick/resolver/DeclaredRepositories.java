// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.model.PackageId;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.repo.EffectivePom;
import cc.jumpkick.repo.EffectivePomBuilder;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.Pom;
import cc.jumpkick.repo.RepoGroup;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * The repositories dependency POMs declare, scoped the way Maven scopes them: a {@code
 * <repository>} in a POM (or its parents) is consulted for that POM's dependencies and theirs, after
 * every repository the project declares, and never for the project's own declarations or another
 * subtree. A package reached through such a POM is <em>granted</em> the repository; its versions,
 * POM and artifact are then asked of the project's group with the grant appended.
 *
 * <p>Trust is the project-declared rule with no way to opt out: a plaintext {@code http://}
 * repository is not used and the lock says so, and an artifact without a published checksum fails
 * the lock naming the repository, as it would for a {@code [repositories]} entry without {@code
 * allow-unverified}. Every repository that is used is named once in a lock note with the POM that
 * introduced it, and a row it serves records it in {@code source}.
 */
final class DeclaredRepositories {

    /** One group with the grant appended and the POM builder over it. */
    private record Scoped(RepoGroup repos, EffectivePomBuilder builder) {}

    private final RepoGroup base;
    private final EffectivePomBuilder baseBuilder;

    /** {@code group:artifact} → the repositories granted to it, in the order they were granted. */
    private final ConcurrentHashMap<String, Set<Pom.Repository>> granted = new ConcurrentHashMap<>();

    /** Grant sets → the group and builder serving them, keyed by their URLs joined. */
    private final ConcurrentHashMap<String, Scoped> scoped = new ConcurrentHashMap<>();

    /** URL → the repository built for it, or absent when its URL is refused. */
    private final ConcurrentHashMap<String, MavenRepo> built = new ConcurrentHashMap<>();

    private final Set<String> refusedUrls = ConcurrentHashMap.newKeySet();
    private final Set<String> notes = ConcurrentHashMap.newKeySet();

    DeclaredRepositories(RepoGroup base, EffectivePomBuilder baseBuilder) {
        this.base = base;
        this.baseBuilder = baseBuilder;
    }

    /**
     * Grant the repositories the POM of {@code pkg} makes available — those granted to {@code pkg}
     * itself plus the POM's own — to every package in {@code children}. Returns the {@code
     * group:artifact} keys whose grant grew, so the caller can drop what it cached for them.
     */
    Set<String> propagate(String pkg, EffectivePom pom, List<String> children) {
        Set<Pom.Repository> inherited = granted.getOrDefault(ga(pkg), Set.of());
        if (inherited.isEmpty() && pom.repositories().isEmpty()) return Set.of();
        LinkedHashSet<Pom.Repository> available = new LinkedHashSet<>(inherited);
        available.addAll(pom.repositories());
        Set<String> grew = new LinkedHashSet<>();
        for (String child : children) {
            String key = ga(child);
            Set<Pom.Repository> before = granted.get(key);
            Set<Pom.Repository> merged = granted.merge(key, Set.copyOf(available), (a, b) -> {
                LinkedHashSet<Pom.Repository> union = new LinkedHashSet<>(a);
                union.addAll(b);
                return Set.copyOf(union);
            });
            if (before == null || merged.size() != before.size()) grew.add(key);
        }
        if (!children.isEmpty()) {
            for (Pom.Repository repository : pom.repositories()) note(repository);
        }
        return grew;
    }

    /** The group a package's versions, POM and artifact are asked of. */
    RepoGroup reposFor(String pkg) {
        return scopedFor(pkg).repos();
    }

    /** The POM builder over {@link #reposFor}. */
    EffectivePomBuilder builderFor(String pkg) {
        return scopedFor(pkg).builder();
    }

    /** One line per repository used and per repository refused, sorted. */
    List<String> notes() {
        List<String> out = new ArrayList<>(notes);
        out.sort(null);
        return List.copyOf(out);
    }

    private Scoped scopedFor(String pkg) {
        Set<Pom.Repository> grant = granted.get(ga(pkg));
        if (grant == null || grant.isEmpty()) return new Scoped(base, baseBuilder);
        List<MavenRepo> extra = new ArrayList<>();
        for (Pom.Repository repository : grant) {
            MavenRepo repo = repoFor(repository);
            if (repo != null) extra.add(repo);
        }
        if (extra.isEmpty()) return new Scoped(base, baseBuilder);
        String key = extra.stream().map(r -> r.baseUrl().toString()).collect(Collectors.joining("\n"));
        return scoped.computeIfAbsent(key, k -> {
            RepoGroup group = base.withReposAppended(extra);
            return new Scoped(group, new EffectivePomBuilder(group));
        });
    }

    private @Nullable MavenRepo repoFor(Pom.Repository repository) {
        MavenRepo hit = built.get(repository.url());
        if (hit != null) return hit;
        if (refusedUrls.contains(repository.url())) return null;
        URI url;
        try {
            url = new URI(repository.url());
        } catch (URISyntaxException e) {
            refuse(repository, "its URL does not parse (" + e.getMessage() + ")");
            return null;
        }
        if ("http".equalsIgnoreCase(url.getScheme()) && !RepositorySpec.loopback(url.getHost())) {
            refuse(
                    repository,
                    "it is plaintext http, and a repository a POM declares has no table to say allow-insecure in;"
                            + " declare it under [repositories] to use it");
            return null;
        }
        if (url.getScheme() == null || url.getHost() == null && !"file".equalsIgnoreCase(url.getScheme())) {
            refuse(repository, "its URL names no host");
            return null;
        }
        MavenRepo repo = base.repos()
                .getFirst()
                .declaredByPom(repository.id(), url, repository.releases(), repository.snapshots());
        built.putIfAbsent(repository.url(), repo);
        return repo;
    }

    private void refuse(Pom.Repository repository, String why) {
        refusedUrls.add(repository.url());
        notes.add("repository `" + repository.id() + "` at " + repository.url() + ", declared by the POM of "
                + repository.declaredBy() + ", was not used: " + why);
    }

    /** Once per repository and declaring POM, however many packages inherit it. */
    private void note(Pom.Repository repository) {
        if (refusedUrls.contains(repository.url())) return;
        String policy = repository.releases() && repository.snapshots()
                ? ""
                : repository.snapshots() ? " for snapshots only" : " for releases only";
        notes.add("repository `" + repository.id() + "` at " + repository.url() + ", declared by the POM of "
                + repository.declaredBy() + ", is consulted" + policy + " for that POM's dependencies and theirs"
                + " after the project's repositories; a row it serves records it as `source`, and it is held to"
                + " the same trust rule as a declared repository (https, published checksums)");
    }

    private static String ga(String pkg) {
        return PackageId.isMavenPackageKey(pkg) ? PackageId.parse(pkg).ga() : pkg;
    }

    /** Package-visible for tests: the repositories granted to {@code pkg}. */
    Map<String, Set<Pom.Repository>> grants() {
        return Map.copyOf(granted);
    }
}
