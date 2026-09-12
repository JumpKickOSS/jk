// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver.pubgrub;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Deciding the next package and locating a term's satisfier are questions about one package, and
 * the partial solution answers them from a per-package index. Measured, not inferred: the solution
 * counts every assignment it hands out for scanning, and a solver subclass records what a
 * whole-stack walk at the same points would have read.
 */
class PubGrubAssignmentIndexTest {

    /** Records the stack size at each point where a whole-stack walk would read all of it. */
    static final class Measured extends PubGrubSolver {
        long wholeStackAtDecisions;
        long wholeStackAtConflicts;

        Measured(PackageSource source) {
            super(source);
        }

        @Override
        protected @Nullable String makeDecision() throws IOException, InterruptedException {
            wholeStackAtDecisions += solution.size();
            return super.makeDecision();
        }

        @Override
        protected @Nullable Incompatibility handleConflict(Incompatibility inco)
                throws IOException, InterruptedException {
            // One satisfier search per term plus the previous-satisfier search, each over the
            // whole stack: the least a resolution step reads when it walks the stack.
            wholeStackAtConflicts += (long) (inco.terms().size() + 1) * solution.size();
            return super.handleConflict(inco);
        }

        long wholeStackReads() {
            return wholeStackAtDecisions + wholeStackAtConflicts;
        }
    }

    /** p0 -> p1 -> ... with 100 versions each and a preferred pin at 50.0.0 — the interning stress graph. */
    private static PackageSource wideGraph(int packages, int versionsPerPkg) {
        return new PackageSource() {
            @Override
            public List<String> versions(String pkg) {
                if (!pkg.startsWith("p")) return List.of();
                List<String> vs = new ArrayList<>(versionsPerPkg);
                vs.add("50.0.0");
                for (int v = versionsPerPkg; v >= 1; v--) {
                    String s = v + ".0.0";
                    if (!s.equals("50.0.0")) vs.add(s);
                }
                return vs;
            }

            @Override
            public List<Term> dependencies(String pkg, String version) {
                if (!pkg.startsWith("p")) return List.of();
                int idx = Integer.parseInt(pkg.substring(1));
                if (idx + 1 >= packages) return List.of();
                return List.of(Term.positive("p" + (idx + 1), VersionSet.atLeast("1.0", true)));
            }
        };
    }

    /**
     * A chain of {@code links} single-version packages whose last link needs {@code a} and {@code
     * b}. {@code a} 2.0 wants {@code shared} 2.0, {@code b} wants {@code shared} 1.0, so the first
     * pick of {@code a} conflicts once {@code b} is decided — on a stack the chain has already made
     * two hundred entries deep — and resolution backjumps to {@code a} 1.0.
     */
    private static PackageSource chainThenConflict(int links) {
        InMemoryPackageSource.Builder b = InMemoryPackageSource.builder();
        for (int i = 0; i < links; i++) {
            final int n = i;
            if (i == links - 1) {
                b.version("p" + n, "1.0", deps -> deps.require("a", VersionSet.atLeast("1.0", true))
                        .require("b", VersionSet.exact("1.0")));
            } else {
                b.version("p" + n, "1.0", deps -> deps.require("p" + (n + 1), VersionSet.exact("1.0")));
            }
        }
        b.version("shared", "2.0");
        b.version("shared", "1.0");
        b.version("a", "2.0", deps -> deps.require("shared", VersionSet.exact("2.0")));
        b.version("a", "1.0", deps -> deps.require("shared", VersionSet.exact("1.0")));
        b.version("b", "1.0", deps -> deps.require("shared", VersionSet.exact("1.0")));
        return b.build();
    }

    @Test
    @Timeout(10)
    void deciding_eighty_packages_never_rescans_the_assignment_stack() throws Exception {
        int packages = 80;
        Measured solver = new Measured(wideGraph(packages, 100));
        Map<String, String> solution =
                solver.solve("root", "1.0", List.of(Term.positive("p0", VersionSet.atLeast("1.0", true))));

        assertThat(solution.get("p0")).isEqualTo("50.0.0");
        assertThat(solution.size()).isGreaterThan(packages / 2);

        long recorded = solver.solution.assignmentsRecorded();
        long scanned = solver.solution.assignmentsScanned();
        assertThat(solver.wholeStackAtDecisions)
                .as("a whole-stack scan per decision is quadratic in the stack")
                .isGreaterThan(recorded * 10);
        assertThat(scanned)
                .as("per-package reads (%d) against assignments recorded (%d)", scanned, recorded)
                .isLessThanOrEqualTo(recorded);
        assertThat(scanned).isLessThan(solver.wholeStackAtDecisions / 10);
    }

