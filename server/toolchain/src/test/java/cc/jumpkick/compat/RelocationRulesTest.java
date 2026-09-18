// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.RelocationRules.Relocation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A Shade relocation imports as a jk rule where the two agree on every class: plain and
 * path-spelled ones as themselves, an {@code a.b.**} include as the rule for {@code a.b}, an
 * exclude another relocation lands in the same place as redundant, and what the rules cannot
 * express by label.
 */
class RelocationRulesTest {

    @Test
    void a_plain_relocation_and_its_path_spelled_twin_are_one_dotted_rule() {
        RelocationRules.Mapped mapped = RelocationRules.map(List.of(
                new Relocation("org/apache/lucene", "org/neo4j/shaded/lucene9"),
                new Relocation("org.apache.commons", "org.neo4j.shaded.commons")));

        assertThat(mapped.rules())
                .containsExactly(
                        Map.entry("org.apache.lucene", "org.neo4j.shaded.lucene9"),
                        Map.entry("org.apache.commons", "org.neo4j.shaded.commons"));
        assertThat(mapped.unmapped()).isEmpty();
    }

    /** nacos's client: an exclude carved out for a second relocation that lands the package where the first would have. */
    @Test
    void an_exclude_another_relocation_lands_in_the_same_place_is_redundant_and_so_is_that_relocation() {
        RelocationRules.Mapped mapped = RelocationRules.map(List.of(
                new Relocation(
                        "io.grpc",
                        "com.alibaba.nacos.shaded.io.grpc",
                        false,
                        List.of(),
                        List.of("io.grpc.netty.shaded.io.grpc.netty.*")),
                new Relocation(
                        "io.grpc.netty.shaded.io.grpc.netty",
                        "com.alibaba.nacos.shaded.io.grpc.netty.shaded.io.grpc.netty",
                        false,
                        List.of("io.grpc.netty.shaded.io.grpc.netty.*"),
                        List.of()),
                new Relocation("com.google", "com.alibaba.nacos.shaded.com.google")));

        assertThat(mapped.rules())
                .containsExactly(
                        Map.entry("io.grpc", "com.alibaba.nacos.shaded.io.grpc"),
                        Map.entry("com.google", "com.alibaba.nacos.shaded.com.google"));
        assertThat(mapped.unmapped()).isEmpty();
    }

    @Test
    void an_exclude_nothing_else_relocates_keeps_its_relocation_out_by_name() {
        RelocationRules.Mapped mapped = RelocationRules.map(List.of(
                new Relocation("com.google.common", "com.ex.shaded.guava"),
                new Relocation(
                        "org.apache.commons.io",
                        "com.ex.shaded.commons.io",
                        false,
                        List.of(),
                        List.of("org.apache.commons.io.input.*"))));

        assertThat(mapped.rules()).containsExactly(Map.entry("com.google.common", "com.ex.shaded.guava"));
        assertThat(mapped.unmapped())
                .containsExactly(
                        "org.apache.commons.io → com.ex.shaded.commons.io (excludes org.apache.commons.io.input.*)");
    }

    @Test
    void a_whole_package_include_is_the_rule_for_that_package_and_a_class_include_needs_the_rules_to_agree() {
        RelocationRules.Mapped mapped = RelocationRules.map(List.of(
                new Relocation(
                        "org.apache",
                        "shaded.org.apache",
                        false,
                        List.of("org.apache.lucene.**", "org/apache/commons/**", "org.apache.http.HttpClient"),
                        List.of()),
                new Relocation("org.apache.http", "shaded.org.apache.http")));

        assertThat(mapped.rules())
                .containsExactly(
                        Map.entry("org.apache.lucene", "shaded.org.apache.lucene"),
                        Map.entry("org.apache.commons", "shaded.org.apache.commons"),
                        Map.entry("org.apache.http", "shaded.org.apache.http"));
        assertThat(mapped.unmapped())
                .as("the second relocation moves the named class exactly where the first would")
                .isEmpty();

        RelocationRules.Mapped alone = RelocationRules.map(List.of(new Relocation(
                "org.apache", "shaded.org.apache", false, List.of("org.apache.http.HttpClient"), List.of())));
        assertThat(alone.rules()).isEmpty();
        assertThat(alone.unmapped())
                .containsExactly("org.apache → shaded.org.apache (includes org.apache.http.HttpClient)");
    }

    @Test
    void a_raw_string_rewrite_is_covered_by_a_rule_of_the_same_name_and_a_row_without_one() {
        RelocationRules.Mapped covered = RelocationRules.map(List.of(
                new Relocation("org.apache.lucene", "org.neo4j.shaded.lucene9"),
                new Relocation("org/apache/lucene", "org/neo4j/shaded/lucene9", true, List.of(), List.of())));
        assertThat(covered.unmapped()).isEmpty();

        RelocationRules.Mapped bare = RelocationRules.map(
                List.of(new Relocation("org/apache/lucene", "org/neo4j/shaded/lucene9", true, List.of(), List.of())));
        assertThat(bare.rules()).isEmpty();
        assertThat(bare.unmapped()).containsExactly("org/apache/lucene → org/neo4j/shaded/lucene9 (rawString)");
    }

    @Test
    void ant_paths_match_as_shade_matches_them() {
        assertThat(RelocationRules.matches("a.b.**", "a.b.c.D")).isTrue();
        assertThat(RelocationRules.matches("a.b.*", "a.b.D")).isTrue();
        assertThat(RelocationRules.matches("a.b.*", "a.b.c.D")).isFalse();
        assertThat(RelocationRules.matches("a.b.*", "a.b")).isTrue();
        assertThat(RelocationRules.matches("a.b.D", "a.b.D")).isTrue();
        assertThat(RelocationRules.matches("a.b.D", "a.b.E")).isFalse();
    }
}
