// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver.pubgrub;

import cc.jumpkick.resolve.ResolveProfile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * PubGrub assignment stack (decisions + derivations) with decision-level backtracking. Per-package
 * constraints start as {@link VersionSet} and project onto {@link AllowedSet} once the universe is
 * known.
 */
public final class PartialSolution {

    private final List<Assignment> assignments = new ArrayList<>();

    /**
     * The stack split by package, each list in global order. Satisfier search and universe
     * rebinding are questions about one package, so they read that package's assignments rather
     * than the whole stack — on a large BOM graph the stack runs to thousands of entries and those
     * questions are asked on every conflict.
     */
    private final Map<String, List<Assignment>> assignmentsByPackage = new HashMap<>();

    /**
     * Packages some positive term requires and no decision has settled, keyed by the global index
     * of the package's first assignment. The next decision is the earliest-mentioned of them, which
     * is what a scan of the stack in order would find — without the scan.
     */
    private final TreeMap<Integer, String> undecidedPositive = new TreeMap<>();

    /** Live map owned by the solver; entries appear as packages are interned. */
    private final Map<String, VersionUniverse> universes;

    private final Map<String, PackageState> byPackage = new HashMap<>();
    private final Map<String, String> decisionByPackage = new TreeMap<>();
    private int decisionLevel = 0;

    /** Assignments pushed by {@link #decide}/{@link #derive}; replays after a backtrack do not count. */
    private long assignmentsRecorded;

    /** Assignments handed out for scanning, by {@link #assignments()} and {@link #assignmentsFor}. */
    private long assignmentsScanned;

    /**
     * Per-package constraint state. {@code allowed} is non-null once the package has been bound to
     * a {@link VersionUniverse}; after that further narrowing is bitset-only (avoids Union blow-up
     * from repeated exact exclusions). {@code continuous} freezes at the pre-bind snapshot for
     * diagnostics when the bitset becomes empty.
     */
    static final class PackageState {
        VersionSet continuous = VersionSet.ALL;

        @Nullable
        AllowedSet allowed; // null until universe bound

        boolean hasPositive;
        boolean mentioned;

        /** Global index of the package's first assignment; -1 until one is registered. */
        int firstIndex = -1;
    }

    public PartialSolution(Map<String, VersionUniverse> universes) {
        this.universes = Objects.requireNonNull(universes, "universes");
    }

    public sealed interface Assignment {
        Term term();

        int decisionLevel();

        int globalIndex();

        record Decision(Term term, int decisionLevel, int globalIndex) implements Assignment {}

        record Derivation(Term term, int decisionLevel, int globalIndex, Incompatibility cause) implements Assignment {}
    }

    public void decide(String pkg, String version) {
        decisionLevel++;
        Term decision = Term.positive(pkg, VersionSet.exact(version));
        Assignment a = new Assignment.Decision(decision, decisionLevel, assignments.size());
        push(a);
        decisionByPackage.put(pkg, version);
        register(decision, a.globalIndex());
        undecidedPositive.remove(Objects.requireNonNull(byPackage.get(pkg)).firstIndex);
    }

    public void derive(Term term, Incompatibility cause) {
        Assignment a = new Assignment.Derivation(term, decisionLevel, assignments.size(), cause);
        push(a);
        register(term, a.globalIndex());
    }

    private void push(Assignment a) {
        assignments.add(a);
        index(a);
        assignmentsRecorded++;
    }

    private void index(Assignment a) {
        assignmentsByPackage
                .computeIfAbsent(a.term().pkg(), k -> new ArrayList<>())
                .add(a);
    }

    /** Fold {@code t}, recorded at global index {@code at}, into its package's state. */
    private void register(Term t, int at) {
        PackageState s = byPackage.computeIfAbsent(t.pkg(), k -> new PackageState());
        if (s.firstIndex < 0) s.firstIndex = at;
        s.mentioned = true;
        if (t.positive() && !s.hasPositive) {
            s.hasPositive = true;
            if (!decisionByPackage.containsKey(t.pkg())) undecidedPositive.put(s.firstIndex, t.pkg());
        }
        VersionSet effective = t.effectiveVersions();
        // The continuous set narrows on every assignment, bitset or not: satisfies() and
        // positiveSet() read it whenever the bitset is empty — a lazy singleton universe that a
        // later positive term falls outside of — and a snapshot frozen at bind time let unit
        // propagation re-derive the same term until the step budget ran out.
        s.continuous = s.continuous.intersect(effective);
        VersionUniverse u = universes.get(t.pkg());
        if (u != null) {
            if (s.allowed == null) {
                // Bind: project the constraints accumulated before the universe was known.
                s.allowed = u.project(s.continuous);
            } else {
                s.allowed = s.allowed.intersect(u.project(effective));
            }
        }
    }

