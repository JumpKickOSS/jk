// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver.pubgrub;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * PubGrub assignment stack (decisions + derivations) with decision-level backtracking. Per-package
 * constraints start as {@link VersionSet} and project onto {@link AllowedSet} once the universe is
 * known.
 */
public final class PartialSolution {

    private final List<Assignment> assignments = new ArrayList<>();

    /** Live map owned by the solver; entries appear as packages are interned. */
    private final Map<String, VersionUniverse> universes;

    private final Map<String, PackageState> byPackage = new HashMap<>();
    private final Map<String, String> decisionByPackage = new TreeMap<>();
    private int decisionLevel = 0;

    /**
     * Per-package constraint state. {@code allowed} is non-null once the package has been bound to
     * a {@link VersionUniverse}; after that further narrowing is bitset-only (avoids Union blow-up
     * from repeated exact exclusions). {@code continuous} freezes at the pre-bind snapshot for
     * diagnostics when the bitset becomes empty.
     */
    static final class PackageState {
        VersionSet continuous = VersionSet.ALL;
        AllowedSet allowed; // null until universe bound
        boolean hasPositive;
        boolean mentioned;
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
        assignments.add(new Assignment.Decision(decision, decisionLevel, assignments.size()));
        decisionByPackage.put(pkg, version);
        register(decision);
    }

    public void derive(Term term, Incompatibility cause) {
        assignments.add(new Assignment.Derivation(term, decisionLevel, assignments.size(), cause));
        register(term);
    }

    private void register(Term t) {
        PackageState s = byPackage.computeIfAbsent(t.pkg(), k -> new PackageState());
        s.mentioned = true;
        if (t.positive()) {
            s.hasPositive = true;
        }
        VersionSet effective = t.effectiveVersions();
        VersionUniverse u = universes.get(t.pkg());
        if (u != null) {
            if (s.allowed == null) {
                // Bind: project continuous constraints accumulated before the universe was known.
                s.continuous = s.continuous.intersect(effective);
                s.allowed = u.project(s.continuous);
            } else {
                s.allowed = s.allowed.intersect(u.project(effective));
                // continuous stays frozen at bind-time snapshot (diagnostics only when bits empty)
            }
        } else {
            s.continuous = s.continuous.intersect(effective);
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
        for (Assignment a : assignments) {
            if (!a.term().pkg().equals(pkg)) continue;
            mentioned = true;
            if (a.term().positive()) hasPos = true;
            cont = cont.intersect(a.term().effectiveVersions());
        }
        PackageState s = byPackage.computeIfAbsent(pkg, k -> new PackageState());
        s.continuous = cont;
        s.hasPositive = hasPos || s.hasPositive;
        s.mentioned = mentioned || s.mentioned;
        s.allowed = u.project(cont);
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
    public String choosePreferred(String pkg) {
        PackageState s = byPackage.get(pkg);
        if (s == null || s.allowed == null) return null;
        return s.allowed.choosePreferred();
    }

    /** True iff every version still allowed for {@code term.pkg()} satisfies {@code term}. */
    public boolean satisfies(Term term) {
        PackageState s = byPackage.get(term.pkg());
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
        return java.util.Collections.unmodifiableList(assignments);
    }

    /** Snapshot of decisions without sorting (hot path). Prefer over {@link #decisions()} in the solver. */
    public Map<String, String> decisionsUnsorted() {
        return java.util.Collections.unmodifiableMap(decisionByPackage);
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
        for (Assignment a : assignments) {
            register(a.term());
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
    }

    public enum IncompatibilityRelation {
        SATISFIED,
        ALMOST_SATISFIED,
        INCONCLUSIVE
    }

    public record Relation(IncompatibilityRelation kind, Term unsatisfied) {}
}
