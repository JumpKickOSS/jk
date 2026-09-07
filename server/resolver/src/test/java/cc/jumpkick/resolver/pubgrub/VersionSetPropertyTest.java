// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver.pubgrub;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/** {@link VersionSet} is a Boolean algebra over the total order of versions; membership proves it. */
class VersionSetPropertyTest {

    static final List<String> POOL =
            List.of("0.5", "1.0", "1.0.1", "1.1-alpha", "1.1", "2.0-SNAPSHOT", "2.0", "2.10", "3.0-rc1", "3.0");

    @Provide
    Arbitrary<VersionSet> sets() {
        Arbitrary<String> v = Arbitraries.of(POOL);
        Arbitrary<Boolean> bit = Arbitraries.of(true, false);
        Arbitrary<VersionSet> simple = Arbitraries.oneOf(
                Arbitraries.just(VersionSet.EMPTY),
                Arbitraries.just(VersionSet.ALL),
                v.map(VersionSet::exact),
                v.flatMap(x -> bit.map(inc -> VersionSet.atLeast(x, inc))),
                v.flatMap(x -> bit.map(inc -> VersionSet.lessThan(x, inc))),
                Arbitraries.integers().between(0, POOL.size() - 2).flatMap(i -> Arbitraries.integers()
                        .between(i + 1, POOL.size() - 1)
                        .flatMap(j -> bit.flatMap(loInc ->
                                bit.map(hiInc -> VersionSet.between(POOL.get(i), loInc, POOL.get(j), hiInc))))));
        return Arbitraries.recursive(
                () -> simple,
                s -> Arbitraries.oneOf(
                        s.flatMap(a -> s.map(a::union)),
                        s.flatMap(a -> s.map(a::intersect)),
                        s.map(VersionSet::complement)),
                0,
                3);
    }

    @Property(tries = 500)
    void union_is_disjunction_of_membership(@ForAll("sets") VersionSet a, @ForAll("sets") VersionSet b) {
        VersionSet u = a.union(b);
        for (String v : POOL) {
            assertThat(u.contains(v)).as("%s in union of %s and %s", v, a, b).isEqualTo(a.contains(v) || b.contains(v));
        }
    }

    @Property(tries = 500)
    void intersection_is_conjunction_of_membership(@ForAll("sets") VersionSet a, @ForAll("sets") VersionSet b) {
        VersionSet i = a.intersect(b);
        for (String v : POOL) {
            assertThat(i.contains(v))
                    .as("%s in intersection of %s and %s", v, a, b)
                    .isEqualTo(a.contains(v) && b.contains(v));
        }
    }

    @Property(tries = 500)
    void complement_flips_membership_and_is_an_involution(@ForAll("sets") VersionSet a) {
        VersionSet c = a.complement();
        for (String v : POOL) {
            assertThat(c.contains(v)).as("%s in complement of %s", v, a).isEqualTo(!a.contains(v));
            assertThat(c.complement().contains(v))
                    .as("%s in double complement of %s", v, a)
                    .isEqualTo(a.contains(v));
        }
    }

    @Property(tries = 500)
    void emptiness_agrees_with_membership(@ForAll("sets") VersionSet a) {
        // An empty set contains nothing from the pool; a set that contains nothing from the pool
        // may still be non-empty (a gap between pool versions), so only one direction is a law.
        if (a.isEmpty()) {
            for (String v : POOL)
                assertThat(a.contains(v)).as("empty set admits %s", v).isFalse();
        }
        assertThat(a.intersect(a.complement()).isEmpty())
                .as("%s intersected with its complement is empty", a)
                .isTrue();
    }
}
