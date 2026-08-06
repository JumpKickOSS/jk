// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.PackageId;
import cc.jumpkick.model.PlatformPolicy;
import cc.jumpkick.repo.EffectivePom;
import cc.jumpkick.repo.EffectivePomBuilder;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.Pom;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.resolver.pubgrub.Diagnostics;
import cc.jumpkick.resolver.pubgrub.PackageSource;
import cc.jumpkick.resolver.pubgrub.PubGrubSolver;
import cc.jumpkick.resolver.pubgrub.Term;
import cc.jumpkick.resolver.pubgrub.UnsatisfiableException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiConsumer;

/** {@link Resolver} backed by {@link PubGrubSolver} — maps jk deps onto solver terms. */
public final class PubGrubResolver implements Resolver {

    private static final String ROOT_PKG = "<root>";
    private static final String ROOT_VERSION = "0.0.0";

    private final PackageSource source;
    private final EffectivePomBuilder pomBuilder;
    private KmpRedirects kmp = KmpRedirects.NONE;
    /** Optional palette injected by the CLI so diagnostic colors match the live theme. */
    cc.jumpkick.resolver.pubgrub.Diagnostics.Palette palette; // package-private for LockOrchestrator
    /** Optional live graph progress (package key, version) during PubGrub decisions. */
    private BiConsumer<String, String> onDecision;

    public PubGrubResolver(MavenRepo repo) {
        this(RepoGroup.of(repo));
    }

    public PubGrubResolver(RepoGroup repos) {
        this(repos, Map.of());
    }

    /**
     * @param bomConstraints {@code group:artifact → recommended version} from the user's platform
     * BOMs (soft prefer — full candidate list, pin first; Gradle {@code platform} parity).
     * Empty map = no BOM preferences.
     */
    public PubGrubResolver(RepoGroup repos, Map<String, String> bomConstraints) {
        this(repos, bomConstraints, Map.of());
    }

    /**
     * Soft-prefer BOM and locked versions (front of candidate list; PubGrub backtracks if ruled
     * out).
     */
    public PubGrubResolver(
            RepoGroup repos, Map<String, String> bomConstraints, Map<String, String> lockedVersionPrefs) {
        this(repos, bomConstraints, lockedVersionPrefs, KmpRedirects.NONE);
    }

    /** As above with KMP root-module redirect resolution (see {@link KmpRedirects}). */
    public PubGrubResolver(
            RepoGroup repos,
            Map<String, String> bomConstraints,
            Map<String, String> lockedVersionPrefs,
            KmpRedirects kmp) {
        this(repos, bomConstraints, lockedVersionPrefs, kmp, PlatformPolicy.ENFORCED);
    }

    /** As above with {@link PlatformPolicy}; unmapped fills default to MEDIATE. */
    public PubGrubResolver(
            RepoGroup repos,
            Map<String, String> bomConstraints,
            Map<String, String> lockedVersionPrefs,
            KmpRedirects kmp,
            PlatformPolicy platformPolicy) {
        this(repos, bomConstraints, lockedVersionPrefs, kmp, platformPolicy, null);
    }

    /** Full constructor with both platform policies. */
    public PubGrubResolver(
            RepoGroup repos,
            Map<String, String> bomConstraints,
            Map<String, String> lockedVersionPrefs,
            KmpRedirects kmp,
            PlatformPolicy platformPolicy,
            cc.jumpkick.model.UnmappedPolicy unmappedPolicy) {
        EffectivePomBuilder builder = new EffectivePomBuilder(repos);
        this.pomBuilder = builder;
        this.kmp = kmp;
        this.source = new MavenPackageSource(
                repos, builder, bomConstraints, lockedVersionPrefs, kmp, platformPolicy, unmappedPolicy);
    }

    /** Test seam: lets unit tests inject an in-memory {@link PackageSource}. */
    PubGrubResolver(PackageSource source, EffectivePomBuilder pomBuilder) {
        this(source, pomBuilder, KmpRedirects.NONE);
    }

