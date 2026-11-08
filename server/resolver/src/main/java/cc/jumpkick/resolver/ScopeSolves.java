// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.PackageId;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.EffectivePomBuilder;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
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

    /**
     * The three resolutions, one per graph, the roots the test graph was settled with ({@link
     * TestRoots}), and every {@linkplain ConditionalDependencies conditional dependency} that joined
     * the main or test graph as finally solved.
     */
    record Solved(
            Resolution main, Resolution test, Resolution processor, List<Dependency> testRoots, List<Joined> joined) {
        Solved {
            testRoots = List.copyOf(testRoots);
            joined = List.copyOf(joined);
        }

        /** The {@code added-by} lines of every module a conditional dependency brought in, by package key. */
        Map<String, List<String>> addedBy() {
            Map<String, List<String>> out = new LinkedHashMap<>();
            for (Joined j : joined) {
                List<String> reasons = out.computeIfAbsent(j.dependencyKey(), k -> new ArrayList<>());
                if (!reasons.contains(j.active().reason()))
                    reasons.add(j.active().reason());
            }
            return out;
        }
    }

    /**
     * A conditional dependency in {@code graph}: the declaring module as solved ({@code
     * module@version}), the joined module's package key and its solved {@code module@version}, and
     * the declaration. The declarer of a test-graph join may be a main-graph module: its row then
     * carries the edge, which only a test classpath walk follows, since the joined row is test's.
     */
    record Joined(
            LockRoots.GraphGroup graph,
            String declarer,
            String dependencyKey,
            String dependency,
            ConditionalDependencies.Active active) {

        /** The declaring module's package key. */
        String declarerKey() {
            return active.declarer();
        }
    }

    /**
     * The roots jk adds to the test graph, read off the solved main graph and a solved test graph,
     * which together are the test classpath ({@link
     * TestEngines#injectedRoots(cc.jumpkick.model.JkBuild, java.util.function.Function,
     * java.util.function.Function)}). The test graph is solved again with them only when it does
     * not already hold each at the version it names.
     */
    interface TestRoots {
        List<Dependency> derive(Resolution main, Resolution test);

        /** Adds nothing: the test graph is solved once, with the roots it was given. */
        TestRoots NONE = (main, test) -> List.of();
    }

    /** How many times the test graph may be solved before its derived roots are taken as they stand. */
    private static final int TEST_PASSES = 3;

    private final @Nullable Resolver resolverOverride;
    private final @Nullable MavenPackageSource sharedSource;
    private final EffectivePomBuilder pomBuilder;
    private final KmpRedirects kmp;
    private final Predicate<String> floorPin;
    private final @Nullable ConditionalDependencies conditionals;
    private final TestRoots testRoots;

    /** The {@code group:artifact}s a conditional dependency rooted: their pins are floors. */
    private final Set<String> conditionalRoots = new LinkedHashSet<>();

    /** Every conditional dependency that joined a graph, in the order they joined. */
    private final Set<Joined> joined = new LinkedHashSet<>();

    /** One sentence per test-scope exact pin that gave way to main's version. */
    private final List<String> overrides = new ArrayList<>();

    /**
     * @param resolverOverride a test's stand-in solver, or {@code null} for PubGrub over {@code sharedSource}
     * @param sharedSource the package source shared by all three graphs; {@code null} only with an override
     * @param floorPin which roots' exact pins, by {@code group:artifact}, read as a floor the graph
     *     may raise (steered to the pin where nothing asks higher) rather than as the version
     * @param conditionals the conditional dependencies the main and test graphs follow; {@code null}
     *     when no active plugin declares any
     * @param testRoots the roots the solved test graph calls for beyond the ones it was given
     */
    ScopeSolves(
            @Nullable Resolver resolverOverride,
            @Nullable MavenPackageSource sharedSource,
            EffectivePomBuilder pomBuilder,
            KmpRedirects kmp,
            Predicate<String> floorPin,
            @Nullable ConditionalDependencies conditionals,
            TestRoots testRoots) {
        this.resolverOverride = resolverOverride;
        this.sharedSource = sharedSource;
        this.pomBuilder = pomBuilder;
        this.kmp = kmp;
        this.floorPin = floorPin;
        this.conditionals = conditionals;
        this.testRoots = testRoots;
    }

    /**
     * Solve every graph in {@link #ORDER}, seeding each with {@code lockedVersionPrefs} plus every
     * earlier decision. The test graph is solved again while the roots {@link TestRoots} reads off
     * it are not what it holds.
     */
    Solved solve(LockRoots.Roots roots, Map<String, String> lockedVersionPrefs, LockProgress progress)
            throws IOException, InterruptedException {
        Map<String, String> prefs = new HashMap<>(lockedVersionPrefs);
        Map<String, String> mainPins = exactRoots(roots.main());
        EnumMap<LockRoots.GraphGroup, Resolution> solved = new EnumMap<>(LockRoots.GraphGroup.class);
        List<Dependency> settledTestRoots = roots.test();
        for (LockRoots.GraphGroup graph : ORDER) {
            boolean test = graph == LockRoots.GraphGroup.TEST;
            Map<String, String> inherited = test ? mainPins : Map.of();
            Resolution main = solved.get(LockRoots.GraphGroup.MAIN);
            List<Dependency> graphRoots =
                    test ? mainGoverned(roots.of(graph), Objects.requireNonNull(main), true) : roots.of(graph);
            Resolution resolution = solveGraph(graph, graphRoots, main, inherited, prefs, progress);
            if (test) {
                for (int pass = 1; ; pass++) {
                    List<Dependency> derived = mainGoverned(
                            testRoots.derive(Objects.requireNonNull(main), resolution),
                            Objects.requireNonNull(main),
                            false);
                    if (holds(resolution, derived) || pass == TEST_PASSES) {
                        settledTestRoots = withRoots(roots.test(), derived);
                        break;
                    }
                    graphRoots = withRoots(graphRoots, derived);
                    // The joins of the pass being replaced are not the graph's: the next pass records its own.
                    joined.removeIf(j -> j.graph() == LockRoots.GraphGroup.TEST);
                    resolution = solveGraph(graph, graphRoots, main, inherited, prefs, progress);
                }
                noteDeadDuals(resolution, Objects.requireNonNull(main));
            }
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
                Objects.requireNonNull(solved.get(LockRoots.GraphGroup.PROCESSOR)),
                settledTestRoots,
                new ArrayList<>(joined));
    }

    /** One graph solved from {@code graphRoots}, its conditional dependencies followed (the processor has none). */
    private Resolution solveGraph(
            LockRoots.GraphGroup graph,
            List<Dependency> graphRoots,
            @Nullable Resolution main,
            Map<String, String> inherited,
            Map<String, String> prefs,
            LockProgress progress)
            throws IOException, InterruptedException {
        Resolution resolution = resolve(graphRoots, inherited, new HashMap<>(prefs), progress);
        if (conditionals == null || graph == LockRoots.GraphGroup.PROCESSOR) return resolution;
        Resolution context = graph == LockRoots.GraphGroup.TEST ? main : null;
        return followConditionals(
                conditionals, graph, graphRoots, resolution, context, inherited, new HashMap<>(prefs), progress);
    }

    /**
     * {@code roots} with each of {@code added} in place of the root on the same package, and the
     * rest of {@code added} after them.
     */
    private static List<Dependency> withRoots(List<Dependency> roots, List<Dependency> added) {
        Map<String, Dependency> byKey = new LinkedHashMap<>();
        for (Dependency root : roots) byKey.putIfAbsent(root.packageKey(), root);
        for (Dependency root : added) byKey.put(root.packageKey(), root);
        return new ArrayList<>(byKey.values());
    }

    /** True when {@code resolution} holds every root of {@code roots}, at its version when the root is exact. */
    private static boolean holds(Resolution resolution, List<Dependency> roots) {
        for (Dependency root : roots) {
            Resolution.ResolvedModule held = resolution.modules().get(root.packageKey());
            if (held == null) return false;
            if (root.version() instanceof VersionSelector.Exact exact
                    && !exact.version().equals(held.version())) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@code resolution} solved again with every conditional dependency whose condition its closure
     * (with {@code context}'s, the main graph under the test graph) meets as a root, until a solve
     * brings in no more; each such dependency is then an edge of the module declaring it, so the
     * lock's graph reaches it from there, and its {@code added-by} says why. A module of {@code
     * context} declares too: a main-graph extension whose condition only the test graph meets joins
     * the test graph. A dependency {@code context} already holds is on this graph's classpath
     * through it and is not rooted again.
     */
    private Resolution followConditionals(
            ConditionalDependencies conditionals,
            LockRoots.GraphGroup graph,
            List<Dependency> roots,
            Resolution resolution,
            @Nullable Resolution context,
            Map<String, String> inheritedPins,
            Map<String, String> prefs,
            LockProgress progress)
            throws IOException, InterruptedException {
        Collection<Resolution.ResolvedModule> around =
                context == null ? List.of() : context.modules().values();
        List<Dependency> grown = new ArrayList<>(roots);
        Set<String> rooted = new LinkedHashSet<>();
        while (true) {
            List<ConditionalDependencies.Active> active =
                    conditionals.active(resolution.modules().values(), around);
            boolean added = false;
            for (ConditionalDependencies.Active a : active) {
                String key = a.module();
                if (resolution.modules().containsKey(key)) continue;
                if (context != null && context.modules().containsKey(key)) continue;
                if (!rooted.add(key)) continue;
                Dependency root = conditionals.root(a);
                conditionalRoots.add(root.module());
                grown.add(root);
                added = true;
            }
            if (!added) return withConditionalEdges(graph, resolution, context, active);
            resolution = resolve(grown, inheritedPins, new HashMap<>(prefs), progress);
        }
    }

    /**
     * {@code resolution} with each active conditional dependency it holds an edge of its declarer,
     * when the declarer is in it too, and each recorded as {@link Joined}; a declarer only {@code
     * context} holds gets its edge on its lock row ({@link LockfileAssembler}).
     */
    private Resolution withConditionalEdges(
            LockRoots.GraphGroup graph,
            Resolution resolution,
            @Nullable Resolution context,
            List<ConditionalDependencies.Active> active) {
        if (active.isEmpty()) return resolution;
        Map<String, Resolution.ResolvedModule> modules = new LinkedHashMap<>(resolution.modules());
        for (ConditionalDependencies.Active a : active) {
            Resolution.ResolvedModule dependency = modules.get(a.module());
            if (dependency == null) continue;
            Resolution.ResolvedModule declarer = modules.get(a.declarer());
            Resolution.ResolvedModule declaring = declarer != null || context == null
                    ? declarer
                    : context.modules().get(a.declarer());
            if (declaring == null) continue;
            joined.add(new Joined(graph, declaring.coord(), dependency.module(), dependency.coord(), a));
            if (declarer == null || declarer.deps().contains(dependency.coord())) continue;
            List<String> deps = new ArrayList<>(declarer.deps());
            deps.add(dependency.coord());
            modules.put(
                    a.declarer(),
                    new Resolution.ResolvedModule(
                            declarer.module(), declarer.version(), deps, declarer.declared(), declarer.excluded()));
        }
        return new Resolution(modules);
    }

    /** The pins that gave way to main's version, one sentence each, in root order. */
    List<String> overrides() {
        return List.copyOf(overrides);
    }

    /**
     * The test graph's roots with each exact pin on a module main decided rewritten to main's
     * version. The test classpath is the main classpath plus the test rows, so main's version is the
     * one there whatever the pin asks; writing the pin's version as a test row would give the
     * classpath nothing to read. With {@code say}, each displaced pin is recorded for {@link
     * #overrides}; a root jk adds itself is no pin of the user's to move and says nothing.
     */
    private List<Dependency> mainGoverned(List<Dependency> roots, Resolution main, boolean say) {
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
            String override = root.module() + ": test pin " + exact.version() + " ignored, tests use the main version "
                    + decided.version() + "; move the pin to [dependencies] or drop it";
            if (say) overrides.add(override);
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
            overrides.add(module + ": tests use the main version " + mains.version() + ", not " + decided.version()
                    + (holders.isEmpty() ? "" : " (asked by " + String.join(", ", holders) + ")") + "; declare "
                    + module + " " + decided.version() + " in [dependencies], or use a test dependency that accepts "
                    + mains.version());
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
        Map<String, String> floors = new LinkedHashMap<>();
        exact.forEach((ga, version) -> {
            if (floorPin.test(ga) || conditionalRoots.contains(ga)) floors.put(ga, version);
        });
        if (!floors.isEmpty()) roots = asFloors(roots, floors.keySet());
        // Main's classpath already fixed these versions; a module this graph pins itself keeps its own.
        Map<String, String> governing = new LinkedHashMap<>();
        for (var e : inheritedPins.entrySet()) {
            if (!exact.containsKey(e.getKey())) governing.put(e.getKey(), e.getValue());
        }
        sharedSource.setGoverningVersions(governing);
        // exclusion state is per-graph; main's clean paths must not bleed into
        // the test/processor solves.
        sharedSource.resetSolveScopedState();
        return new PubGrubResolver(sharedSource, pomBuilder, kmp)
                .withPinFloors(floors)
                .withOnDecision(progress::graphPackage)
                .resolve(roots);
    }

    /** {@code roots} with the exact pin of each module in {@code modules} a floor: {@code 1.2} reads as {@code >=1.2}. */
    private static List<Dependency> asFloors(List<Dependency> roots, Set<String> modules) {
        List<Dependency> out = new ArrayList<>(roots.size());
        for (Dependency d : roots) {
            out.add(
                    !d.isWorkspace()
                                    && modules.contains(d.module())
                                    && d.version() instanceof VersionSelector.Exact exact
                            ? d.withVersion(VersionSelector.parse(">=" + exact.version()))
                            : d);
        }
        return out;
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
