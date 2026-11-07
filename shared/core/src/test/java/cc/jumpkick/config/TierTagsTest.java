// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link TierTags}: the root's {@code [test]} lists and profiles win outright over a member's own
 * lists, a member's lists count only when the root sets none, and a profile takes its parent's tag
 * lists and {@code all-suites} through {@code inherits}.
 */
class TierTagsTest {

    private static Path workspace(Path ws, String rootTables, String memberTables) throws Exception {
        Files.writeString(ws.resolve("jk.toml"), """
                group = "t"
                version = "0.0.1"
                java = 25

                [workspace]
                modules = ["lib"]

                """ + rootTables);
        Path lib = Files.createDirectories(ws.resolve("lib"));
        Files.writeString(lib.resolve("jk.toml"), "name = \"lib\"\n\n" + memberTables);
        return lib;
    }

    @Test
    void a_members_own_list_does_not_override_the_roots(@TempDir Path ws) throws Exception {
        Path lib = workspace(ws, """
                [test]
                exclude-tags = ["integration", "bench"]
                """, """
                [test]
                exclude-tags = ["slow"]
                include-tags = ["unit"]
                """);
        TierTags tiers = TierTags.of(lib, null);
        assertThat(tiers.excludeTags()).containsExactly("integration", "bench");
        assertThat(tiers.includeTags())
                .as("the root wins outright, it does not merge")
                .isEmpty();
        assertThat(tiers.spoke()).isTrue();
    }

    @Test
    void a_members_own_list_counts_only_when_the_root_sets_none(@TempDir Path ws) throws Exception {
        Path lib = workspace(ws, "", """
                [test]
                exclude-tags = ["slow"]
                """);
        assertThat(TierTags.of(lib, null).excludeTags()).containsExactly("slow");
        assertThat(TierTags.of(ws, null)).isEqualTo(TierTags.NONE);
    }

    @Test
    void a_root_profile_wins_over_the_members_list(@TempDir Path ws) throws Exception {
        Path lib = workspace(ws, """
                [profiles.integration]
                include-tags = ["integration"]
                """, """
                [test]
                exclude-tags = ["slow"]
                """);
        TierTags tiers = TierTags.of(lib, "integration");
        assertThat(tiers.includeTags()).containsExactly("integration");
        assertThat(tiers.excludeTags()).isEmpty();
    }

    @Test
    void an_inheriting_profile_gets_its_parents_tags_and_all_suites(@TempDir Path ws) throws Exception {
        Path lib = workspace(ws, """
                [test]
                exclude-tags = ["integration"]

                [profiles.integration]
                include-tags = ["integration"]
                exclude-tags = []
                all-suites = true

                [profiles.nightly]
                inherits = "integration"

                [profiles.nightly-bench]
                inherits = "nightly"
                include-tags = ["bench"]
                """, "");
        TierTags nightly = TierTags.of(lib, "nightly");
        assertThat(nightly.includeTags()).containsExactly("integration");
        assertThat(nightly.excludeTags()).as("the parent's clear holds").isEmpty();
        assertThat(nightly.allSuites()).isTrue();

        TierTags bench = TierTags.ofRoot(ws, "nightly-bench");
        assertThat(bench.includeTags())
                .as("the child's list replaces its parent's")
                .containsExactly("bench");
        assertThat(bench.excludeTags()).isEmpty();
        assertThat(bench.allSuites()).isTrue();
    }

    @Test
    void a_profile_the_root_does_not_declare_leaves_the_baseline(@TempDir Path ws) throws Exception {
        workspace(ws, """
                [test]
                exclude-tags = ["integration"]
                """, "");
        assertThat(TierTags.ofRoot(ws, "ci").excludeTags()).containsExactly("integration");
        assertThat(TierTags.profile(ws, "ci")).isEqualTo(TierTags.NONE);
    }

    @Test
    void a_cycle_or_a_non_boolean_all_suites_is_refused(@TempDir Path ws) throws Exception {
        workspace(ws, """
                [profiles.a]
                inherits = "b"

                [profiles.b]
                inherits = "a"

                [profiles.c]
                all-suites = "yes"
                """, "");
        assertThatThrownBy(() -> TierTags.profile(ws, "a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cycle in profile inheritance");
        assertThatThrownBy(() -> TierTags.profile(ws, "c"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("profiles.c.all-suites must be true or false");
    }
}
