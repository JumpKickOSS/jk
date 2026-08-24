// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static cc.jumpkick.cli.tui.JkManagerTestSupport.stream;
import static cc.jumpkick.cli.tui.JkManagerTestSupport.stripAll;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.theme.Rgb;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.NerdFontCaps;
import cc.jumpkick.terminal.Ansi;
import cc.jumpkick.terminal.Size;
import cc.jumpkick.terminal.Style;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Plan-mode header pill, work-tree rows, and resize/reflow repaint behavior of the JkManager component. */
class JkManagerTreeTest {

    @Test
    void goal_header_bar_and_phase_chain() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Building", false);
        cm.nerdFont = NerdFontCaps.NONE;
        cm.progress(45, 100);
        cm.stepDone("acme:api", "parse-build", true, "resolve"); // success → removed from chain
        cm.stepRunning("acme:api", "compile-java", "compile");

        var raw = cm.renderBuildPlanLines(120, 112_000);
        String all = String.join("\n", stripAll(raw));
        assertThat(TestAnsi.strip(raw.get(0)))
                .contains("Building")
                .contains(Spinner.PULSE_GLYPH)
                .contains("1m 52s")
                .doesNotContain("acme:api"); // module lives on the tree row, not the header
        assertThat(all).contains("45%");
        // Only running Compile stays; Resolve succeeded and is gone. Module › phase on the row.
        assertThat(all).contains("acme:api").contains("Compile").contains("›");
        assertThat(all).doesNotContain("Resolve");
        assertThat(all).containsAnyOf("├─", "╰─", "+-", "`-");
    }

    @Test
    void nerdfont_header_wraps_the_name_in_a_pill_with_a_powerline_cap() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdFont = NerdFontCaps.ALL;
        cm.progress(45, 100);

        String header = cm.renderBuildPlanLines(120, 0).get(0);
        // Pill: pulse circle + name + powerline cap.
        assertThat(TestAnsi.strip(header)).contains(Spinner.PULSE_GLYPH + " Build " + Glyphs.SEGMENT_END_NERD);
        Style chip = Theme.active().planChip();
        assertThat(header).startsWith(Theme.colorize(" ", chip));
        assertThat(header).contains(Theme.colorize("Build", chip));
        // Cap: FG = chip blue; BG = bar lead color.
        Rgb lead = new ProgressBar().leadColor(45, 100);
        assertThat(header)
                .contains(Theme.colorize(
                        Glyphs.SEGMENT_END_NERD,
                        Theme.active()
                                .withBackground(
                                        Theme.active().bright(Theme.active().planBadgeColor()), lead)));
    }

    @Test
    void canceling_a_goal_region_returns_to_column_zero_before_erasing() {
        var buf = new ByteArrayOutputStream();
        var cm = new JkManager(stream(buf), true, true, 80); // animate + plan mode
        cm.progress(2, 4);
        cm.stepRunning("m", "compile");
        cm.tick(); // paint the live region
        buf.reset();

        cm.renderCanceled();

        // On Ctrl-C the tty echoes "^C" at the cursor (two columns in); cursorUp keeps
        // the column, so the wipe must emit a carriage return before
        // ERASE_DISPLAY_TO_END — otherwise the first two columns (the spinner glyph)
        // of the top line survive.
        String raw = buf.toString(StandardCharsets.UTF_8);
        assertThat(raw).contains("\r" + Ansi.ERASE_DISPLAY_TO_END);
    }

    @Test
    void tree_rows_use_blue_spinner_and_blue_phase_not_background_pills() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdFont = NerdFontCaps.NONE;
        cm.stepRunning("com.foo:bar", "compile", "compile");
        cm.stepDone("com.foo:baz", "test", false, "test");
        var raw = cm.renderBuildPlanLines(120, 0);
        String joined = String.join("\n", raw);
        String visible = String.join("\n", stripAll(raw));
        Theme t = Theme.active();
        // Compact module › phase: no bg pills / powerline caps on the tree.
        assertThat(visible).contains("com.foo:bar").contains("›").contains("Compile");
        assertThat(visible).contains("com.foo:baz").contains("Test");
        // Running phase is bold blue (web parity); failed phase stays red.
        assertThat(joined).contains(Theme.colorize("Compile", t.blue().bold()));
        assertThat(joined).contains(Theme.colorize("Test", t.error()));
        assertThat(joined).contains(Theme.colorize("›", t.darkGray()));
        assertThat(joined).doesNotContain(Glyphs.PILL_LEFT_NERD);
        // Running row uses fill-circle (○) in constant blue — not the solid ● pulse glyph.
        assertThat(visible).contains("\u25CB"); // ○ frame 0
        assertThat(joined).contains(Theme.colorize("\u25CB", t.blue()));
        assertThat(visible).doesNotContain(Spinner.PULSE_GLYPH + " com.foo:bar");
    }

    @Test
    void tree_fill_spinner_cycles_circle_bullseye_fisheye() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdFont = NerdFontCaps.NONE;
        cm.stepRunning("m", "compile", "compile");
        assertThat(Spinner.FILL_PHASES).containsExactly("\u25CB", "\u25CE", "\u25C9", "\u25CE");
        assertThat(Spinner.FILL_HOLD).isEqualTo(4);
        assertThat(Spinner.fillGlyph(0)).isEqualTo("\u25CB");
        assertThat(Spinner.fillGlyph(4)).isEqualTo("\u25CE");
        String line0 = stripAll(cm.renderBuildPlanLines(120, 0)).get(1);
        assertThat(line0).contains("\u25CB");
    }

    @Test
    void tree_row_appends_step_message_as_detail_after_phase() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdFont = NerdFontCaps.NONE;
        cm.stepRunning("cc.jumpkick:jk-java-compiler", "package-jar", "package");
        cm.stepMessage("cc.jumpkick:jk-java-compiler", "package-jar", "shrinking jar");

        String all = String.join("\n", stripAll(cm.renderBuildPlanLines(120, 0)));
        // ● module › Package › shrinking jar
        assertThat(all)
                .contains("cc.jumpkick:jk-java-compiler")
                .contains("Package")
                .contains("shrinking jar")
                .contains("›");
        assertThat(all.indexOf("Package")).isLessThan(all.indexOf("shrinking jar"));
    }

    @Test
    void tree_row_strips_redundant_module_prefix_from_test_labels() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdFont = NerdFontCaps.NONE;
        cm.stepRunning("cc.jumpkick:jk-core", "run-tests", "test");
        cm.stepMessage("cc.jumpkick:jk-core", "run-tests", "cc.jumpkick:jk-core :: FooTest.bar()  [w2]");

        String all = String.join("\n", stripAll(cm.renderBuildPlanLines(120, 0)));
        assertThat(all).contains("FooTest.bar()").contains("[w2]");
        // Module appears once as the row coordinate, not again in the detail segment.
        int first = all.indexOf("cc.jumpkick:jk-core");
        int second = all.indexOf("cc.jumpkick:jk-core", first + 1);
        assertThat(second).isLessThan(0);
    }

    @Test
    void test_detail_uses_java_syntax_highlighting() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdFont = NerdFontCaps.NONE;
        cm.stepRunning("cc.jumpkick:jk-engine", "run-tests", "test");
        cm.stepMessage("cc.jumpkick:jk-engine", "run-tests", "VariantSwitchTest.switching_variants(Path)");

        var raw = cm.renderBuildPlanLines(120, 0);
        String joined = String.join("\n", raw);
        String visible = String.join("\n", stripAll(raw));
        assertThat(visible).contains("VariantSwitchTest.switching_variants(Path)");
        // Class name → TYPE, method → FUNCTION (same roles as source snippets).
        Theme t = Theme.active();
        assertThat(joined).contains(Theme.colorize("VariantSwitchTest", t.synType()));
        assertThat(joined).contains(Theme.colorize("switching_variants", t.synFunction()));
        assertThat(joined).contains(Theme.colorize("Path", t.synType()));
    }

    @Test
    void tree_rows_never_wrap_long_test_details() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdFont = NerdFontCaps.NONE;
        cm.stepRunning("cc.jumpkick:jk-engine", "run-tests", "test");
        String longName = "VariantSwitchTest.switching_variants_drops_the_previous_values_extra_src_classes(Path)";
        cm.stepMessage("cc.jumpkick:jk-engine", "run-tests", longName);

        int cols = 60;
        // Paint uses rowColumnBudget (terminal width − 1) so the trailing … is not lost to
        // DEC auto-wrap on the last column.
        int paintCols = JkManagerColor.rowColumnBudget(cols);
        for (String line : cm.renderBuildPlanLines(cols, 0)) {
            assertThat(RenderContext.visibleWidth(JkManager.truncateVisible(line, paintCols)))
                    .isLessThanOrEqualTo(paintCols);
        }
        // Truncation adds an ellipsis rather than wrapping.
        String painted =
                JkManager.truncateVisible(cm.renderBuildPlanLines(cols, 0).get(1), paintCols);
        assertThat(TestAnsi.strip(painted)).contains("…");
        assertThat(TestAnsi.strip(painted)).endsWith("…");
    }

    @Test
    void paint_picks_up_terminal_resize_and_rewrites_truncated_rows() {
        // Mid-build maximize: SIGWINCH clears Size's cache. Paint must re-read size
        // and force a full rewrite so a long test name is not left clipped.
        var savedProbe = Size.probe;
        try {
            Size.probe = () -> new Size.Window(24, 40);
            Size.reset();

            var buf = new ByteArrayOutputStream();
            // animate=true so tick paints; package ctor avoids starting the animator thread.
            var cm = new JkManager(stream(buf), true, true, 40);
            cm.height = 24;
            cm.name = "Build";
            cm.startNanos = System.nanoTime();
            cm.nerdFont = NerdFontCaps.NONE;
            cm.stepRunning("cc.jumpkick:jk-io", "run-tests", "test");
            String longName = "EffectivePomBuilderTest.concurrent_walkers_on_a_parent_cycle_fail_loudly_in_ci";
            cm.stepMessage("cc.jumpkick:jk-io", "run-tests", longName);

            cm.tick();
            assertThat(cm.width()).isEqualTo(40);
            String narrow = TestAnsi.strip(buf.toString(StandardCharsets.UTF_8));
            assertThat(narrow).contains("…");
            assertThat(narrow).doesNotContain(longName);

            // Same as the SIGWINCH handler: drop the cache; next paint pays one re-probe.
            Size.probe = () -> new Size.Window(24, 160);
            Size.reset();
            buf.reset();
            cm.tick();

            assertThat(cm.width()).isEqualTo(160);
            String wide = TestAnsi.strip(buf.toString(StandardCharsets.UTF_8));
            assertThat(wide).contains(longName);
            // Tree detail no longer needs an ellipsis at 160 columns.
            assertThat(wide.lines()
                            .filter(l -> l.contains("EffectivePomBuilderTest"))
                            .findFirst())
                    .isPresent()
                    .get()
                    .asString()
                    .doesNotContain("…");
        } finally {
            Size.probe = savedProbe;
            Size.reset();
        }
    }

    @Test
    void reflow_detection_is_env_driven_and_defaults_to_clipping() {
        // overshooting the wipe on a clipping terminal destroys completed output, so
        // unknown terminals must read as clipping.
        Map<String, String> vte = Map.of("VTE_VERSION", "7802");
        assertThat(TerminalReflow.detect(vte::get)).isTrue();
        assertThat(TerminalReflow.detect(Map.of("TERM_PROGRAM", "WezTerm")::get))
                .isTrue();
        assertThat(TerminalReflow.detect(Map.of("WT_SESSION", "x")::get)).isTrue();
        assertThat(TerminalReflow.detect(Map.of("TERM", "xterm-kitty")::get)).isTrue();
        assertThat(TerminalReflow.detect(Map.of("TERM", "xterm-256color")::get)).isFalse();
        assertThat(TerminalReflow.detect(Map.of("TERM", "screen")::get)).isFalse();
        assertThat(TerminalReflow.detect(k -> null)).isFalse();
    }

    @Test
    void shrink_wipe_climbs_only_the_logical_rows_on_clipping_terminals() {
        // on a clipping terminal the wipe must be exactly lastLines.size() rows —
        // the reflow estimate overshoots into (and erases) completed output above the region.
        var savedProbe = Size.probe;
        try {
            Size.probe = () -> new Size.Window(24, 80);
            Size.reset();
            var buf = new ByteArrayOutputStream();
            var cm = new JkManager(stream(buf), true, true, 80);
            cm.height = 24;
            cm.name = "Build";
            cm.startNanos = System.nanoTime();
            cm.nerdFont = NerdFontCaps.NONE;
            cm.stepRunning("cc.jumpkick:jk-io", "run-tests", "test");
            cm.stepMessage("cc.jumpkick:jk-io", "run-tests", "SomeVeryLongTestClassName.and_a_member_name_that_pads");
            cm.tick();
            int drawn = cm.view.renderBuildPlanLines(80, 0).size();

            Size.probe = () -> new Size.Window(24, 40);
            Size.reset();
            buf.reset();

            TerminalReflow.force(false); // clipping terminal
            cm.tick();
            String clipped = buf.toString(StandardCharsets.UTF_8);
            assertThat(clipped).contains(Ansi.cursorUp(drawn));

            // Reflowing terminal: same shrink climbs the (larger) estimated physical height.
            Size.probe = () -> new Size.Window(24, 80);
            Size.reset();
            TerminalReflow.force(true);
            cm.tick(); // repaint at 80 again
            Size.probe = () -> new Size.Window(24, 40);
            Size.reset();
            List<String> last = cm.view.renderBuildPlanLines(80, 0);
            int estimate = Math.max(JkManagerView.physicalRowsAfterReflow(last, 80, 40), last.size());
            buf.reset();
            cm.tick();
            assertThat(buf.toString(StandardCharsets.UTF_8)).contains(Ansi.cursorUp(estimate));
        } finally {
            TerminalReflow.force(null);
            Size.probe = savedProbe;
            Size.reset();
        }
    }

    @Test
    void write_above_after_a_shrink_wipes_with_post_resize_geometry() {
        // writeAbove used pre-resize linesDrawn for its erase; the reflow-aware sync
        // must run first so no orphan rows survive above the emitted line.
        var savedProbe = Size.probe;
        try {
            Size.probe = () -> new Size.Window(24, 80);
            Size.reset();
            var buf = new ByteArrayOutputStream();
            var cm = new JkManager(stream(buf), true, true, 80);
            cm.height = 24;
            cm.name = "Build";
            cm.startNanos = System.nanoTime();
            cm.nerdFont = NerdFontCaps.NONE;
            cm.stepRunning("cc.jumpkick:jk-io", "run-tests", "test");
            cm.stepMessage("cc.jumpkick:jk-io", "run-tests", "SomeVeryLongTestClassName.and_a_member_name_that_pads");
            cm.tick();
            List<String> last = cm.view.renderBuildPlanLines(80, 0);

            Size.probe = () -> new Size.Window(24, 40);
            Size.reset();
            TerminalReflow.force(true);
            int estimate = Math.max(JkManagerView.physicalRowsAfterReflow(last, 80, 40), last.size());
            buf.reset();
            // Hidden write only buffers; open the pane so paint runs under the new column budget.
            cm.view.writeAbove("WARN something happened");
            assertThat(cm.outputWindow().size()).isEqualTo(1);
            cm.showProcessFailureOutput();
            String out = buf.toString(StandardCharsets.UTF_8);
            // Region repainted under the new width with the pane line included.
            assertThat(TestAnsi.strip(out)).contains("WARN something happened");
            assertThat(cm.width()).isEqualTo(40);
            assertThat(estimate).isGreaterThan(0); // reflow estimate still computed above
        } finally {
            TerminalReflow.force(null);
            Size.probe = savedProbe;
            Size.reset();
        }
    }

    @Test
    void renderBuildPlanLines_uses_its_cols_argument_not_a_second_terminal_read() {
        // One width sample per frame: a SIGWINCH landing between paintBuildPlan's size sync and
        // the tree render must not leak a second Size read into row content while the
        // truncation budget, paintedCols, and the reflow-wipe estimate still use the sample.
        var savedProbe = Size.probe;
        try {
            var buf = new ByteArrayOutputStream();
            var cm = new JkManager(stream(buf), true, true, 120);
            cm.height = 24;
            cm.name = "Build";
            cm.startNanos = System.nanoTime();
            cm.nerdFont = NerdFontCaps.NONE;
            cm.stepRunning("cc.jumpkick:jk-cli", "compile", "compile");
            cm.stepMessage("cc.jumpkick:jk-cli", "compile", "compiling 42 sources");

            Size.probe = () -> new Size.Window(24, 200);
            Size.reset();
            List<String> sampled = cm.renderBuildPlanLines(120, 4_000);
            Size.probe = () -> new Size.Window(24, 30);
            Size.reset();
            assertThat(cm.renderBuildPlanLines(120, 4_000)).isEqualTo(sampled);
        } finally {
            Size.probe = savedProbe;
            Size.reset();
        }
    }

    @Test
    void physical_rows_after_reflow_grows_when_columns_shrink() {
        // A line painted ~79 cols wide reflows to 2 physical rows at 40 cols.
        String wide = "x".repeat(79);
        assertThat(JkManagerView.physicalRowsAfterReflow(List.of(wide), 80, 40)).isEqualTo(2);
        assertThat(JkManagerView.physicalRowsAfterReflow(List.of(wide, wide), 80, 40))
                .isEqualTo(4);
        // Widen / same width: still one physical row per logical line.
        assertThat(JkManagerView.physicalRowsAfterReflow(List.of(wide), 40, 80)).isEqualTo(1);
        assertThat(JkManagerView.physicalRowsAfterReflow(List.of(""), 80, 40)).isEqualTo(1);
        assertThat(JkManagerView.physicalRowsAfterReflow(List.of(), 80, 40)).isZero();
    }

    @Test
    void paint_on_column_shrink_wipes_reflowed_physical_rows_before_repaint() {
        // Shrink reflows long painted lines onto extra physical rows. cursorUp(logical) then
        // undershoots and the next header stacks under the orphan. Wipe must cursor-up by the
        // reflow estimate (≥ logical) and erase before painting the narrower region.
        var savedProbe = Size.probe;
        try {
            Size.probe = () -> new Size.Window(24, 120);
            Size.reset();

            var buf = new ByteArrayOutputStream();
            var cm = new JkManager(stream(buf), true, true, 120);
            cm.height = 24;
            cm.name = "Build";
            cm.startNanos = System.nanoTime();
            cm.nerdFont = NerdFontCaps.NONE;
            cm.stepRunning("cc.jumpkick:jk-cli", "native-image", "native");
            cm.stepMessage("cc.jumpkick:jk-cli", "native-image", "[5/8] Inlining methods...");
            cm.tick();

            List<String> painted = cm.lastLines;
            assertThat(painted).isNotEmpty();
            int expectedUp = Math.max(JkManagerView.physicalRowsAfterReflow(painted, 120, 50), painted.size());

            Size.probe = () -> new Size.Window(24, 50);
            Size.reset();
            buf.reset();
            cm.tick();

            assertThat(cm.width()).isEqualTo(50);
            String raw = buf.toString(StandardCharsets.UTF_8);
            // Wipe path: cursor-up by physical reflow rows, then erase-display-to-end, then paint.
            assertThat(raw).contains(Ansi.cursorUp(expectedUp));
            assertThat(raw).contains("\r" + Ansi.ERASE_DISPLAY_TO_END);
            // Only one Build header in the post-shrink frame (not a stacked orphan + new paint).
            String visible = TestAnsi.strip(raw);
            long buildHeaders = visible.lines().filter(l -> l.contains("Build")).count();
            assertThat(buildHeaders).isEqualTo(1);
        } finally {
            Size.probe = savedProbe;
            Size.reset();
        }
    }
}