    /**
     * Project any continuous-only state for {@code pkg} onto its universe (already in the shared
     * map). No-op if unbound or already indexed.
     */
    public void bindUniverse(String pkg) {
        VersionUniverse u = universes.get(pkg);
        if (u == null) return;
        PackageState s = byPackage.get(pkg);
        if (s == null) {
            s = new PackageState();
            s.allowed = u.all();
            byPackage.put(pkg, s);
            return;
        }
        if (s.allowed != null) return;
        s.allowed = u.project(s.continuous);
    }

    /**
     * After the solver replaces a lazy singleton {@link VersionUniverse} with the full metadata
     * list, rebuild {@code pkg}'s continuous constraint from all assignments and re-project onto the
     * new universe.
     */
    public void rebindAfterUniverseExpand(String pkg) {
        VersionUniverse u = universes.get(pkg);
        if (u == null) return;
        VersionSet cont = VersionSet.ALL;
        boolean hasPos = false;
        boolean mentioned = false;
        for (Assignment a : assignmentsFor(pkg)) {
            mentioned = true;
            if (a.term().positive()) hasPos = true;
            cont = cont.intersect(a.term().effectiveVersions());
        }
        PackageState s = byPackage.computeIfAbsent(pkg, k -> new PackageState());
        s.continuous = cont;
        s.hasPositive = hasPos || s.hasPositive;
        s.mentioned = mentioned || s.mentioned;
        s.allowed = u.project(cont);
        if (s.hasPositive && s.firstIndex >= 0 && !decisionByPackage.containsKey(pkg)) {
            undecidedPositive.put(s.firstIndex, pkg);
        }
    }

    /**
     * The earliest-mentioned package that a positive term requires and no decision has settled, or
     * {@code null} when every required package is decided — the solve is then complete.
     */
    public @Nullable String nextUndecidedPositive() {
        Map.Entry<Integer, String> first = undecidedPositive.firstEntry();
        return first == null ? null : first.getValue();
    }

    /**
     * True iff at least one positive term about {@code pkg} has been recorded — i.e. the partial
     * solution requires the package to exist at some version. False for "phantom" packages mentioned
     * only by negative incompatibilities.
     */
    public boolean hasPositiveTerm(String pkg) {
        PackageState s = byPackage.get(pkg);
        return s != null && s.hasPositive;
    }

    public int decisionLevel() {
        return decisionLevel;
    }

    /**
     * Intersection of all term constraints known so far for {@code pkg}, as a continuous {@link
     * VersionSet}. When interned and still non-empty, a union of remaining exact versions; when
     * interned and empty, the pre-bind continuous snapshot (for NoVersions diagnostics).
     */
    public VersionSet positiveSet(String pkg) {
        PackageState s = byPackage.get(pkg);
        if (s == null) return VersionSet.ALL;
        if (s.allowed != null) {
            if (s.allowed.isEmpty()) return s.continuous;
            return s.allowed.toVersionSet();
        }
        return s.continuous;
    }

    /** True when no advertised version remains for an interned package, or continuous is empty. */
    public boolean hasNoCandidates(String pkg) {
        PackageState s = byPackage.get(pkg);
        if (s == null) return false;
        if (s.allowed != null) return s.allowed.isEmpty();
        return s.continuous.isEmpty();
    }

    /**
     * Preferred version among remaining candidates for an interned package, or {@code null}.
     * Requires {@link #bindUniverse} / a universe entry first.
     */
    public @Nullable String choosePreferred(String pkg) {
        return choosePreferred(pkg, Set.of());
    }

    /** As {@link #choosePreferred(String)}, steering to a version in {@code declared} when one is allowed. */
    public @Nullable String choosePreferred(String pkg, Set<String> declared) {
        PackageState s = byPackage.get(pkg);
        if (s == null || s.allowed == null) return null;
        return s.allowed.choosePreferred(declared);
    }

    /**
     * The intersection of every term recorded about {@code pkg}, as a continuous set — the shape of
     * what was asked (a floor, a range), independent of which versions the universe advertises.
     */
    public VersionSet constraint(String pkg) {
        PackageState s = byPackage.get(pkg);
        return s == null ? VersionSet.ALL : s.continuous;
    }

