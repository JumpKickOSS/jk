// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.ShortTempDirs;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class JkResultsCoverageSectionTest {

    private static final Path WS = ShortTempDirs.path().resolve("ws");

    private static BuildRecord record(long buildNumber, List<BuildRecord.Coverage> coverage) {
        return new BuildRecord(
                        "id",
                        buildNumber,
                        BuildRecord.SCHEMA,
                        "test",
                        WS.toString(),
                        "g:ws",
                        "pid",
                        1_000,
                        1_100,
                        100,
                        true,
                        false,
                        0,
                        "9.9",
                        null,
                        List.of(),
                        List.of(),
                        List.of(),
                        "cli",
                        null,
                        null,
                        null,
                        false,
                        null,
                        0L)
                .withCoverage(coverage);
    }

    private static BuildRecord.Coverage module(String name, long lc, long lm, long bc, long bm) {
        return new BuildRecord.Coverage(
                WS.resolve(name).toString(),
                "g:" + name,
                lc,
                lm,
                bc,
                bm,
                WS.resolve("target/" + name + "/reports/coverage/index.html").toString());
    }

    /** The pointer is relative to the record's root, in this platform's spelling. */
    private static String pointer(String relative) {
        return "- Coverage HTML: `" + Path.of(relative) + "`";
    }

    private static String render(BuildRecord r, @Nullable BuildRecord previous) {
        return JkResultsMarkdown.render(r, null, null, List.of(), previous);
    }

    @Test
    void a_run_without_coverage_writes_no_block_and_no_headline_line() {
        String md = render(record(3, List.of()), null);
        assertThat(md).doesNotContain("## Coverage").doesNotContain("Coverage:").doesNotContain("Coverage HTML");
    }

    @Test
    void a_first_run_shows_per_module_percentages_the_total_and_the_html_pointer() {
        String md = render(record(3, List.of(module("lib", 120, 30, 20, 20), module("app", 0, 50, 0, 0))), null);

        assertThat(md)
                .contains("Coverage: **60.0%** lines · 50.0% branches · 2 modules\n")
                .contains(pointer("target/reports/coverage/index.html"))
                .contains("## Coverage\n\n| Module | Lines | Branches |\n|---|---|---|\n")
                .contains("| g:lib | 80.0% (120/150) | 50.0% (20/40) |\n")
                .contains("| g:app | 0.0% (0/50) | 100.0% (0/0) |\n")
                .contains("| **all** | 60.0% (120/200) | 50.0% (20/40) |\n")
                .contains("floor: a `coverage.line` guard")
                .doesNotContain("| Δ |");
    }

    @Test
    void a_later_run_shows_the_delta_against_the_previous_coverage_run() {
        BuildRecord previous = record(3, List.of(module("lib", 100, 50, 20, 20)));
        BuildRecord now = record(5, List.of(module("lib", 120, 30, 19, 21), module("app", 10, 0, 0, 0)));

        String md = render(now, previous);

        assertThat(md)
                .contains("_Δ vs run #3_")
                .contains("| Module | Lines | Δ | Branches | Δ |")
                .contains("| g:lib | 80.0% (120/150) | +13.3 | 47.5% (19/40) | −2.5 |\n")
                .contains("| g:app | 100.0% (10/10) | new | 100.0% (0/0) | new |\n")
                .contains("| **all** | 81.3% (130/160) | +14.6 | 47.5% (19/40) | −2.5 |\n");
    }

    @Test
    void a_single_module_points_at_its_own_page_relative_to_the_root() {
        String md = render(record(3, List.of(module("lib", 1, 0, 0, 0))), null);
        assertThat(md).contains(pointer("target/lib/reports/coverage/index.html"));
        assertThat(md).contains("Coverage: **100.0%** lines · 100.0% branches\n");
    }

    @Test
    void signed_points_carry_a_sign_and_one_decimal() {
        assertThat(JkResultsCoverageSection.signed(1.26)).isEqualTo("+1.3");
        assertThat(JkResultsCoverageSection.signed(-0.04)).isEqualTo("±0.0");
        assertThat(JkResultsCoverageSection.signed(-0.5)).isEqualTo("−0.5");
    }
}
