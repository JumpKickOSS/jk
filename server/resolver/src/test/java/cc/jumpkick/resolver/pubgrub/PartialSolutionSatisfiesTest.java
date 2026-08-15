// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver.pubgrub;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 *  — the positive-term absence guard in {@link PartialSolution#satisfies}, mirror of the
 *  absence guard in {@code contradicts()}: a package holding only negative assignments
 * (the post-backjump state) may end up unselected entirely, so no negative narrowing can satisfy
 * a POSITIVE term, no matter how tight the set projection looks.
 */
class PartialSolutionSatisfiesTest {

    private static Incompatibility cause(Term t) {
        return new Incompatibility(List.of(t), new Incompatibility.Cause.NoVersions(t.pkg(), t.effectiveVersions()));
    }

    @Test
    void positive_term_is_not_satisfied_by_negative_only_assignments() {
        PartialSolution s = new PartialSolution(Map.of());
        Term notB12 = Term.positive("b", VersionSet.exact("1.2")).invert();
        s.derive(notB12, cause(notB12));

        // Set-wise, continuous(b) = ALL \ {1.2} ⊆ ALL — but b has no positive commitment and may
        // never be selected, so the positive term must not be reported satisfied.
        assertThat(s.satisfies(Term.positive("b", VersionSet.ALL))).isFalse();
        // The negative view of the same state still satisfies by plain set logic.
        assertThat(s.satisfies(notB12)).isTrue();
    }

    @Test
    void positive_term_on_unmentioned_package_is_not_satisfied() {
        PartialSolution s = new PartialSolution(Map.of());
        assertThat(s.satisfies(Term.positive("ghost", VersionSet.ALL))).isFalse();
    }

    @Test
    void positive_term_satisfied_once_a_positive_commitment_exists() {
        PartialSolution s = new PartialSolution(Map.of());
        Term b1x = Term.positive("b", VersionSet.between("1.0", true, "2.0", false));
        s.derive(b1x, cause(b1x));
        assertThat(s.satisfies(Term.positive("b", VersionSet.between("0.5", true, "3.0", false))))
                .isTrue();
        // And a decision satisfies exactly.
        s.decide("c", "2.0");
        assertThat(s.satisfies(Term.positive("c", VersionSet.exact("2.0")))).isTrue();
    }

    @Test
    void backtrack_restores_absence_semantics() {
        PartialSolution s = new PartialSolution(Map.of());
        Term notB12 = Term.positive("b", VersionSet.exact("1.2")).invert();
        s.derive(notB12, cause(notB12));
        s.decide("a", "1.0"); // level 1
        Term b1x = Term.positive("b", VersionSet.between("1.0", true, "2.0", false));
        s.derive(b1x, cause(b1x));
        assertThat(s.satisfies(Term.positive("b", VersionSet.ALL))).isTrue();

        s.backtrack(0);
        // The positive commitment was rolled back; only ¬b{1.2} survives.
        assertThat(s.satisfies(Term.positive("b", VersionSet.ALL))).isFalse();
    }
}
