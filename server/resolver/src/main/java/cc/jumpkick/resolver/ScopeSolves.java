// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.PackageId;
import cc.jumpkick.model.PinPolicy;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.EffectivePomBuilder;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The three scope solves, in the order that decides which version wins: {@link #ORDER}. Each graph
 * is seeded with the versions every earlier graph decided, so a module main already chose is
 * preferred by test and processor rather than re-decided; a module reachable only from a later
 * graph is solved fresh. The test classpath is the main classpath plus the test rows, so a module
 * main pins exactly is that version on it: the test graph takes main's exact pins for every edge
 * onto them under both pin policies, the way its own exact roots govern under {@code nearest}, and a
 * test root's own exact pin on a module main decided gives way to main's version, since main's is
 * the one on the test classpath whatever the pin asks ({@link #overrides} names each), and a test
 * dependency whose POM floors such a module above main's version is named the same way, since the
 * row its graph decides is one the test classpath never reads. One {@link
 * MavenPackageSource} serves all three so version and dependency caches survive the scope split,
 * while its per-graph exclusion state is reset between them: main's clean paths must not bleed into
 * the test and processor solves.
 */
final class ScopeSolves {

    /** Solve order. A graph's decisions seed every graph after it, never before. */
    static final List<LockRoots.GraphGroup> ORDER =
            List.of(LockRoots.GraphGroup.MAIN, LockRoots.GraphGroup.TEST, LockRoots.GraphGroup.PROCESSOR);

    /** The three resolutions, one per graph. */
    record Solved(Resolution main, Resolution test, Resolution processor) {}

    private final @Nullable Resolver resolverOverride;
    private final @Nullable MavenPackageSource sharedSource;
    private final EffectivePomBuilder pomBuilder;
    private final KmpRedirects kmp;
    private final PinPolicy pinPolicy;

    /** One sentence per test-scope exact pin that gave way to main's version. */
    private final List<String> overrides = new ArrayList<>();

    /**
     * @param resolverOverride a test's stand-in solver, or {@code null} for PubGrub over {@code sharedSource}
     * @param sharedSource the package source shared by all three graphs; {@code null} only with an override
     * @param pinPolicy whether each graph's exact roots override the transitive constraints on them
     */
    ScopeSolves(
            @Nullable Resolver resolverOverride,
            @Nullable MavenPackageSource sharedSource,
            EffectivePomBuilder pomBuilder,
            KmpRedirects kmp,
            PinPolicy pinPolicy) {
        this.resolverOverride = resolverOverride;
        this.sharedSource = sharedSource;
        this.pomBuilder = pomBuilder;
        this.kmp = kmp;
        this.pinPolicy = pinPolicy == null ? PinPolicy.EXACT : pinPolicy;
    }

    /** Solve every graph in {@link #ORDER}, seeding each with {@code lockedVersionPrefs} plus every earlier decision. */
    Solved solve(LockRoots.Roots roots, Map<String, String> lockedVersionPrefs, LockProgress progress)
            throws IOException, InterruptedException {
        Map<String, String> prefs = new HashMap<>(lockedVersionPrefs);
        Map<String, String> mainPins = exactRoots(roots.main());
        EnumMap<LockRoots.GraphGroup, Resolution> solved = new EnumMap<>(LockRoots.GraphGroup.class);
        for (LockRoots.GraphGroup graph : ORDER) {
            boolean test = graph == LockRoots.GraphGroup.TEST;
            Map<String, String> inherited = test ? mainPins : Map.of();
            List<Dependency> graphRoots = test
                    ? mainGoverned(roots.of(graph), Objects.requireNonNull(solved.get(LockRoots.GraphGroup.MAIN)))
                    : roots.of(graph);
            Resolution resolution = resolve(graphRoots, inherited, new HashMap<>(prefs), progress);
            if (test) noteDeadDuals(resolution, Objects.requireNonNull(solved.get(LockRoots.GraphGroup.MAIN)));
            progress.noteGraph(resolution);
            // Locked pins and earlier graphs win over this graph's decisions: putIfAbsent, in ORDER.
            for (var e : resolution.modules().entrySet()) {
                prefs.putIfAbsent(e.getKey(), e.getValue().version());
            }
            solved.put(graph, resolution);
        }
        // ORDER names every group, so each has a resolution by now
        return new Solved(
                Objects.requireNonNull(solved.get(LockRoots.GraphGroup.MAIN)),
                Objects.requireNonNull(solved.get(LockRoots.GraphGroup.TEST)),
                Objects.requireNonNull(solved.get(LockRoots.GraphGroup.PROCESSOR)));
    }

    /** The pins that gave way to main's version, one sentence each, in root order. */
    List<String> overrides() {
        return List.copyOf(overrides);
    }

    /**
     * The test graph's roots with each exact pin on a module main decided rewritten to main's
     * version. The test classpath is the main classpath plus the test rows, so main's version is the
     * one there whatever the pin asks; writing the pin's version as a test row would give the
     * classpath nothing to read. Each displaced pin is recorded for {@link #overrides}.
     */
    private List<Dependency> mainGoverned(List<Dependency> roots, Resolution main) {
        List<Dependency> out = new ArrayList<>(roots.size());
        for (Dependency root : roots) {
            Resolution.ResolvedModule decided = main.modules().get(root.packageKey());
            if (decided == null
                    || root.isWorkspace()
                    || !(root.version() instanceof VersionSelector.Exact exact)
                    || exact.version().equals(decided.version())) {
                out.add(root);
                continue;
            }
            overrides.add(root.module() + " is pinned to " + exact.version()
                    + " in a test scope, but the main graph resolves " + decided.version()
                    + " and the test classpath carries main's version: the lock writes " + decided.version()
                    + " for both scopes; move the pin to a main scope or drop it");
            out.add(root.withVersion(VersionSelector.parse("=" + decided.version())));
        }
        return out;
    }

    /**
     * One sentence per module the test graph decided at a version other than main's, naming the
     * test dependencies whose edges hold it there. The test classpath is the main classpath plus the
     * test rows and main's row wins where both name a module, so the test graph's row is content
     * nothing reads; a pin that would do this is rewritten before the solve ({@link #mainGoverned}),
     * so what arrives here is a floor a test dependency's POM declared, which the solve honours in
     * its own graph the way Maven's nearest-wins would not.
     */
    private void noteDeadDuals(Resolution test, Resolution main) {
        for (Resolution.ResolvedModule decided : test.modules().values()) {
            Resolution.ResolvedModule mains = main.modules().get(decided.module());
            if (mains == null || mains.version().equals(decided.version())) continue;
            String ref = decided.coord();
            List<String> holders = new ArrayList<>();
            for (Resolution.ResolvedModule holder : test.modules().values()) {
                if (!holder.deps().contains(ref)) continue;
                String declared = holder.declared().get(ref);
                holders.add(
                        ga(holder.module()) + "@" + holder.version() + (declared == null ? "" : " (" + declared + ")"));
            }
            String module = ga(decided.module());
            overrides.add(module + " resolves " + mains.version() + " in the main graph and " + decided.version()
                    + " in the test graph" + (holders.isEmpty() ? "" : ", held there by " + String.join(", ", holders))
                    + "; the test classpath carries main's " + mains.version() + ", so the test row at "
                    + decided.version() + " is content nothing reads: declare " + module + " at " + decided.version()
                    + " in a main scope, or use a test dependency that accepts " + mains.version());
        }
    }

    /** {@code group:artifact} of a Maven package key; any other key as it is. */
    private static String ga(String packageKey) {
        return PackageId.isMavenPackageKey(packageKey)
                ? PackageId.parse(packageKey).ga()
                : packageKey;
    }

    /**
     * @param inheritedPins exact pins of an earlier graph whose classpath this graph's classpath
     *     contains; they govern this graph's edges the way its own roots do under {@code nearest},
     *     where the graph's own exact roots take precedence
     */
    private Resolution resolve(
            List<Dependency> roots, Map<String, String> inheritedPins, Map<String, String> prefs, LockProgress progress)
            throws IOException, InterruptedException {
        if (roots.isEmpty()) return new Resolution(Map.of());
        if (resolverOverride != null) return resolverOverride.resolve(roots);
        MavenPackageSource sharedSource =
                Objects.requireNonNull(this.sharedSource, "a solve without an override needs its shared source");
        sharedSource.setLockedVersionPrefs(prefs);
        sharedSource.setSnapshotPackages(snapshotModules(roots));
        Map<String, String> exact = exactRoots(roots);
        Map<String, String> wanted = new LinkedHashMap<>(exact);
        inheritedPins.forEach(wanted::putIfAbsent);
        sharedSource.setExactRoots(wanted);
        // Nearest-wins is a per-graph fact: a test-only pin has no say on the main classpath. A
        // pin inherited from main governs here under both policies: main's classpath already
        // fixed the version, and its own graph judged the pin against main's edges.
        Map<String, String> pins = new LinkedHashMap<>(pinPolicy == PinPolicy.NEAREST ? exact : Map.of());
        for (var e : inheritedPins.entrySet()) if (!exact.containsKey(e.getKey())) pins.put(e.getKey(), e.getValue());
        sharedSource.setNearestPins(pins);
        // exclusion state is per-graph; main's clean paths must not bleed into
        // the test/processor solves.
        sharedSource.resetSolveScopedState();
        return new PubGrubResolver(sharedSource, pomBuilder, kmp)
                .withOnDecision(progress::graphPackage)
                .resolve(roots);
    }

    /** {@code group:artifact → version} for every root declared with an exact pin. */
    private static Map<String, String> exactRoots(List<Dependency> roots) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Dependency d : roots) {
            if (d.isWorkspace()) continue;
            if (d.version() instanceof VersionSelector.Exact exact) out.putIfAbsent(d.module(), exact.version());
        }
        return out;
    }

    /**
     * The {@code group:artifact} keys among {@code roots} that were declared {@code snapshot} — the
     * only selector that opts into pre-releases.
     */
    private static Set<String> snapshotModules(List<Dependency> roots) {
        Set<String> out = new LinkedHashSet<>();
        for (Dependency d : roots) {
            if (d.version() instanceof VersionSelector.Snapshot) out.add(d.module());
        }
        return out;
    }
}
