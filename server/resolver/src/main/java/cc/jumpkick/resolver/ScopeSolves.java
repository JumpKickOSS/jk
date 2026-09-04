// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.EffectivePomBuilder;
import java.io.IOException;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The three scope solves, in the order that decides which version wins: {@link #ORDER}. Each graph
 * is seeded with the versions every earlier graph decided, so a module main already chose is
 * preferred by test and processor rather than re-decided; a module reachable only from a later
 * graph is solved fresh. One {@link MavenPackageSource} serves all three so version and dependency
 * caches survive the scope split, while its per-graph exclusion state is reset between them: main's
 * clean paths must not bleed into the test and processor solves.
 */
final class ScopeSolves {

    /** Solve order. A graph's decisions seed every graph after it, never before. */
    static final List<LockRoots.GraphGroup> ORDER =
            List.of(LockRoots.GraphGroup.MAIN, LockRoots.GraphGroup.TEST, LockRoots.GraphGroup.PROCESSOR);

    /** The three resolutions, one per graph. */
    record Solved(Resolution main, Resolution test, Resolution processor) {}

    private final Resolver resolverOverride;
    private final MavenPackageSource sharedSource;
    private final EffectivePomBuilder pomBuilder;
    private final KmpRedirects kmp;

    /**
     * @param resolverOverride a test's stand-in solver, or {@code null} for PubGrub over {@code sharedSource}
     * @param sharedSource the package source shared by all three graphs; {@code null} only with an override
     */
    ScopeSolves(
            Resolver resolverOverride,
            MavenPackageSource sharedSource,
            EffectivePomBuilder pomBuilder,
            KmpRedirects kmp) {
        this.resolverOverride = resolverOverride;
        this.sharedSource = sharedSource;
        this.pomBuilder = pomBuilder;
        this.kmp = kmp;
    }

    /** Solve every graph in {@link #ORDER}, seeding each with {@code lockedVersionPrefs} plus every earlier decision. */
    Solved solve(LockRoots.Roots roots, Map<String, String> lockedVersionPrefs, LockProgress progress)
            throws IOException, InterruptedException {
        Map<String, String> prefs = new HashMap<>(lockedVersionPrefs);
        EnumMap<LockRoots.GraphGroup, Resolution> solved = new EnumMap<>(LockRoots.GraphGroup.class);
        for (LockRoots.GraphGroup graph : ORDER) {
            Resolution resolution = resolve(roots.of(graph), new HashMap<>(prefs), progress);
            progress.noteGraph(resolution);
            // Locked pins and earlier graphs win over this graph's decisions: putIfAbsent, in ORDER.
            for (var e : resolution.modules().entrySet()) {
                prefs.putIfAbsent(e.getKey(), e.getValue().version());
            }
            solved.put(graph, resolution);
        }
        return new Solved(
                solved.get(LockRoots.GraphGroup.MAIN),
                solved.get(LockRoots.GraphGroup.TEST),
                solved.get(LockRoots.GraphGroup.PROCESSOR));
    }

    private Resolution resolve(List<Dependency> roots, Map<String, String> prefs, LockProgress progress)
            throws IOException, InterruptedException {
        if (roots.isEmpty()) return new Resolution(Map.of());
        if (resolverOverride != null) return resolverOverride.resolve(roots);
        sharedSource.setLockedVersionPrefs(prefs);
        sharedSource.setSnapshotPackages(snapshotModules(roots));
        // exclusion state is per-graph; main's clean paths must not bleed into
        // the test/processor solves.
        sharedSource.resetSolveScopedState();
        return new PubGrubResolver(sharedSource, pomBuilder, kmp)
                .withOnDecision(progress::graphPackage)
                .resolve(roots);
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
