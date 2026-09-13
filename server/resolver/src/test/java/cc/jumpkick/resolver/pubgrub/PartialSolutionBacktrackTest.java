// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver.pubgrub;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A backtrack leaves the partial solution exactly as folding the surviving assignments in from
 * scratch would — whether a package keeps its state untouched, goes back to a checkpoint, or is
 * rebuilt because its universe changed in between. Checked by driving random scripts of decisions,
 * derivations, universe bindings, expansions and backtracks against a fresh replay of the survivors.
 */
class PartialSolutionBacktrackTest {

    private static final List<String> PKGS = List.of("a", "b", "c", "d", "e");
    private static final List<String> VERSIONS = List.of("5", "4", "3", "2", "1");

    private static final Incompatibility CAUSE = new Incompatibility(
            List.of(Term.positive("root", VersionSet.exact("1.0"))), new Incompatibility.Cause.Root("root", "1.0"));

    @Test
    void a_backtrack_matches_a_replay_of_the_surviving_assignments() {
        for (int seed = 0; seed < 200; seed++) {
            Random random = new Random(seed);
            Map<String, VersionUniverse> universes = new HashMap<>();
            PartialSolution live = new PartialSolution(universes);
            live.decide("root", "1.0");
            List<String> trace = new ArrayList<>();
            for (int step = 0; step < 40; step++) {
                String pkg = PKGS.get(random.nextInt(PKGS.size()));
                switch (random.nextInt(10)) {
                    case 0, 1, 2, 3 -> {
                        Term t = randomTerm(random, pkg);
                        live.derive(t, CAUSE);
                        trace.add("derive " + t);
                    }
                    case 4, 5 -> {
                        if (!live.decisionsUnsorted().containsKey(pkg) && live.hasPositiveTerm(pkg)) {
                            String v = VERSIONS.get(random.nextInt(VERSIONS.size()));
                            live.decide(pkg, v);
                            trace.add("decide " + pkg + "=" + v);
                        }
                    }
                    case 6 -> {
                        // The solver binds a universe when it is about to decide a package, so the
                        // package always has assignments by then.
                        if (!universes.containsKey(pkg)
                                && !live.assignmentsFor(pkg).isEmpty()) {
                            // A lazy singleton universe: what the solver seeds from an exact pin.
                            universes.put(pkg, VersionUniverse.of(pkg, List.of(VERSIONS.get(random.nextInt(5)))));
                            live.bindUniverse(pkg);
                            trace.add("bind " + pkg);
                        }
                    }
                    case 7 -> {
                        if (universes.containsKey(pkg) && universes.get(pkg).size() == 1) {
                            universes.put(pkg, VersionUniverse.of(pkg, VERSIONS));
                            live.rebindAfterUniverseExpand(pkg);
                            trace.add("expand " + pkg);
                        }
                    }
                    default -> {
                        int target = random.nextInt(live.decisionLevel() + 1);
                        live.backtrack(target);
                        trace.add("backtrack " + target);
                        assertEquivalentToReplay(live, universes, "seed " + seed + ": " + trace);
                    }
                }
            }
            live.backtrack(random.nextInt(live.decisionLevel() + 1));
            assertEquivalentToReplay(live, universes, "seed " + seed + ": " + trace);
        }
    }

    private static Term randomTerm(Random random, String pkg) {
        String v = VERSIONS.get(random.nextInt(VERSIONS.size()));
        VersionSet set =
                switch (random.nextInt(3)) {
                    case 0 -> VersionSet.exact(v);
                    case 1 -> VersionSet.atLeast(v, true);
                    default -> VersionSet.ALL;
                };
        return random.nextBoolean() ? Term.positive(pkg, set) : Term.negative(pkg, set);
    }

    /** Fold the survivors into a fresh solution over the same universes and compare every observable. */
    private static void assertEquivalentToReplay(
            PartialSolution live, Map<String, VersionUniverse> universes, String script) {
        PartialSolution fresh = new PartialSolution(universes);
        for (PartialSolution.Assignment a : live.assignments()) {
            switch (a) {
                case PartialSolution.Assignment.Decision d ->
                    fresh.decide(
                            d.term().pkg(),
                            d.term().versions().asExactSingleton().orElseThrow());
                case PartialSolution.Assignment.Derivation d -> fresh.derive(d.term(), d.cause());
            }
        }

        assertThat(live.decisionLevel()).as(script).isEqualTo(fresh.decisionLevel());
        assertThat(live.decisionsUnsorted()).as(script).isEqualTo(fresh.decisionsUnsorted());
        assertThat(live.nextUndecidedPositive()).as(script).isEqualTo(fresh.nextUndecidedPositive());
        Set<String> pkgs = new LinkedHashSet<>(PKGS);
        pkgs.add("root");
        for (String pkg : pkgs) {
            String at = script + " @" + pkg;
            assertThat(live.assignmentsFor(pkg)).as(at).isEqualTo(fresh.assignmentsFor(pkg));
            assertThat(live.hasPositiveTerm(pkg)).as(at).isEqualTo(fresh.hasPositiveTerm(pkg));
            assertThat(live.constraint(pkg)).as(at).isEqualTo(fresh.constraint(pkg));
            assertThat(live.positiveSet(pkg)).as(at).isEqualTo(fresh.positiveSet(pkg));
            assertThat(live.hasNoCandidates(pkg)).as(at).isEqualTo(fresh.hasNoCandidates(pkg));
            assertThat(live.choosePreferred(pkg)).as(at).isEqualTo(fresh.choosePreferred(pkg));
            for (String v : VERSIONS) {
                for (VersionSet set : List.of(VersionSet.exact(v), VersionSet.atLeast(v, true), VersionSet.ALL)) {
                    for (Term probe : List.of(Term.positive(pkg, set), Term.negative(pkg, set))) {
                        assertThat(live.satisfies(probe))
                                .as(at + " satisfies " + probe)
                                .isEqualTo(fresh.satisfies(probe));
                        assertThat(live.contradicts(probe))
                                .as(at + " contradicts " + probe)
                                .isEqualTo(fresh.contradicts(probe));
                    }
                }
            }
        }
    }
}
