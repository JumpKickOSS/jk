// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.PackageId;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Maven-backed PubGrub {@link PackageSource}. Caches versions/deps per solve; prefetches transitive
 * metadata on {@link JkThreads#io()} when no soft-prefer pin is known. BOM/lock soft-prefer
 * front-loads candidates (and seeds lazy singleton universes via {@link #preferredVersion}); POM
 * exclusions strip modules when expanding a package.
 */
public final class MavenPackageSource implements PackageSource {

    private static final Set<String> FOLLOWED_SCOPES = Set.of("compile", "runtime");

    /** Max concurrent speculative prefetches. Tuned to stay polite to Maven Central. */
    private static final int PREFETCH_PERMITS = 8;

    private final RepoGroup repos;
    private final EffectivePomBuilder pomBuilder;
    private final Map<String, String> bomConstraints;
    private final KmpRedirects kmp;

    /** Locked versions from a prior lock file — preferred but NOT hard-pinned. Mutable so one shared source can update prefs across main/test/processor solves. */
    private volatile Map<String, String> lockedVersionPrefs;

    private final Map<String, List<String>> versionCache = new ConcurrentHashMap<>();
    /**
     * Raw POM edge cache keyed by {@code pkg@version} only (JK-1202). Exclusion filtering is applied
     * per-call so backtracking does not re-parse EffectivePoms under shifting exclusion keys.
     */
    private final Map<String, List<RawEdge>> rawDepsCache = new ConcurrentHashMap<>();

    /** One compile/runtime edge before inherited-exclusion filtering. */
    private record RawEdge(String depPkg, VersionSet constraint, Set<String> edgeExclusions) {}

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

    /** Refresh soft-prefer lock pins for a subsequent scope solve (does not clear version/deps caches). */
    public void setLockedVersionPrefs(Map<String, String> prefs) {
        this.lockedVersionPrefs = Map.copyOf(Objects.requireNonNull(prefs, "prefs"));
    }

    /**
     * Lock pin wins over BOM pin (same order as {@link #versions} soft-prefer). Used by the solver to
     * seed a lazy singleton universe without maven-metadata (JK-1088).
     */
    @Override
    public Optional<String> preferredVersion(String pkg) {
        String ga = PackageId.parse(pkg).ga();
        String lock = firstNonBlank(lockedVersionPrefs.get(pkg), lockedVersionPrefs.get(ga));
        if (lock != null) return Optional.of(lock);
        String bom = firstNonBlank(bomConstraints.get(ga), bomConstraints.get(pkg));
        if (bom != null) return Optional.of(bom);
        return Optional.empty();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    @Override
    public List<String> versions(String pkg) throws IOException, InterruptedException {
        List<String> cached = versionCache.get(pkg);
        if (cached != null) return cached;

        List<String> available = repos.availableVersions(withVersion(pkg, "any"));
        List<String> sorted = new ArrayList<>(available);
        sorted.sort((a, b) -> Versions.compare(b, a));

        // BOM + lock soft-prefer are GA-scoped (one pin applies to every classifier of the GA).
        String ga = PackageId.parse(pkg).ga();
        preferBom(sorted, bomConstraints.get(ga));
        preferBom(sorted, bomConstraints.get(pkg));
        preferFirst(sorted, lockedVersionPrefs.get(pkg));
        preferFirst(sorted, lockedVersionPrefs.get(ga));

        // JK-1202: highest-wins only needs the soft-prefer pin (if any) + a few highest releases.
        // Full maven-metadata histories (80+ versions) made PubGrub thrash on Quarkus test graphs.
        sorted = compactVersionCandidates(sorted);

        List<String> result = List.copyOf(sorted);
        versionCache.put(pkg, result);
        return result;
    }

    /** Cap candidate list while preserving soft-prefer front and highest releases. */
    static List<String> compactVersionCandidates(List<String> sortedHighestFirst) {
        if (sortedHighestFirst.size() <= 4) return sortedHighestFirst;
        List<String> out = new ArrayList<>(4);
        // Keep order: soft-prefer may already be at index 0.
        for (String v : sortedHighestFirst) {
            if (out.contains(v)) continue;
            out.add(v);
            if (out.size() == 4) break;
        }
        return out;
    }

    /**
     * BOM soft-prefer: move {@code pin} to front when it is already in the metadata list. Does
     * <em>not</em> invent a missing pin (JK-1202): inserting unreleased/stale pins that sit below
     * transitive floors made PubGrub thrash on Quarkus-sized graphs.
     */
    static void preferBom(List<String> versions, String pin) {
        if (pin == null || pin.isBlank()) return;
        if (versions.isEmpty()) {
            // Empty metadata: keep pin as the only candidate (versionless platform roots).
            versions.add(pin);
            return;
        }
        if (versions.remove(pin)) {
            versions.add(0, pin);
        }
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
        List<RawEdge> raw = rawEdges(pkg, version);
        List<Term> out = new ArrayList<>(raw.size());
        for (RawEdge edge : raw) {
            if (isExcluded(edge.depPkg(), excl)) continue;
            // Cascade parent exclusions + edge exclusions onto the child for later expansion.
            if (!excl.isEmpty() || !edge.edgeExclusions().isEmpty()) {
                Set<String> merged = new LinkedHashSet<>(excl);
                merged.addAll(edge.edgeExclusions());
                registerExclusions(edge.depPkg(), merged);
            }
            out.add(Term.positive(edge.depPkg(), edge.constraint()));
        }
        List<Term> immutable = List.copyOf(out);
        prefetchTransitiveAsync(immutable);
        return immutable;
    }

    /** POM edges for {@code pkg@version}, cached without inherited exclusions (JK-1202). */
    private List<RawEdge> rawEdges(String pkg, String version) throws IOException, InterruptedException {
        String key = pkg + "@" + version;
        List<RawEdge> hit = rawDepsCache.get(key);
        if (hit != null) return hit;

        Coordinate coord = withVersion(pkg, version);
        EffectivePom pom;
        try {
            pom = pomBuilder.build(coord);
        } catch (MavenRepo.ArtifactNotFoundException e) {
            throw new VersionUnavailableException(e.getMessage());
        }
        List<RawEdge> out = new ArrayList<>();
        var kmpSelection = kmp.selectionFor(pkg, version);
        Set<String> kmpDropped = Set.of();
        if (kmpSelection.isPresent()) {
            var target = kmpSelection.get().target();
            String targetPkg =
                    PackageId.ofGa(target.group() + ":" + target.module()).key();
            out.add(new RawEdge(targetPkg, VersionSet.exact(target.version()), Set.of()));
            kmpDropped = kmpSelection.get().allTargets();
        }
        for (Pom.Dep dep : pom.dependencies()) {
            if (dep.optional()) continue;
            if (kmpDropped.contains(dep.module())) continue;
            String scope = dep.scope();
            if (scope != null && !scope.isEmpty() && !FOLLOWED_SCOPES.contains(scope)) continue;
            if (dep.version() == null || dep.version().isBlank()) continue;
            String depPkg = packageKey(dep);
            Set<String> edgeExcl = modulesOf(dep.exclusions());
            out.add(new RawEdge(depPkg, constraintForManagedEdge(depPkg, dep.version()), edgeExcl));
        }
        List<RawEdge> immutable = List.copyOf(out);
        rawDepsCache.put(key, immutable);
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

    /**
     * Whether {@code packageKey} is covered by any exclusion entry. Exclusions are GA-scoped
     * ({@code group:artifact} / wildcards); type/classifier do not escape an exclusion.
     */
    static boolean isExcluded(String packageKey, Set<String> exclusions) {
        if (exclusions == null || exclusions.isEmpty()) return false;
        String ga = PackageId.isMavenPackageKey(packageKey)
                ? PackageId.parse(packageKey).ga()
                : packageKey;
        if (exclusions.contains(ga) || exclusions.contains(packageKey)) return true;
        // Wildcard forms stored as "group:*", "*:artifact", "*:*"
        int colon = ga.indexOf(':');
        if (colon < 0) return exclusions.contains("*:*");
        String g = ga.substring(0, colon);
        String a = ga.substring(colon + 1);
        return exclusions.contains(g + ":*") || exclusions.contains("*:" + a) || exclusions.contains("*:*");
    }

    /** Solver package key for a POM dependency ({@code g:a:type:classifier}). */
    static String packageKey(Pom.Dep dep) {
        String type = dep.type() == null || dep.type().isBlank() ? PackageId.DEFAULT_TYPE : dep.type();
        String classifier = dep.classifier() == null ? "" : dep.classifier();
        return PackageId.of(dep.groupId(), dep.artifactId(), type, classifier).key();
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

    /**
     * PubGrub constraint for one POM edge (JK-1202).
     *
     * <p>Without a platform BOM, bare POM versions stay highest-wins ({@code atLeast}) — the
     * historical jk rule. <b>With</b> a platform BOM map present, bare versions become
     * {@code exact}: EffectivePom already applied dependencyManagement, so the string is a filled
     * Maven pin, not a floor. Open floors + soft-prefer pins <em>below</em> those floors (common on
     * Quarkus/Maven-resolver stacks) caused multi-minute PubGrub thrash. Explicit Maven ranges still
     * pass through. When the BOM pin is compatible with the declared version's atLeast floor, the
     * BOM pin wins (platform alignment).
     */
    VersionSet constraintForManagedEdge(String depPkg, String version) {
        String trimmed = version.trim();
        if (VersionSelectors.looksLikeMavenRange(trimmed)) {
            return VersionSelectors.constraintFromPomVersion(trimmed);
        }
        PackageId id = PackageId.parse(depPkg);
        // Classified artifacts (guice:jar:classes): GA maven-metadata highest-wins picks versions
        // that often have no classifier POM → Unavailable thrash (JK-1202).
        if (!id.classifier().isEmpty()) {
            return VersionSet.exact(preferredVersion(depPkg).orElse(trimmed));
        }
        // No platform/lock map: historical highest-wins bare versions.
        if (bomConstraints.isEmpty() && lockedVersionPrefs.isEmpty()) {
            return VersionSelectors.constraintFromPomVersion(trimmed);
        }
        VersionSet asFloor = VersionSelectors.constraintFromPomVersion(trimmed);
        Optional<String> prefer = preferredVersion(depPkg);
        if (prefer.isPresent()) {
            // Platform/lock map entry for this GA: pin is law (enforcedPlatform for the managed set).
            return VersionSet.exact(prefer.get());
        }
        // JK-1202: do not highest-wins-lift io.quarkus.* past the declared BOM line.
        // Lifting bootstrap-maven4-resolver 3.28.5 → 3.38.x caused multi-minute thrash.
        // General multi-group platform-line policy: JK-1205.
        String ga = id.ga();
        if (ga.startsWith("io.quarkus:") || ga.startsWith("io.quarkus.")) {
            return VersionSet.exact(trimmed);
        }
        // Unmanaged plain jars: highest-wins floor.
        return asFloor;
    }

    /**
     * Speculative I/O for children of a just-expanded package (JK-1088):
     *
     * <ul>
     *   <li>When a child has an exact or soft-prefer pin, prefetch that GAV's <b>POM</b> (and let
     *       {@link EffectivePomBuilder} warm its cache) so the next decision hits local-first.
     *   <li>When the child needs a full version list (open range, no prefer), prefetch
     *       maven-metadata as before.
     * </ul>
     *
     * <p>JK-1202: only prefetch the first few children (breadth limit) so large Quarkus-style
     * fan-outs do not stampede parallel BOM expansions under a 256 MiB engine cap.
     */
    private void prefetchTransitiveAsync(List<Term> deps) {
        // JK-1202: skip speculative prefetch when a large platform BOM is in play — parallel
        // EffectivePom expansions of quarkus-bom parents dominated CPU/heap without helping the
        // exact-pin happy path. Small graphs still warm a few children.
        if (bomConstraints.size() > 200) return;
        int budget = 4;
        for (Term dep : deps) {
            if (budget <= 0) return;
            budget--;
            String pkg = dep.pkg();
            String pin = dep.versions()
                    .asExactSingleton()
                    .or(() -> preferredVersion(pkg))
                    .orElse(null);
            if (pin != null) {
                Coordinate child = withVersion(pkg, pin);
                JkThreads.io().execute(() -> {
                    try {
                        prefetchSlots.acquire();
                        try {
                            // Full effective POM (parents + BOM imports). Builder is concurrent-safe
                            // (JK-1090) so sibling prefetches walk chains in parallel.
                            pomBuilder.build(child);
                        } finally {
                            prefetchSlots.release();
                        }
                    } catch (Exception ignored) {
                        // best-effort; sync path surfaces real failures
                    }
                });
                continue;
            }
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
        return PackageId.parse(pkg).withVersion(version);
    }
}
