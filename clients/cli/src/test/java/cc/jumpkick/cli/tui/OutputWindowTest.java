// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
    void display_budget_clamps_to_max_and_free_rows() {
        assertThat(OutputWindow.displayBudget(40, 5)).isEqualTo(34); // 40 - 5 - 1
        assertThat(OutputWindow.displayBudget(300, 1)).isEqualTo(OutputWindow.MAX_LINES);
        assertThat(OutputWindow.displayBudget(10, 20)).isEqualTo(0); // no free rows
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

        cm.outputWindow().show();
        List<String> lines = cm.renderBuildPlanLines(80, 0);
        // pane line + braille rule (with caption) + header
        assertThat(lines.get(0)).contains("hidden-line");
        String rule = TestAnsi.strip(lines.get(1));
        assertThat(rule).contains("output").contains("\u2191"); // ↑
        assertThat(rule).startsWith("\u2812").endsWith("\u2812"); // ⠒
        assertThat(lines.get(2)).contains("Build");
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
        List<String> off = cm.renderBuildPlanLines(80, 0);
        assertThat(TestAnsi.strip(off.get(0))).doesNotContain("output");
        assertThat(off.get(0)).contains("Build");
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
        List<String> lines = cm.renderBuildPlanLines(80, 0);
        assertThat(lines.get(0)).contains("native-image: error");
        cm.close();
    }
}
