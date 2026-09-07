// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver.pubgrub;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Counterexamples the solver property ({@link PubGrubSolverPropertyTest}) shrank to, kept as
 * examples so the five defects they exposed stay fixed: propagation not resuming on a learned
 * incompatibility's packages; two negative terms intersecting to a positive one; the previous
 * satisfier's level being skipped when it shared the most recent satisfier's level; resolution
 * dropping a pivot term the satisfier only partially satisfied, without the paper's residual; and
 * a package state whose continuous set froze at bind time, so a term outside a lazy singleton
 * universe was re-derived until the step budget ran out.
 */
class PubGrubShrunkCounterexampleTest {

    /** The only satisfying choice for {@code p0} is a pre-release reached by backtracking off a stable. */
    @Test
    void a_pre_release_is_selected_when_no_stable_candidate_can_be_satisfied() throws Exception {
        PackageSource src = InMemoryPackageSource.builder()
                .version("p0", "2.0", d -> d.require("p1", VersionSet.exact("3.0")))
                .version("p0", "3.0-rc1")
                .version("p1", "3.0-rc1", d -> d.require("p0", VersionSet.lessThan("3.0-rc2", false)))
                .build();
        List<Term> root = List.of(Term.positive("p0", VersionSet.ALL), Term.positive("p1", VersionSet.ALL));
        assertThat(solve(src, root)).containsEntry("p0", "3.0-rc1").containsEntry("p1", "3.0-rc1");
    }

    /** Two learned clauses about {@code p0} share a level and must resolve into one about {@code p1}. */
    @Test
    void learned_clauses_at_one_level_resolve_instead_of_backtracking_to_the_root() throws Exception {
        PackageSource src = InMemoryPackageSource.builder()
                .version("p0", "2.0", d -> d.require("p1", VersionSet.between("2.0", true, "3.0", false)))
                .version("p0", "3.0-rc2", d -> d.require("p1", VersionSet.between("1.0", true, "2.0", false)))
                .version("p0", "3.0", d -> d.require("p2", VersionSet.lessThan("1.0", false)))
                .version("p0", "1.1", d -> d.require("p2", VersionSet.lessThan("1.1", false))
                        .require("p1", VersionSet.atLeast("2.0", true)))
                .version("p1", "3.0-rc1")
                .version("p1", "1.0")
                .version("p1", "3.0")
                .version("p2", "3.0-rc2", d -> d.require("p0", VersionSet.ALL).require("p1", VersionSet.exact("1.1")))
                .version("p2", "1.0", d -> d.require("p1", VersionSet.exact("3.0")))
                .version("p2", "3.0")
                .build();
        List<Term> root = List.of(
                Term.positive("p1", VersionSet.atLeast("1.0", true)),
                Term.positive("p0", VersionSet.atLeast("1.0", true)),
                Term.positive("p2", VersionSet.exact("3.0")));
        Map<String, String> solution = solve(src, root);
        assertThat(solution).containsEntry("p2", "3.0");
        assertThat(solution.get("p0")).isIn("2.0", "3.0-rc2");
        assertThat(solution.get("p1")).isIn("3.0-rc1", "1.0");
    }

    /** A partially satisfied pivot needs its residual, or the learned clause rules out a lone satisfying leaf. */
    @Test
    void a_partially_satisfied_pivot_keeps_its_residual() throws Exception {
        PackageSource src = InMemoryPackageSource.builder()
                .version("p0", "3.0-rc1")
                .version("p0", "2.0", d -> d.require("p1", VersionSet.ALL)
                        .require("p3", VersionSet.lessThan("3.0-rc1", false)))
                .version("p0", "1.0", d -> d.require("p2", VersionSet.between("3.0-rc2", true, "3.0", false)))
                .version("p0", "1.1", d -> d.require("p2", VersionSet.lessThan("3.0-rc2", false))
                        .require("p1", VersionSet.between("1.1", true, "3.0-rc2", false)))
                .version("p1", "1.0", d -> d.require("p2", VersionSet.between("1.1", true, "2.0", false)))
                .version("p1", "3.0", d -> d.require("p3", VersionSet.ALL)
                        .require("p0", VersionSet.between("3.0-rc1", true, "3.0", false)))
                .version("p1", "3.0-rc2", d -> d.require("p3", VersionSet.between("3.0-rc2", true, "3.0", false)))
                .version("p1", "1.1", d -> d.require("p2", VersionSet.lessThan("3.0-rc1", false))
                        .require("p0", VersionSet.between("3.0-rc2", true, "3.0", false)))
                .version("p2", "3.0-rc1", d -> d.require("p3", VersionSet.atLeast("1.1", true)))
                .version("p2", "1.0", d -> d.require("p0", VersionSet.ALL).require("p1", VersionSet.exact("1.0")))
                .version("p2", "1.1", d -> d.require("p0", VersionSet.atLeast("3.0", true)))
                .version("p3", "1.0")
                .version("p3", "3.0-rc1", d -> d.require("p2", VersionSet.exact("3.0"))
                        .require("p0", VersionSet.exact("3.0-rc2")))
                .version("p3", "1.1", d -> d.require("p1", VersionSet.atLeast("1.1", true))
                        .require("p0", VersionSet.exact("3.0-rc2")))
                .version("p3", "2.0", d -> d.require("p1", VersionSet.lessThan("3.0", false))
                        .require("p2", VersionSet.between("2.0", true, "3.0-rc2", false)))
                .build();
        List<Term> root = List.of(Term.positive("p3", VersionSet.lessThan("3.0-rc1", false)));
        assertThat(solve(src, root)).containsEntry("p3", "1.0");
    }

    /** A term outside a lazily seeded singleton universe must be derived once, then widen the universe. */
    @Test
    void a_derivation_outside_a_lazy_singleton_universe_settles_and_widens() throws Exception {
        PackageSource src = InMemoryPackageSource.builder()
                .version("p0", "3.0-rc2", d -> d.require("p1", VersionSet.exact("1.0")))
                .version("p0", "3.0-rc1")
                .version("p0", "1.0", d -> d.require("p1", VersionSet.exact("3.0-rc2")))
                .version("p1", "3.0-rc2", d -> d.require("p0", VersionSet.exact("1.1")))
                .version("p1", "1.0")
                .build();
        List<Term> root = List.of(Term.positive("p0", VersionSet.lessThan("3.0", false)));
        Map<String, String> solution = solve(src, root);
        assertThat(solution.get("p0")).isIn("3.0-rc1", "3.0-rc2");
    }

    /** The production contract: a narrow solve, then one wide retry when the narrow unsat may be a cap artifact. */
    private static Map<String, String> solve(PackageSource src, List<Term> root) throws Exception {
        PubGrubSolver narrow = new PubGrubSolver(src);
        try {
            return narrow.solve("root", "1.0", root);
        } catch (UnsatisfiableException first) {
            assertThat(narrow.maybeIncomplete())
                    .as("a narrow unsat here must admit it may be incomplete")
                    .isTrue();
            return new PubGrubSolver(src).withWideUniverses().solve("root", "1.0", root);
        }
    }
}
