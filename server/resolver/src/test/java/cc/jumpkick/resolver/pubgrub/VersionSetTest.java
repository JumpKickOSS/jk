// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver.pubgrub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class VersionSetTest {

    @Test
    void empty_is_subset_of_everything() {
        assertThat(VersionSet.EMPTY.subsetOf(VersionSet.ALL)).isTrue();
        assertThat(VersionSet.EMPTY.subsetOf(VersionSet.exact("1.0"))).isTrue();
    }

    @Test
    void all_contains_anything() {
        assertThat(VersionSet.ALL.contains("anything")).isTrue();
        assertThat(VersionSet.ALL.complement()).isSameAs(VersionSet.EMPTY);
    }

    @Test
    void only_a_set_with_a_ceiling_has_an_upper_bound() {
        assertThat(VersionSet.atLeast("1.0", true).hasUpperBound()).isFalse();
        assertThat(VersionSet.ALL.hasUpperBound()).isFalse();
        assertThat(VersionSet.between("1.0", true, "2.0", false).hasUpperBound())
                .isTrue();
        assertThat(VersionSet.exact("1.0").hasUpperBound()).isTrue();
        assertThat(VersionSet.lessThan("2.0", false).hasUpperBound()).isTrue();
        // A floor with one version excluded still reaches upward without end.
        VersionSet holed = VersionSet.atLeast("1.0", true)
                .intersect(VersionSet.exact("1.5").complement());
        assertThat(holed).isInstanceOf(VersionSet.Union.class);
        assertThat(holed.hasUpperBound()).isFalse();
        VersionSet twoRanges =
                VersionSet.between("1.0", true, "1.5", false).union(VersionSet.between("2.0", true, "3.0", false));
        assertThat(twoRanges.hasUpperBound()).isTrue();
    }

    @Test
    void exact_contains_only_that_version() {
        VersionSet v = VersionSet.exact("1.2.3");
        assertThat(v.contains("1.2.3")).isTrue();
        assertThat(v.contains("1.2.4")).isFalse();
        assertThat(v.contains("1.2.2")).isFalse();
    }

    @Test
    void asExactSingleton_only_for_point_ranges() {
        assertThat(VersionSet.exact("1.2.3").asExactSingleton()).contains("1.2.3");
        assertThat(VersionSet.atLeast("1.0", true).asExactSingleton()).isEmpty();
        assertThat(VersionSet.ALL.asExactSingleton()).isEmpty();
        assertThat(VersionSet.between("1.0", true, "2.0", false).asExactSingleton())
                .isEmpty();
    }

    @Test
    void range_inclusive_exclusive_endpoints() {
        VersionSet r = VersionSet.between("1.0", true, "2.0", false);
        assertThat(r.contains("1.0")).isTrue();
        assertThat(r.contains("1.5")).isTrue();
        assertThat(r.contains("2.0")).isFalse();
        assertThat(r.contains("0.9")).isFalse();
    }

    @Test
    void intersect_of_overlapping_ranges() {
        VersionSet a = VersionSet.between("1.0", true, "3.0", false);
        VersionSet b = VersionSet.between("2.0", true, "4.0", false);
        VersionSet ab = a.intersect(b);
        assertThat(ab.contains("2.5")).isTrue();
        assertThat(ab.contains("1.5")).isFalse();
        assertThat(ab.contains("3.0")).isFalse();
    }

    @Test
    void intersect_of_disjoint_ranges_is_empty() {
        VersionSet a = VersionSet.between("1.0", true, "2.0", false);
        VersionSet b = VersionSet.between("3.0", true, "4.0", false);
        assertThat(a.intersect(b).isEmpty()).isTrue();
    }

    @Test
    void union_of_overlapping_ranges_merges() {
        VersionSet a = VersionSet.between("1.0", true, "2.0", false);
        VersionSet b = VersionSet.between("1.5", true, "3.0", false);
        VersionSet ab = a.union(b);
        assertThat(ab.contains("1.0")).isTrue();
        assertThat(ab.contains("2.5")).isTrue();
        assertThat(ab.contains("3.0")).isFalse();
        // Should fuse into a single range, not a Union.
        assertThat(ab).isInstanceOf(VersionSet.Range.class);
    }

    @Test
    void union_of_disjoint_ranges_is_a_union() {
        VersionSet a = VersionSet.between("1.0", true, "2.0", false);
        VersionSet b = VersionSet.between("3.0", true, "4.0", false);
        VersionSet ab = a.union(b);
        assertThat(ab).isInstanceOf(VersionSet.Union.class);
        assertThat(ab.contains("1.5")).isTrue();
        assertThat(ab.contains("2.5")).isFalse();
        assertThat(ab.contains("3.5")).isTrue();
        assertCanonical(ab);
    }

    @Test
    void complement_of_range_is_two_disjoint_ranges() {
        VersionSet r = VersionSet.between("1.0", true, "2.0", false);
        VersionSet c = r.complement();
        assertThat(c.contains("0.5")).isTrue();
        assertThat(c.contains("1.0")).isFalse();
        assertThat(c.contains("1.5")).isFalse();
        assertThat(c.contains("2.0")).isTrue();
        assertThat(c.contains("3.0")).isTrue();
        assertCanonical(c);
    }

    @Test
    void complement_of_complement_is_self() {
        VersionSet r = VersionSet.between("1.0", true, "2.0", false);
        assertThat(r.complement().complement().contains("1.0")).isTrue();
        assertThat(r.complement().complement().contains("2.0")).isFalse();
        // Structural: double complement of a single range is that range again.
        assertThat(r.complement().complement()).isEqualTo(r);
    }

    @Test
    void subset_holds_for_nested_ranges() {
        VersionSet inner = VersionSet.between("1.5", true, "1.8", false);
        VersionSet outer = VersionSet.between("1.0", true, "2.0", false);
        assertThat(inner.subsetOf(outer)).isTrue();
        assertThat(outer.subsetOf(inner)).isFalse();
    }

    @Test
    void inverted_range_at_construction_is_rejected() {
        assertThatThrownBy(() -> VersionSet.between("2.0", true, "1.0", true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- R1: flatten / merge / canonical form --------------------------------

    @Test
    void union_of_touching_ranges_merges() {
        // [1.0, 2.0) ∪ [2.0, 3.0) shares the endpoint 2.0 (second inclusive) → one range.
        VersionSet a = VersionSet.between("1.0", true, "2.0", false);
        VersionSet b = VersionSet.between("2.0", true, "3.0", false);
        VersionSet ab = a.union(b);
        assertThat(ab).isInstanceOf(VersionSet.Range.class);
        assertThat(ab.contains("1.0")).isTrue();
        assertThat(ab.contains("2.0")).isTrue();
        assertThat(ab.contains("2.9")).isTrue();
        assertThat(ab.contains("3.0")).isFalse();
    }

    @Test
    void of_flattens_nested_unions() {
        VersionSet u1 =
                VersionSet.between("1.0", true, "2.0", false).union(VersionSet.between("3.0", true, "4.0", false));
        VersionSet u2 =
                VersionSet.between("5.0", true, "6.0", false).union(VersionSet.between("7.0", true, "8.0", false));
        // Pre-R1: Union.intersect could nest; of() must flatten.
        VersionSet combined = VersionSet.Union.of(List.of(u1, u2));
        assertThat(combined).isInstanceOf(VersionSet.Union.class);
        assertCanonical(combined);
        VersionSet.Union u = (VersionSet.Union) combined;
        assertThat(u.parts()).hasSize(4);
        assertThat(combined.contains("1.5")).isTrue();
        assertThat(combined.contains("3.5")).isTrue();
        assertThat(combined.contains("5.5")).isTrue();
        assertThat(combined.contains("7.5")).isTrue();
        assertThat(combined.contains("2.5")).isFalse();
    }

    @Test
    void of_merges_overlapping_parts_across_nested_unions() {
        // Two unions that together form a contiguous [1,4).
        VersionSet u1 =
                VersionSet.between("1.0", true, "2.5", false).union(VersionSet.between("5.0", true, "6.0", false));
        VersionSet u2 =
                VersionSet.between("2.0", true, "4.0", false).union(VersionSet.between("7.0", true, "8.0", false));
        VersionSet combined = VersionSet.Union.of(List.of(u1, u2));
        assertCanonical(combined);
        // [1,2.5) ∪ [2,4) → [1,4); plus [5,6) and [7,8).
        assertThat(combined).isInstanceOf(VersionSet.Union.class);
        VersionSet.Union u = (VersionSet.Union) combined;
        assertThat(u.parts()).hasSize(3);
        assertThat(combined.contains("1.0")).isTrue();
        assertThat(combined.contains("3.0")).isTrue();
        assertThat(combined.contains("4.0")).isFalse();
        assertThat(combined.contains("5.5")).isTrue();
        assertThat(combined.contains("7.5")).isTrue();
    }

    @Test
    void union_intersect_union_is_canonical_not_nested() {
        VersionSet a =
                VersionSet.between("1.0", true, "3.0", false).union(VersionSet.between("5.0", true, "7.0", false));
        VersionSet b =
                VersionSet.between("2.0", true, "4.0", false).union(VersionSet.between("6.0", true, "8.0", false));
        VersionSet ab = a.intersect(b);
        // [2,3) ∪ [6,7)
        assertThat(ab).isInstanceOf(VersionSet.Union.class);
        assertCanonical(ab);
        assertThat(ab.contains("2.5")).isTrue();
        assertThat(ab.contains("3.5")).isFalse();
        assertThat(ab.contains("6.5")).isTrue();
        assertThat(ab.contains("7.5")).isFalse();
    }

    @Test
    void successive_single_version_exclusions_stay_canonical() {
        // Simulate PubGrub excluding highest versions one at a time from ALL:
        // ¬{3.0} ∩ ¬{2.0} ∩ ¬{1.0} over a discrete-ish hole pattern via exact complements.
        VersionSet allowed = VersionSet.ALL;
        for (String v : List.of("3.0", "2.0", "1.0")) {
            allowed = allowed.intersect(VersionSet.exact(v).complement());
            assertCanonical(allowed);
        }
        // ALL − {1,2,3} = (-∞,1) ∪ (1,2) ∪ (2,3) ∪ (3,+∞) — four ranges, no nesting.
        assertThat(allowed).isInstanceOf(VersionSet.Union.class);
        VersionSet.Union u = (VersionSet.Union) allowed;
        assertThat(u.parts()).hasSize(4);
        assertThat(allowed.contains("0.9")).isTrue();
        assertThat(allowed.contains("1.0")).isFalse();
        assertThat(allowed.contains("1.5")).isTrue();
        assertThat(allowed.contains("2.0")).isFalse();
        assertThat(allowed.contains("2.5")).isTrue();
        assertThat(allowed.contains("3.0")).isFalse();
        assertThat(allowed.contains("4.0")).isTrue();
    }

    @Test
    void of_absorbs_all_and_drops_empty() {
        VersionSet r = VersionSet.exact("1.0");
        assertThat(VersionSet.Union.of(List.of(r, VersionSet.ALL))).isSameAs(VersionSet.ALL);
        assertThat(VersionSet.Union.of(List.of(VersionSet.EMPTY, r))).isEqualTo(r);
        assertThat(VersionSet.Union.of(List.of(VersionSet.EMPTY))).isSameAs(VersionSet.EMPTY);
    }

    @Test
    void subsetOf_short_circuits_on_empty_and_all() {
        VersionSet r = VersionSet.exact("1.0");
        assertThat(VersionSet.EMPTY.subsetOf(r)).isTrue();
        assertThat(r.subsetOf(VersionSet.ALL)).isTrue();
        assertThat(VersionSet.ALL.subsetOf(r)).isFalse();
        assertThat(r.subsetOf(VersionSet.EMPTY)).isFalse();
    }

    @Test
    void complement_of_union_is_canonical() {
        VersionSet u =
                VersionSet.between("1.0", true, "2.0", false).union(VersionSet.between("3.0", true, "4.0", false));
        VersionSet c = u.complement();
        assertCanonical(c);
        // Double complement recovers membership (structural equality of multi-range sets
        // is via parts; membership is the contract).
        for (String v : List.of("0.5", "1.0", "1.5", "2.0", "2.5", "3.0", "3.5", "4.0", "5.0")) {
            assertThat(c.complement().contains(v)).as(v).isEqualTo(u.contains(v));
        }
        assertThat(c.complement()).isEqualTo(u);
    }

    /**
     * Property-style: random unions/intersects of simple ranges never produce nested Unions and
     * always keep parts sorted and non-adjacent.
     */
    @Test
    void random_algebra_stays_canonical() {
        Random rnd = new Random(0xC0FFEE);
        String[] vers = {"0.5", "1.0", "1.5", "2.0", "2.5", "3.0", "3.5", "4.0", "5.0", "6.0"};
        VersionSet acc = VersionSet.EMPTY;
        for (int i = 0; i < 200; i++) {
            VersionSet next = randomRange(rnd, vers);
            acc = (i % 3 == 0) ? acc.intersect(next) : acc.union(next);
            assertCanonical(acc);
            for (String v : vers) {
                boolean inAcc = acc.contains(v);
                // Over the total order every string is in or out — complement flips membership.
                assertThat(acc.complement().contains(v)).as("¬acc ∋ %s", v).isEqualTo(!inAcc);
                assertThat(acc.complement().complement().contains(v))
                        .as("¬¬acc ∋ %s", v)
                        .isEqualTo(inAcc);
            }
            assertCanonical(acc.complement());
        }
    }

    @Test
    void many_exclusions_from_all_membership_round_trips() {
        // 50 excluded exact versions — the soft-BOM stress shape in miniature.
        VersionSet allowed = VersionSet.ALL;
        List<String> excluded = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            String v = i + ".0.0";
            excluded.add(v);
            allowed = allowed.intersect(VersionSet.exact(v).complement());
            assertCanonical(allowed);
        }
        for (String v : excluded) {
            assertThat(allowed.contains(v)).as(v).isFalse();
        }
        // Versions not among the 50 exact holes remain allowed.
        assertThat(allowed.contains("100.0.0")).isTrue();
        assertThat(allowed.contains("0.1.0")).isTrue();
        // Double complement preserves membership.
        VersionSet back = allowed.complement().complement();
        for (String v : excluded) {
            assertThat(back.contains(v)).as(v).isFalse();
        }
        assertThat(back.contains("99.0.0")).isTrue();
        assertThat(back).isEqualTo(allowed);
    }

    // --- helpers -------------------------------------------------------------

    /** Assert no nested unions; Union parts are Ranges, size≥2, sorted, pairwise non-touching. */
    private static void assertCanonical(VersionSet set) {
        switch (set) {
            case VersionSet.Empty ignored -> {
                // ok
            }
            case VersionSet.All ignored -> {
                // ok
            }
            case VersionSet.Range ignored -> {
                // ok
            }
            case VersionSet.Union u -> {
                assertThat(u.parts()).hasSizeGreaterThanOrEqualTo(2);
                for (VersionSet.Range part : u.parts()) {
                    assertThat(part).isInstanceOf(VersionSet.Range.class);
                }
                for (int i = 0; i < u.parts().size(); i++) {
                    VersionSet.Range a = u.parts().get(i);
                    if (i + 1 < u.parts().size()) {
                        VersionSet.Range b = u.parts().get(i + 1);
                        assertThat(a.overlapsOrTouches(b))
                                .as("parts %s and %s must not overlap or touch", a, b)
                                .isFalse();
                        assertThat(VersionSet.Range.compareLow(a, b))
                                .as("parts must be sorted by lower bound: %s then %s", a, b)
                                .isLessThanOrEqualTo(0);
                    }
                }
            }
        }
    }

    private static VersionSet randomRange(Random rnd, String[] vers) {
        int i = rnd.nextInt(vers.length);
        int j = rnd.nextInt(vers.length);
        if (i > j) {
            int t = i;
            i = j;
            j = t;
        }
        boolean minInc = rnd.nextBoolean();
        boolean maxInc = rnd.nextBoolean();
        if (i == j && (!minInc || !maxInc)) {
            // Avoid empty exact-ish ranges.
            return VersionSet.exact(vers[i]);
        }
        try {
            return VersionSet.between(vers[i], minInc, vers[j], maxInc);
        } catch (IllegalArgumentException e) {
            return VersionSet.exact(vers[i]);
        }
    }
}
