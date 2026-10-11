// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Per-origin registration, the live intersection, stale expansions and the pruned-edge lines a lock row carries. */
class ExclusionLedgerTest {

    @Test
    void live_registrations_intersect_and_an_empty_view_collapses_the_set() {
        ExclusionLedger ledger = new ExclusionLedger();
        ledger.register(
                "com.foo:target",
                "jk.toml:a",
                ExclusionLedger.view(Set.of("com.foo:leaf", "com.foo:other"), "jk.toml:a"));
        assertThat(ledger.exclusionsFor("com.foo:target")).containsExactlyInAnyOrder("com.foo:leaf", "com.foo:other");

        ledger.register(
                "com.foo:target",
                "com.foo:mid:jar:@1.0",
                ExclusionLedger.view(Set.of("com.foo:leaf"), "com.foo:mid@1.0"));
        assertThat(ledger.exclusionsFor("com.foo:target")).containsExactly("com.foo:leaf");
        assertThat(ledger.viewFor("com.foo:target"))
                .containsExactly(Map.entry("com.foo:leaf", Set.of("jk.toml:a", "com.foo:mid@1.0")));

        ledger.register("com.foo:target", "com.foo:clean:jar:@1.0", Map.of());
        assertThat(ledger.exclusionsFor("com.foo:target")).isEmpty();
        assertThat(ledger.viewFor("com.foo:target")).isEmpty();
    }

    @Test
    void a_parent_the_round_decided_at_another_version_no_longer_speaks_for_its_child() {
        ExclusionLedger ledger = new ExclusionLedger();
        ledger.register("com.foo:c:jar:", "com.foo:b:jar:@1.5", Map.of());
        ledger.register(
                "com.foo:c:jar:", "com.foo:b:jar:@1.0", ExclusionLedger.view(Set.of("com.foo:x"), "com.foo:b@1.0"));
        assertThat(ledger.exclusionsFor("com.foo:c:jar:")).isEmpty();

        ledger.decided(Map.of("com.foo:b:jar:", "1.0", "com.foo:c:jar:", "1.0"));
        assertThat(ledger.exclusionsFor("com.foo:c:jar:")).containsExactly("com.foo:x");

        ledger.decided(Map.of("com.foo:b:jar:", "1.5", "com.foo:c:jar:", "1.0"));
        assertThat(ledger.exclusionsFor("com.foo:c:jar:")).isEmpty();

        ledger.decided(Map.of("com.foo:c:jar:", "1.0"));
        assertThat(ledger.exclusionsFor("com.foo:c:jar:")).isEmpty();
    }

    @Test
    void a_later_registration_from_the_same_origin_replaces_the_earlier_one() {
        ExclusionLedger ledger = new ExclusionLedger();
        ledger.register(
                "com.foo:c:jar:", "com.foo:b:jar:@1.0", ExclusionLedger.view(Set.of("com.foo:x"), "com.foo:b@1.0"));
        ledger.register("com.foo:c:jar:", "com.foo:b:jar:@1.0", Map.of());
        assertThat(ledger.exclusionsFor("com.foo:c:jar:")).isEmpty();
    }

    @Test
    void pruned_edges_name_the_child_and_every_origin_of_the_pattern_that_matched() {
        ExclusionLedger ledger = new ExclusionLedger();
        ledger.register(
                "com.foo:child:jar:",
                "jk.toml:parent",
                ExclusionLedger.view(Set.of("com.bar:*", "com.foo:leaf"), "jk.toml:parent"));
        ledger.recordExpansion(
                "com.foo:child:jar:",
                "1.0",
                Set.of("com.baz:kept:jar:"),
                Set.of("com.bar:leaf:jar:", "com.foo:leaf:jar:", "com.bar:util:jar:"));

        assertThat(ledger.prunedEdges("com.foo:child:jar:", "1.0"))
                .containsExactly(
                        "com.bar:leaf <- jk.toml:parent",
                        "com.bar:util <- jk.toml:parent",
                        "com.foo:leaf <- jk.toml:parent");
        assertThat(ledger.prunedEdges("com.foo:child:jar:", "2.0")).isEmpty();
        ledger.decided(Map.of("com.foo:child:jar:", "1.0"));
        assertThat(ledger.anyExpansionStale()).isFalse();
    }

