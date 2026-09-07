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
    void contradicts_when_intersection_is_empty() {
        Term a = Term.positive("a", VersionSet.exact("1.0"));
        Term b = Term.positive("a", VersionSet.exact("2.0"));
        assertThat(a.contradicts(b)).isTrue();
    }

    @Test
    void does_not_contradict_when_sets_overlap() {
        Term a = Term.positive("a", VersionSet.between("1.0", true, "3.0", false));
        Term b = Term.positive("a", VersionSet.between("2.0", true, "4.0", false));
        assertThat(a.contradicts(b)).isFalse();
    }

    @Test
    void terms_on_different_packages_never_contradict() {
        Term a = Term.positive("a", VersionSet.exact("1.0"));
        Term b = Term.positive("b", VersionSet.exact("1.0"));
        assertThat(a.contradicts(b)).isFalse();
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