    /** True iff every version still allowed for {@code term.pkg()} satisfies {@code term}. */
    public boolean satisfies(Term term) {
        // Decided packages are singletons — avoid AllowedSet project/intersect on the hot path.
        String decided = decisionByPackage.get(term.pkg());
        if (decided != null) {
            return term.effectiveVersions().contains(decided);
        }
        PackageState s = byPackage.get(term.pkg());
        // Mirror of the absence guard in contradicts(): a package with no positive
        // commitment — mentioned only negatively (e.g. ¬b{1.2} learned during conflict
        // resolution, the exact post-backjump state), or not mentioned at all — may end up
        // unselected entirely, and per the paper a negative assignment can never satisfy a
        // POSITIVE term. Without this, set projection alone reported such terms satisfied,
        // letting relationTo() misclassify a dependency incompatibility as SATISFIED (spurious
        // conflict → wrong learned inco/backjump) or ALMOST_SATISFIED toward over-resolution.
        if (term.positive() && (s == null || !s.hasPositive)) return false;
        VersionSet effective = term.effectiveVersions();
        if (s == null) {
            // Unmentioned package is unconstrained (universe of all version strings).
            return VersionSet.ALL.subsetOf(effective);
        }
        VersionUniverse u = universes.get(term.pkg());
        // Non-empty bitset: discrete membership is authoritative.
        if (s.allowed != null && u != null && !s.allowed.isEmpty()) {
            return s.allowed.subsetOf(u.project(effective));
        }
        // Empty bitset (or not yet bound): use continuous snapshot. Vacuous empty-set logic
        // would make every term both satisfy and contradict, collapsing dependency
        // incompatibilities into false conflicts (see DiagnosticsTest.renders_missing_version).
        return s.continuous.subsetOf(effective);
    }

    /**
     * True iff every allowed version of {@code term.pkg} contradicts {@code term}.
     *
     * <p>If no assignment mentions {@code term.pkg} at all, the package is unconstrained
     * nothing the solution holds can contradict a term about it. Without this guard, a negative
     * term whose {@code effectiveVersions} is {@link VersionSet#EMPTY} (the negation of a positive
     * root term with {@code versionSet = ALL}, i.e. an unbounded {@code @latest} selector) would
     * spuriously appear contradicted — and the inco carrying it would stay INCONCLUSIVE forever,
     * so no positive term for the package ever gets derived and the dep is silently dropped from
     * the resolution. Once even a negative derivation narrows the package, real set logic must
     * apply (see the infinite-derive failure otherwise).
     */
    public boolean contradicts(Term term) {
        PackageState s = byPackage.get(term.pkg());
        if (s == null || !s.mentioned) return false;
        // A NEGATIVE term is also satisfied by the package being absent entirely (paper §Terms:
        // "¬foo ^1.0 is satisfied ... or if no version of foo is selected at all"). A package
        // mentioned only negatively (e.g. ¬b{1.2} learned during conflict resolution) may still
        // end up unselected, so set-projection "contradiction" — which assumes presence — must
        // not fire. Without this, a dependency incompatibility {a, ¬b[range]} went permanently
        // INCONCLUSIVE after a backjump, b's positive term was never re-derived, and the solve
        // terminated WITHOUT the mandatory subtree.
        if (!term.positive() && !s.hasPositive) return false;
        // The mirror for a POSITIVE term: a package mentioned only negatively may still be selected
        // at any version those terms leave, so no positive term about it is contradicted yet — even
        // when the bound universe advertises no such version. Judging that by the universe made a
        // no-candidates clause "contradicted" instead of almost satisfied, its negation was never
        // derived, and the conflict that produced it recurred until the budget ran out (the paper's
        // relations are over sets; the universe informs decisions, not derivations).
        if (term.positive() && !s.hasPositive) return false;
        String decided = decisionByPackage.get(term.pkg());
        if (decided != null) {
            return !term.effectiveVersions().contains(decided);
        }
        VersionSet effective = term.effectiveVersions();
        VersionUniverse u = universes.get(term.pkg());
        if (s.allowed != null && u != null && !s.allowed.isEmpty()) {
            return s.allowed.intersect(u.project(effective)).isEmpty();
        }
        return s.continuous.intersect(effective).isEmpty();
    }

    /** Snapshot of all packages with finalized decisions. */
    public Map<String, String> decisions() {
        return new TreeMap<>(decisionByPackage);
    }

    /**
     * Live assignment stack (unmodifiable view). Callers must not retain across {@link #backtrack}
     * or {@link #decide}/{@link #derive}. Avoids per-call {@code List.copyOf} on the hot PubGrub
     * path.
     */
    public List<Assignment> assignments() {
        assignmentsScanned += assignments.size();
        return Collections.unmodifiableList(assignments);
    }

