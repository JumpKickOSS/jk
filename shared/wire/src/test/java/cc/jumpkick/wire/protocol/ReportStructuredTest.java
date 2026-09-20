// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The structured (MCP) encodings carry the same facts as the wire encodings. */
class ReportStructuredTest {

    @Test
    @SuppressWarnings("unchecked")
    void why_report_groups_paths_per_match() {
        WhyReport r = new WhyReport(
                null,
                List.of("g:a", "g:b"),
                List.of("1.0", "2.0"),
                List.of("", "lib"),
                List.of("", "org.example:bom:1.0"),
                List.of("0", "1", "0"),
                List.of("root>g:a@1.0", "root>g:b@2.0", "other>g:a@1.0"),
                List.of("^1\t1.0", "\t[2.0,3.0)", "\t"),
                List.of("g:app, g:lib", "", "g:lib"),
                List.of("g:c\tjk.toml:a\tg:a@1.0"));
        Map<String, Object> m = r.toStructured();
        List<Map<String, Object>> matches = (List<Map<String, Object>>) requireNonNull(m.get("matches"));
        assertThat(matches).hasSize(2);
        assertThat(matches.get(0).get("name")).isEqualTo("g:a");
        assertThat((List<String>) matches.get(0).get("members")).isEmpty();
        assertThat((List<String>) matches.get(1).get("members")).containsExactly("lib");
        assertThat((List<String>) matches.get(0).get("paths")).containsExactly("root>g:a@1.0", "other>g:a@1.0");
        assertThat((List<String>) matches.get(1).get("paths")).containsExactly("root>g:b@2.0");
        // One selector list per path, one entry per step, "" where the lock does not say.
        assertThat((List<List<String>>) matches.get(0).get("declared"))
                .containsExactly(List.of("^1", "1.0"), List.of("", ""));
        assertThat((List<List<String>>) matches.get(1).get("declared")).containsExactly(List.of("", "[2.0,3.0)"));
        // Per path, the workspace units that declared its root; "" for the project's own manifest.
        assertThat((List<String>) matches.get(0).get("declaredBy")).containsExactly("g:app, g:lib", "g:lib");
        assertThat((List<String>) matches.get(1).get("declaredBy")).containsExactly("");
        List<Map<String, Object>> pruned = (List<Map<String, Object>>) requireNonNull(m.get("exclusions"));
        assertThat(pruned).hasSize(1);
        assertThat(pruned.getFirst())
                .containsEntry("name", "g:c")
                .containsEntry("excludedBy", "jk.toml:a")
                .containsEntry("under", "g:a@1.0");
        assertThat(WhyReport.error("boom").toStructured()).containsEntry("error", "boom");
    }

    @Test
    @SuppressWarnings("unchecked")
    void outdated_report_rows_round_trip_to_maps() {
        OutdatedReport r = OutdatedReport.of(
                true,
                List.of(
                        new OutdatedReport.Row("app", "g:a", "g:a", "main", "1.0", "1.1", "2.0", "major bump"),
                        new OutdatedReport.Row("app", "g:b", "", "main", "3.0", "3.0", "3.0", "")));
        Map<String, Object> m = r.toStructured(false);
        assertThat(m).containsEntry("workspace", true).containsEntry("checked", 2);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) requireNonNull(m.get("rows"));
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("coordinate", "g:a");
            assertThat(row).containsEntry("current", "1.0");
            assertThat(row).containsEntry("latest", "2.0");
            assertThat(row).containsEntry("tip", "major bump");
        });
        List<Map<String, Object>> every =
                (List<Map<String, Object>>) requireNonNull(r.toStructured(true).get("rows"));
        assertThat(every).hasSize(2);
        assertThat(OutdatedReport.error("down").toStructured(false)).containsEntry("error", "down");
    }

    @Test
    void outdated_row_moves_when_a_column_is_ahead_or_current_is_unknown() {
        assertThat(new OutdatedReport.Row("", "g:a", "", "main", "1.0", "1.0", "1.0", "").canMove())
                .isFalse();
        assertThat(new OutdatedReport.Row("", "g:a", "", "main", "1.0", "1.1", "1.1", "").canMove())
                .isTrue();
        assertThat(new OutdatedReport.Row("", "g:a", "", "main", "1.0", "1.0", "2.0", "").canMove())
                .isTrue();
        assertThat(new OutdatedReport.Row("", "g:a", "", "main", "", "1.0", "1.0", "").canMove())
                .isTrue();
        assertThat(new OutdatedReport.Row("", "g:a", "", "main", "v1.0", "v1.0", "v1.2", "").canMove())
                .isTrue();
        assertThat(new OutdatedReport.Row("", "g:a", "", "main", "1.0", "", "1.0", "").canMove())
                .isFalse();
        assertThat(OutdatedReport.ahead("2.0", "1.0")).isTrue();
        assertThat(OutdatedReport.ahead("1.0", "tip")).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void affected_tests_report_rows_round_trip_to_maps() {
        AffectedTestsReport r = AffectedTestsReport.of(
                20, 3, List.of(new AffectedTestsReport.Row(100, "com.acme.FooTest", "name-body:com.acme.Foo")));
        Map<String, Object> m = r.toStructured();
        assertThat(m).containsEntry("cap", 20).containsEntry("candidateCount", 3);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) requireNonNull(m.get("ranked"));
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("class", "com.acme.FooTest");
            assertThat(row).containsEntry("score", 100);
            assertThat(row).containsEntry("reason", "name-body:com.acme.Foo");
        });
        assertThat(AffectedTestsReport.error("stale", "rebuild").toStructured()).containsEntry("error", "rebuild");
    }
}
