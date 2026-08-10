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

    /** {@code from->to} pairs already reported this resolve — one relocation line per lock. */
    private final java.util.Set<String> reportedRelocations = java.util.concurrent.ConcurrentHashMap.newKeySet();
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
            // Parallel-load BOM/lock pins before the first decide (warm disk, cold process).
            source.warmUp();
            decisions = solveFor(rootTerms);
            // Intersection exclusion sets only narrow as paths register, so a package decided
            // early can have filtered an edge the converged set keeps (clean path discovered
            // deeper than the excluding one). Re-solve with the converged sets until no decided
            // expansion is stale — sets only shrink, so this terminates; in practice one extra
            // round, and only when the order-dependence actually bit.
            if (source instanceof MavenPackageSource mps) {
                for (int round = 0; round < 4 && mps.anyExpansionStale(decisions); round++) {
                    decisions = solveFor(rootTerms);
                }
            }
        } finally {
            // Speculative prefetches run on the shared io pool with no handle back here. Let them
            // finish before the caller moves on — otherwise a lock that has already returned is
            // still writing into the cache the caller may be about to read, delete, or replace.
            source.quiesce();
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

        // Exclusions are a SOLVE-time concern, not a lock-edge concern. MavenPackageSource applies
        // them while listing candidates, so an excluded-everywhere package never reaches
        // `decisions`. Once a package IS in the resolution, every POM edge pointing at it is real
        // and the closure needs it — filtering here is what dropped logback-classic → logback-core
        // when an unrelated Micronaut edge excluded logback-core, and the assembly then shipped
        // without ch.qos.logback.core (ea6dc765). Do not reintroduce a per-edge exclusion filter
        // below: `decisions.containsKey` is the whole rule (JK-1660).
        Map<String, Set<String>> dependsOn = new HashMap<>();
        for (Map.Entry<String, String> e : decisions.entrySet()) {
            Set<String> deps = new LinkedHashSet<>();
            if (pomBuilder != null) {
                // Mirror MavenPackageSource's KMP rewrite: the dep edges must show the
                // GMM-selected platform artifact, not the POM's platform fallback. A rewritten
                // edge is still a POM edge, so it follows the same rule as the loop below.
                var kmpSelection = kmp.selectionFor(e.getKey(), e.getValue());
                Set<String> kmpDropped = Set.of();
                if (kmpSelection.isPresent()) {
                    var target = kmpSelection.get().target();
                    String targetPkg = PackageId.ofGa(target.group() + ":" + target.module())
                            .key();
                    if (decisions.containsKey(targetPkg)) {
                        deps.add(targetPkg + "@" + decisions.get(targetPkg));
                    }
                    kmpDropped = kmpSelection.get().allTargets();
                }
                EffectivePom pom = pomBuilder.build(toCoord(e.getKey(), e.getValue()));
                // A relocation stub's one edge is the redirect. Without it the target would sit in
                // the lock unreachable from anything, and every consumer of the graph — tree,
                // explain, packaging closure — would treat it as orphaned.
                var moved = pom.relocation();
                if (moved != null && moved.redirects(toCoord(e.getKey(), e.getValue()))) {
                    var to = moved.applyTo(toCoord(e.getKey(), e.getValue()));
                    String toPkg = cc.jumpkick.model.PackageId.ofGa(to.group() + ":" + to.artifact())
                            .key();
                    if (decisions.containsKey(toPkg)) {
                        deps.add(toPkg + "@" + decisions.get(toPkg));
                    }
                    // The <message> is the mechanism's whole point for the user: upstream retired
                    // the coordinate and says what to do about it. Following the redirect silently
                    // leaves jk.toml naming a dead artifact forever (JK-1709). Once per lock.
                    if (reportedRelocations.add(e.getKey() + "->" + toPkg)) {
                        String msg = moved.message();
                        System.err.println("jk: " + e.getKey() + "@" + e.getValue() + " has been relocated to "
                                + to.group() + ":" + to.artifact()
                                + (msg == null || msg.isBlank() ? "" : " — " + msg.trim()));
                    }
                    dependsOn.put(e.getKey(), deps);
                    continue;
                }
                for (Pom.Dep d : pom.dependencies()) {
                    if (d.optional()) continue;
                    if (kmpDropped.contains(d.module())) continue;
                    String scope = d.scope();
                    if (scope != null && !scope.isEmpty() && !scope.equals("compile") && !scope.equals("runtime"))
                        continue;
                    if (d.version() == null || d.version().isBlank()) continue;
                    String childPkg = MavenPackageSource.packageKey(d);
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

    /** Run the solver, retrying once with full candidate histories, and render diagnostics on unsat. */
    private Map<String, String> solveFor(List<Term> rootTerms) throws IOException, InterruptedException {
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

        return decisions;
    }

    private static Coordinate toCoord(String packageKey, String version) {
        return PackageId.parse(packageKey).withVersion(version);
    }
}