    /**
     * The assignments about {@code pkg}, in global order (unmodifiable view; same retention rule
     * as {@link #assignments()}). Empty when nothing mentions the package.
     */
    public List<Assignment> assignmentsFor(String pkg) {
        List<Assignment> own = assignmentsByPackage.get(pkg);
        if (own == null) return List.of();
        assignmentsScanned += own.size();
        return Collections.unmodifiableList(own);
    }

    /** Assignments on the stack right now. */
    public int size() {
        return assignments.size();
    }

    /** How many assignments {@link #decide}/{@link #derive} have pushed over the solve. */
    public long assignmentsRecorded() {
        return assignmentsRecorded;
    }

    /**
     * How many assignments were handed out for scanning, over the solve. A whole-stack scan per
     * decision or per satisfier lookup makes this quadratic in the stack; the per-package index
     * keeps it near the number recorded.
     */
    public long assignmentsScanned() {
        return assignmentsScanned;
    }

    /** Snapshot of decisions without sorting (hot path). Prefer over {@link #decisions()} in the solver. */
    public Map<String, String> decisionsUnsorted() {
        return Collections.unmodifiableMap(decisionByPackage);
    }

    /**
     * Discard every assignment with decision level &gt; {@code targetLevel} and replay the survivors.
     * Used by conflict resolution. Universes stay cached on the solver; replay re-binds bitsets.
     */
    public void backtrack(int targetLevel) {
        if (targetLevel < 0) {
            throw new IllegalArgumentException("cannot backtrack below 0: " + targetLevel);
        }
        assignments.removeIf(a -> a.decisionLevel() > targetLevel);
        decisionLevel = targetLevel;
        decisionByPackage.clear();
        byPackage.clear();
        assignmentsByPackage.clear();
        undecidedPositive.clear();
        // Decisions first, then every term: a package is usually required by a derivation before
        // it is decided, and register() offers an undecided required package for decision — so
        // the surviving decisions must all be known before any term is replayed, or a decided
        // package whose first mention came earlier would be offered a second time.
        for (Assignment a : assignments) {
            // Decisions are always exact singles (VersionSet.exact).
            if (a instanceof Assignment.Decision d
                    && d.term().effectiveVersions() instanceof VersionSet.Range r
                    && r.min() != null
                    && r.max() != null
                    && r.minInclusive()
                    && r.maxInclusive()
                    && r.min().equals(r.max())) {
                decisionByPackage.put(d.term().pkg(), r.min());
            }
        }
        for (Assignment a : assignments) {
            index(a);
            register(a.term(), a.globalIndex());
        }
    }

    /**
     * Compute the relation between an {@link Incompatibility} and the current partial solution.
     * Distinguishes the three states PubGrub acts on (paper §3 / §4):
     *
     * <ul>
     * <li>{@link IncompatibilityRelation#SATISFIED}: every term holds — a real conflict, must be
     * resolved.
     * <li>{@link IncompatibilityRelation#ALMOST_SATISFIED}: exactly one term doesn't hold, the rest
     * do — unit propagation fires.
     * <li>{@link IncompatibilityRelation#INCONCLUSIVE}: more than one term unresolved — nothing to
     * do yet.
     * </ul>
     */
    public Relation relationTo(Incompatibility inco) {
        long t0 = ResolveProfile.on() ? System.nanoTime() : 0L;
        try {
            Term unsatisfied = null;
            int unsatisfiedCount = 0;
            for (Term term : inco.terms()) {
                if (contradicts(term)) {
                    return new Relation(IncompatibilityRelation.INCONCLUSIVE, null);
                }
                if (!satisfies(term)) {
                    unsatisfied = term;
                    unsatisfiedCount++;
                    if (unsatisfiedCount > 1) {
                        return new Relation(IncompatibilityRelation.INCONCLUSIVE, null);
                    }
                }
            }
            if (unsatisfied == null) {
                return new Relation(IncompatibilityRelation.SATISFIED, null);
            }
            return new Relation(IncompatibilityRelation.ALMOST_SATISFIED, unsatisfied);
        } finally {
            if (ResolveProfile.on()) {
                ResolveProfile.relation(System.nanoTime() - t0);
            }
        }
    }

    public enum IncompatibilityRelation {
        SATISFIED,
        ALMOST_SATISFIED,
        INCONCLUSIVE
    }

    public record Relation(
            IncompatibilityRelation kind, @Nullable Term unsatisfied) {}
}