    /** Shared-source constructorreuse POM/version caches across scope groups. */
    public PubGrubResolver(PackageSource source, EffectivePomBuilder pomBuilder, KmpRedirects kmp) {
        this.source = Objects.requireNonNull(source, "source");
        this.pomBuilder = pomBuilder;
        this.kmp = kmp == null ? KmpRedirects.NONE : kmp;
    }

    /**fire during each PubGrub decision so lock progress can advance mid-scope. */
    public PubGrubResolver withOnDecision(BiConsumer<String, String> onDecision) {
        this.onDecision = onDecision;
        return this;
    }

    @Override
    public Resolution resolve(List<Dependency> roots) throws IOException, InterruptedException {
        List<Term> rootTerms = new ArrayList<>(roots.size());
        Map<String, String> rootDepNames = new HashMap<>();
        for (Dependency dep : roots) {
            // Declared GAs → g:a:jar:; kind=tests → g:a:test-jar:tests.
            String pkg = dep.packageKey();
            rootTerms.add(Term.positive(pkg, VersionSelectors.toVersionSet(dep.version())));
            // Skip workspace placeholders — they never hit the network so
            // the artifact-defaulting hint would be misleading there.
            if (!dep.isWorkspace()) {
                rootDepNames.put(pkg, dep.library());
            }
        }

        Map<String, String> decisions;
        try {
            PubGrubSolver solver = new PubGrubSolver(source);
            if (onDecision != null) solver.withOnDecision(onDecision);
            try {
                decisions = solver.solve(ROOT_PKG, ROOT_VERSION, rootTerms);
            } catch (UnsatisfiableException first) {
                // Bounded retry with full historiescompact candidate lists
                // can make a satisfiable graph LOOK unsat when conflict resolution never
                // revisits the starved package. Source caches make the retry cheap; a real
                // unsat fails again and its (better-informed) diagnostics win.
                if (!solver.maybeIncomplete()) throw first;
                PubGrubSolver wide = new PubGrubSolver(source).withWideUniverses();
                if (onDecision != null) wide.withOnDecision(onDecision);
                decisions = wide.solve(ROOT_PKG, ROOT_VERSION, rootTerms);
            }
        } catch (UnsatisfiableException e) {
            boolean ansi =
                    System.console() != null && !"dumb".equals(System.getenv("TERM")) && System.getenv("CI") == null;
            // Use the injected palette (from the CLI theme) when available; fall back to the
            // built-in DEFAULT which hard-codes the same values as JkDarkTheme.
            cc.jumpkick.resolver.pubgrub.Diagnostics.Palette palette = this.palette != null
                    ? this.palette
                    : (ansi
                            ? cc.jumpkick.resolver.pubgrub.Diagnostics.Palette.DEFAULT
                            : cc.jumpkick.resolver.pubgrub.Diagnostics.Palette.PLAIN);
            throw new UnsatisfiableException(Diagnostics.render(e.rootCause(), palette), e.rootCause());
        }

        // Drop the synthetic root from the result and build the per-module dep lists.
        decisions = new TreeMap<>(decisions);
        decisions.remove(ROOT_PKG);

        // KMP global variant exclusion (A5f finding 20): a platform artifact's own POM can name
        // a non-selected SIBLING concretely (datastore-core-okio-jvm → datastore-core-jvm)
        // variant-aware in GMM space, a double-define at dex in POM space. When the selected
        // sibling made it into the resolution, the non-selected one leaves it; its dep edges
        // (built below) drop with it, and the selected artifact supplies the classes.
        for (var drop : kmp.droppedSiblings().entrySet()) {
            if (decisions.containsKey(drop.getValue())) {
                decisions.remove(drop.getKey());
            }
        }

        // Exclusions effective when listing each package's children: seed from parent edges
        // (P depends on A with exclusions E → E applies under A), then cascade down so
        // subtree members inherit (Maven: exclusion covers the whole branch).
        Map<String, Set<String>> exclWhenListing = new HashMap<>();
        if (pomBuilder != null) {
            for (Map.Entry<String, String> e : decisions.entrySet()) {
                EffectivePom pom = pomBuilder.build(toCoord(e.getKey(), e.getValue()));
                for (Pom.Dep d : pom.dependencies()) {
                    Set<String> edgeExcl = MavenPackageSource.modulesOf(d.exclusions());
                    if (edgeExcl.isEmpty()) continue;
                    exclWhenListing.merge(MavenPackageSource.packageKey(d), edgeExcl, PubGrubResolver::unionSets);
                }
            }
            // Cascade: if A has exclusions E and A→B, B also filters by E.
            boolean changed = true;
            while (changed) {
                changed = false;
                for (Map.Entry<String, String> e : decisions.entrySet()) {
                    Set<String> parentExcl = exclWhenListing.get(e.getKey());
                    if (parentExcl == null || parentExcl.isEmpty()) continue;
                    EffectivePom pom = pomBuilder.build(toCoord(e.getKey(), e.getValue()));
                    for (Pom.Dep d : pom.dependencies()) {
                        String childPkg = MavenPackageSource.packageKey(d);
                        if (!decisions.containsKey(childPkg)) continue;
                        Set<String> before = exclWhenListing.getOrDefault(childPkg, Set.of());
                        Set<String> merged = unionSets(before, parentExcl);
                        if (merged.size() != before.size()) {
                            exclWhenListing.put(childPkg, merged);
                            changed = true;
                        }
                    }
                }
            }
        }

        Map<String, Set<String>> dependsOn = new HashMap<>();
        for (Map.Entry<String, String> e : decisions.entrySet()) {
            Set<String> deps = new LinkedHashSet<>();
            if (pomBuilder != null) {
                Set<String> excl = exclWhenListing.getOrDefault(e.getKey(), Set.of());
                // Mirror MavenPackageSource's KMP rewrite: the dep edges must show the
                // GMM-selected platform artifact, not the POM's platform fallback.
                var kmpSelection = kmp.selectionFor(e.getKey(), e.getValue());
                Set<String> kmpDropped = Set.of();
                if (kmpSelection.isPresent()) {
                    var target = kmpSelection.get().target();
                    String targetPkg = PackageId.ofGa(target.group() + ":" + target.module())
                            .key();
                    if (decisions.containsKey(targetPkg) && !MavenPackageSource.isExcluded(targetPkg, excl)) {
                        deps.add(targetPkg + "@" + decisions.get(targetPkg));
                    }
                    kmpDropped = kmpSelection.get().allTargets();
                }
                EffectivePom pom = pomBuilder.build(toCoord(e.getKey(), e.getValue()));
                for (Pom.Dep d : pom.dependencies()) {
                    if (d.optional()) continue;
                    if (kmpDropped.contains(d.module())) continue;
                    String scope = d.scope();
                    if (scope != null && !scope.isEmpty() && !scope.equals("compile") && !scope.equals("runtime"))
                        continue;
                    if (d.version() == null || d.version().isBlank()) continue;
                    String childPkg = MavenPackageSource.packageKey(d);
                    if (MavenPackageSource.isExcluded(childPkg, excl)) continue;
                    if (!decisions.containsKey(childPkg)) continue;
                    deps.add(childPkg + "@" + decisions.get(childPkg));
                }
            }
            dependsOn.put(e.getKey(), deps);
        }

        Map<String, Resolution.ResolvedModule> out = new TreeMap<>();
        for (Map.Entry<String, String> e : decisions.entrySet()) {
            out.put(
                    e.getKey(),
                    new Resolution.ResolvedModule(
                            e.getKey(), e.getValue(), new ArrayList<>(dependsOn.getOrDefault(e.getKey(), Set.of()))));
        }
        return new Resolution(out);
    }

    private static Coordinate toCoord(String packageKey, String version) {
        return PackageId.parse(packageKey).withVersion(version);
    }

    private static Set<String> unionSets(Set<String> a, Set<String> b) {
        if (a == null || a.isEmpty()) return b == null ? Set.of() : Set.copyOf(b);
        if (b == null || b.isEmpty()) return Set.copyOf(a);
        Set<String> u = new LinkedHashSet<>(a);
        u.addAll(b);
        return Set.copyOf(u);
    }
}
