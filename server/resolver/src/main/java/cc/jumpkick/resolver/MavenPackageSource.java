// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.model.Coordinate;
import cc.jumpkick.repo.EffectivePom;
import cc.jumpkick.repo.EffectivePomBuilder;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.Pom;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.resolver.pubgrub.PackageSource;
import cc.jumpkick.resolver.pubgrub.Term;
import cc.jumpkick.resolver.pubgrub.VersionSet;
import cc.jumpkick.run.JkThreads;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Maven-backed PubGrub {@link PackageSource}. Caches versions/deps per solve; prefetches transitive
 * metadata on {@link JkThreads#io()}. BOM/lock soft-prefer front-loads candidates; POM exclusions
 * strip modules when expanding a package.
 */
public final class MavenPackageSource implements PackageSource {

    private static final Set<String> FOLLOWED_SCOPES = Set.of("compile", "runtime");

    /** Max concurrent speculative prefetches. Tuned to stay polite to Maven Central. */
    private static final int PREFETCH_PERMITS = 8;

    private final RepoGroup repos;
    private final EffectivePomBuilder pomBuilder;
    private final Map<String, String> bomConstraints;
    private final KmpRedirects kmp;

    /** Locked versions from a prior lock file — preferred but NOT hard-pinned. */
    private final Map<String, String> lockedVersionPrefs;

    private final Map<String, List<String>> versionCache = new ConcurrentHashMap<>();
    /**
     * Dep-list cache keyed by {@code pkg@version!exclusionKey} so a package expanded under different
     * inherited exclusion sets is not served a stale list.
     */
    private final Map<String, List<Term>> depsCache = new ConcurrentHashMap<>();

    /**
     * Modules to strip when expanding a package (union of exclusions registered by parents, cascaded
     * down the subtree). Keyed by package module id.
     */
    private final ConcurrentHashMap<String, Set<String>> exclusionsWhenExpanding = new ConcurrentHashMap<>();

    private final Semaphore prefetchSlots = new Semaphore(PREFETCH_PERMITS);

    public MavenPackageSource(MavenRepo repo, EffectivePomBuilder pomBuilder) {
        this(RepoGroup.of(repo), pomBuilder, Map.of());
    }

    public MavenPackageSource(RepoGroup repos, EffectivePomBuilder pomBuilder) {
        this(repos, pomBuilder, Map.of());
    }

    public MavenPackageSource(RepoGroup repos, EffectivePomBuilder pomBuilder, Map<String, String> bomConstraints) {
        this(repos, pomBuilder, bomConstraints, Map.of());
    }

    /**
     * Soft-prefer variant: {@code bomConstraints} and {@code lockedVersionPrefs} move their
     * preferred versions to the front of each package's candidate list (full metadata list still
     * available). PubGrub selects the prefer first; if a constraint rules it out, it backtracks to
     * the next candidate. When both apply, the lock preference is applied last so it sits at the
     * front (conservative re-lock beats the BOM recommendation).
     */
    public MavenPackageSource(
            RepoGroup repos,
            EffectivePomBuilder pomBuilder,
            Map<String, String> bomConstraints,
            Map<String, String> lockedVersionPrefs) {
        this(repos, pomBuilder, bomConstraints, lockedVersionPrefs, KmpRedirects.NONE);
    }

    /** As above with KMP root-module redirect resolution (see {@link KmpRedirects}). */
    public MavenPackageSource(
            RepoGroup repos,
            EffectivePomBuilder pomBuilder,
            Map<String, String> bomConstraints,
            Map<String, String> lockedVersionPrefs,
            KmpRedirects kmp) {
        this.repos = Objects.requireNonNull(repos, "repos");
        this.pomBuilder = Objects.requireNonNull(pomBuilder, "pomBuilder");
        this.bomConstraints = Map.copyOf(Objects.requireNonNull(bomConstraints, "bomConstraints"));
        this.lockedVersionPrefs = Map.copyOf(Objects.requireNonNull(lockedVersionPrefs, "lockedVersionPrefs"));
        this.kmp = Objects.requireNonNull(kmp, "kmp");
    }

    @Override
    public List<String> versions(String pkg) throws IOException, InterruptedException {
        List<String> cached = versionCache.get(pkg);
        if (cached != null) return cached;
        List<String> available = repos.availableVersions(withVersion(pkg, "any"));
        List<String> sorted = new ArrayList<>(available);
        sorted.sort((a, b) -> Versions.compare(b, a));

        preferBom(sorted, bomConstraints.get(pkg));
        preferFirst(sorted, lockedVersionPrefs.get(pkg));

        List<String> result = List.copyOf(sorted);
        versionCache.put(pkg, result);
        return result;
    }

    /**
     * BOM soft-prefer: move {@code pin} to front, inserting it if metadata does not list it
     * (platform-managed versionless deps often have no maven-metadata hit before first fetch).
     */
    static void preferBom(List<String> versions, String pin) {
        if (pin == null || pin.isBlank()) return;
        versions.remove(pin);
        versions.add(0, pin);
    }

    /**
     * Move {@code preferred} to index 0 when it is already in {@code versions}. No-op when {@code
     * preferred} is null or absent (lock prefs never invent versions).
     */
    static void preferFirst(List<String> versions, String preferred) {
        if (preferred == null || preferred.isBlank()) return;
        if (versions.isEmpty()) return;
        if (versions.getFirst().equals(preferred)) return;
        if (versions.remove(preferred)) {
            versions.add(0, preferred);
        }
    }

    @Override
    public List<Term> dependencies(String pkg, String version) throws IOException, InterruptedException {
        Set<String> excl = exclusionsWhenExpanding.getOrDefault(pkg, Set.of());
        String key = pkg + "@" + version + "!" + exclusionCacheKey(excl);
        List<Term> cached = depsCache.get(key);
        if (cached != null) return cached;

        Coordinate coord = withVersion(pkg, version);
        EffectivePom pom;
        try {
            pom = pomBuilder.build(coord);
        } catch (MavenRepo.ArtifactNotFoundException e) {
            throw new VersionUnavailableException(e.getMessage());
        }
        List<Term> out = new ArrayList<>();
        var kmpSelection = kmp.selectionFor(pkg, version);
        Set<String> kmpDropped = Set.of();
        if (kmpSelection.isPresent()) {
            var target = kmpSelection.get().target();
            String targetModule = target.group() + ":" + target.module();
            if (!isExcluded(targetModule, excl)) {
                out.add(Term.positive(targetModule, VersionSet.exact(target.version())));
                // Cascade parent exclusions onto the redirect target.
                registerExclusions(targetModule, excl);
            }
            kmpDropped = kmpSelection.get().allTargets();
        }
        for (Pom.Dep dep : pom.dependencies()) {
            if (dep.optional()) continue;
            if (kmpDropped.contains(dep.module())) continue;
            String scope = dep.scope();
            if (scope != null && !scope.isEmpty() && !FOLLOWED_SCOPES.contains(scope)) continue;
            if (dep.version() == null || dep.version().isBlank()) continue;
            if (isExcluded(dep.module(), excl)) continue;

            // Register this edge's exclusions for when the child is expanded, and cascade
            // exclusions inherited from our own parents (Maven subtree exclusion).
            Set<String> childExcl = modulesOf(dep.exclusions());
            if (!excl.isEmpty() || !childExcl.isEmpty()) {
                Set<String> merged = new LinkedHashSet<>(excl);
                merged.addAll(childExcl);
                registerExclusions(dep.module(), merged);
            }

            out.add(Term.positive(dep.module(), VersionSelectors.constraintFromPomVersion(dep.version())));
        }
        List<Term> immutable = List.copyOf(out);
        depsCache.put(key, immutable);
        prefetchVersionsAsync(immutable);
        return immutable;
    }

    /**
     * Union {@code extra} into the exclusion set applied when {@code pkg} is expanded. Package-visible
     * for tests.
     */
    void registerExclusions(String pkg, Set<String> extra) {
        if (extra == null || extra.isEmpty()) return;
        exclusionsWhenExpanding.merge(pkg, Set.copyOf(extra), (a, b) -> {
            Set<String> u = new LinkedHashSet<>(a);
            u.addAll(b);
            return Set.copyOf(u);
        });
    }

    /** Whether {@code module} ({@code group:artifact}) is covered by any exclusion entry. */
    static boolean isExcluded(String module, Set<String> exclusions) {
        if (exclusions == null || exclusions.isEmpty()) return false;
        if (exclusions.contains(module)) return true;
        // Wildcard forms stored as "group:*", "*:artifact", "*:*"
        int colon = module.indexOf(':');
        if (colon < 0) return exclusions.contains("*:*");
        String g = module.substring(0, colon);
        String a = module.substring(colon + 1);
        return exclusions.contains(g + ":*") || exclusions.contains("*:" + a) || exclusions.contains("*:*");
    }

    static Set<String> modulesOf(List<Pom.Dep.Exclusion> exclusions) {
        if (exclusions == null || exclusions.isEmpty()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (Pom.Dep.Exclusion e : exclusions) {
            String g = e.groupId() == null || e.groupId().isBlank() ? "*" : e.groupId();
            String a = e.artifactId() == null || e.artifactId().isBlank() ? "*" : e.artifactId();
            out.add(g + ":" + a);
        }
        return out;
    }

    private static String exclusionCacheKey(Set<String> excl) {
        if (excl == null || excl.isEmpty()) return "";
        return String.join(",", excl.stream().sorted().toList());
    }

    private void prefetchVersionsAsync(List<Term> deps) {
        for (Term dep : deps) {
            String pkg = dep.pkg();
            if (versionCache.containsKey(pkg)) continue;
            JkThreads.io().execute(() -> {
                try {
                    prefetchSlots.acquire();
                    try {
                        versions(pkg);
                    } finally {
                        prefetchSlots.release();
                    }
                } catch (Exception ignored) {
                    // best-effort prefetch; surface errors via the sync path
                }
            });
        }
    }

    private static Coordinate withVersion(String pkg, String version) {
        return Coordinate.ofModule(pkg, version);
    }
}
