// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver.pubgrub;

import cc.jumpkick.resolve.ResolveProfile;
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
import org.jspecify.annotations.Nullable;

/**
 * PubGrub version solver: root deps + {@link PackageSource} → package → version map. Constraints
 * intern onto {@link VersionUniverse}/{@link AllowedSet} bitsets; budgets via {@code
 * JK_RESOLVE_MAX_DECISIONS} / {@code JK_RESOLVE_TIMEOUT_MS}.
 *
 * <p>When the positive constraint is an exact singleton, or the source has a soft-prefer pin that
 * already satisfies the constraint, seed a singleton {@link VersionUniverse} without calling {@link
 * PackageSource#versions}. Expand to the full advertised list only when that seed cannot produce a
 * viable candidate.
 *
 * <p><strong>Anti-loop:</strong> correct PubGrub learning should never re-enter a decision
 * assignment that already conflicted. Large AndroidX/BOM graphs can still thrash (broken POM
 * floors, incomplete universes, prefer-pin churn). Watermarks and step budgets make that fail
 * closed instead of spinning for minutes:
 *
 * <ul>
 *   <li>every budget check counts a <em>step</em> (propagation storms included, not only decides)
 *   <li>re-entering a decision map fingerprint that previously conflicted (cleared on universe
 *       expand, which can invalidate premature conflicts)
 * </ul>
 */
public class PubGrubSolver {

    /** Default max {@link PartialSolution#decide} calls per solve (env {@code JK_RESOLVE_MAX_DECISIONS}). */
    public static final int DEFAULT_MAX_DECISIONS = 100_000;

    /** Default wall-clock budget in ms; {@code 0} = unlimited (env {@code JK_RESOLVE_TIMEOUT_MS}). */
    public static final long DEFAULT_TIMEOUT_MS = 120_000L;

    /**
     * Multiplier on {@code maxDecisions} for total solver steps (outer loops, propagation rounds,
     * conflict-resolution iterations). Propagation-only storms never increment {@code
     * decisionCount} — steps close that hole.
     */
    public static final int STEPS_PER_DECISION = 16;

    private static final int AVAILABLE_SAMPLE = 12;

    protected final PackageSource source;

    /** Cached discrete version lists per package for the duration of one {@link #solve}. */
    private final Map<String, VersionUniverse> universes = new HashMap<>();

    /**
     * Packages whose universe was seeded from an exact constraint or soft-prefer pin without a full
     * {@link PackageSource#versions} load. Expanded once if the seed has no viable candidate.
     */
    private final Set<String> lazyUniverses = new HashSet<>();

    /** Packages whose universe is the compact {@code versions()} list — widenable. */
    private final Set<String> cappedUniverses = new HashSet<>();

    protected final PartialSolution solution;
    protected final List<Incompatibility> incompatibilities = new ArrayList<>();

    /**
     * Propagation watch index: every incompatibility keyed by each package its terms mention.
     * Without this, each propagation round rescans the full list (quadratic on large BOM graphs).
     */
    private final Map<String, List<Incompatibility>> incompatibilitiesByPackage = new LinkedHashMap<>();

    protected @Nullable String rootPkg;

    private final int maxDecisions;
    private final int maxSteps;
    private final long deadlineNanos; // Long.MAX_VALUE = unlimited

    private int decisionCount;
    private int stepCount;

    /**
     * Fingerprints of decision maps that already caused a conflict. Re-entering one means learning
     * failed to exclude that assignment (loop). Cleared when a universe expands (prior conflicts may
     * have been cap artifacts).
     */
    /** Decision-map fingerprint at a conflict → how many incompatibilities were known then. */
    private final Map<Long, Integer> conflictedDecisionFingerprints = new HashMap<>();

