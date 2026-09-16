// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Intersection registration, origin bookkeeping and the pruned-edge lines a lock row carries. */
class ExclusionLedgerTest {

    @Test
    void registrations_intersect_and_an_empty_view_collapses_the_set() {
        ExclusionLedger ledger = new ExclusionLedger();
        ledger.register("com.foo:target", ExclusionLedger.view(Set.of("com.foo:leaf", "com.foo:other"), "jk.toml:a"));
        assertThat(ledger.exclusionsFor("com.foo:target")).containsExactlyInAnyOrder("com.foo:leaf", "com.foo:other");

        ledger.register("com.foo:target", ExclusionLedger.view(Set.of("com.foo:leaf"), "com.foo:mid@1.0"));
        assertThat(ledger.exclusionsFor("com.foo:target")).containsExactly("com.foo:leaf");
        assertThat(ledger.viewFor("com.foo:target"))
                .containsExactly(Map.entry("com.foo:leaf", Set.of("jk.toml:a", "com.foo:mid@1.0")));

        ledger.register("com.foo:target", Map.of());
        assertThat(ledger.exclusionsFor("com.foo:target")).isEmpty();
        assertThat(ledger.viewFor("com.foo:target")).isEmpty();
    }

    @Test
    void pruned_edges_name_the_child_and_every_origin_of_the_pattern_that_matched() {
        ExclusionLedger ledger = new ExclusionLedger();
        ledger.register(
                "com.foo:child:jar:", ExclusionLedger.view(Set.of("com.bar:*", "com.foo:leaf"), "jk.toml:parent"));
        ledger.recordFiltered(
                "com.foo:child:jar:", "1.0", Set.of("com.bar:leaf:jar:", "com.foo:leaf:jar:", "com.bar:util:jar:"));

        assertThat(ledger.prunedEdges("com.foo:child:jar:", "1.0"))
                .containsExactly(
                        "com.bar:leaf <- jk.toml:parent",
                        "com.bar:util <- jk.toml:parent",
                        "com.foo:leaf <- jk.toml:parent");
        assertThat(ledger.prunedEdges("com.foo:child:jar:", "2.0")).isEmpty();
        assertThat(ledger.anyExpansionStale(Map.of("com.foo:child:jar:", "1.0")))
                .isFalse();
    }

    @Test
    void a_child_the_converged_set_keeps_is_a_stale_expansion_and_no_pruned_edge() {
        ExclusionLedger ledger = new ExclusionLedger();
        ledger.register("com.foo:child:jar:", ExclusionLedger.view(Set.of("com.foo:leaf"), "jk.toml:parent"));
        ledger.recordFiltered("com.foo:child:jar:", "1.0", Set.of("com.foo:leaf:jar:"));
        ledger.register("com.foo:child:jar:", Map.of());

        assertThat(ledger.anyExpansionStale(Map.of("com.foo:child:jar:", "1.0")))
                .isTrue();
        assertThat(ledger.prunedEdges("com.foo:child:jar:", "1.0")).isEmpty();
        ledger.recordFiltered("com.foo:child:jar:", "1.0", null);
        assertThat(ledger.anyExpansionStale(Map.of("com.foo:child:jar:", "1.0")))
                .isFalse();
    }

    @Test
    void a_registration_without_an_origin_prunes_with_the_coordinate_alone() {
        ExclusionLedger ledger = new ExclusionLedger();
        ledger.register("com.foo:child:jar:", ExclusionLedger.view(Set.of("*:leaf"), null));
        ledger.recordFiltered("com.foo:child:jar:", "1.0", Set.of("com.foo:leaf:jar:"));
        assertThat(ledger.prunedEdges("com.foo:child:jar:", "1.0")).containsExactly("com.foo:leaf");
    }

    @Test
    void reset_forgets_everything() {
        ExclusionLedger ledger = new ExclusionLedger();
        ledger.register("com.foo:child:jar:", ExclusionLedger.view(Set.of("com.foo:leaf"), "jk.toml:parent"));
        ledger.recordFiltered("com.foo:child:jar:", "1.0", Set.of("com.foo:leaf:jar:"));
        ledger.reset();
        assertThat(ledger.exclusionsFor("com.foo:child:jar:")).isEmpty();
        assertThat(ledger.prunedEdges("com.foo:child:jar:", "1.0")).isEmpty();
    }
}