    @Test
    @Timeout(10)
    void a_conflict_deep_in_the_stack_reads_only_the_conflicting_packages() throws Exception {
        int links = 60;
        Measured solver = new Measured(chainThenConflict(links));
        Map<String, String> solution =
                solver.solve("root", "1.0", List.of(Term.positive("p0", VersionSet.exact("1.0"))));

        // Semantics first: the conflict was real, and resolution settled it the PubGrub way.
        assertThat(solution).containsEntry("a", "1.0").containsEntry("b", "1.0").containsEntry("shared", "1.0");
        assertThat(solution).hasSize(links + 4);
        assertThat(solver.wholeStackAtConflicts)
                .as("the stack was walked in resolution")
                .isPositive();

        long recorded = solver.solution.assignmentsRecorded();
        long scanned = solver.solution.assignmentsScanned();
        assertThat(scanned)
                .as("per-package reads (%d) against assignments recorded (%d)", scanned, recorded)
                .isLessThan(recorded);
        assertThat(scanned)
                .as("whole-stack walks would have read at least %d", solver.wholeStackReads())
                .isLessThan(solver.wholeStackReads() / 10);
    }

    @Test
    void the_next_decision_is_the_earliest_mentioned_undecided_required_package() {
        PartialSolution ps = new PartialSolution(new HashMap<>());
        ps.decide("root", "1.0");
        Incompatibility cause = new Incompatibility(
                List.of(Term.positive("root", VersionSet.exact("1.0"))), new Incompatibility.Cause.Root("root", "1.0"));
        // b is mentioned first, negatively; a is required first; then b becomes required.
        ps.derive(Term.negative("b", VersionSet.exact("9.0")), cause);
        ps.derive(Term.positive("a", VersionSet.ALL), cause);
        assertThat(ps.nextUndecidedPositive())
                .as("a negative mention does not make b a candidate")
                .isEqualTo("a");
        ps.derive(Term.positive("b", VersionSet.ALL), cause);
        assertThat(ps.nextUndecidedPositive())
                .as("b was mentioned before a, so a stack scan in order would offer b first")
                .isEqualTo("b");
        ps.decide("b", "1.0");
        assertThat(ps.nextUndecidedPositive()).isEqualTo("a");
        ps.decide("a", "2.0");
        assertThat(ps.nextUndecidedPositive()).isNull();

        // Backtracking rebuilds the index from the surviving prefix.
        ps.backtrack(1);
        assertThat(ps.nextUndecidedPositive()).isEqualTo("b");
        assertThat(ps.assignmentsFor("b")).hasSize(2);
        assertThat(ps.assignmentsFor("a")).hasSize(1);
        assertThat(ps.assignmentsFor("nobody")).isEmpty();
    }

    @Test
    void a_package_decided_after_its_first_mention_is_not_offered_again_after_a_backtrack() {
        PartialSolution ps = new PartialSolution(new HashMap<>());
        ps.decide("root", "1.0"); // level 1
        Incompatibility cause = new Incompatibility(
                List.of(Term.positive("root", VersionSet.exact("1.0"))), new Incompatibility.Cause.Root("root", "1.0"));
        // The usual order of a dependency: required by a derivation first, decided afterwards.
        ps.derive(Term.positive("a", VersionSet.ALL), cause);
        ps.decide("a", "1.0"); // level 2
        ps.derive(Term.positive("b", VersionSet.ALL), cause);
        ps.decide("b", "1.0"); // level 3
        assertThat(ps.nextUndecidedPositive()).isNull();

        // The surviving prefix still decides a; a stack scan in order would skip it as decided.
        ps.backtrack(2);
        assertThat(ps.nextUndecidedPositive())
                .as("a is decided in the surviving prefix; b is required and undecided")
                .isEqualTo("b");
        assertThat(ps.decisionsUnsorted()).containsOnlyKeys("root", "a");
        ps.decide("b", "1.0");
        assertThat(ps.nextUndecidedPositive()).isNull();

        ps.backtrack(1);
        assertThat(ps.nextUndecidedPositive())
                .as("a's decision is gone, a is required again")
                .isEqualTo("a");
    }
}
