// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.RenderContext;
import cc.jumpkick.runtime.TaskForecast;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure unit coverage for {@link ExplainCommand} plan rendering helpers. */
class ExplainCommandEstimateTest {

    @Test
    void fully_cached_build_time_is_under_one_second_not_unknown() {
        assertThat(ExplainCommand.buildTimeEstimateValue(0, true)).isEqualTo("<1s");
    }

    @Test
    void dirty_with_no_eta_is_not_yet_measured() {
        assertThat(ExplainCommand.buildTimeEstimateValue(0, false)).isEqualTo("not yet measured");
    }

    @Test
    void sub_second_eta_formats_as_under_one_second() {
        assertThat(ExplainCommand.buildTimeEstimateValue(400, false)).isEqualTo("<1s");
    }

    @Test
    void multi_second_eta_uses_tilde_estimate() {
        assertThat(ExplainCommand.buildTimeEstimateValue(8_000, false)).isEqualTo("~8s");
        assertThat(ExplainCommand.buildTimeEstimateValue(158_000, false)).isEqualTo("~2m 38s");
    }

    @Test
    void buildGraph_uses_name_pills_and_skips_index() {
        var dirty = TaskForecast.Module.fromWire(
                Path.of("/tmp/a"),
                "com.example:app",
                List.of(
                        new TaskForecast.Task("compile-main", TaskForecast.Status.RUN, "full compile", null),
                        new TaskForecast.Task("package-jar", TaskForecast.Status.RUN, "package", null)),
                10,
                0,
                true,
                false);
        var clean = TaskForecast.Module.fromWire(
                Path.of("/tmp/b"),
                "com.example:lib",
                List.of(new TaskForecast.Task("compile-main", TaskForecast.Status.CACHED, "", "x")),
                5,
                2,
                true,
                false);

        List<String> lines = ExplainCommand.buildGraph(
                        "com.example:root", List.of(clean, dirty), false, Theme.active(), false)
                .render(RenderContext.current().withAnsi(false));
        String joined = String.join("\n", lines);
        assertThat(joined).contains(" = Build Graph >");
        assertThat(joined).contains("* com.example:root");
        assertThat(joined).contains("[Fully Cached]").contains("1 module is fresh");
        assertThat(joined).contains("[Rebuild]").contains("1 module is dirty");
        assertThat(joined).contains("[app]");
        assertThat(joined).doesNotContain("[01]").doesNotContain("01");
        assertThat(joined).contains("[ ] Compile");
        // Cached names hang off Fully Cached with no extra spine; Rebuild has a spacer.
        assertThat(joined).contains(" +-[Fully Cached]");
        assertThat(joined).contains("    |");
        assertThat(joined).contains("    `-[app]");
    }

    @Test
    void chunkNames_packs_fixed_width_rows() {
        List<String> names = List.of("a", "b", "c", "d", "e", "f", "g");
        assertThat(ExplainCommand.chunkNames(names, 4))
                .containsExactly(List.of("a", "b", "c", "d"), List.of("e", "f", "g"));
        assertThat(ExplainCommand.chunkNames(List.of("only"), 4)).containsExactly(List.of("only"));
    }

    @Test
    void cached_name_line_is_check_plus_name_with_dim_commas() {
        String line = TestAnsi.strip(ExplainCommand.renderCachedNameLine(
                List.of("jk-core", "jk-cli", "jk-web", "jk-model"), true, Theme.active(), false));
        assertThat(line).isEqualTo("+ jk-core, + jk-cli, + jk-web, + jk-model,");
        String last = TestAnsi.strip(
                ExplainCommand.renderCachedNameLine(List.of("jk-android"), false, Theme.active(), false));
        assertThat(last).isEqualTo("+ jk-android");
    }

    @Test
    void formatStepName_pads_shorter_names_with_dots_to_align_with_longest() {
        // package-assembly (16) + 2-dot gap → width 18, matching the explain verbose tree.
        int width = "package-assembly".length() + 2;
        assertThat(ExplainCommand.formatStepName("compile-main", width, Theme.active(), false))
                .isEqualTo("compile-main......");
        assertThat(ExplainCommand.formatStepName("run-tests", width, Theme.active(), false))
                .isEqualTo("run-tests.........");
        assertThat(ExplainCommand.formatStepName("package-assembly", width, Theme.active(), false))
                .isEqualTo("package-assembly..");
    }

