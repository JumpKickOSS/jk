// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.host.Log;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.PackageId;
import cc.jumpkick.model.PlatformPolicy;
import cc.jumpkick.model.UnmappedPolicy;
import cc.jumpkick.repo.EffectivePom;
import cc.jumpkick.repo.EffectivePomBuilder;
import cc.jumpkick.repo.GradleModuleMetadata;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.Pom;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.resolve.ResolveProfile;
import cc.jumpkick.resolver.pubgrub.PackageSource;
import cc.jumpkick.resolver.pubgrub.Term;
import cc.jumpkick.resolver.pubgrub.VersionSet;
import cc.jumpkick.run.JkThreads;
import cc.jumpkick.version.Versions;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/**
 * Maven-backed PubGrub {@link PackageSource}. Caches versions/deps per solve; prefetches transitive
 * metadata on {@link JkThreads#io} when no preferred pin is known. Platform policy (default
 * {@link PlatformPolicy#ENFORCED}) controls BOM-map pins; without a BOM map, bare edges stay
 * highest-wins floors. BOM/lock prefs seed lazy singleton universes via {@link #preferredVersion}.
 * POM exclusions strip modules when expanding a package.
 */
public final class MavenPackageSource implements PackageSource {

    private static final Set<String> FOLLOWED_SCOPES = Set.of("compile", "runtime");

    /**
     * Concurrent warm of POMs / KMP metadata (pre-solve BOM blast + frontier prefetch). Disk-bound
     * on a warm CAS; virtual threads + local store tolerate higher fan-out than network-polite
     * Maven Central.
     */
    private static final int PREFETCH_PERMITS = 32;

    /** Upper bound on how long a solve waits for speculative prefetches to wind down. */
    private static final long QUIESCE_TIMEOUT_MS = 30_000;

    private final RepoGroup repos;
    private final EffectivePomBuilder pomBuilder;
    private final Map<String, String> bomConstraints;
    private final PlatformPolicy platformPolicy;
    private final UnmappedPolicy unmappedPolicy;
    private final KmpRedirects kmp;

    /** Locked versions from a prior lock file — preferred but NOT hard-pinned. Mutable so one shared source can update prefs across main/test/processor solves. */
    private volatile Map<String, String> lockedVersionPrefs;

    /**
     * GA keys the manifest asked for with the {@code snapshot} selector — the one opt-in that wants
     * pre-releases. Mutable for the same reason as {@link #lockedVersionPrefs}.
     */
    private volatile Set<String> snapshotPackages = Set.of();

    /**
     * {@code group:artifact} → the exact version the project declares for it. Together with {@link
     * #declaredVersions} these are the versions a repository walk must find before it stops, the way
     * Maven asks every repository for an exact version rather than the first catalog that answers.
     */
    private volatile Map<String, String> exactRoots = Map.of();

    private final Map<String, List<String>> versionCache = new ConcurrentHashMap<>();
    private final Map<String, List<String>> expandedVersionCache = new ConcurrentHashMap<>();
    /** pkg → the wanted set its cached candidate lists were computed for; see {@link #dropStaleCandidates}. */
    private final Map<String, Set<String>> wantedAtCache = new ConcurrentHashMap<>();
    /**
     * Raw POM edge cache keyed by {@code pkg@version} only. Exclusion filtering is applied
     * per-call so backtracking does not re-parse EffectivePoms under shifting exclusion keys.
     */
    private final Map<String, List<RawEdge>> rawDepsCache = new ConcurrentHashMap<>();

    /**
     * One compile/runtime edge before inherited-exclusion filtering. {@code declaredVersion} is the
     * plain version the POM wrote, or {@code null} when it wrote a range or the edge is synthetic.
     * A {@code constraintOnly} edge is a Gradle metadata {@code dependencyConstraints} entry: it
     * bounds {@code depPkg} when something else brings it in and is silent otherwise.
     */
    private record RawEdge(
            String depPkg,
            VersionSet constraint,
            Set<String> edgeExclusions,
            @Nullable String declaredVersion,
            boolean constraintOnly) {

        RawEdge(String depPkg, VersionSet constraint, Set<String> edgeExclusions, @Nullable String declaredVersion) {
            this(depPkg, constraint, edgeExclusions, declaredVersion, false);
        }
    }

    /** Package key → plain versions edges expanded in this solve have declared for it. */
    private final ConcurrentHashMap<String, Set<String>> declaredVersions = new ConcurrentHashMap<>();

    /** The graph's exact roots when they override transitive constraints (see {@link NearestPins}). */
    private final NearestPins nearestPins = new NearestPins();

    /** One sentence per edge whose classifier reads the running host; see {@link #hostClassifierNotes}. */
    private final Set<String> hostClassifierNotes = ConcurrentHashMap.newKeySet();

    /** The repositories dependency POMs declare, granted per subtree; see {@link DeclaredRepositories}. */
    private final DeclaredRepositories declared;

    /**
     * Modules to strip when expanding a package, keyed by package module id.
     *
     * <p>Maven drops a dependency only when <em>every</em> path reaching it excludes that
     * dependency, so entries here are the <strong>intersection</strong> of the sets registered by
     * parents, not their union. Two parents, one excluding and one not, leave the child expanding
     * with nothing stripped.
     *
     * <p>Intersection only shrinks, so registrations may arrive in any order and still converge on
     * the same set.
     */
    private final ConcurrentHashMap<String, Set<String>> exclusionsWhenExpanding = new ConcurrentHashMap<>();
    /** pkg@version → edges its last expansion filtered; see {@link #anyExpansionStale}. */
    private final ConcurrentHashMap<String, Set<String>> filteredAtExpansion = new ConcurrentHashMap<>();

