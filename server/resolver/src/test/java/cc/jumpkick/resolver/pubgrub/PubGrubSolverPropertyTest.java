// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver.pubgrub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.resolver.Versions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.EdgeCasesMode;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Solver invariants over generated dependency universes. A universe is a handful of packages,
 * each with a few versions drawn from a pool that mixes stables and pre-releases, and each
 * version declaring constraints on other packages. The invariants are the solver's contract:
 * a solution satisfies every constraint it selected; unsatisfiable means unsatisfiable (checked
 * by exhaustive search on small universes); the answer does not depend on input order; and a
 * pre-release is never selected while a stable candidate is allowed.
 */
class PubGrubSolverPropertyTest {

    /** A looping solve on a four-package universe fails fast rather than stalling the suite. */
    static final int BUDGET = 2_000;

    static final long TIMEOUT_MS = 5_000;

    /** Versions in ascending order; three stables, two pre-releases, one more stable. */
    static final List<String> POOL = List.of("1.0", "1.1", "2.0", "3.0-rc1", "3.0-rc2", "3.0");

    /** One generated universe: package, then version, then constraints on other packages. */
    record Universe(Map<String, Map<String, Map<String, VersionSet>>> table, List<Term> rootDeps) {
        List<String> packages() {
            return new ArrayList<>(table.keySet());
        }

        PackageSource source(Random order) {
            InMemoryPackageSource.Builder b = InMemoryPackageSource.builder();
            List<String> pkgs = packages();
            Collections.shuffle(pkgs, order);
            for (String pkg : pkgs) {
                List<String> versions = new ArrayList<>(table.get(pkg).keySet());
                Collections.shuffle(versions, order);
                for (String v : versions) {
                    Map<String, VersionSet> deps = table.get(pkg).get(v);
                    b.version(pkg, v, d -> deps.forEach(d::require));
                }
            }
            return b.build();
        }

        /** Every root dep and every selected version's dep is satisfied by {@code assignment}. */
        boolean isValidSolution(Map<String, String> assignment) {
            for (Term t : rootDeps) {
                String v = assignment.get(t.pkg());
                if (v == null || !t.versions().contains(v)) return false;
            }
            for (var e : assignment.entrySet()) {
                if (e.getKey().equals("root")) continue;
                Map<String, VersionSet> deps =
                        table.getOrDefault(e.getKey(), Map.of()).get(e.getValue());
                if (deps == null) return false;
                for (var d : deps.entrySet()) {
                    String v = assignment.get(d.getKey());
                    if (v == null || !d.getValue().contains(v)) return false;
                }
            }
            return true;
        }

        /** Exhaustive search: does any assignment satisfy the root deps and every selected dep? */
        boolean satisfiableByBruteForce() {
            return search(new HashMap<>(), new ArrayList<>(rootDeps));
        }

        private boolean search(Map<String, String> chosen, List<Term> pending) {
            // Every pending constraint on an already-chosen package must hold.
            for (Term t : pending) {
                String v = chosen.get(t.pkg());
                if (v != null && !t.versions().contains(v)) return false;
            }
            Term open = pending.stream()
                    .filter(t -> !chosen.containsKey(t.pkg()))
                    .findFirst()
                    .orElse(null);
            if (open == null) return true;
            Map<String, Map<String, VersionSet>> versions = table.getOrDefault(open.pkg(), Map.of());
            for (String v : versions.keySet()) {
                if (!open.versions().contains(v)) continue;
                chosen.put(open.pkg(), v);
                List<Term> next = new ArrayList<>(pending);
                versions.get(v).forEach((dep, set) -> next.add(Term.positive(dep, set)));
                if (search(chosen, next)) return true;
                chosen.remove(open.pkg());
            }
            return false;
        }
    }

    @Provide
    Arbitrary<VersionSet> constraints() {
        Arbitrary<String> v = Arbitraries.of(POOL);
        return Arbitraries.oneOf(
                v.map(VersionSet::exact),
                v.map(min -> VersionSet.atLeast(min, true)),
                v.map(max -> VersionSet.lessThan(max, false)),
                Arbitraries.just(VersionSet.ALL),
                Arbitraries.integers().between(0, POOL.size() - 2).flatMap(i -> Arbitraries.integers()
                        .between(i + 1, POOL.size() - 1)
                        .map(j -> VersionSet.between(POOL.get(i), true, POOL.get(j), false))));
    }

