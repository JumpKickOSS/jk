// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static cc.jumpkick.cli.tui.JkManagerTestSupport.stream;
import static cc.jumpkick.cli.tui.JkManagerTestSupport.stripAll;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.NerdFontCaps;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Phase chain, brief errors, completions tail, output window, and elapsed formatting of the JkManager plan chrome. */
class JkManagerChromeTest {

    @Test
    void preflight_detail_shows_on_phase_only_tree_row() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdFont = NerdFontCaps.NONE;
        cm.preflight("lock", 0, 1, "resolving dependencies");

        String all = String.join("\n", stripAll(cm.renderBuildPlanLines(120, 0)));
        assertThat(all).contains("Lock").contains("resolving dependencies");
    }

    @Test
    void phase_chain_shows_running_and_failed_only_newest_first() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Building", false);
        cm.nerdFont = NerdFontCaps.NONE;
        cm.stepDone("m", "s1", true, "resolve"); // success → dropped
        cm.stepDone("m", "s2", false, "compile"); // failed → stays
        cm.stepRunning("m", "s3", "test");

        String all = String.join("\n", stripAll(cm.renderBuildPlanLines(120, 0)));
        assertThat(all).contains("Test").contains("Compile");
        assertThat(all).doesNotContain("Resolve");
        // Running rows first (newest), then failed by finish seq.
        assertThat(all.indexOf("Test")).isLessThan(all.indexOf("Compile"));
        assertThat(all).contains(Glyphs.CROSS); // failed row icon
    }

    @Test
    void finishModule_drops_orphan_active_rows() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdFont = NerdFontCaps.NONE;
        cm.stepRunning("cc.jumpkick:jk-client-io", "ensure-jdk", "resolve");
        cm.stepMessage("cc.jumpkick:jk-client-io", "ensure-jdk", "resolve JDK");
        cm.stepRunning("cc.jumpkick:jk-cli", "native-image", "native");

        cm.finishModule("cc.jumpkick:jk-client-io", true);

        String all = String.join("\n", stripAll(cm.renderBuildPlanLines(120, 0)));
        assertThat(all).contains("jk-cli").contains("Native");
        assertThat(all).doesNotContain("jk-client-io");
        assertThat(all).doesNotContain("resolve JDK");
    }

    @Test
    void stepRunning_does_not_resurrect_a_finished_step() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdFont = NerdFontCaps.NONE;
        cm.stepRunning("m", "ensure-jdk", "resolve");
        cm.stepDone("m", "ensure-jdk", true, "resolve");
        cm.stepRunning("m", "ensure-jdk", "resolve"); // late / out-of-order start
        cm.stepMessage("m", "ensure-jdk", "resolve JDK");

        String all = String.join("\n", stripAll(cm.renderBuildPlanLines(120, 0)));
        assertThat(all).doesNotContain("Resolve");
        assertThat(all).doesNotContain("resolve JDK");
    }

    @Test
    void failed_phase_shows_brief_error_below() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Building", false);
        cm.nerdFont = NerdFontCaps.NONE;
        cm.stepRunning("m", "compile", "compile");
        cm.attachPhaseError("m", "compile", "compile", "javac failed: cannot find symbol");
        cm.stepDone("m", "compile", false, "compile");

        var lines = stripAll(cm.renderBuildPlanLines(120, 0));
        String all = String.join("\n", lines);
        assertThat(all).contains("Compile").contains("cannot find symbol");
    }

    @Test
    void attachPhaseError_clamp_never_splits_a_surrogate_pair() {
        // The 96-code-unit clamp lands the cut at index 93; when that splits an emoji's
        // surrogate pair the brief-error row would end in a lone high surrogate (mojibake).
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdFont = NerdFontCaps.NONE;
        cm.stepRunning("m", "compile", "compile");
        String brief = "a".repeat(92) + "😀" + " trailing context that forces the clamp";
        cm.attachPhaseError("m", "compile", "compile", brief);
        cm.stepDone("m", "compile", false, "compile");

        String all = String.join("\n", stripAll(cm.renderBuildPlanLines(120, 0)));
        assertThat(all).contains("a".repeat(92) + "…");
        assertThat(all.chars().anyMatch(c -> Character.isHighSurrogate((char) c) || Character.isLowSurrogate((char) c)))
                .isFalse();
    }

    @Test
    void attachPhaseError_uses_row_wire_phase_when_callers_pass_empty_phase() {
        // listeners pass phase=""; step key is compile-java, phase node is compile.
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdFont = NerdFontCaps.NONE;
        cm.stepRunning("m", "compile-java", "compile");
        cm.attachPhaseError("m", "compile-java", "", "cannot find symbol Foo");
        cm.stepDone("m", "compile-java", false, "compile");

        var lines = stripAll(cm.renderBuildPlanLines(120, 0));
        String all = String.join("\n", lines);
        assertThat(all).contains("Compile").contains("cannot find symbol Foo");
        assertThat(all).doesNotContain("Failed\n"); // not the generic-only brief when we have a real one
    }

    @Test
    void brief_error_line_colors_message_not_rail() {
        // Rail/indent is dim; only the message is error-red (not the whole " │ Failed" string).
        Theme t = Theme.active();
        String mid = JkManager.renderBriefErrorLine(false, "Failed");
        String last = JkManager.renderBriefErrorLine(true, "boom");
        assertThat(mid).isEqualTo(Theme.colorize(" │  ", t.darkGray()) + Theme.colorize("Failed", t.error()));
        assertThat(last).isEqualTo(Theme.colorize("    ", t.darkGray()) + Theme.colorize("boom", t.error()));
        // Whole-line coloring would put the rail inside one error-styled span — must not.
        assertThat(mid).isNotEqualTo(Theme.colorize(" │  Failed", t.error()));
    }

    @Test
    void brief_error_under_last_tree_entry_uses_space_indent_not_rail() {
        // ╰─ then spaces, not │ under a closing branch.
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdFont = NerdFontCaps.NONE;
        cm.stepRunning("m", "compile-java", "compile");
        cm.attachPhaseError("m", "compile-java", "compile", "boom");
        cm.stepDone("m", "compile-java", false, "compile");

        var lines = stripAll(cm.renderBuildPlanLines(120, 0));
        // Find the error line after the last ╰─ row
        boolean sawClose = false;
        for (String line : lines) {
            if (line.startsWith(" ╰─") || line.startsWith(" `-")) sawClose = true;
            if (sawClose && line.contains("boom")) {
                assertThat(line).startsWith("    boom");
                assertThat(line).doesNotContain("│");
                return;
            }
        }
        throw new AssertionError("expected brief error under closing branch, got:\n" + String.join("\n", lines));
    }

    @Test
    void tree_is_vertically_compact_without_blank_rail_spacers() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Building", false);
        cm.nerdFont = NerdFontCaps.NONE;
        cm.stepRunning("com.foo:a", "a", "compile");
        cm.stepRunning("com.foo:b", "b", "test");
        var lines = stripAll(cm.renderBuildPlanLines(120, 0));
        // header + two tree rows only (no leading │, no blank │ between).
        assertThat(lines).hasSize(3);
        assertThat(lines.get(1)).matches(" [├+].*").contains("com.foo").contains("›");
        assertThat(lines.get(2)).matches(" [╰`].*").contains("com.foo").contains("›");
        for (String line : lines) {
            assertThat(line.strip()).isNotEqualTo("│");
        }
    }

    @Test
    void region_is_capped_to_terminal_height() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Building", false);
        cm.height = 6;
        for (int i = 0; i < 20; i++) cm.stepRunning("m", "p" + i, "phase" + i);

        var lines = cm.renderBuildPlanLines(120, 0);
        assertThat(lines.size()).isLessThanOrEqualTo(6 - 1);
    }

    @Test
    void phase_chain_uses_tree_connectors() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Building", false);
        cm.nerdFont = NerdFontCaps.NONE;
        cm.stepRunning("m", "compile", "compile");
        var lines = cm.renderBuildPlanLines(120, 0);
        // lines[0]=header, lines[1]=single work row (closing branch) — no leading blank rail.
        assertThat(TestAnsi.strip(lines.get(1))).matches(" [╰`].*Compile.*");
    }

    @Test
    void completed_lines_render_below_the_phase_chain_newest_first() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdFont = NerdFontCaps.NONE;
        cm.stepRunning("m", "compile", "compile");
        cm.addCompletion("✓ [13 of 17] g:a13 took 1s");
        cm.addCompletion("✓ [14 of 17] g:a14 took 1s");

        var lines = cm.renderBuildPlanLines(120, 0);
        // header, work row (╰─), then completions newest first.
        assertThat(TestAnsi.strip(lines.get(1))).matches(" [╰`].*Compile.*");
        assertThat(TestAnsi.strip(lines.get(2))).isEqualTo("    ✓ [14 of 17] g:a14 took 1s");
        assertThat(TestAnsi.strip(lines.get(3))).isEqualTo("    ✓ [13 of 17] g:a13 took 1s");
    }

    @Test
    void addCompletion_does_not_print_into_scrollback() {
        var buf = new ByteArrayOutputStream();
        var cm = new JkManager(stream(buf), true, true, 80);
        cm.name = "Build";
        cm.startNanos = System.nanoTime();
        cm.stepRunning("m", "compile");
        cm.tick();
        buf.reset();

        cm.addCompletion("✓ [01 of 3] ex:lib took 1s");

        assertThat(TestAnsi.strip(buf.toString(StandardCharsets.UTF_8))).doesNotContain("✓ [01 of 3] ex:lib took 1s");
        assertThat(stripAll(cm.renderBuildPlanLines(80, 0)).stream()
                        .anyMatch(l -> l.contains("✓ [01 of 3] ex:lib took 1s")))
                .isTrue();
    }

    @Test
    void completed_tail_is_wiped_on_settle_not_copied_above_chrome() {
        var buf = new ByteArrayOutputStream();
        var cm = new JkManager(stream(buf), true, true, 80);
        cm.name = "Build";
        cm.startNanos = System.nanoTime();
        cm.stepRunning("m", "compile");
        cm.addCompletion("✓ [01 of 3] ex:lib took 1s");
        cm.tick();
        buf.reset();

        cm.finishBuildPlanSuccess("built 3 modules");

        assertThat(TestAnsi.strip(buf.toString(StandardCharsets.UTF_8))).doesNotContain("✓ [01 of 3] ex:lib took 1s");
    }

    @Test
    void completed_tail_is_not_starved_by_a_full_work_tree() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdFont = NerdFontCaps.NONE;
        cm.height = 10;
        for (int i = 0; i < 8; i++) {
            cm.stepRunning("g:m" + i, "compile");
        }
        cm.addCompletion("✓ [01 of 3] g:a took 1s");

        var all = String.join("\n", stripAll(cm.renderBuildPlanLines(120, 0)));
        assertThat(all).contains("✓ [01 of 3] g:a took 1s");
    }

    @Test
    void completed_tail_caps_and_collapses_overflow_into_a_footer() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.stepRunning("m", "compile");
        for (int i = 1; i <= 8; i++) cm.addCompletion("✓ [0" + i + " of 17] g:a" + i + " took 1s");

        var all = String.join("\n", stripAll(cm.renderBuildPlanLines(120, 0)));
        // Only MAX_COMPLETIONS (5) show; the newest is first; 3 collapse into the footer.
        assertThat(all).contains("    ✓ [08 of 17]").contains("    ✓ [04 of 17]");
        assertThat(all).doesNotContain("[03 of 17]");
        assertThat(all).contains("      … plus 3 more …");
    }

    @Test
    void write_above_buffers_when_hidden_and_repaints_when_shown() {
        var buf = new ByteArrayOutputStream();
        var cm = new JkManager(stream(buf), true, true, 80);
        cm.progress(1, 4);
        cm.stepRunning("m", "compile");
        cm.tick(); // initial region paint
        buf.reset();

        cm.writeAbove("javac: warning in Foo.java");
        // Hidden by default: buffered only — no permanent scrollback line.
        assertThat(cm.outputWindow().size()).isEqualTo(1);
        assertThat(TestAnsi.strip(buf.toString(StandardCharsets.UTF_8))).doesNotContain("javac: warning in Foo.java");

        cm.showProcessFailureOutput(); // force-open: commit buffer + paint rule/wedge
        String visible = TestAnsi.strip(buf.toString(StandardCharsets.UTF_8));
        assertThat(visible).contains("javac: warning in Foo.java");
        assertThat(visible).contains("output"); // rule caption

        // Further lines while open: lift live region, emit line, repaint wedge (immediate).
        buf.reset();
        cm.writeAbove("second line");
        assertThat(cm.outputWindow().size()).isEqualTo(2);
        assertThat(TestAnsi.strip(buf.toString(StandardCharsets.UTF_8))).contains("second line");
    }

    @Test
    void writeAbove_splits_a_multiline_diagnostic_into_scrollback_rows() {
        var buf = new ByteArrayOutputStream();
        var cm = new JkManager(stream(buf), true, true, 80);
        cm.name = "Build";
        cm.progress(1, 4);
        cm.stepRunning("m", "compile-java");
        cm.tick();
        buf.reset();

        String report = "Compile Java Failure\n ┃ error: class expected\n ┃     Foo.java:1\n ┗━";
        cm.writeAbove(report);
        assertThat(cm.outputWindow().size()).isEqualTo(4);

        cm.showProcessFailureOutput();
        String visible = TestAnsi.strip(buf.toString(StandardCharsets.UTF_8));
        assertThat(visible).contains("Compile Java Failure");
        assertThat(visible).contains("error: class expected");
        assertThat(visible).contains("Foo.java:1");
        // Still multi-line — not one squash of rails onto the header.
        assertThat(visible.indexOf("Compile Java Failure")).isLessThan(visible.indexOf("error: class expected"));
        cm.close();
    }

    @Test
    void toggle_off_replaces_peek_rule_with_blank_separator() {
        var buf = new ByteArrayOutputStream();
        var cm = new JkManager(stream(buf), true, true, 80);
        cm.name = "Build";
        cm.progress(1, 4);
        cm.stepRunning("m", "compile");
        cm.tick();
        cm.outputWindow().show();
        cm.writeAbove("native-image: step");
        assertThat(cm.outputWindow().committedScrollbackLines()).isEqualTo(1);
        List<String> on = cm.renderBuildPlanLines(80, 0);
        assertThat(TestAnsi.strip(on.get(0))).contains("output");

        cm.toggleOutputWindow(); // hide: rule → blank, not delete separator
        assertThat(cm.outputWindow().visible()).isFalse();
        List<String> off = cm.renderBuildPlanLines(80, 0);
        assertThat(off.get(0)).isEmpty();
        assertThat(TestAnsi.strip(off.get(1))).doesNotContain("output");
        assertThat(off.get(1)).isNotEmpty();
        assertThat(cm.outputWindow().committedScrollbackLines()).isEqualTo(1);
    }

    @Test
    void capture_output_buffers_system_out_in_the_output_window() {
        var buf = new ByteArrayOutputStream();
        var cm = new JkManager(stream(buf), true, true, 80);
        cm.stepRunning("m", "compile");
        cm.tick();
        buf.reset();

        PrintStream original = System.out;
        try (var scope = cm.captureOutput()) {
            System.out.println("from a step");
        }
        assertThat(System.out).isSameAs(original); // streams restored
        assertThat(cm.outputWindow().size()).isEqualTo(1);
        assertThat(cm.outputWindow().linesForDisplay(10)).contains("from a step");
    }

    @Test
    void fmt_elapsed_formats_minutes_and_seconds() {
        assertThat(JkManager.fmtElapsed(112_000)).isEqualTo("1m 52s");
        assertThat(JkManager.fmtElapsed(52_000)).isEqualTo("52s");
        assertThat(JkManager.fmtElapsed(0)).isEqualTo("0s");
    }
}