    private final Semaphore prefetchSlots = new Semaphore(PREFETCH_PERMITS);

    /** Speculative prefetches submitted and not yet finished. Guards {@link #quiesce}. */
    private final AtomicInteger outstandingPrefetches = new AtomicInteger();

    private final Object prefetchIdle = new Object();

    /** Ensures {@link #warmUp} runs once per source instance (main/test/processor share one source). */
    private final AtomicBoolean warmedUp = new AtomicBoolean();

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
     * front (a locked pin beats the BOM recommendation).
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
        this(repos, pomBuilder, bomConstraints, lockedVersionPrefs, kmp, PlatformPolicy.ENFORCED);
    }

    /** As above with {@link PlatformPolicy}; unmapped fills default to MEDIATE. */
    public MavenPackageSource(
            RepoGroup repos,
            EffectivePomBuilder pomBuilder,
            Map<String, String> bomConstraints,
            Map<String, String> lockedVersionPrefs,
            KmpRedirects kmp,
            PlatformPolicy platformPolicy) {
        this(repos, pomBuilder, bomConstraints, lockedVersionPrefs, kmp, platformPolicy, null);
    }

    /** Full constructor with both platform policies. */
    public MavenPackageSource(
            RepoGroup repos,
            EffectivePomBuilder pomBuilder,
            Map<String, String> bomConstraints,
            Map<String, String> lockedVersionPrefs,
            KmpRedirects kmp,
            PlatformPolicy platformPolicy,
            @Nullable UnmappedPolicy unmappedPolicy) {
        this.repos = Objects.requireNonNull(repos, "repos");
        this.pomBuilder = Objects.requireNonNull(pomBuilder, "pomBuilder");
        this.bomConstraints = Map.copyOf(Objects.requireNonNull(bomConstraints, "bomConstraints"));
        this.lockedVersionPrefs = Map.copyOf(Objects.requireNonNull(lockedVersionPrefs, "lockedVersionPrefs"));
        this.kmp = Objects.requireNonNull(kmp, "kmp");
        this.platformPolicy = platformPolicy == null ? PlatformPolicy.ENFORCED : platformPolicy;
        this.unmappedPolicy = unmappedPolicy == null ? UnmappedPolicy.MEDIATE : unmappedPolicy;
        this.declared = new DeclaredRepositories(this.repos, this.pomBuilder);
    }

    /**
     * The group {@code pkg}'s artifact is fetched from: the project's repositories, followed by any
     * a dependency POM declared for the subtree {@code pkg} was reached through.
     */
    public RepoGroup reposFor(String pkg) {
        return declared.reposFor(pkg);
    }

    /** The POM builder over {@link #reposFor}. */
    public EffectivePomBuilder pomBuilderFor(String pkg) {
        return declared.builderFor(pkg);
    }

    /**
     * One sentence per repository a dependency POM declared and this solve consulted, naming the
     * repository, its URL and the POM that introduced it, and one per repository refused.
     */
    public List<String> declaredRepositoryNotes() {
        return declared.notes();
    }

    public PlatformPolicy platformPolicy() {
        return platformPolicy;
    }

    /** Refresh soft-prefer lock pins for a subsequent scope solve (does not clear version/deps caches). */
    public void setLockedVersionPrefs(Map<String, String> prefs) {
        this.lockedVersionPrefs = Map.copyOf(Objects.requireNonNull(prefs, "prefs"));
    }

    /**
     * Declare which packages were requested with {@code snapshot}, keyed by {@code group:artifact}.
     *
     * <p>Every other floating selector resolves to stable releases only, which is what the compact
     * candidate window enforces. {@code snapshot} is the sanctioned way out, so those packages skip
     * that narrowing and take the newest advertised version, pre-release or not.
     */
    public void setSnapshotPackages(Set<String> gaKeys) {
        this.snapshotPackages = Set.copyOf(Objects.requireNonNull(gaKeys, "gaKeys"));
        // The compact window differs for snapshot packages, so a list cached under the previous
        // policy would be stale.
        versionCache.clear();
    }

    /**
     * Reset per-solve expansion state before another scope solve on this shared source. Exclusion
     * registrations (and the filtered-at-expansion records that drive the stale-expansion
     * fixpoint) are facts about the <em>current</em> graph's paths: with intersection semantics a
     * clean main-scope path collapses a child's exclusion set to empty, and carrying that into the
     * test/processor solve would over-include modules that every path in the new graph excludes.
     * Version and raw-POM-edge caches survive — raw edges are cached before exclusion filtering,
     * so they are graph-independent.
     */
    public void resetSolveScopedState() {
        exclusionsWhenExpanding.clear();
        filteredAtExpansion.clear();
        declaredVersions.clear();
    }

    /** The exact roots of the graph about to be solved, or empty when pins are plain constraints. */
    public void setNearestPins(Map<String, String> gaToVersion) {
        nearestPins.set(gaToVersion);
    }

    /** Every root the project declares with an exact pin, {@code group:artifact → version}. */
    public void setExactRoots(Map<String, String> gaToVersion) {
        this.exactRoots = Map.copyOf(Objects.requireNonNull(gaToVersion, "gaToVersion"));
    }

    /**
     * The versions of {@code pkg} something has asked for by name: the project's exact pin and every
     * plain version a POM edge wrote. A repository walk continues past the first catalog until
     * it has seen them all.
     */
    private Set<String> wantedVersions(String pkg) {
        LinkedHashSet<String> out = new LinkedHashSet<>(declaredVersions(pkg));
        String root = firstNonBlank(
                exactRoots.get(pkg), exactRoots.get(PackageId.parse(pkg).ga()));
        if (root != null) out.add(root);
        return out;
    }

    /**
     * Forget the candidate lists of {@code pkg} when a version was asked for after they were
     * computed: a later edge naming a version the first repository never listed is exactly what
     * must send the walk on to the next one.
     */
    private void dropStaleCandidates(String pkg, Set<String> wanted) {
        Set<String> known = wantedAtCache.get(pkg);
        if (known != null && !known.containsAll(wanted)) {
            versionCache.remove(pkg);
            expandedVersionCache.remove(pkg);
        }
    }

    @Override
    public List<String> refusalNotes(String pkg) {
        List<String> out = new ArrayList<>();
        RepoGroup group = declared.reposFor(pkg);
        for (String wanted : wantedVersions(pkg)) {
            if (!Versions.isSnapshot(wanted)) continue;
            List<MavenRepo> asked = group.repositoriesFor(withVersion(pkg, wanted));
            List<String> serving = new ArrayList<>();
            List<String> refusing = new ArrayList<>();
            for (MavenRepo repo : asked) {
                (repo.servesSnapshots() ? serving : refusing).add(repo.name() + " (" + repo.policyLabel() + ")");
            }
            String display = PackageId.parse(pkg).display();
            if (serving.isEmpty()) {
                out.add(wanted + " is a snapshot, and no repository " + display + " may resolve from serves"
                        + " snapshots: " + String.join(", ", refusing) + ". Declare one under [repositories]"
                        + " (snapshots are on there unless it says snapshots = false), or let the POM that names"
                        + " it declare a <repository> with <snapshots><enabled>true</enabled>");
            } else {
                out.add(wanted + " is a snapshot; the repositories serving snapshots for " + display + " do not"
                        + " list it: " + String.join(", ", serving));
            }
        }
        return List.copyOf(out);
    }

    /** Every transitive constraint a nearest pin overrode so far, one rendered line each, sorted. */
    public List<String> nearestOverrides() {
        return nearestPins.renderedOverrides();
    }

    /**
     * Every expanded edge whose classifier a POM spells with a host property ({@code
     * ${javafx.platform}}, {@code ${os.detected.classifier}}), one sentence each, sorted: the lock
     * pins this machine's artifact, and a lock made elsewhere pins that machine's.
     */
    public List<String> hostClassifierNotes() {
        List<String> out = new ArrayList<>(hostClassifierNotes);
        out.sort(null);
        return List.copyOf(out);
    }

    private VersionSet nearestOrOwn(String parentPkg, String parentVersion, RawEdge edge) {
        return nearestPins.constraintFor(
                parentPkg, parentVersion, edge.depPkg(), edge.constraint(), edge.declaredVersion());
    }

    @Override
    public Set<String> declaredVersions(String pkg) {
        Set<String> declared = declaredVersions.get(pkg);
        return declared == null ? Set.of() : Set.copyOf(declared);
    }

    /** True when {@code pkg} was requested with the {@code snapshot} selector. */
    private boolean isSnapshotPackage(String pkg) {
        if (snapshotPackages.isEmpty()) return false;
        return snapshotPackages.contains(pkg)
                || snapshotPackages.contains(PackageId.parse(pkg).ga());
    }

    /**
     * Lock pin wins over BOM pin (same order as {@link #versions} soft-prefer). Used by the solver to
     * seed a lazy singleton universe without maven-metadata.
     */
    @Override
    public Optional<String> preferredVersion(String pkg) {
        String ga = PackageId.parse(pkg).ga();
        if (isSnapshotPackage(pkg)) {
            // `snapshot` means "the newest thing published", so it outranks a lock pin — a re-lock is
            // precisely when it should move. Seeding a singleton universe here also keeps
            // AllowedSet#choosePreferred's stable preference from quietly handing back an older
            // release than the pre-release that was asked for.
            try {
                List<String> ordered = orderedVersions(pkg);
                String newest = highestOf(ordered);
                if (newest != null) return Optional.of(newest);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                // Metadata unreachable: fall through to the ordinary prefs rather than fail here.
            }
        }
        String lock = firstNonBlank(lockedVersionPrefs.get(pkg), lockedVersionPrefs.get(ga));
        if (lock != null) return Optional.of(lock);
        String bom = firstNonBlank(bomConstraints.get(ga), bomConstraints.get(pkg));
        if (bom != null) return Optional.of(bom);
        return Optional.empty();
    }

    private static @Nullable String firstNonBlank(@Nullable String a, @Nullable String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    /**
     * FLOOR lower bound: the higher of the platform pin and the edge's own declared version
     * a floor must never clamp an edge below what its POM requires.
     */
    private static String floorOf(String bomPin, String edgeVersion) {
        if (edgeVersion == null || edgeVersion.isEmpty()) return bomPin;
        return Versions.compare(edgeVersion, bomPin) > 0 ? edgeVersion : bomPin;
    }

    @Override
    public List<String> versions(String pkg) throws IOException, InterruptedException {
        Set<String> wanted = wantedVersions(pkg);
        dropStaleCandidates(pkg, wanted);
        List<String> cached = versionCache.get(pkg);
        if (cached != null) return cached;

        long t0 = ResolveProfile.on() ? System.nanoTime() : 0L;
        // highest-wins only needs the soft-prefer pin (if any) + a few highest releases.
        // Full maven-metadata histories (80+ versions) made PubGrub thrash on Quarkus test graphs.
        List<String> ordered = orderedVersions(pkg, wanted);
        // `snapshot` asked for the bleeding edge explicitly, so leave its window unnarrowed.
        List<String> result =
                List.copyOf(isSnapshotPackage(pkg) ? compactHighest(ordered) : compactVersionCandidates(ordered));
        versionCache.put(pkg, result);
        wantedAtCache.put(pkg, Set.copyOf(wanted));
        if (ResolveProfile.on()) {
            ResolveProfile.versions(System.nanoTime() - t0);
        }
        return result;
    }

    /**
     * Un-capped candidate list for the solver's widen-on-failure pathwhen every
     * compact candidate is ruled out (a Maven range below the top releases, backtracking past
     * the pin), the solver re-expands from the full advertised history instead of hard-failing
     * a satisfiable graph. The solver applies its own cap.
     */
    @Override
    public List<String> expandedVersions(String pkg) throws IOException, InterruptedException {
        Set<String> wanted = wantedVersions(pkg);
        dropStaleCandidates(pkg, wanted);
        List<String> cached = expandedVersionCache.get(pkg);
        if (cached != null) return cached;
        List<String> result = List.copyOf(orderedVersions(pkg, wanted));
        expandedVersionCache.put(pkg, result);
        wantedAtCache.put(pkg, Set.copyOf(wanted));
        return result;
    }

    private List<String> orderedVersions(String pkg) throws IOException, InterruptedException {
        return orderedVersions(pkg, wantedVersions(pkg));
    }

    /**
     * Advertised versions, highest-first, BOM/lock soft-prefers front-loaded. Snapshots are
     * candidates only for a {@code snapshot} package or when a wanted version is one.
     */
    private List<String> orderedVersions(String pkg, Set<String> wanted) throws IOException, InterruptedException {
        boolean snapshots = isSnapshotPackage(pkg) || wanted.stream().anyMatch(Versions::isSnapshot);
        List<String> available = declared.reposFor(pkg).availableVersions(withVersion(pkg, "any"), wanted, snapshots);
        List<String> sorted = new ArrayList<>(available);
        sorted.sort((a, b) -> Versions.compare(b, a));

        // BOM + lock soft-prefer are GA-scoped (one pin applies to every classifier of the GA).
        // Later calls win the front — mirror preferredVersion's precedence exactly
        // (lock pkg > lock ga > bom ga > bom pkg) or classifier duals pick divergent
        // versions between the lazy-seed and expanded paths.
        String ga = PackageId.parse(pkg).ga();
        preferBom(sorted, bomConstraints.get(pkg));
        preferBom(sorted, bomConstraints.get(ga));
        preferFirst(sorted, lockedVersionPrefs.get(ga));
        preferFirst(sorted, lockedVersionPrefs.get(pkg));
        return sorted;
    }

    /** How many candidates the compact window keeps. */
    private static final int COMPACT_CANDIDATES = 4;

    /**
     * Cap the candidate list while preserving the soft-prefer front and, critically, keeping
     * something <em>stable</em> in the window.
     *
     * <p>The cap exists so PubGrub does not thrash on 80-version histories. Taking simply
     * the highest four, though, starves {@link
     * cc.jumpkick.resolver.pubgrub.AllowedSet#choosePreferred} of any stable candidate whenever a
     * project publishes four or more pre-releases above its latest release. jackson-annotations sits
     * at 3.0-rc5..rc2 above a stable 2.22, so a caret on 2.22 resolved to <b>3.0-rc5</b>.
     * The stable preference downstream was correct all along — it was simply never offered a stable.
     *
     * <p>So the highest <em>stable</em> versions fill the window first and pre-releases take only the
     * slots left over (which is what keeps a project that has never cut a stable release resolvable).
     * A constraint that genuinely needs a pre-release still resolves: every stable candidate fails it,
     * the window is exhausted, and the solver widens to the unfiltered history via {@link
     * #expandedVersions} — the path that exists for exactly this shape of miss. That keeps
     * transitive POM edges pinned to milestone builds working, since those arrive as constraints
     * rather than as manifest selectors.
     */
    static List<String> compactVersionCandidates(List<String> sortedHighestFirst) {
        if (sortedHighestFirst.size() <= COMPACT_CANDIDATES) {
            // The full history is the universe, so the downstream stable preference can already see
            // a stable candidate. Nothing to protect against here.
            return sortedHighestFirst;
        }

        // A lock/BOM soft-prefer sits at index 0 without necessarily being the highest version, and
        // it must survive the cap even when it is itself a pre-release: an explicit pin outranks this
        // policy.
        String front = sortedHighestFirst.get(0);
        String naturalMax = highestOf(sortedHighestFirst);
        boolean pinnedFront = !front.equals(naturalMax);

        LinkedHashSet<String> picked = new LinkedHashSet<>();
        if (pinnedFront) {
            picked.add(front);
            // Keep the natural max too. AllowedSet infers "this front is a pin, take it
            // unconditionally" by finding some higher version in the universe — drop that and a
            // pre-release pin silently loses to a lower stable.
            picked.add(naturalMax);
        }
        for (String v : sortedHighestFirst) {
            if (picked.size() >= COMPACT_CANDIDATES) break;
            if (Versions.isStable(v)) picked.add(v);
        }
        for (String v : sortedHighestFirst) {
            if (picked.size() >= COMPACT_CANDIDATES) break;
            picked.add(v);
        }

        // Restore highest-first order (choosePreferred walks the universe in index order), then put
        // any pin back at the front where preferFirst/preferBom left it.
        List<String> out = new ArrayList<>(picked);
        out.sort((a, b) -> Versions.compare(b, a));
        if (pinnedFront) {
            out.remove(front);
            out.add(0, front);
        }
        return List.copyOf(out);
    }

    /**
     * Cap to the highest candidates with no stability policy at all — the window for a {@code
     * snapshot} package, which asked for the newest thing published whatever it is.
     */
    static List<String> compactHighest(List<String> sortedHighestFirst) {
        if (sortedHighestFirst.size() <= COMPACT_CANDIDATES) return sortedHighestFirst;
        List<String> out = new ArrayList<>(COMPACT_CANDIDATES);
        for (String v : sortedHighestFirst) {
            if (out.contains(v)) continue;
            out.add(v);
            if (out.size() == COMPACT_CANDIDATES) break;
        }
        return List.copyOf(out);
    }

    /** The highest version under Maven ordering, or null for an empty list. */
    private static @Nullable String highestOf(List<String> versions) {
        String max = null;
        for (String v : versions) {
            if (max == null || Versions.compare(v, max) > 0) max = v;
        }
        return max;
    }

    /**
     * BOM soft-prefer: move {@code pin} to front when it is already in the metadata list. Does
     * <em>not</em> invent a missing pininserting unreleased/stale pins that sit below
     * transitive floors made PubGrub thrash on Quarkus-sized graphs.
     */
    static void preferBom(List<String> versions, @Nullable String pin) {
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
    static void preferFirst(List<String> versions, @Nullable String preferred) {
        if (preferred == null || preferred.isBlank()) return;
        if (versions.isEmpty()) return;
        if (versions.getFirst().equals(preferred)) return;
        if (versions.remove(preferred)) {
            versions.add(0, preferred);
        }
    }

    @Override
    public List<Term> dependencies(String pkg, String version) throws IOException, InterruptedException {
        long t0 = ResolveProfile.on() ? System.nanoTime() : 0L;
        Set<String> excl = exclusionsWhenExpanding.getOrDefault(pkg, Set.of());
        List<RawEdge> raw = rawEdges(pkg, version);
        List<Term> out = new ArrayList<>(raw.size());
        Set<String> filtered = null;
        for (RawEdge edge : raw) {
            if (edge.constraintOnly()) {
                // Not a path to the module, so it neither obeys nor registers exclusions. The
                // solver reads the negative term as "absent or within": the module stays out
                // unless an edge brings it in, and then sits within the constraint.
                if (edge.declaredVersion() != null) {
                    declaredVersions
                            .computeIfAbsent(edge.depPkg(), k -> ConcurrentHashMap.newKeySet())
                            .add(edge.declaredVersion());
                }
                out.add(Term.negative(
                        edge.depPkg(), nearestOrOwn(pkg, version, edge).complement()));
                continue;
            }
            if (isExcluded(edge.depPkg(), excl)) {
                if (filtered == null) filtered = new LinkedHashSet<>();
                filtered.add(edge.depPkg());
                continue;
            }
            // Cascade parent exclusions + edge exclusions onto the child. Registered even when
            // empty: an unencumbered path is exactly what has to collapse the child's set to
            // nothing, and staying silent here would leave another path's exclusions standing.
            Set<String> merged = new LinkedHashSet<>(excl);
            merged.addAll(edge.edgeExclusions());
            registerExclusions(edge.depPkg(), merged);
            if (edge.declaredVersion() != null) {
                declaredVersions
                        .computeIfAbsent(edge.depPkg(), k -> ConcurrentHashMap.newKeySet())
                        .add(edge.declaredVersion());
            }
            out.add(Term.positive(edge.depPkg(), nearestOrOwn(pkg, version, edge)));
        }
        // Remember what this expansion dropped so the resolver can detect a stale expansion
        // after the exclusion sets converge (they only ever narrow). Overwrite, not merge: a
        // re-expansion in a later solve round supersedes the earlier one.
        String expansionKey = pkg + "@" + version;
        if (filtered == null) {
            filteredAtExpansion.remove(expansionKey);
        } else {
            filteredAtExpansion.put(expansionKey, Set.copyOf(filtered));
        }
        List<Term> immutable = List.copyOf(out);
        prefetchTransitiveAsync(immutable);
        if (ResolveProfile.on()) {
            ResolveProfile.deps(System.nanoTime() - t0);
        }
        return immutable;
    }

    /** POM edges for {@code pkg@version}, cached without inherited exclusions. */
    private List<RawEdge> rawEdges(String pkg, String version) throws IOException, InterruptedException {
        // GA@ver: jar/aar (and classifiers) share one POM; BOM warm seeds the default jar: key.
        String key = rawEdgesCacheKey(pkg, version);
        List<RawEdge> hit = rawDepsCache.get(key);
        if (hit != null) return hit;

        Coordinate coord = withVersion(pkg, version);
        EffectivePom pom;
        try {
            pom = declared.builderFor(pkg).build(coord);
        } catch (MavenRepo.ArtifactNotFoundException e) {
            throw new VersionUnavailableException(e.getMessage());
        }
        // <distributionManagement><relocation>: this coordinate moved. The stub has no classes and
        // no dependencies of its own, so its one edge is to the target — which is how Maven and
        // Gradle render it too. Chains terminate because each hop is a normal package expansion.
        Pom.Relocation moved = pom.relocation();
        if (moved != null && moved.redirects(coord)) {
            Coordinate to = moved.applyTo(coord);
            String toPkg = PackageId.ofGa(to.group() + ":" + to.artifact()).key();
            List<RawEdge> redirect = List.of(new RawEdge(toPkg, VersionSet.exact(to.version()), Set.of(), null));
            rawDepsCache.put(key, redirect);
            return redirect;
        }

        List<RawEdge> out = new ArrayList<>();
        var kmpSelection = kmp.selectionFor(pkg, version);
        Set<String> kmpDropped = Set.of();
        if (kmpSelection.isPresent()) {
            var target = kmpSelection.get().target();
            String targetPkg =
                    PackageId.ofGa(target.group() + ":" + target.module()).key();
            out.add(new RawEdge(targetPkg, VersionSet.exact(target.version()), Set.of(), null));
            kmpDropped = kmpSelection.get().allTargets();
        }
        // Gradle metadata constraints: how androidx keeps a family on one version (core-ktx
        // constrains core to its own version and back). Each participates in conflict resolution
        // for a module already in the graph without adding it — Gradle's semantics.
        for (GradleModuleMetadata.Constraint c : kmp.constraintsFor(pkg, version)) {
            String depPkg = PackageId.ofGa(c.group() + ":" + c.module()).key();
            String spec = c.version().trim();
            String declared = c.strictly() || VersionSelectors.looksLikeMavenRange(spec) ? null : spec;
            out.add(new RawEdge(depPkg, constraintForGmmConstraint(depPkg, c), Set.of(), declared, true));
        }
        for (Pom.Dep dep : pom.dependencies()) {
            if (dep.optional()) continue;
            if (kmpDropped.contains(dep.module())) continue;
            String scope = dep.scope();
            if (scope != null && !scope.isEmpty() && !FOLLOWED_SCOPES.contains(scope)) continue;
            if (dep.version() == null || dep.version().isBlank()) continue;
            String depPkg = packageKey(dep);
            String hostExpression = pom.hostClassified().get(dep.module());
            if (hostExpression != null) {
                hostClassifierNotes.add(PackageId.parse(pkg).ga() + " " + version + " depends on " + dep.module()
                        + " with classifier `" + dep.classifier() + "`: the POM spells it " + hostExpression
                        + ", which follows the host, so a lock made on another platform pins that platform's artifact");
            }
            Set<String> edgeExcl = modulesOf(dep.exclusions());
            String edgeVersion = dep.version().trim();
            String declared = VersionSelectors.looksLikeMavenRange(edgeVersion) ? null : edgeVersion;
            out.add(new RawEdge(depPkg, constraintForManagedEdge(depPkg, edgeVersion), edgeExcl, declared));
        }
        List<RawEdge> immutable = List.copyOf(out);
        grantDeclaredRepositories(pkg, pom, immutable);
        rawDepsCache.put(key, immutable);
        return immutable;
    }

    /**
     * Hand the repositories {@code pom} makes available to every child edge, and forget what was
     * cached for a child whose repository set grew: a version list or POM asked before the grant
     * was answered by fewer repositories than the child now has.
     */
    private void grantDeclaredRepositories(String pkg, EffectivePom pom, List<RawEdge> edges) {
        List<String> children = new ArrayList<>(edges.size());
        for (RawEdge edge : edges) children.add(edge.depPkg());
        for (String ga : declared.propagate(pkg, pom, children)) {
            versionCache.keySet().removeIf(k -> ga.equals(gaOf(k)));
            expandedVersionCache.keySet().removeIf(k -> ga.equals(gaOf(k)));
            rawDepsCache.keySet().removeIf(k -> k.startsWith(ga + "@"));
        }
    }

    private static String gaOf(String pkg) {
        return PackageId.isMavenPackageKey(pkg) ? PackageId.parse(pkg).ga() : pkg;
    }

    /**
     * Intersect {@code extra} into the exclusion set applied when {@code pkg} is expanded — a
     * module is stripped only if every observed parent path strips it. The first registration
     * establishes the set; later ones can only narrow it. Package-visible for tests.
     */
    void registerExclusions(String pkg, Set<String> extra) {
        Set<String> incoming = extra == null ? Set.of() : Set.copyOf(extra);
        exclusionsWhenExpanding.merge(pkg, incoming, (existing, fresh) -> {
            if (existing.isEmpty() || fresh.isEmpty()) return Set.of();
            Set<String> both = new LinkedHashSet<>(existing);
            both.retainAll(fresh);
            return Set.copyOf(both);
        });
    }

    /** The exclusions currently applied when {@code pkg} expands. Package-visible for tests. */
    Set<String> exclusionsFor(String pkg) {
        return exclusionsWhenExpanding.getOrDefault(pkg, Set.of());
    }

    /**
     * Whether any decided package's expansion filtered an edge the converged exclusion set would
     * keep. Intersection registrations only narrow a package's set, so an expansion taken before
     * a clean path registered (discovered deeper than the excluding path) can bake a
     * too-aggressive filter into the solve — the dropped child never enters the resolution and
     * no conflict ever surfaces it. A stale expansion means the solve must be re-run with the
     * converged sets. Package-visible for the resolver's fixpoint loop.
     */
    boolean anyExpansionStale(Map<String, String> decisions) {
        for (Map.Entry<String, String> e : decisions.entrySet()) {
            Set<String> filtered = filteredAtExpansion.get(e.getKey() + "@" + e.getValue());
            if (filtered == null) continue;
            Set<String> converged = exclusionsFor(e.getKey());
            for (String dep : filtered) {
                if (!isExcluded(dep, converged)) return true;
            }
        }
        return false;
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

    /**
     * Solver package key for a POM dependency ({@code g:a:type:classifier}). The type names the
     * artifact's packaging, not a different library: an edge that says {@code aar} and one that
     * says nothing are the same package, so both converge on one version. The assembler recovers
     * the packaging from the POM when it materialises the artifact.
     */
    static String packageKey(Pom.Dep dep) {
        String type = solverType(dep.type());
        String classifier = dep.classifier() == null ? "" : dep.classifier();
        return PackageId.of(dep.groupId(), dep.artifactId(), type, classifier).key();
    }

    /** A packaging that is still one library on the classpath keys as the default type. */
    static String solverType(@Nullable String declared) {
        if (declared == null || declared.isBlank()) return PackageId.DEFAULT_TYPE;
        return switch (declared) {
            case "aar", "bundle" -> PackageId.DEFAULT_TYPE;
            default -> declared;
        };
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

    /**
     * PubGrub constraint for one POM edge.
     *
     * <p>Bare versions (after {@link EffectivePom} fills dependencyManagement) are not Maven
     * floors — Maven treats them as the chosen version. jk's historical default without a
     * platform was highest-wins ({@code atLeast}). That must <em>not</em> apply under a platform
     * BOM: lifting a filled pin (parent or import depMgmt) silently breaks the BOM contract
     * (e.g. {@code named-locks} 2.x next to {@code maven-resolver-api} 1.9).
     *
     * <ul>
     * <li><b>No platform BOM</b> ({@code bomConstraints} empty): bare → {@code atLeast}
     * (highest-wins). Explicit user ranges / open selectors still use their VersionSet.
     * <li><b>Platform BOM present + {@link PlatformPolicy#ENFORCED}</b> (default): BOM-map GAs
     * use {@code exact(bomPin)}; unmapped bare fills follow {@link
     * cc.jumpkick.model.UnmappedPolicy} — highest-wins by default, {@code exact} under
     * {@code strict}.
     * <li><b>Platform BOM + {@link PlatformPolicy#FLOOR}</b>: BOM-map GAs use {@code
     * atLeast(max(bomPin, edge))} (may lift, never clamps below the edge's declared
     * version —; unmapped bare fills follow {@link cc.jumpkick.model.UnmappedPolicy}.
     * </ul>
     */
    VersionSet constraintForManagedEdge(String depPkg, String version) {
        String trimmed = version.trim();
        PackageId id = PackageId.parse(depPkg);
        String ga = id.ga();
        String bomPin = firstNonBlank(bomConstraints.get(ga), bomConstraints.get(depPkg));

        // Maven bracket ranges in POMs (AndroidX loves `[1.4.0]` exact / floor-as-bracket).
        // Platform ENFORCED must still win for managed GAs — otherwise compose-bom pins lose to
        // every `[x.y.z]` edge and PubGrub cannot align adaptive/suite (NIA). FLOOR keeps the
        // range as a floor at max(bomPin, range).
        if (VersionSelectors.looksLikeMavenRange(trimmed)) {
            if (bomPin != null) {
                if (platformPolicy == PlatformPolicy.FLOOR) {
                    return VersionSet.atLeast(bomPin, true);
                }
                return VersionSet.exact(bomPin);
            }
            return VersionSelectors.constraintFromPomVersion(trimmed);
        }

        // Classified artifacts (guice:jar:classes): GA maven-metadata highest-wins picks versions
        // that often have no classifier POM → Unavailable thrash.
        if (!id.classifier().isEmpty()) {
            if (bomPin != null && platformPolicy == PlatformPolicy.FLOOR) {
                return VersionSet.atLeast(floorOf(bomPin, trimmed), true);
            }
            return VersionSet.exact(bomPin != null ? bomPin : trimmed);
        }

        // Platform map entry.
        if (bomPin != null) {
            if (platformPolicy == PlatformPolicy.FLOOR) {
                // Opt-in soft platform: pin is a floor; preferBom still front-loads the pin.
                return VersionSet.atLeast(floorOf(bomPin, trimmed), true);
            }
            return VersionSet.exact(bomPin);
        }
        if (!bomConstraints.isEmpty() && unmappedPolicy == UnmappedPolicy.STRICT) {
            // [resolve] unmapped = "strict": exact fills for unmanaged GAs — every diamond on
            // them is a hard error (maximum reproducibility). Default is MEDIATE
            // fall through to highest-wins, Maven/Gradle parity; the named-locks hazard class
            // is covered by family-align MAPPING those GAs into the BOM constraints.
            return VersionSet.exact(trimmed);
        }
        // No platform, or unmapped-mediate: highest-wins bare versions. Lock prefs only reorder candidates.
        return VersionSelectors.constraintFromPomVersion(trimmed);
    }

    /**
     * PubGrub constraint for a Gradle metadata {@code dependencyConstraints} entry. A {@code
     * requires} is a POM-style version — a floor under conflict resolution when bare, a range when
     * bracketed — and follows the platform rules of {@link #constraintForManagedEdge}. A {@code
     * strictly} pins, unless a platform BOM manages the module, in which case the BOM's say stands
     * as it does for every edge.
     */
    VersionSet constraintForGmmConstraint(String depPkg, GradleModuleMetadata.Constraint c) {
        String spec = c.version().trim();
        if (c.strictly() && !VersionSelectors.looksLikeMavenRange(spec)) {
            String ga = PackageId.parse(depPkg).ga();
            if (firstNonBlank(bomConstraints.get(ga), bomConstraints.get(depPkg)) == null) {
                return VersionSet.exact(spec);
            }
        }
        return constraintForManagedEdge(depPkg, spec);
    }

    /**
     * Fire-and-forget parallel warm of BOM/lock pins: fill KMP + effective-POM process caches (and
     * {@link #rawDepsCache} via {@link #rawEdges}) so PubGrub's first decides race a hot frontier.
     * Does not block the solver — a full drain made first-in-process worse than racing.
     */
    @Override
    public void warmUp() {
        if (!warmedUp.compareAndSet(false, true)) return;
        if (bomConstraints.isEmpty() && lockedVersionPrefs.isEmpty()) return;

        Map<String, String> pins = new LinkedHashMap<>();
        for (var e : bomConstraints.entrySet()) {
            if (e.getValue() == null || e.getValue().isBlank()) continue;
            try {
                pins.putIfAbsent(PackageId.ofGa(e.getKey()).key(), e.getValue());
            } catch (RuntimeException badKey) {
                // skip
                Log.debug("warmUp: skip", badKey);
            }
        }
        for (var e : lockedVersionPrefs.entrySet()) {
            if (e.getValue() == null || e.getValue().isBlank()) continue;
            try {
                String key = e.getKey();
                String pkg = PackageId.isMavenPackageKey(key)
                        ? PackageId.parse(key).key()
                        : PackageId.ofGa(key).key();
                pins.putIfAbsent(pkg, e.getValue());
            } catch (RuntimeException badKey) {
                // skip
                Log.debug("warmUp: skip", badKey);
            }
        }
        int n = 0;
        // Modest fan-out: huge BOM blasts thrash under load and made cold times noisier than a
        // smaller head-start + frontier prefetch during expand.
        final int cap = bomConstraints.size() > 200 ? 160 : 64;
        for (var e : pins.entrySet()) {
            if (n++ >= cap) break;
            String pkg = e.getKey();
            String pin = e.getValue();
            submitPrefetch(() -> rawEdges(pkg, pin));
        }
    }

    /**
     * Speculative I/O for children of a just-expanded package — the next PubGrub decides.
     *
     * <ul>
     * <li>Exact pins: warm effective POM <em>and</em> KMP {@code .module} redirect in parallel so
     * the next decide does not block on serial disk+JSON work.
     * <li>Open ranges: left cold (metadata list is cheap enough on miss).
     * </ul>
     *
     * <p>Large platform BOMs still prefetch a bounded frontier of exact-pin children (not zero —
     * the old {@code size > 200 → return} left Android/Compose graphs fully serial).
     */
    private void prefetchTransitiveAsync(List<Term> deps) {
        // Warm the next decide frontier in parallel. Unbounded fan-out stampeded heap on Quarkus;
        // large BOMs still get a wide window so Android/Compose stay ahead of PubGrub.
        int budget = bomConstraints.size() > 200 ? 64 : 16;
        for (Term dep : deps) {
            if (budget <= 0) return;
            // A constraint brings nothing in; there is no next decide to warm for it.
            if (!dep.positive()) continue;
            String pkg = dep.pkg();
            // Exact edge pins only — soft-prefer (BOM) of every managed GA floods the pool;
            // warmUp already blasted the BOM map.
            Optional<String> exact = dep.versions().asExactSingleton();
            if (exact.isEmpty()) continue;
            String pin = exact.get();
            budget--;
            Coordinate child = withVersion(pkg, pin);
            // One task does both: KMP redirect then POM (POM often already warm from the parent walk).
            submitPrefetch(() -> {
                kmp.selectionFor(pkg, pin);
                declared.builderFor(pkg).build(child);
            });
        }
    }

    /** Run {@code work} on the io pool under a permit, counted so {@link #quiesce} can wait for it. */
    private void submitPrefetch(PrefetchWork work) {
        outstandingPrefetches.incrementAndGet();
        try {
            JkThreads.io().execute(() -> {
                try {
                    prefetchSlots.acquire();
                    try {
                        work.run();
                    } finally {
                        prefetchSlots.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    // best-effort warming; the sync path surfaces real failures
                    Log.debug("submitPrefetch: best-effort warming", e);
                } finally {
                    finishPrefetch();
                }
            });
        } catch (RuntimeException e) {
            finishPrefetch(); // rejected before it ever ran
            throw e;
        }
    }

    private void finishPrefetch() {
        if (outstandingPrefetches.decrementAndGet() == 0) {
            synchronized (prefetchIdle) {
                prefetchIdle.notifyAll();
            }
        }
    }

    @FunctionalInterface
    private interface PrefetchWork {
        void run() throws Exception;
    }

    /**
     * Block until no speculative prefetch is running. Warming is best-effort, so this gives up
     * after {@link #QUIESCE_TIMEOUT_MS} rather than holding a build hostage to a wedged fetch.
     */
    @Override
    public void quiesce() {
        long deadline = System.nanoTime() + QUIESCE_TIMEOUT_MS * 1_000_000L;
        synchronized (prefetchIdle) {
            while (outstandingPrefetches.get() > 0) {
                long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
                if (remainingMs <= 0) return;
                try {
                    prefetchIdle.wait(remainingMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static Coordinate withVersion(String pkg, String version) {
        return PackageId.parse(pkg).withVersion(version);
    }

    /** Cache key for POM edge lists — type/classifier-independent (one POM per GAV). */
    static String rawEdgesCacheKey(String pkg, String version) {
        try {
            if (PackageId.isMavenPackageKey(pkg)) {
                return PackageId.parse(pkg).ga() + "@" + version;
            }
        } catch (RuntimeException e) {
            // fall through
            Log.debug("rawEdgesCacheKey: fall through", e);
        }
        return pkg + "@" + version;
    }
}