    @Provide
    Arbitrary<Universe> universes() {
        Arbitrary<Integer> width = Arbitraries.integers().between(1, 4);
        return width.flatMap(n -> {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < n; i++) names.add("p" + i);
            // A version's dependencies name other packages only: nothing depends on itself.
            Arbitrary<Map<String, Map<String, Map<String, VersionSet>>>> table = Arbitraries.just(names)
                    .flatMap(all -> {
                        Arbitrary<Map<String, Map<String, Map<String, VersionSet>>>> acc =
                                Arbitraries.just(new LinkedHashMap<>());
                        for (String pkg : all) {
                            List<String> others =
                                    all.stream().filter(o -> !o.equals(pkg)).toList();
                            Arbitrary<Map<String, VersionSet>> depsOf = others.isEmpty()
                                    ? Arbitraries.just(Map.of())
                                    : Arbitraries.maps(Arbitraries.of(others), constraints())
                                            .ofMinSize(0)
                                            .ofMaxSize(2);
                            Arbitrary<Map<String, Map<String, VersionSet>>> versionsOf = Arbitraries.maps(
                                            Arbitraries.of(POOL), depsOf)
                                    .ofMinSize(1)
                                    .ofMaxSize(4);
                            acc = acc.flatMap(m -> versionsOf.map(vs -> {
                                var copy = new LinkedHashMap<>(m);
                                copy.put(pkg, vs);
                                return copy;
                            }));
                        }
                        return acc;
                    });
            Arbitrary<List<Term>> roots = Arbitraries.maps(Arbitraries.of(names), constraints())
                    .ofMinSize(1)
                    .ofMaxSize(3)
                    .map(m -> m.entrySet().stream()
                            .map(e -> Term.positive(e.getKey(), e.getValue()))
                            .toList());
            return table.flatMap(t -> roots.map(r -> new Universe(t, r)));
        });
    }

    @Property(tries = 300, edgeCases = EdgeCasesMode.NONE)
    void a_solution_satisfies_every_constraint_it_selected(@ForAll("universes") Universe u) throws Exception {
        Map<String, String> solution = solveOrNull(u, new Random(1));
        if (solution == null) return; // the unsat half is its own property
        for (Term root : u.rootDeps()) {
            assertThat(solution).as("root dep %s selected", root.pkg()).containsKey(root.pkg());
            assertThat(root.versions().contains(solution.get(root.pkg())))
                    .as("root dep %s admits %s", root, solution.get(root.pkg()))
                    .isTrue();
        }
        for (var e : solution.entrySet()) {
            if (e.getKey().equals("root")) continue;
            Map<String, VersionSet> deps = u.table().get(e.getKey()).get(e.getValue());
            assertThat(deps)
                    .as("%s@%s is a version the universe has", e.getKey(), e.getValue())
                    .isNotNull();
            deps.forEach((dep, set) -> {
                assertThat(solution)
                        .as("%s@%s needs %s", e.getKey(), e.getValue(), dep)
                        .containsKey(dep);
                assertThat(set.contains(solution.get(dep)))
                        .as("%s@%s needs %s in %s, got %s", e.getKey(), e.getValue(), dep, set, solution.get(dep))
                        .isTrue();
            });
        }
    }

    @Property(tries = 300, edgeCases = EdgeCasesMode.NONE)
    void unsatisfiable_means_no_assignment_exists(@ForAll("universes") Universe u) throws Exception {
        boolean solved = solveOrNull(u, new Random(2)) != null;
        assertThat(solved).as("solver agrees with exhaustive search").isEqualTo(u.satisfiableByBruteForce());
    }

    @Property(tries = 100)
    void the_solution_does_not_depend_on_input_order(@ForAll("universes") Universe u, @ForAll long seed)
            throws Exception {
        Map<String, String> a = solveOrNull(u, new Random(seed));
        Map<String, String> b = solveOrNull(u, new Random(seed ^ 0x5DEECE66DL));
        assertThat(a).isEqualTo(b);
    }

    @Property(tries = 300, edgeCases = EdgeCasesMode.NONE)
    void a_pre_release_is_never_selected_while_a_stable_swap_would_also_solve(@ForAll("universes") Universe u)
            throws Exception {
        Map<String, String> solution = solveOrNull(u, new Random(3));
        if (solution == null) return;
        for (var e : solution.entrySet()) {
            if (e.getKey().equals("root") || Versions.isStable(e.getValue())) continue;
            // The stable preference is local: with every other choice held fixed, no stable version
            // of this package may complete the same solution. (A stable version whose own
            // dependencies conflict with the rest is not a candidate the preference could take.)
            for (String candidate : u.table().get(e.getKey()).keySet()) {
                if (!Versions.isStable(candidate)) continue;
                Map<String, String> swapped = new HashMap<>(solution);
                swapped.put(e.getKey(), candidate);
                assertThat(u.isValidSolution(swapped))
                        .as(
                                "%s: stable %s completes the solution yet pre-release %s was chosen",
                                e.getKey(), candidate, e.getValue())
                        .isFalse();
            }
        }
    }

    @Property(tries = 100)
    void an_exact_root_dep_on_an_unknown_version_is_unsatisfiable(@ForAll("universes") Universe u) {
        String pkg = u.packages().getFirst();
        List<Term> deps = List.of(Term.positive(pkg, VersionSet.exact("9.9.9")));
        assertThatThrownBy(
                        () -> new PubGrubSolver(u.source(new Random(4)), BUDGET, TIMEOUT_MS).solve("root", "1.0", deps))
                .isInstanceOf(UnsatisfiableException.class);
    }

    /**
     * The production contract ({@code PubGrubResolver}): a narrow solve, then one wide retry when
     * the narrow verdict was unsat over a possibly-incomplete universe. Null means unsatisfiable.
     */
    private static Map<String, String> solveOrNull(Universe u, Random order) throws Exception {
        PackageSource source = u.source(order);
        PubGrubSolver narrow = new PubGrubSolver(source, BUDGET, TIMEOUT_MS);
        try {
            return narrow.solve("root", "1.0", u.rootDeps());
        } catch (UnsatisfiableException first) {
            assertThat(first.rootCause())
                    .as("an unsat result names its root incompatibility")
                    .isNotNull();
            if (!narrow.maybeIncomplete()) return null;
            try {
                return new PubGrubSolver(source, BUDGET, TIMEOUT_MS)
                        .withWideUniverses()
                        .solve("root", "1.0", u.rootDeps());
            } catch (UnsatisfiableException second) {
                assertThat(second.rootCause()).isNotNull();
                return null;
            }
        }
    }
}
