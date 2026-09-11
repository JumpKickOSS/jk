// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.JkBuild;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@code [audit] ignore}: the advisories the audit reports without gating on, each with its reason. */
class JkBuildParserAuditTest {

    @Test
    void entries_keep_manifest_order_and_carry_id_reason_and_optional_until() {
        JkBuild b = JkBuildParser.parse(JkBuildParserFixtures.PROJECT + """

                [audit]
                ignore = [
                  { id = "GHSA-aaaa-bbbb-cccc", reason = "test-only dependency", until = "2026-12-31" },
                  { id = "CVE-2025-0001", reason = "not reachable from this code" },
                ]
                """);
        List<JkBuild.AuditIgnore> ignores = b.build().auditIgnores();
        assertThat(ignores)
                .containsExactly(
                        new JkBuild.AuditIgnore(
                                "GHSA-aaaa-bbbb-cccc", "test-only dependency", LocalDate.of(2026, 12, 31)),
                        new JkBuild.AuditIgnore("CVE-2025-0001", "not reachable from this code", null));
    }

    @Test
    void the_array_of_tables_spelling_and_a_bare_toml_date_are_the_same_entry() {
        JkBuild b = JkBuildParser.parse(JkBuildParserFixtures.PROJECT + """

                [[audit.ignore]]
                id     = "GHSA-aaaa-bbbb-cccc"
                reason = "test-only dependency"
                until  = 2026-12-31
                """);
        assertThat(b.build().auditIgnores())
                .containsExactly(new JkBuild.AuditIgnore(
                        "GHSA-aaaa-bbbb-cccc", "test-only dependency", LocalDate.of(2026, 12, 31)));
    }

    @Test
    void an_absent_table_ignores_nothing() {
        assertThat(JkBuildParser.parse(JkBuildParserFixtures.PROJECT).build().auditIgnores())
                .isEmpty();
        assertThat(JkBuildParser.parse(JkBuildParserFixtures.PROJECT + "\n[audit]\n")
                        .build()
                        .auditIgnores())
                .isEmpty();
    }

    @Test
    void an_unknown_key_is_refused_naming_the_known_ones_at_both_levels() {
        assertThatThrownBy(() -> JkBuildParser.parse(JkBuildParserFixtures.PROJECT + """

                        [audit]
                        ignores = []
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessage("[audit] unknown key `ignores` — expected one of: ignore");
        assertThatThrownBy(() -> JkBuildParser.parse(JkBuildParserFixtures.PROJECT + """

                        [audit]
                        ignore = [{ id = "GHSA-x", reason = "r", untill = "2026-12-31" }]
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessage("[audit].ignore[0] unknown key `untill` — expected one of: id, reason, until");
    }

    /** An ignore nobody can review is a hole, not a policy. */
    @Test
    void an_entry_without_a_reason_is_refused() {
        assertThatThrownBy(() -> JkBuildParser.parse(JkBuildParserFixtures.PROJECT + """

                        [audit]
                        ignore = [{ id = "GHSA-aaaa-bbbb-cccc" }]
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("GHSA-aaaa-bbbb-cccc needs a reason");
        assertThatThrownBy(() -> JkBuildParser.parse(JkBuildParserFixtures.PROJECT + """

                        [audit]
                        ignore = [{ id = "GHSA-aaaa-bbbb-cccc", reason = "  " }]
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("GHSA-aaaa-bbbb-cccc needs a reason");
    }

    @Test
    void an_entry_without_an_id_is_refused() {
        assertThatThrownBy(() -> JkBuildParser.parse(JkBuildParserFixtures.PROJECT + """

                        [audit]
                        ignore = [{ reason = "r" }]
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageStartingWith("[audit].ignore[0] needs id = ");
    }

    @Test
    void until_must_be_an_iso_date() {
        assertThatThrownBy(() -> JkBuildParser.parse(JkBuildParserFixtures.PROJECT + """

                        [audit]
                        ignore = [{ id = "GHSA-x", reason = "r", until = "31/12/2026" }]
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessage("[audit].ignore GHSA-x until must be an ISO date (YYYY-MM-DD), got `31/12/2026`");
        assertThatThrownBy(() -> JkBuildParser.parse(JkBuildParserFixtures.PROJECT + """

                        [audit]
                        ignore = [{ id = "GHSA-x", reason = "r", until = 2026 }]
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessage("[audit].ignore GHSA-x until must be an ISO date (YYYY-MM-DD)");
    }

    @Test
    void shapes_that_are_not_an_array_of_tables_are_refused() {
        assertThatThrownBy(() -> JkBuildParser.parse(JkBuildParserFixtures.PROJECT + """

                        [audit]
                        ignore = "GHSA-x"
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageStartingWith("[audit].ignore must be an array of tables");
        assertThatThrownBy(() -> JkBuildParser.parse(JkBuildParserFixtures.PROJECT + """

                        [audit]
                        ignore = ["GHSA-x"]
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageStartingWith("[audit].ignore[0] must be a table");
        assertThatThrownBy(() -> JkBuildParser.parse(JkBuildParserFixtures.PROJECT + """

                        audit = "off"
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageStartingWith("[audit] must be a table");
    }

    @Test
    void an_entry_expires_the_day_after_until_and_never_without_one() {
        JkBuild.AuditIgnore dated = new JkBuild.AuditIgnore("GHSA-x", "r", LocalDate.of(2026, 12, 31));
        assertThat(dated.expiredOn(LocalDate.of(2026, 12, 31))).isFalse();
        assertThat(dated.expiredOn(LocalDate.of(2027, 1, 1))).isTrue();
        assertThat(new JkBuild.AuditIgnore("GHSA-x", "r", null).expiredOn(LocalDate.of(2999, 1, 1)))
                .isFalse();
    }
}