    /**
     * Optional progress hook fired after each successful non-root {@link PartialSolution#decide}.
     * Listener must be cheap/thread-safe if shared.
     */
    private @Nullable BiConsumer<String, String> onDecision;

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
        // Saturate on overflow for huge maxDecisions test seams.
        long steps = (long) maxDecisions * STEPS_PER_DECISION;
        this.maxSteps = steps > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) steps;
        this.deadlineNanos = timeoutMs <= 0 ? Long.MAX_VALUE : System.nanoTime() + timeoutMs * 1_000_000L;
    }

    /**
     * Wide modeload full advertised histories up front instead of compact
     * lists and lazy preferred-singleton seeds. Used for the one bounded retry after an unsat
     * verdict that involved potentially-incomplete universes — conflict resolution can derive
     * root-level unsat from capped candidate lists without ever revisiting a decision, so the
     * decision-time widen hooks alone cannot recover those graphs.
     */
    /** Temporary resolution trace switch for the debug test. */
    private boolean wideUniverses;

    /** True when any universe was seeded from a compact list or preferred singleton. */
    private boolean usedCompactUniverse;

    public PubGrubSolver withWideUniverses() {
        this.wideUniverses = true;
        return this;
    }

    /** An unsat verdict from this solver might be a cap artifact — worth one wide retry. */
    public boolean maybeIncomplete() {
        return usedCompactUniverse;
    }

    /** Progress hook for live graph ticks during solve (LockOrchestrator /. */
    /** The root package {@link #solve} named; every learned clause about it is spelled against this. */
    private String root() {
        return Objects.requireNonNull(rootPkg, "solve() names the root before anything reads it");
    }

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
        long solveT0 = ResolveProfile.on() ? System.nanoTime() : 0L;
        this.rootPkg = rootPkg;
        this.decisionCount = 0;
        this.stepCount = 0;
        this.conflictedDecisionFingerprints.clear();
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
        noteDecision(rootPkg, rootVersion);

        String next = rootPkg;
        while (next != null) {
            checkBudget();
            propagate(next);
            next = makeDecision();
        }
        if (ResolveProfile.on()) {
            ResolveProfile.solve(System.nanoTime() - solveT0);
        }
        return solution.decisions();
    }

    private void checkBudget() {
        // Count every entry: unit-prop rounds and conflict-resolution steps, not only decides.
        stepCount++;
        if (stepCount > maxSteps) {
            throwBudget("exceeded solver step budget ("
                    + stepCount
                    + " steps, limit "
                    + maxSteps
                    + "); set JK_RESOLVE_MAX_DECISIONS to raise");
        }
        if (decisionCount > maxDecisions) {
            throwBudget("exceeded max decisions (" + maxDecisions + "); set JK_RESOLVE_MAX_DECISIONS to raise");
        }
        if (deadlineNanos != Long.MAX_VALUE && System.nanoTime() > deadlineNanos) {
            throwBudget("exceeded resolve time budget; set JK_RESOLVE_TIMEOUT_MS to raise (0 = unlimited)");
        }
    }

    private void throwBudget(String reason) {
        Incompatibility inco = new Incompatibility(
                List.of(Term.positive(root(), VersionSet.EMPTY)), new Incompatibility.Cause.BudgetExceeded(reason));
        throw new UnsatisfiableException(inco);
    }

    /**
     * After {@link PartialSolution#decide}, fail closed if this full decision map already conflicted
     * (learning failed to exclude it).
     */
    private void noteDecision(String pkg, String version) {
        long fp = decisionFingerprint();
        Integer known = conflictedDecisionFingerprints.get(fp);
        // Re-entering a decision map that conflicted is ordinary search when a clause was learned in
        // between — the learned clause is what steers the next decisions elsewhere. Only the same
        // map under the same incompatibility set is a loop: nothing changed, nothing will.
        if (known != null && known == incompatibilities.size()) {
            throwBudget("solver loop: re-entered conflicted decision assignment (watermark); last decide "
                    + pkg
                    + "@"
                    + version);
        }
    }

    /**
     * Mark the current decision map as known-unsat. Multiple incompatibilities may fire on the same
     * assignment before a real backtrack — only {@link #noteDecision} fails closed when decides
     * rebuild this map later.
     */
    private void watermarkConflict() {
        conflictedDecisionFingerprints.put(decisionFingerprint(), incompatibilities.size());
    }

    /** Stable fingerprint of the current package→version decision map (TreeMap order). */
    private long decisionFingerprint() {
        long h = 0xcbf29ce484222325L; // FNV-1a offset basis
        // decisionByPackage is a TreeMap — iteration is sorted by package key.
        for (Map.Entry<String, String> e : solution.decisionsUnsorted().entrySet()) {
            h = fnv1a(h, e.getKey());
            h = fnv1a(h, "\0");
            h = fnv1a(h, e.getValue());
            h = fnv1a(h, "\n");
        }
        return h;
    }

    private static long fnv1a(long h, String s) {
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }

    // --- unit propagation --------------------------------------------------

    protected void propagate(String changedPackage) throws IOException, InterruptedException {
        Set<String> changed = new LinkedHashSet<>();
        changed.add(changedPackage);

        while (!changed.isEmpty()) {
            checkBudget();
            String pkg = removeFirst(changed);
            for (Incompatibility inco : new ArrayList<>(incompatibilitiesByPackage.getOrDefault(pkg, List.of()))) {
                PartialSolution.Relation rel = solution.relationTo(inco);
                switch (rel.kind()) {
                    case SATISFIED -> {
                        // Paper §6: the learned incompatibility is almost satisfied by the
                        // backtracked solution, so its packages join the propagation queue — the
                        // queue this loop was draining never named the package whose version the
                        // conflict just ruled out, and the next decision would pick it again.
                        Incompatibility learned = handleConflict(inco);
                        for (Term term : (learned == null ? inco : learned).terms()) changed.add(term.pkg());
                    }
                    case ALMOST_SATISFIED -> {
                        Term derived = Objects.requireNonNull(rel.unsatisfied(), "ALMOST_SATISFIED names its term")
                                .invert();
                        // satisfies() itself requires a positive commitment for positive terms
                        // , so a derivation that would first mark presence never skips.
                        if (solution.satisfies(derived)) continue;
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
     * Conflict resolution + backtracking, per PubGrub paper §6. Returns the incompatibility learned
     * and recorded at the backtrack point, so the caller can resume propagation on its packages.
     */
    protected @Nullable Incompatibility handleConflict(Incompatibility inco) throws IOException, InterruptedException {
        // A conflict is judged over the packages' universes, and a lazy singleton or capped list is
        // an incomplete one: seeded by a constraint the stack may since have dropped, or by a window
        // that never held every version, it narrows a package past what the assignments say and
        // lets a clause that should have derived read as satisfied. Resolution then walks stack
        // prefixes against the same universe and names the wrong satisfier. Complete every universe
        // first; a conflict that does not survive that was an artifact, and there is nothing to learn.
        if (widenIncompleteUniverses()
                && solution.relationTo(inco).kind() != PartialSolution.IncompatibilityRelation.SATISFIED) {
            return null;
        }
        // Once per conflict entry (not per resolution step): the decision map at the conflict is
        // unsat. Resolution may walk several derived incompatibilities before backtracking.
        watermarkConflict();
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
                return current;
            }

            Incompatibility prior = ((PartialSolution.Assignment.Derivation) step.mostRecent).cause();
            current = resolveIncompatibilities(current, prior, step.mostRecentTerm, step.mostRecent.term());
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
            } else {
                // Paper §6: the previous satisfier's level, whatever it is. Skipping a satisfier at
                // the most recent one's own level left previousLevel at the root, so the solver
                // backtracked to level 1 with the unresolved incompatibility instead of resolving
                // it against the prior cause — and relearned the same clause every round.
                previousLevel = Math.max(previousLevel, satisfier.decisionLevel());
            }
        }
        // The previous satisfier may live in the pivot's own package: when the satisfier's term
        // covers the pivot only together with earlier assignments of that package (`p *` narrowed
        // by two `¬p {v}` derivations, say), the last of those is what the paper's definition
        // names, and its level decides between resolving and backjumping. Ignoring it backjumped
        // to the root with the unresolved clause and relearned it every round.
        // an incompatibility has at least one term, so the loop above found a satisfier
        Term pivotTerm = Objects.requireNonNull(mostRecentTerm);
        PartialSolution.Assignment pivot = Objects.requireNonNull(mostRecent);
        PartialSolution.Assignment previous = satisfierGiven(pivotTerm, pivot);
        if (previous != null) previousLevel = Math.max(previousLevel, previous.decisionLevel());
        return new ResolutionStep(pivotTerm, pivot, previousLevel);
    }

    private PartialSolution.Assignment findSatisfier(Term term) {
        PartialSolution.Assignment satisfier = satisfierGiven(term, null);
        if (satisfier == null) {
            throw new IllegalStateException("term not actually satisfied: " + term + " in " + solution.assignments());
        }
        return satisfier;
    }

    /**
     * The earliest assignment of the term's package whose prefix — together with {@code given}
     * when present — satisfies {@code term}; null when none does. With {@code given} null this is
     * the paper's satisfier; with the satisfier itself as {@code given} it is the previous
     * satisfier, the assignment the satisfier needed alongside it (null when it needed none).
     *
     * <p>A POSITIVE term needs a positive assignment in the prefix before it can be satisfied —
     * raw set intersection loses positivity, and the reference algorithm's term intersection
     * (negative ∩ positive = positive) never lets negative-only narrowing satisfy a positive term.
     * Without this the satisfier could land on an earlier negative assignment and compute a
     * too-shallow backjump level.
     */
    private PartialSolution.@Nullable Assignment satisfierGiven(Term term, PartialSolution.@Nullable Assignment given) {
        boolean sawPositive = given != null && given.term().positive();
        int before = given == null ? Integer.MAX_VALUE : given.globalIndex();
        VersionUniverse u = universes.get(term.pkg());
        if (u != null) {
            AllowedSet target = u.project(term.effectiveVersions());
            AllowedSet accumulated =
                    given == null ? u.all() : u.project(given.term().effectiveVersions());
            if (given != null && (!term.positive() || sawPositive) && accumulated.subsetOf(target)) return null;
            for (PartialSolution.Assignment a : solution.assignments()) {
                if (!a.term().pkg().equals(term.pkg()) || a.globalIndex() >= before) continue;
                sawPositive |= a.term().positive();
                accumulated = accumulated.intersect(u.project(a.term().effectiveVersions()));
                if ((!term.positive() || sawPositive) && accumulated.subsetOf(target)) return a;
            }
            return null;
        }

        VersionSet accumulated = given == null ? VersionSet.ALL : given.term().effectiveVersions();
        if (given != null && (!term.positive() || sawPositive) && accumulated.subsetOf(term.effectiveVersions())) {
            return null;
        }
        for (PartialSolution.Assignment a : solution.assignments()) {
            if (!a.term().pkg().equals(term.pkg()) || a.globalIndex() >= before) continue;
            sawPositive |= a.term().positive();
            accumulated = accumulated.intersect(a.term().effectiveVersions());
            if ((!term.positive() || sawPositive) && accumulated.subsetOf(term.effectiveVersions())) return a;
        }
        return null;
    }

    /**
     * Paper §6 resolution: the union of both incompatibilities' terms minus the pivot package —
     * plus, when the satisfying assignment only partially satisfies the pivot term, the residual
     * {@code ¬(satisfier ∩ ¬pivot)}. Dropping the pivot outright there learns a clause that holds
     * only under the versions the satisfier ruled out, and the solver then excludes candidates the
     * conflict never touched.
     */
    private Incompatibility resolveIncompatibilities(Incompatibility a, Incompatibility b, Term pivot, Term satisfier) {
        LinkedHashMap<String, Term> merged = new LinkedHashMap<>();
        for (Incompatibility source : List.of(a, b)) {
            for (Term term : source.terms()) {
                if (term.pkg().equals(pivot.pkg())) continue;
                merged.merge(term.pkg(), term, Term::intersect);
            }
        }
        if (!coversInUniverse(satisfier, pivot)) {
            Term residual = satisfier.intersect(pivot.invert()).invert();
            if (!residual.isEmpty()) merged.merge(pivot.pkg(), residual, Term::intersect);
        }
        List<Term> survivors = new ArrayList<>();
        for (Term t : merged.values()) {
            if (!t.effectiveVersions().isEmpty()) survivors.add(t);
        }
        if (survivors.isEmpty()) {
            survivors = List.of(Term.positive(root(), VersionSet.EMPTY));
        }
        return new Incompatibility(survivors, new Incompatibility.Cause.Derived(a, b));
    }

    /**
     * Whether {@code satisfier} alone satisfies {@code pivot} — judged over the package's bound
     * universe when there is one, so a range that names no advertised version does not count as a
     * difference and spawn a vacuous residual that resolution could never discharge. A negative
     * satisfier never covers a positive pivot: the pivot also asserts the package is selected, which
     * only a positive assignment can supply, so the residual must keep that presence in the learned
     * clause — dropping it learned "p0 = 1.0 or p2 = 1.0" from two clauses that only bound p3.
     */
    private boolean coversInUniverse(Term satisfier, Term pivot) {
        if (pivot.positive() && !satisfier.positive()) return false;
        VersionUniverse u = universes.get(pivot.pkg());
        if (u != null) {
            return u.project(satisfier.effectiveVersions()).subsetOf(u.project(pivot.effectiveVersions()));
        }
        return satisfier.relation(pivot) == Term.Relation.SATISFIES;
    }

    private boolean isFailure(Incompatibility inco) {
        if (inco.terms().isEmpty()) return true;
        return inco.terms().size() == 1 && inco.terms().getFirst().pkg().equals(root());
    }

    // --- decisions ---------------------------------------------------------

    protected @Nullable String makeDecision() throws IOException, InterruptedException {
        // iterate the assignment stack once without copying into a TreeMap decisions
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
            if (solution.hasNoCandidates(pkg) && widenable(pkg)) {
                expandUniverse(pkg);
            }

            String pick = solution.hasNoCandidates(pkg) ? null : solution.choosePreferred(pkg);
            if (pick == null) {
                // Diagnostics: if we never widened, expand once for a useful sample.
                if (widenable(pkg)) {
                    expandUniverse(pkg);
                }
                VersionUniverse known = universes.get(pkg);
                boolean unknownPackage = known == null || known.size() == 0;
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
                if (widenable(pkg)) {
                    expandUniverse(pkg);
                }
                // Exact pin that was never advertised (or only candidate failed): NoVersions gives
                // better diagnostics ("available: …") than Unavailable alone.
                if (solution.hasNoCandidates(pkg)) {
                    VersionUniverse known = universes.get(pkg);
                    boolean unknownPackage = known == null || known.size() == 0;
                    VersionSet allowed = solution.positiveSet(pkg);
                    if (allowed.isEmpty()) allowed = VersionSet.ALL;
                    addIncompatibility(new Incompatibility(
                            List.of(Term.positive(pkg, allowed)),
                            new Incompatibility.Cause.NoVersions(pkg, allowed, unknownPackage, sampleAvailable(pkg))));
                    return pkg;
                }
                addIncompatibility(new Incompatibility(
                        List.of(Term.positive(pkg, VersionSet.exact(pick))),
                        new Incompatibility.Cause.Unavailable(pkg, pick, e.getMessage())));
                return pkg;
            }
            solution.decide(pkg, pick);
            decisionCount++;
            noteDecision(pkg, pick);
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
            } else if (wideUniverses) {
                List<String> versions = source.expandedVersions(pkg);
                if (versions.size() > MAX_EXPANDED_VERSIONS) {
                    versions = List.copyOf(versions.subList(0, MAX_EXPANDED_VERSIONS));
                }
                universes.put(pkg, VersionUniverse.of(pkg, versions));
            } else {
                Optional<String> preferred = source.preferredVersion(pkg);
                if (preferred.isPresent() && positive.contains(preferred.get())) {
                    universes.put(pkg, VersionUniverse.of(pkg, List.of(preferred.get())));
                    lazyUniverses.add(pkg);
                    usedCompactUniverse = true;
                } else {
                    List<String> versions = source.versions(pkg);
                    universes.put(pkg, VersionUniverse.of(pkg, versions));
                    cappedUniverses.add(pkg); // compact list — widenable on exhaustion
                    usedCompactUniverse = true;
                }
            }
        }
        solution.bindUniverse(pkg);
    }

    /**
     * Replace a lazy singleton with the advertised list and re-project constraints.
     *
     * <p>filter to versions that satisfy the package's positive constraint, and cap the
     * list. Full maven-metadata histories (hundreds of releases) made AllowedSet/BitSet work and
     * soft-prefer scans dominate CPU on large BOM graphs once any pin failed.
     */
    private void expandUniverse(String pkg) throws IOException, InterruptedException {
        boolean wasLazy = lazyUniverses.remove(pkg);
        boolean wasCapped = cappedUniverses.remove(pkg);
        if (!wasLazy && !wasCapped) return;
        // Widen from the FULL advertised historyversions is compacted for the
        // happy path, and re-reading it here left MAX_EXPANDED_VERSIONS dead — a range below
        // the compact candidates (or backtracking past them) hard-failed a satisfiable graph.
        List<String> versions = source.expandedVersions(pkg);
        // Cap long metadata histories. Do not filter against the continuous positive set
        // here: after a failed soft-prefer pin the discrete rebind must still see every advertised
        // candidate the pin was chosen from, or Unavailable(pin) can empty the domain incorrectly.
        if (versions.size() > MAX_EXPANDED_VERSIONS) {
            versions = List.copyOf(versions.subList(0, MAX_EXPANDED_VERSIONS));
        }
        universes.put(pkg, VersionUniverse.of(pkg, versions));
        solution.rebindAfterUniverseExpand(pkg);
        // Prior conflicts may have been artifacts of a singleton/compact candidate list. Expanding
        // invalidates those watermarks so a genuine wider solve can rebuild the same decides.
        conflictedDecisionFingerprints.clear();
    }

    /** Expand every lazy singleton and capped universe; true when any changed. */
    private boolean widenIncompleteUniverses() throws IOException, InterruptedException {
        List<String> incomplete = new ArrayList<>(lazyUniverses);
        incomplete.addAll(cappedUniverses);
        for (String pkg : incomplete) expandUniverse(pkg);
        return !incomplete.isEmpty();
    }

    /** A universe that can still grow: a lazy singleton or a compact (capped) candidate list. */
    private boolean widenable(String pkg) {
        return lazyUniverses.contains(pkg) || cappedUniverses.contains(pkg);
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
