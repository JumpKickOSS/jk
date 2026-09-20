// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.wire.protocol.OutdatedReport;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class JkOutdatedMarkdownTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 20);

    private static OutdatedReport twoModules() {
        return OutdatedReport.of(
                true,
                List.of(
                        new OutdatedReport.Row(
                                "acme:core", "org.tomlj:tomlj", "tomlj", "main", "1.1.1", "1.1.1", "1.3.0", ""),
                        new OutdatedReport.Row(
                                "acme:app", "org.tomlj:tomlj", "tomlj", "main", "1.2.0", "1.2.0", "1.3.0", ""),
                        new OutdatedReport.Row(
                                "acme:core",
                                "org.junit.jupiter:junit-jupiter",
                                "",
                                "test",
                                "6.1.3",
                                "6.1.3",
                                "6.1.3",
                                ""),
                        new OutdatedReport.Row(
                                "acme:app",
                                "org.junit.jupiter:junit-jupiter",
                                "",
                                "test",
                                "6.1.3",
                                "6.1.3",
                                "6.1.3",
                                ""),
                        new OutdatedReport.Row(
                                "acme:app", "org.antlr:antlr4-runtime", "", "main", "4.11.1", "4.11.1", "4.13.2", "")));
    }

    @Test
    void the_rollup_leads_the_per_module_rows_follow_and_the_rest_is_a_rollup_list() {
        String md = JkOutdatedMarkdown.render(twoModules(), false, DAY);

        assertThat(md)
                .startsWith("# jk outdated dependencies\n\n"
                        + "2026-09-20 · 2 modules · 5 checked · **3** can move across **2** coordinates\n");
        assertThat(md)
                .contains("## Can move\n\n| Dependency | Current | Compatible | Latest | Modules | Scope |\n")
                .contains("| `org.antlr:antlr4-runtime` | 4.11.1 | 4.11.1 | 4.13.2 | 1 | main |")
                .contains(
                        "| `org.tomlj:tomlj` (tomlj) | 1.1.1 ×1 · 1.2.0 ×1 | 1.1.1 ×1 · 1.2.0 ×1 | 1.3.0 | 2 | main |");
        assertThat(md)
                .contains("## By module\n\n| Module | Dependency | Current | Compatible | Latest | Scope |\n")
                .contains("| `acme:core` | `org.tomlj:tomlj` | 1.1.1 | 1.1.1 | 1.3.0 | main |")
                .contains("| `acme:app` | `org.tomlj:tomlj` | 1.2.0 | 1.2.0 | 1.3.0 | main |");
        assertThat(md).contains("## Up to date\n\n- `org.junit.jupiter:junit-jupiter` 6.1.3 (test) · 2 modules\n");
        assertThat(md).doesNotContain("Offline").doesNotContain("| Tip |");
    }

    @Test
    void standalone_project_has_no_module_column_or_by_module_section_and_offline_adds_the_note() {
        OutdatedReport report = OutdatedReport.of(
                false, List.of(new OutdatedReport.Row("", "com.foo:leaf", "", "main", "1.0", "1.1", "2.0", "2.1-rc1")));
        String md = JkOutdatedMarkdown.render(report, true, DAY);

        assertThat(md).contains("2026-09-20 · 1 module · 1 checked · **1** can move\n\n> Offline:");
        assertThat(md).contains("| Dependency | Current | Compatible | Latest | Tip | Scope |\n");
        assertThat(md).contains("| `com.foo:leaf` | 1.0 | 1.1 | 2.0 | 2.1-rc1 | main |");
        assertThat(md).doesNotContain("## By module").doesNotContain("Modules");
        assertThat(md).contains("## Up to date\n\n_none_\n");
    }

    @Test
    void empty_cells_render_as_a_dash() {
        OutdatedReport report = OutdatedReport.of(
                false, List.of(new OutdatedReport.Row("", "com.foo:leaf", "", "main", "", "", "2.0", null)));
        String md = JkOutdatedMarkdown.render(report, false, DAY);
        assertThat(md).contains("| `com.foo:leaf` | — | — | 2.0 | main |");
    }
}