    @Test
    void formatStepName_ansi_keeps_visible_width_and_name_prefix() {
        int width = "package-assembly".length() + 2;
        String styled = ExplainCommand.formatStepName("compile-main", width, Theme.active(), true);
        assertThat(TestAnsi.strip(styled)).isEqualTo("compile-main......");
    }

    @Test
    void phase_chain_rolls_tasks_into_compile_test_package_with_details() {
        var module = TaskForecast.Module.fromWire(
                Path.of("/tmp/m"),
                "com.example:app",
                List.of(
                        new TaskForecast.Task("write-stamp", TaskForecast.Status.RUN, "", null),
                        new TaskForecast.Task("compile-main", TaskForecast.Status.CACHED, "", "abcd1234"),
                        new TaskForecast.Task(
                                "compile-test", TaskForecast.Status.RUN, "compile · 0 sources changed", null),
                        new TaskForecast.Task("run-tests", TaskForecast.Status.RUN, "run tests · ~28 tests", null),
                        new TaskForecast.Task("package-jar", TaskForecast.Status.RUN, "repackage", null)),
                12,
                28,
                true,
                false);

        String chain = TestAnsi.strip(ExplainCommand.renderStageChain(module, Theme.active(), false));
        // Bookkeeping write-stamp is omitted; cached compile, dirty test/package with counts.
        assertThat(chain).isEqualTo("+ Compile > [ ] Test ~28 tests > [ ] Package");
    }

    @Test
    void phase_chain_includes_native_and_source_detail_on_dirty_compile() {
        var module = TaskForecast.Module.fromWire(
                Path.of("/tmp/cli"),
                "cc.jumpkick:jk-cli",
                List.of(
                        new TaskForecast.Task(
                                "compile-main",
                                TaskForecast.Status.RUN,
                                "full compile · 239 sources · classpath changed",
                                null),
                        new TaskForecast.Task("run-tests", TaskForecast.Status.RUN, "run tests · ~1103 tests", null),
                        new TaskForecast.Task(
                                "package-jar", TaskForecast.Status.RUN, "repackage · compile changed", null),
                        new TaskForecast.Task(
                                "native-image", TaskForecast.Status.RUN, "rebuild · compile changed", null)),
                239,
                1103,
                true,
                false);

        String chain = TestAnsi.strip(ExplainCommand.renderStageChain(module, Theme.active(), false));
        assertThat(chain).isEqualTo("[ ] Compile 239 sources > [ ] Test ~1,103 tests > [ ] Package > [ ] Native");
        assertThat(ExplainCommand.producesNative(module)).isTrue();
    }

    @Test
    void dirty_compile_shows_the_changed_count_not_the_module_total() {
        // JK-1897: an incremental recompile must read as one — the forecast says how many
        // sources actually changed; the module's full source count overstates the work.
        var module = TaskForecast.Module.fromWire(
                Path.of("/tmp/m"),
                "com.example:app",
                List.of(
                        new TaskForecast.Task(
                                "compile-main", TaskForecast.Status.RUN, "compile · 3 sources changed", null),
                        new TaskForecast.Task("run-tests", TaskForecast.Status.RUN, "run tests · ~28 tests", null)),
                239,
                28,
                true,
                false);
        String chain = TestAnsi.strip(ExplainCommand.renderStageChain(module, Theme.active(), false));
        assertThat(chain).isEqualTo("[ ] Compile 3 sources changed > [ ] Test ~28 tests");
        assertThat(ExplainCommand.changedSourceCount(module)).isEqualTo(3);
    }

    @Test
    void single_changed_source_and_single_test_are_singular() {
        // JK-1897/JK-1898: "1 source changed", "~1 test" — never "~1 tests".
        var module = TaskForecast.Module.fromWire(
                Path.of("/tmp/m"),
                "com.example:app",
                List.of(
                        new TaskForecast.Task(
                                "compile-main", TaskForecast.Status.RUN, "compile · 1 source changed", null),
                        new TaskForecast.Task("run-tests", TaskForecast.Status.RUN, "run tests", null)),
                239,
                1,
                true,
                false);
        String chain = TestAnsi.strip(ExplainCommand.renderStageChain(module, Theme.active(), false));
        assertThat(chain).isEqualTo("[ ] Compile 1 source changed > [ ] Test ~1 test");
    }

