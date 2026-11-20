// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver.pubgrub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TermTest {

    @Test
    void invert_flips_polarity() {
        Term t = Term.positive("a", VersionSet.exact("1.0"));
        assertThat(t.invert().positive()).isFalse();
        assertThat(t.invert().invert()).isEqualTo(t);
    }

    @Test
    void only_a_negative_term_over_no_versions_always_holds() {
        assertThat(Term.negative("a", VersionSet.EMPTY).alwaysHolds()).isTrue();
        Term absent = Term.negative("a", VersionSet.exact("1.0")).intersect(Term.negative("a", VersionSet.ALL));
        assertThat(absent.effectiveVersions().isEmpty()).isTrue();
        assertThat(absent.alwaysHolds()).as("absent is a constraint").isFalse();
        assertThat(Term.positive("a", VersionSet.ALL).alwaysHolds()).isFalse();
        assertThat(Term.positive("a", VersionSet.EMPTY).alwaysHolds()).isFalse();
    }

    @Test
    void relation_satisfies_when_this_implies_other() {
        Term tighter = Term.positive("a", VersionSet.exact("1.5"));
        Term wider = Term.positive("a", VersionSet.between("1.0", true, "2.0", false));
        assertThat(tighter.relation(wider)).isEqualTo(Term.Relation.SATISFIES);
        // The wider doesn't necessarily satisfy the tighter.
        assertThat(wider.relation(tighter)).isNotEqualTo(Term.Relation.SATISFIES);
    }

    @Test
    void relation_contradicts_when_disjoint() {
        Term a = Term.positive("a", VersionSet.exact("1.0"));
        Term b = Term.positive("a", VersionSet.exact("2.0"));
        assertThat(a.relation(b)).isEqualTo(Term.Relation.CONTRADICTS);
    }

    @Test
    void relation_overlaps_when_partial() {
        Term a = Term.positive("a", VersionSet.between("1.0", true, "3.0", false));
        Term b = Term.positive("a", VersionSet.between("2.0", true, "4.0", false));
        assertThat(a.relation(b)).isEqualTo(Term.Relation.OVERLAPS);
    }

    @Test
    void a_positive_term_satisfies_a_negative_one_it_is_disjoint_from() {
        Term notOne = Term.negative("a", VersionSet.exact("1.0"));
        assertThat(Term.positive("a", VersionSet.exact("2.0")).relation(notOne)).isEqualTo(Term.Relation.SATISFIES);
        assertThat(Term.positive("a", VersionSet.exact("1.0")).relation(notOne)).isEqualTo(Term.Relation.CONTRADICTS);
        assertThat(Term.positive("a", VersionSet.ALL).relation(notOne)).isEqualTo(Term.Relation.OVERLAPS);
        assertThat(Term.positive("a", VersionSet.exact("1.0")).relation(Term.negative("a", VersionSet.ALL)))
                .as("present contradicts absent")
                .isEqualTo(Term.Relation.CONTRADICTS);
    }

    @Test
    void a_negative_term_never_satisfies_a_positive_one() {
        Term notOne = Term.negative("a", VersionSet.exact("1.0"));
        assertThat(notOne.relation(Term.positive("a", VersionSet.ALL)))
                .as("the package may be absent")
                .isEqualTo(Term.Relation.OVERLAPS);
        assertThat(notOne.relation(Term.positive("a", VersionSet.atLeast("2.0", true))))
                .isEqualTo(Term.Relation.OVERLAPS);
        assertThat(notOne.relation(Term.positive("a", VersionSet.exact("1.0")))).isEqualTo(Term.Relation.CONTRADICTS);
    }

    @Test
    void absent_contradicts_every_positive_term() {
        Term absent = Term.negative("a", VersionSet.ALL);
        assertThat(absent.relation(Term.positive("a", VersionSet.exact("1.0")))).isEqualTo(Term.Relation.CONTRADICTS);
        assertThat(absent.relation(Term.positive("a", VersionSet.ALL))).isEqualTo(Term.Relation.CONTRADICTS);
    }

    @Test
    void two_negative_terms_never_contradict() {
        Term notNew = Term.negative("a", VersionSet.atLeast("1.0", true));
        Term notOld = Term.negative("a", VersionSet.lessThan("1.0", false));
        assertThat(notNew.relation(notOld)).as("both hold when a is absent").isEqualTo(Term.Relation.OVERLAPS);
        assertThat(notOld.relation(notNew)).isEqualTo(Term.Relation.OVERLAPS);
        Term absent = Term.negative("a", VersionSet.ALL);
        assertThat(absent.relation(notNew)).isEqualTo(Term.Relation.SATISFIES);
        assertThat(notNew.relation(absent)).isEqualTo(Term.Relation.OVERLAPS);
    }

    @Test
    void a_negative_term_satisfies_one_that_excludes_fewer_versions() {
        Term notOne = Term.negative("a", VersionSet.exact("1.0"));
        Term notOneToTwo = Term.negative("a", VersionSet.between("1.0", true, "2.0", true));
        assertThat(notOneToTwo.relation(notOne)).isEqualTo(Term.Relation.SATISFIES);
        assertThat(notOne.relation(notOneToTwo)).isEqualTo(Term.Relation.OVERLAPS);
    }

    @Test
    void relations_over_no_versions() {
        Term none = Term.positive("a", VersionSet.EMPTY);
        Term always = Term.negative("a", VersionSet.EMPTY);
        Term one = Term.positive("a", VersionSet.exact("1.0"));
        Term notOne = Term.negative("a", VersionSet.exact("1.0"));
        // A positive term over no versions holds for no assignment and implies every term.
        assertThat(none.relation(one)).isEqualTo(Term.Relation.SATISFIES);
        assertThat(none.relation(notOne)).isEqualTo(Term.Relation.SATISFIES);
        assertThat(notOne.relation(none)).isEqualTo(Term.Relation.CONTRADICTS);
        assertThat(always.relation(none)).isEqualTo(Term.Relation.CONTRADICTS);
        // ¬a ∅ holds for every assignment: every term implies it, and it implies only a term that also always holds.
        assertThat(one.relation(always)).isEqualTo(Term.Relation.SATISFIES);
        assertThat(notOne.relation(always)).isEqualTo(Term.Relation.SATISFIES);
        assertThat(always.relation(always)).isEqualTo(Term.Relation.SATISFIES);
        assertThat(always.relation(one)).isEqualTo(Term.Relation.OVERLAPS);
        assertThat(always.relation(notOne)).isEqualTo(Term.Relation.OVERLAPS);
    }

    @Test
    void negative_term_effective_set_is_complement() {
        Term t = Term.negative("a", VersionSet.exact("1.0"));
        assertThat(t.effectiveVersions().contains("1.0")).isFalse();
        assertThat(t.effectiveVersions().contains("2.0")).isTrue();
    }

    @Test
    void two_negative_terms_intersect_to_the_negative_of_their_union() {
        Term a = Term.negative("a", VersionSet.between("1.0", true, "2.0", false));
        Term b = Term.negative("a", VersionSet.between("2.0", true, "3.0", false));
        Term both = a.intersect(b);
        assertThat(both.positive()).as("still permits the package to be absent").isFalse();
        assertThat(both.versions()).isEqualTo(VersionSet.between("1.0", true, "3.0", false));
        assertThat(both.effectiveVersions().contains("0.5")).isTrue();
        assertThat(both.effectiveVersions().contains("2.5")).isFalse();
    }

    @Test
    void a_positive_term_makes_the_intersection_positive() {
        Term a = Term.positive("a", VersionSet.atLeast("1.0", true));
        Term b = Term.negative("a", VersionSet.exact("2.0"));
        Term both = a.intersect(b);
        assertThat(both.positive()).isTrue();
        assertThat(both.versions().contains("1.5")).isTrue();
        assertThat(both.versions().contains("2.0")).isFalse();
    }

    @Test
    void intersecting_across_packages_throws() {
        Term a = Term.positive("a", VersionSet.exact("1.0"));
        Term b = Term.positive("b", VersionSet.exact("1.0"));
        assertThatThrownBy(() -> a.intersect(b)).isInstanceOf(IllegalArgumentException.class);
    }
}
