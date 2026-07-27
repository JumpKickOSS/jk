// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver.pubgrub;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * PubGrub version solver: root deps + {@link PackageSource} → package → version map. Constraints
 * intern onto {@link VersionUniverse}/{@link AllowedSet} bitsets; budgets via {@code
 * JK_RESOLVE_MAX_DECISIONS} / {@code JK_RESOLVE_TIMEOUT_MS}.
 *
 * <p>JK-1088: when the positive constraint is an exact singleton, or the source has a soft-prefer
 * pin that already satisfies the constraint, seed a singleton {@link VersionUniverse} without
 * calling {@link PackageSource#versions}. Expand to the full advertised list only when that seed
 * cannot produce a viable candidate.
 */
public class PubGrubSolver {

    /** Default max {@link PartialSolution#decide} calls per solve (env {@code JK_RESOLVE_MAX_DECISIONS}). */
    public static final int DEFAULT_MAX_DECISIONS = 100_000;

    /** Default wall-clock budget in ms; {@code 0} = unlimited (env {@code JK_RESOLVE_TIMEOUT_MS}). */
    public static final long DEFAULT_TIMEOUT_MS = 120_000L;

    private static final int AVAILABLE_SAMPLE = 12;

    protected final PackageSource source;

    /** Cached discrete version lists per package for the duration of one {@link #solve}. */
    private final Map<String, VersionUniverse> universes = new HashMap<>();

    /**
     * Packages whose universe was seeded from an exact constraint or soft-prefer pin without a full
     * {@link PackageSource#versions} load. Expanded once if the seed has no viable candidate.
     */
    private final Set<String> lazyUniverses = new HashSet<>();

    protected final PartialSolution solution;
    protected final List<Incompatibility> incompatibilities = new ArrayList<>();

    /**
     * Propagation watch index: every incompatibility keyed by each package its terms mention.
     * Without this, each propagation round rescans the full list (quadratic on large BOM graphs).
     */
    private final Map<String, List<Incompatibility>> incompatibilitiesByPackage = new LinkedHashMap<>();

    protected String rootPkg;

    private final int maxDecisions;
    private final long deadlineNanos; // Long.MAX_VALUE = unlimited

    private int decisionCount;
    private int loopCount;

    /**
     * Optional progress hook (JK-1091): fired after each successful non-root {@link
     * PartialSolution#decide}. Listener must be cheap/thread-safe if shared.
     */
    private BiConsumer<String, String> onDecision;

    public PubGrubSolver(PackageSource source) {
        this(source, envMaxDecisions(), envTimeoutMs());
    }

    /** Test / tuning seam with explicit budgets. {@code timeoutMs <= 0} means no wall-clock limit. */
    public PubGrubSolver(PackageSource source, int maxDecisions, long timeoutMs) {
        this.source = Objects.requireNonNull(source, "source");
        this.solution = new PartialSolution(universes);
        if (maxDecisions <= 0) {
            throw new IllegalArgumentException("maxDecisions must be positive: " + maxDecisions);
        }
        this.maxDecisions = maxDecisions;
        this.deadlineNanos = timeoutMs <= 0 ? Long.MAX_VALUE : System.nanoTime() + timeoutMs * 1_000_000L;
    }

    /** Progress hook for live graph ticks during solve (LockOrchestrator / JK-1091). */
    public PubGrubSolver withOnDecision(BiConsumer<String, String> onDecision) {
        this.onDecision = onDecision;
        return this;
    }

    private static int envMaxDecisions() {
        String v = System.getenv("JK_RESOLVE_MAX_DECISIONS");
        if (v == null || v.isBlank()) return DEFAULT_MAX_DECISIONS;
        try {
            int n = Integer.parseInt(v.trim());
            return n > 0 ? n : DEFAULT_MAX_DECISIONS;
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_DECISIONS;
        }
    }

    private static long envTimeoutMs() {
        String v = System.getenv("JK_RESOLVE_TIMEOUT_MS");
        if (v == null || v.isBlank()) return DEFAULT_TIMEOUT_MS;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_TIMEOUT_MS;
        }
    }

    /**
     * @param rootPkg name of the root project (e.g. {@code com.example:widget})
     * @param rootVersion version of the root project
     * @param rootDeps positive {@link Term}s — the user-declared dependencies
     */
    public Map<String, String> solve(String rootPkg, String rootVersion, List<Term> rootDeps)
            throws IOException, InterruptedException {
        this.rootPkg = rootPkg;
        this.decisionCount = 0;
        this.loopCount = 0;
        Term rootTerm = Term.positive(rootPkg, VersionSet.exact(rootVersion));

        for (Term dep : rootDeps) {
            addIncompatibility(new Incompatibility(
                    List.of(rootTerm, dep.invert()), new Incompatibility.Cause.Dependency(rootTerm, dep)));
        }

        // Root is not fetched from the package source — seed a singleton universe for bit space.
        universes.put(rootPkg, VersionUniverse.of(rootPkg, List.of(rootVersion)));
        solution.bindUniverse(rootPkg);
        solution.decide(rootPkg, rootVersion);
        decisionCount++;

        String next = rootPkg;
        while (next != null) {
            checkBudget();
            propagate(next);
            next = makeDecision();
            loopCount++;
        }
        return solution.decisions();
    }

    private void checkBudget() {
        if (decisionCount > maxDecisions) {
            throwBudget("exceeded max decisions (" + maxDecisions + "); set JK_RESOLVE_MAX_DECISIONS to raise");
        }
        if (deadlineNanos != Long.MAX_VALUE && System.nanoTime() > deadlineNanos) {
            throwBudget("exceeded resolve time budget; set JK_RESOLVE_TIMEOUT_MS to raise (0 = unlimited)");
        }
        if (loopCount > maxDecisions * 4L) {
            // Propagation/backtrack storms without new decisions.
            throwBudget("exceeded solver iteration budget (" + loopCount + " loops)");
        }
    }

    private void throwBudget(String reason) {
        Incompatibility inco = new Incompatibility(
                List.of(Term.positive(rootPkg, VersionSet.EMPTY)), new Incompatibility.Cause.BudgetExceeded(reason));
        throw new UnsatisfiableException(inco);
    }

    // --- unit propagation --------------------------------------------------

    protected void propagate(String changedPackage) {
        Set<String> changed = new LinkedHashSet<>();
        changed.add(changedPackage);

        while (!changed.isEmpty()) {
            checkBudget();
            String pkg = removeFirst(changed);
            for (Incompatibility inco : new ArrayList<>(incompatibilitiesByPackage.getOrDefault(pkg, List.of()))) {
                PartialSolution.Relation rel = solution.relationTo(inco);
                switch (rel.kind()) {
                    case SATISFIED -> handleConflict(inco);
                    case ALMOST_SATISFIED -> {
                        Term derived = rel.unsatisfied().invert();
                        boolean marksPresence = derived.positive() && !solution.hasPositiveTerm(derived.pkg());
                        if (solution.satisfies(derived) && !marksPresence) continue;
                        solution.derive(derived, inco);
                        changed.add(derived.pkg());
                    }
                    case INCONCLUSIVE -> {
                        // nothing
                    }
                }
            }
        }
    }

    /**
     * Conflict resolution + backtracking, per PubGrub paper §6.
     */
    protected void handleConflict(Incompatibility inco) {
        Incompatibility current = inco;
        while (true) {
            checkBudget();
            if (isFailure(current)) {
                throw new UnsatisfiableException(current);
            }

            ResolutionStep step = computeResolutionStep(current);

            if (step.mostRecent instanceof PartialSolution.Assignment.Decision
                    || step.previousLevel < step.mostRecent.decisionLevel()) {
                solution.backtrack(step.previousLevel);
                addIncompatibility(current);
                return;
            }

            Incompatibility prior = ((PartialSolution.Assignment.Derivation) step.mostRecent).cause();
            current = resolveIncompatibilities(current, prior, step.mostRecentTerm);
        }
    }

    private record ResolutionStep(Term mostRecentTerm, PartialSolution.Assignment mostRecent, int previousLevel) {}

    private ResolutionStep computeResolutionStep(Incompatibility inco) {
        PartialSolution.Assignment mostRecent = null;
        Term mostRecentTerm = null;
        int previousLevel = 1;
        for (Term term : inco.terms()) {
            PartialSolution.Assignment satisfier = findSatisfier(term);
            if (mostRecent == null || satisfier.globalIndex() > mostRecent.globalIndex()) {
                if (mostRecent != null) {
                    previousLevel = Math.max(previousLevel, mostRecent.decisionLevel());
                }
                mostRecent = satisfier;
                mostRecentTerm = term;
            } else if (satisfier.decisionLevel() != mostRecent.decisionLevel()) {
                previousLevel = Math.max(previousLevel, satisfier.decisionLevel());
            }
        }
        return new ResolutionStep(mostRecentTerm, mostRecent, previousLevel);
    }

    private PartialSolution.Assignment findSatisfier(Term term) {
        VersionUniverse u = universes.get(term.pkg());
        if (u != null) {
            AllowedSet target = u.project(term.effectiveVersions());
            AllowedSet accumulated = u.all();
            for (PartialSolution.Assignment a : solution.assignments()) {
                if (!a.term().pkg().equals(term.pkg())) continue;
                accumulated = accumulated.intersect(u.project(a.term().effectiveVersions()));
                if (accumulated.subsetOf(target)) {
                    return a;
                }
            }
            throw new IllegalStateException("term not actually satisfied: " + term + " in " + solution.assignments());
        }

        VersionSet accumulated = VersionSet.ALL;
        for (PartialSolution.Assignment a : solution.assignments()) {
            if (!a.term().pkg().equals(term.pkg())) continue;
            accumulated = accumulated.intersect(a.term().effectiveVersions());
            if (accumulated.subsetOf(term.effectiveVersions())) {
                return a;
            }
        }
        throw new IllegalStateException("term not actually satisfied: " + term + " in " + solution.assignments());
    }

    private Incompatibility resolveIncompatibilities(Incompatibility a, Incompatibility b, Term pivot) {
        LinkedHashMap<String, Term> merged = new LinkedHashMap<>();
        for (Incompatibility source : List.of(a, b)) {
            for (Term term : source.terms()) {
                if (term.pkg().equals(pivot.pkg())) continue;
                merged.merge(term.pkg(), term, Term::intersect);
            }
        }
        List<Term> survivors = new ArrayList<>();
        for (Term t : merged.values()) {
            if (!t.effectiveVersions().isEmpty()) survivors.add(t);
        }
        if (survivors.isEmpty()) {
            survivors = List.of(Term.positive(rootPkg, VersionSet.EMPTY));
        }
        return new Incompatibility(survivors, new Incompatibility.Cause.Derived(a, b));
    }

    private boolean isFailure(Incompatibility inco) {
        if (inco.terms().isEmpty()) return true;
        return inco.terms().size() == 1 && inco.terms().getFirst().pkg().equals(rootPkg);
    }

    // --- decisions ---------------------------------------------------------

    protected String makeDecision() throws IOException, InterruptedException {
        // JK-1202: iterate the assignment stack once without copying into a TreeMap decisions()
        // each time — both were dominant alloc sources on large BOM graphs.
        Set<String> seen = new LinkedHashSet<>();
        for (PartialSolution.Assignment a : solution.assignments()) {
            seen.add(a.term().pkg());
        }
        Map<String, String> decided = solution.decisionsUnsorted();

        for (String pkg : seen) {
            if (decided.containsKey(pkg)) continue;
            if (!solution.hasPositiveTerm(pkg)) continue;

            ensureUniverse(pkg);
            // Lazy singleton may project empty (pref outside constraint) or after Unavailable.
            if (solution.hasNoCandidates(pkg) && lazyUniverses.contains(pkg)) {
                expandUniverse(pkg);
            }

            String pick = solution.hasNoCandidates(pkg) ? null : solution.choosePreferred(pkg);
            if (pick == null) {
                // Diagnostics: if we never loaded metadata, expand once for a useful sample.
                if (lazyUniverses.contains(pkg)) {
                    expandUniverse(pkg);
                }
                boolean unknownPackage = universes.get(pkg).size() == 0;
                VersionSet allowed = solution.positiveSet(pkg);
                if (allowed.isEmpty()) allowed = VersionSet.ALL;
                List<String> available = sampleAvailable(pkg);
                addIncompatibility(new Incompatibility(
                        List.of(Term.positive(pkg, allowed)),
                        new Incompatibility.Cause.NoVersions(pkg, allowed, unknownPackage, available)));
                return pkg;
            }

            List<Term> deps;
            try {
                deps = source.dependencies(pkg, pick);
            } catch (PackageSource.VersionUnavailableException e) {
                // Expand before recording Unavailable so unit-prop can exclude `pick` against a
                // full candidate list (a lazy singleton of only `pick` would mark the inco as
                // already SATISFIED and short-circuit into a false unsatisfiable).
                if (lazyUniverses.contains(pkg)) {
                    expandUniverse(pkg);
                }
                // Exact pin that was never advertised (or only candidate failed): NoVersions gives
                // better diagnostics ("available: …") than Unavailable alone.
                if (solution.hasNoCandidates(pkg)) {
                    boolean unknownPackage = universes.get(pkg).size() == 0;
                    VersionSet allowed = solution.positiveSet(pkg);
                    if (allowed.isEmpty()) allowed = VersionSet.ALL;
                    addIncompatibility(new Incompatibility(
                            List.of(Term.positive(pkg, allowed)),
                            new Incompatibility.Cause.NoVersions(
                                    pkg, allowed, unknownPackage, sampleAvailable(pkg))));
                    return pkg;
                }
                addIncompatibility(new Incompatibility(
                        List.of(Term.positive(pkg, VersionSet.exact(pick))),
                        new Incompatibility.Cause.Unavailable(pkg, pick, e.getMessage())));
                return pkg;
            }
            solution.decide(pkg, pick);
            decisionCount++;
            checkBudget();
            if (onDecision != null) {
                onDecision.accept(pkg, pick);
            }

            Term decisionTerm = Term.positive(pkg, VersionSet.exact(pick));
            for (Term depTerm : deps) {
                addIncompatibility(new Incompatibility(
                        List.of(decisionTerm, depTerm.invert()),
                        new Incompatibility.Cause.Dependency(decisionTerm, depTerm)));
            }
            return pkg;
        }
        return null;
    }

    private List<String> sampleAvailable(String pkg) {
        VersionUniverse u = universes.get(pkg);
        if (u == null || u.size() == 0) return List.of();
        List<String> all = u.versions();
        if (all.size() <= AVAILABLE_SAMPLE) return List.copyOf(all);
        return List.copyOf(all.subList(0, AVAILABLE_SAMPLE));
    }

    /**
     * Bind a discrete universe for {@code pkg}. Prefer a singleton seed (exact constraint or
     * soft-prefer pin) to avoid maven-metadata on the happy path; expand later if needed.
     */
    private void ensureUniverse(String pkg) throws IOException, InterruptedException {
        if (!universes.containsKey(pkg)) {
            VersionSet positive = solution.positiveSet(pkg);
            Optional<String> exact = positive.asExactSingleton();
            if (exact.isPresent()) {
                // Exact pin: singleton is complete for success; no lazy expand needed unless we
                // want metadata samples on failure (handled at NoVersions).
                universes.put(pkg, VersionUniverse.of(pkg, List.of(exact.get())));
                lazyUniverses.add(pkg);
            } else {
                Optional<String> preferred = source.preferredVersion(pkg);
                if (preferred.isPresent() && positive.contains(preferred.get())) {
                    universes.put(pkg, VersionUniverse.of(pkg, List.of(preferred.get())));
                    lazyUniverses.add(pkg);
                } else {
                    List<String> versions = source.versions(pkg);
                    universes.put(pkg, VersionUniverse.of(pkg, versions));
                }
            }
        }
        solution.bindUniverse(pkg);
    }

    /**
     * Replace a lazy singleton with the advertised list and re-project constraints.
     *
     * <p>JK-1202: filter to versions that satisfy the package's positive constraint, and cap the
     * list. Full maven-metadata histories (hundreds of releases) made AllowedSet/BitSet work and
     * soft-prefer scans dominate CPU on large BOM graphs once any pin failed.
     */
    private void expandUniverse(String pkg) throws IOException, InterruptedException {
        if (!lazyUniverses.remove(pkg)) return;
        List<String> versions = source.versions(pkg);
        // Cap long metadata histories (JK-1202). Do not filter against the continuous positive set
        // here: after a failed soft-prefer pin the discrete rebind must still see every advertised
        // candidate the pin was chosen from, or Unavailable(pin) can empty the domain incorrectly.
        if (versions.size() > MAX_EXPANDED_VERSIONS) {
            versions = List.copyOf(versions.subList(0, MAX_EXPANDED_VERSIONS));
        }
        universes.put(pkg, VersionUniverse.of(pkg, versions));
        solution.rebindAfterUniverseExpand(pkg);
    }

    /** Max candidates kept after expand (highest-first order already applied by PackageSource). */
    private static final int MAX_EXPANDED_VERSIONS = 48;

    protected void addIncompatibility(Incompatibility inco) {
        incompatibilities.add(inco);
        Set<String> pkgs = new LinkedHashSet<>();
        for (Term term : inco.terms()) pkgs.add(term.pkg());
        for (String pkg : pkgs) {
            incompatibilitiesByPackage
                    .computeIfAbsent(pkg, k -> new ArrayList<>())
                    .add(inco);
        }
    }

    private static String removeFirst(Set<String> set) {
        var iter = set.iterator();
        String first = iter.next();
        iter.remove();
        return first;
    }
}