    @Test
    void summary_table_lists_plan_items_rebuild_surface_and_eta() {
        var dirty = TaskForecast.Module.fromWire(
                Path.of("/tmp/a"),
                "com.example:a",
                List.of(
                        new TaskForecast.Task("compile-main", TaskForecast.Status.RUN, "full compile", null),
                        new TaskForecast.Task("package-jar", TaskForecast.Status.RUN, "package", null),
                        new TaskForecast.Task("native-image", TaskForecast.Status.RUN, "rebuild", null)),
                10,
                0,
                true,
                false);
        var clean = TaskForecast.Module.fromWire(
                Path.of("/tmp/b"),
                "com.example:b",
                List.of(
                        new TaskForecast.Task("compile-main", TaskForecast.Status.CACHED, "", "x"),
                        new TaskForecast.Task("package-jar", TaskForecast.Status.CACHED, "", "y")),
                5,
                2,
                true,
                false);

        // 8s remaining against 16s full rebuild → 50% effort (time-weighted, not counts).
        List<String> lines =
                ExplainCommand.renderSummaryTable(List.of(dirty, clean), 8_000, 16_000, false, Theme.active(), false)
                        .stream()
                        .map(TestAnsi::strip)
                        .toList();

        String joined = String.join("\n", lines);
        assertThat(joined).contains("Plan Item");
        assertThat(joined).contains("Modules");
        assertThat(joined).contains("Sources");
        assertThat(joined).contains("Packages");
        assertThat(joined).contains("Native Bins");
        assertThat(joined).contains("Total rebuild effort");
        assertThat(joined).contains("Build time estimate");
        assertThat(joined).contains("~8s");
        assertThat(joined).contains("50%");
        // One of two modules dirty → Modules rebuild 1; native only on dirty module.
        assertThat(joined).containsPattern("Modules.*1");
    }

    @Test
    void rebuild_effort_is_remaining_over_full_eta() {
        assertThat(ExplainCommand.rebuildEffortPct(90_000, 180_000)).isEqualTo(50);
        assertThat(ExplainCommand.rebuildEffortPct(180_000, 180_000)).isEqualTo(100);
        assertThat(ExplainCommand.rebuildEffortPct(0, 180_000)).isEqualTo(0);
        assertThat(ExplainCommand.rebuildEffortPct(10_000, 0)).isEqualTo(100);
        assertThat(ExplainCommand.rebuildEffortPct(200_000, 180_000)).isEqualTo(100);
    }

    @Test
    void summary_table_footer_join_uses_tee_and_cross_by_rail_continuity() {
        var m = TaskForecast.Module.fromWire(
                Path.of("/tmp/a"),
                "com.example:a",
                List.of(
                        new TaskForecast.Task("compile-main", TaskForecast.Status.RUN, "full", null),
                        new TaskForecast.Task("package-jar", TaskForecast.Status.RUN, "pkg", null)),
                10,
                0,
                true,
                false);

        // Force ANSI path so box-drawing junctions are present (plain mode uses +).
        List<String> lines =
                ExplainCommand.renderSummaryTable(List.of(m), 1_000, 2_000, false, Theme.active(), true).stream()
                        .map(TestAnsi::strip)
                        .toList();

        String footerJoin = "";
        String footerRow = "";
        String footerClose = "";
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("Total rebuild effort")) {
                footerJoin = lines.get(i - 1);
                footerRow = lines.get(i);
                footerClose = lines.get(i + 2); // effort, eta, then close
                break;
            }
        }
        // Ending rails (Plan Item|Total, Rebuild|Delta) → ┴; continuing mid rail → ┼.
        assertThat(footerJoin).startsWith("├").endsWith("┤");
        assertThat(footerJoin).contains("┴").contains("┼");
        assertThat(footerJoin).doesNotContain("┬");
        // Junction order on the collapse rule: ┴ then ┼ then ┴.
        int firstTee = footerJoin.indexOf('┴');
        int cross = footerJoin.indexOf('┼');
        int secondTee = footerJoin.lastIndexOf('┴');
        assertThat(firstTee).isGreaterThan(0);
        assertThat(cross).isGreaterThan(firstTee);
        assertThat(secondTee).isGreaterThan(cross);
        // Mid ┼ lines up with the footer label|value │ and the bottom ┴.
        assertThat(cross).isEqualTo(footerRow.indexOf('│', 1));
        assertThat(cross).isEqualTo(footerClose.indexOf('┴'));
    }
}