    @Test
    void a_pruned_child_the_converged_set_keeps_is_a_stale_expansion_and_no_pruned_edge() {
        ExclusionLedger ledger = new ExclusionLedger();
        ledger.register(
                "com.foo:child:jar:", "jk.toml:parent", ExclusionLedger.view(Set.of("com.foo:leaf"), "jk.toml:parent"));
        ledger.recordExpansion("com.foo:child:jar:", "1.0", Set.of(), Set.of("com.foo:leaf:jar:"));
        ledger.register("com.foo:child:jar:", "com.foo:clean:jar:@1.0", Map.of());
        ledger.decided(Map.of("com.foo:child:jar:", "1.0", "com.foo:clean:jar:", "1.0"));

        assertThat(ledger.anyExpansionStale()).isTrue();
        assertThat(ledger.prunedEdges("com.foo:child:jar:", "1.0")).isEmpty();
        ledger.recordExpansion("com.foo:child:jar:", "1.0", Set.of("com.foo:leaf:jar:"), Set.of());
        assertThat(ledger.anyExpansionStale()).isFalse();
    }

    @Test
    void a_kept_child_the_converged_set_prunes_is_a_stale_expansion() {
        ExclusionLedger ledger = new ExclusionLedger();
        ledger.register("com.foo:c:jar:", "com.foo:b:jar:@1.5", Map.of());
        ledger.register(
                "com.foo:c:jar:", "com.foo:b:jar:@1.0", ExclusionLedger.view(Set.of("com.foo:x"), "com.foo:b@1.0"));
        ledger.recordExpansion("com.foo:c:jar:", "1.0", Set.of("com.foo:x:jar:"), Set.of());
        ledger.decided(Map.of("com.foo:b:jar:", "1.0", "com.foo:c:jar:", "1.0"));

        assertThat(ledger.anyExpansionStale()).isTrue();
        ledger.recordExpansion("com.foo:c:jar:", "1.0", Set.of(), Set.of("com.foo:x:jar:"));
        assertThat(ledger.anyExpansionStale()).isFalse();
        assertThat(ledger.prunedEdges("com.foo:c:jar:", "1.0")).containsExactly("com.foo:x <- com.foo:b@1.0");
    }

    @Test
    void a_registration_without_an_origin_prunes_with_the_coordinate_alone() {
        ExclusionLedger ledger = new ExclusionLedger();
        ledger.register("com.foo:child:jar:", "jk.toml:parent", ExclusionLedger.view(Set.of("*:leaf"), null));
        ledger.recordExpansion("com.foo:child:jar:", "1.0", Set.of(), Set.of("com.foo:leaf:jar:"));
        assertThat(ledger.prunedEdges("com.foo:child:jar:", "1.0")).containsExactly("com.foo:leaf");
    }

    @Test
    void reset_forgets_everything() {
        ExclusionLedger ledger = new ExclusionLedger();
        ledger.register(
                "com.foo:child:jar:", "jk.toml:parent", ExclusionLedger.view(Set.of("com.foo:leaf"), "jk.toml:parent"));
        ledger.recordExpansion("com.foo:child:jar:", "1.0", Set.of(), Set.of("com.foo:leaf:jar:"));
        ledger.decided(Map.of("com.foo:child:jar:", "1.0"));
        ledger.reset();
        assertThat(ledger.exclusionsFor("com.foo:child:jar:")).isEmpty();
        assertThat(ledger.prunedEdges("com.foo:child:jar:", "1.0")).isEmpty();
        assertThat(ledger.anyExpansionStale()).isFalse();
    }
}
