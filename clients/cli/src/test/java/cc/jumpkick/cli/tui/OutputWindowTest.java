// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OutputWindowTest {

    @Test
    void ring_evicts_oldest_past_max_lines() {
        OutputWindow w = new OutputWindow();
        for (int i = 0; i < OutputWindow.MAX_LINES + 50; i++) {
            w.append("line-" + i);
        }
        assertThat(w.size()).isEqualTo(OutputWindow.MAX_LINES);
        List<String> shown = w.linesForDisplay(OutputWindow.MAX_LINES);
        assertThat(shown.getFirst()).isEqualTo("line-50");
        assertThat(shown.getLast()).isEqualTo("line-" + (OutputWindow.MAX_LINES + 49));
    }

    @Test
    void append_reports_acceptance_even_when_ring_is_full() {
        // JK-2085: once full, every accepted append evicts one line — size stays constant, so a
        // before/after size compare misreads acceptance as a blank-strip.
        OutputWindow w = new OutputWindow();
        for (int i = 0; i < OutputWindow.MAX_LINES; i++) w.append("fill-" + i);
        assertThat(w.append("over-capacity")).isTrue();
        assertThat(w.append("   ")).isFalse();
        assertThat(w.append(null)).isFalse();
        assertThat(w.linesForDisplay(OutputWindow.MAX_LINES).getLast()).isEqualTo("over-capacity");
    }

    @Test
    void writeAbove_keeps_printing_past_ring_capacity_when_not_animating() {
        // JK-2085: piped/CI plan mode (animate=false) printed nothing after the 200th line.
        CommandWedge.resetEnvelope();
        var buf = new ByteArrayOutputStream();
        var cm = new JkManager(new PrintStream(buf, true, StandardCharsets.UTF_8), false, true, 80);
        cm.name = "Build";
        cm.startNanos = System.nanoTime();
        int total = OutputWindow.MAX_LINES + 50;
        for (int i = 0; i < total; i++) cm.writeAbove("tool-line-" + i);
        String out = buf.toString(StandardCharsets.UTF_8);
        assertThat(out).contains("tool-line-0");
        assertThat(out).contains("tool-line-" + (OutputWindow.MAX_LINES + 49));
        cm.close();
    }

    @Test
    void display_budget_clamps_to_max_and_free_rows() {
        // rows - chrome - rule - cursor-park
        assertThat(OutputWindow.displayBudget(40, 5)).isEqualTo(33); // 40 - 5 - 2
        assertThat(OutputWindow.displayBudget(300, 1)).isEqualTo(OutputWindow.MAX_LINES);
        assertThat(OutputWindow.displayBudget(10, 20)).isEqualTo(0); // no free rows
        assertThat(OutputWindow.maxRegionLines(24)).isEqualTo(23);
    }

    @Test
    void live_region_never_fills_full_terminal_height() {
        CommandWedge.resetEnvelope();
        var buf = new ByteArrayOutputStream();
        var cm = new JkManager(new PrintStream(buf, true, StandardCharsets.UTF_8), true, true, 80);
        cm.name = "Build";
        cm.height = 20;
        cm.startNanos = System.nanoTime();
        cm.outputWindow().show();
        List<String> lines = cm.renderBuildPlanLines(80, 0);
        // Live region is rule + chrome only (process lines are scrollback).
        assertThat(lines.size()).isLessThanOrEqualTo(OutputWindow.maxRegionLines(20));
        assertThat(TestAnsi.strip(String.join("\n", lines))).contains("output");
        cm.close();
    }

    @Test
    void append_splits_embedded_newlines() {
        OutputWindow w = new OutputWindow();
        w.append("header\n ┃ body\n ┗━");
        assertThat(w.size()).isEqualTo(3);
        assertThat(w.linesForDisplay(10)).containsExactly("header", " ┃ body", " ┗━");
    }

    @Test
    void lines_for_display_takes_newest_within_budget() {
        OutputWindow w = new OutputWindow();
        for (int i = 0; i < 10; i++) w.append("L" + i);
        assertThat(w.linesForDisplay(3)).containsExactly("L7", "L8", "L9");
        assertThat(w.linesForDisplay(0)).isEmpty();
    }

    @Test
    void toggle_and_show() {
        OutputWindow w = new OutputWindow();
        assertThat(w.visible()).isFalse();
        assertThat(w.toggle()).isTrue();
        assertThat(w.visible()).isTrue();
        w.hide();
        assertThat(w.visible()).isFalse();
        w.show();
        assertThat(w.visible()).isTrue();
    }

    @Test
    void plan_opens_peek_when_config_build_output_true() {
        CommandWedge.resetEnvelope();
        var prev = cc.jumpkick.config.SessionContext.current();
        try {
            cc.jumpkick.config.SessionContext.installConfig(
                    cc.jumpkick.config.JkConfig.empty().withBuildOutput(Optional.of(true)));
            var buf = new ByteArrayOutputStream();
            var cm = JkManager.plan(new PrintStream(buf, true, StandardCharsets.UTF_8), "Build", false);
            assertThat(cm.outputWindow().visible()).isTrue();
            List<String> lines = cm.renderBuildPlanLines(80, 0);
            assertThat(TestAnsi.strip(lines.get(0))).contains("output");
            cm.close();
        } finally {
            cc.jumpkick.config.SessionContext.install(prev);
        }
    }

    @Test
    void plan_keeps_peek_closed_by_default() {
        CommandWedge.resetEnvelope();
        var prev = cc.jumpkick.config.SessionContext.current();
        try {
            cc.jumpkick.config.SessionContext.installConfig(cc.jumpkick.config.JkConfig.empty());
            var buf = new ByteArrayOutputStream();
            var cm = JkManager.plan(new PrintStream(buf, true, StandardCharsets.UTF_8), "Build", false);
            assertThat(cm.outputWindow().visible()).isFalse();
            cm.close();
        } finally {
            cc.jumpkick.config.SessionContext.install(prev);
        }
    }

    @Test
    void writeAbove_buffers_when_hidden_and_paints_when_shown() {
        CommandWedge.resetEnvelope();
        var buf = new ByteArrayOutputStream();
        // animate=true so plan mode buffers without always printing
        var cm = new JkManager(new PrintStream(buf, true, StandardCharsets.UTF_8), true, true, 80);
        cm.name = "Build";
        cm.startNanos = System.nanoTime();
        cm.writeAbove("hidden-line");
        assertThat(cm.outputWindow().size()).isEqualTo(1);
        assertThat(cm.outputWindow().visible()).isFalse();
        assertThat(buf.toString(StandardCharsets.UTF_8)).doesNotContain("hidden-line");

        // Live region is rule + chrome only — process lines are scrollback, not lastLines.
        cm.outputWindow().show();
        List<String> lines = cm.renderBuildPlanLines(80, 0);
        String rule = TestAnsi.strip(lines.get(0));
        assertThat(rule).contains("output").contains("\u2191");
        assertThat(rule).startsWith("\u2812").endsWith("\u2812");
        assertThat(lines.get(1)).contains("Build");
        assertThat(String.join("\n", lines)).doesNotContain("hidden-line");
        cm.close();
    }

    @Test
    void visible_empty_buffer_still_shows_rule_above_wedge() {
        CommandWedge.resetEnvelope();
        var buf = new ByteArrayOutputStream();
        var cm = new JkManager(new PrintStream(buf, true, StandardCharsets.UTF_8), true, true, 80);
        cm.name = "Build";
        cm.startNanos = System.nanoTime();
        cm.outputWindow().show();
        List<String> lines = cm.renderBuildPlanLines(80, 0);
        assertThat(TestAnsi.strip(lines.get(0))).contains("output");
        assertThat(lines.get(1)).contains("Build");
        cm.outputWindow().hide();
        // No committed process lines → no blank stand-in; chrome is first.
        List<String> off = cm.renderBuildPlanLines(80, 0);
        assertThat(TestAnsi.strip(off.get(0))).doesNotContain("output");
        assertThat(off.get(0)).contains("Build");
        cm.close();
    }

    @Test
    void hide_after_committed_output_replaces_rule_with_blank_separator() {
        CommandWedge.resetEnvelope();
        var buf = new ByteArrayOutputStream();
        var cm = new JkManager(new PrintStream(buf, true, StandardCharsets.UTF_8), true, true, 80);
        cm.name = "Build";
        cm.startNanos = System.nanoTime();
        cm.outputWindow().show();
        cm.outputWindow().append("native-image: compiling");
        cm.outputWindow().noteCommitted(1);
        List<String> on = cm.renderBuildPlanLines(80, 0);
        assertThat(TestAnsi.strip(on.get(0))).contains("output");
        assertThat(on.get(1)).contains("Build");

        cm.outputWindow().hide();
        List<String> off = cm.renderBuildPlanLines(80, 0);
        // Blank stand-in for the rule — not chrome immediately under process output.
        assertThat(off.get(0)).isEmpty();
        assertThat(off.get(1)).contains("Build");
        assertThat(TestAnsi.strip(String.join("\n", off))).doesNotContain("↑ output ↑");
        cm.close();
    }

    @Test
    void settle_after_committed_process_output_inserts_one_blank_before_chip() {
        CommandWedge.resetEnvelope();
        var buf = new ByteArrayOutputStream();
        var cm = new JkManager(new PrintStream(buf, true, StandardCharsets.UTF_8), true, true, 80);
        cm.name = "Build";
        cm.startNanos = System.nanoTime();
        cm.tick(); // initial region paint
        cm.outputWindow().show();
        cm.writeAbove("tool: line-one");
        assertThat(cm.outputWindow().committedScrollbackLines()).isGreaterThan(0);
        buf.reset();
        cm.finishBuildPlanSuccess("built");
        String out = buf.toString(StandardCharsets.UTF_8);
        // Settle chip is preceded by a blank (separator under process scrollback).
        assertThat(out).contains("\n\n");
        assertThat(out).contains("built");
        assertThat(out).doesNotEndWith("\n\n"); // no trailing blank before prompt
        cm.close();
    }

    @Test
    void rule_line_is_full_width_with_centered_caption() {
        int cols = 40;
        String plain = TestAnsi.strip(OutputWindow.ruleLine(cols));
        int width = JkManagerColor.rowColumnBudget(cols);
        assertThat(plain).hasSize(width);
        assertThat(plain).contains("\u2191 output \u2191");
        assertThat(plain).isEqualTo(OutputWindow.centeredRuleBody(width));
        // Left and right braille runs differ by at most 1 (integer split).
        int left = plain.indexOf(' ');
        int right = plain.length() - plain.lastIndexOf(' ') - 1;
        assertThat(Math.abs(left - right)).isLessThanOrEqualTo(1);
        // Narrow terminal: still fits width without throwing.
        assertThat(TestAnsi.strip(OutputWindow.ruleLine(8))).hasSize(JkManagerColor.rowColumnBudget(8));
    }

    @Test
    void force_show_on_step_failure_excludes_run_tests() {
        assertThat(JkManager.forceShowOnStepFailure("native-image", "native")).isTrue();
        assertThat(JkManager.forceShowOnStepFailure("compile-main", "compile")).isTrue();
        assertThat(JkManager.forceShowOnStepFailure("run-tests", "test")).isFalse();
        assertThat(JkManager.forceShowOnStepFailure("run-tests-fork", "test")).isFalse();
    }

    @Test
    void showProcessFailureOutput_opens_pane() {
        CommandWedge.resetEnvelope();
        var buf = new ByteArrayOutputStream();
        var cm = new JkManager(new PrintStream(buf, true, StandardCharsets.UTF_8), true, true, 80);
        cm.name = "Build";
        cm.startNanos = System.nanoTime();
        cm.writeAbove("native-image: error");
        assertThat(cm.outputWindow().visible()).isFalse();
        cm.showProcessFailureOutput();
        assertThat(cm.outputWindow().visible()).isTrue();
        // Open paints committed lines + live rule; live region starts with the rule.
        String out = TestAnsi.strip(buf.toString(StandardCharsets.UTF_8));
        assertThat(out).contains("native-image: error");
        assertThat(out).contains("output");
        List<String> live = cm.renderBuildPlanLines(80, 0);
        assertThat(TestAnsi.strip(live.get(0))).contains("output");
        cm.close();
    }
}
