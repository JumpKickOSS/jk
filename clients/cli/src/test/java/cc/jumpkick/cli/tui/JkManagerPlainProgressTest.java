// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static cc.jumpkick.cli.tui.JkManagerTestSupport.stream;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.NerdFontCaps;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Window-title OSC handling and plain (no-ANSI) progress output of the JkManager component. */
class JkManagerPlainProgressTest {

    @Test
    void setWindowTitle_emits_osc0_and_clears_on_settle() {
        var buf = new ByteArrayOutputStream();
        var cm = JkManager.plan(stream(buf), "Build", true);
        cm.setWindowTitle("JumpKick - Building cc.jumpkick:jk:0.12.0...");
        String set = buf.toString(StandardCharsets.UTF_8);
        // OSC 0: fill-circle glyph + base, terminated with ST (ESC \), not BEL.
        String expected = "\033]0;" + Spinner.fillGlyph(0) + " JumpKick - Building cc.jumpkick:jk:0.12.0...\033\\";
        assertThat(set).contains(expected);
        buf.reset();
        cm.finishBuildPlanSuccess("ok", List.of());
        String cleared = buf.toString(StandardCharsets.UTF_8);
        assertThat(cleared).contains("\033]0;\033\\");
    }

    @Test
    void window_title_never_reaches_non_animated_output() {
        // Piped / CI / --quiet builds (animate=false) must stay byte-clean of OSC — the
        // escapes would land verbatim in the redirected stream.
        var buf = new ByteArrayOutputStream();
        var cm = JkManager.plan(stream(buf), "Build", false);
        cm.setWindowTitle("JumpKick - Building g:a:v...");
        cm.addTask("g:a", "compile-main");
        cm.stepDone("g:a", "compile-main", true);
        cm.tick();
        cm.finishBuildPlanSuccess("ok", List.of());
        assertThat(buf.toString(StandardCharsets.UTF_8)).doesNotContain("\033]0;");
    }

    @Test
    void window_title_suppressed_in_no_ansi_mode() {
        // --no-ansi on a real TTY: still animated, but ANSI sequences are promised away.
        var noAnsi = cc.jumpkick.config.JkConfig.empty().withNoAnsi(Optional.of(true));
        cc.jumpkick.config.SessionContext.runWhere(
                cc.jumpkick.config.Session.defaults().withConfig(noAnsi), () -> {
                    var buf = new ByteArrayOutputStream();
                    var cm = JkManager.plan(stream(buf), "Build", true);
                    cm.setWindowTitle("JumpKick - Building g:a:v...");
                    cm.finishBuildPlanSuccess("ok", List.of());
                    assertThat(buf.toString(StandardCharsets.UTF_8)).doesNotContain("\033]0;");
                });
    }

    @Test
    void plain_progress_emits_stage_changes_not_percent_ticks() {
        var noAnsi = cc.jumpkick.config.JkConfig.empty().withNoAnsi(Optional.of(true));
        cc.jumpkick.config.SessionContext.runWhere(
                cc.jumpkick.config.Session.defaults().withConfig(noAnsi), () -> {
                    var buf = new ByteArrayOutputStream();
                    var cm = JkManager.plan(stream(buf), "Format", true);
                    cm.addTaskLabeled("", "fmt", "Examining source files");
                    cm.stepRunning("", "fmt");
                    cm.progress(0, 100);
                    cm.progress(15, 100);
                    cm.progress(25, 100); // percent ticks must not reprint the same stage
                    cm.progress(100, 100);
                    cm.finishBuildPlanSuccess("Already formatted - took 547ms", List.of());
                    String out = buf.toString(StandardCharsets.UTF_8);
                    assertThat(out).doesNotContain("\u001B[");
                    assertThat(out).doesNotContain(Spinner.PULSE_GLYPH);
                    assertThat(out).contains("jk: * Format > initializing...");
                    assertThat(out).contains("jk: * Format > Examining source files :: 0% - prepare");
                    assertThat(out).doesNotContain("jk: * Format > Examining source files :: 10% - prepare");
                    assertThat(out).doesNotContain("jk: * Format > Examining source files :: 20% - prepare");
                    assertThat(out).doesNotContain("jk: * Format > Examining source files :: 40% - prepare");
                    assertThat(out).contains("jk: * Format > 100% - done");
                    assertThat(out).contains("jk: + Format > Already formatted - took 547ms");
                    // No mid-run 100% working line — 100% is only the done line.
                    assertThat(out).doesNotContain("100% - prepare");
                    assertThat(out.split("Examining source files :: ", -1).length - 1)
                            .isEqualTo(1);
                });
    }

    @Test
    void plain_progress_line_helper_shape() {
        assertThat(JkManager.plainProgressLine("Format", "Examining source files", 0, false))
                .isEqualTo("jk: * Format > Examining source files :: 0% - prepare");
        assertThat(JkManager.plainProgressLine("Format", "Examining source files", 100, true))
                .isEqualTo("jk: * Format > Examining source files :: 100% - done");
        assertThat(JkManager.plainIndeterminateLine("Format", "Examining source files", false))
                .isEqualTo("jk: * Format > Examining source files");
    }

    @Test
    void plain_progress_announces_eta_as_soon_as_it_is_known() {
        var noAnsi = cc.jumpkick.config.JkConfig.empty().withNoAnsi(Optional.of(true));
        cc.jumpkick.config.SessionContext.runWhere(
                cc.jumpkick.config.Session.defaults().withConfig(noAnsi), () -> {
                    var buf = new ByteArrayOutputStream();
                    var cm = JkManager.plan(stream(buf), "Build", true);
                    cm.setPlanCoord("cc.jumpkick:jk");
                    cm.setRemainingWorkEstimate(91_000);
                    String out = buf.toString(StandardCharsets.UTF_8);
                    assertThat(out).contains("jk: * Build > initializing...");
                    assertThat(out).contains("jk: * Build > cc.jumpkick:jk :: 0% - prepare");
                    // The countdown is wall-clock anchored: the seconds digit can slip while a
                    // loaded suite JVM gets from plan() to the render, so pin the announce (ETA
                    // present at 0%, before any module work), not the exact second.
                    assertThat(out)
                            .containsPattern("jk: \\* Build > cc\\.jumpkick:jk :: 0% \\(ETA ~1m \\d{1,2}s\\) - start");
                });
    }

    @Test
    void plain_progress_emits_phase_and_built_immediately() {
        var noAnsi = cc.jumpkick.config.JkConfig.empty().withNoAnsi(Optional.of(true));
        cc.jumpkick.config.SessionContext.runWhere(
                cc.jumpkick.config.Session.defaults().withConfig(noAnsi), () -> {
                    var buf = new ByteArrayOutputStream();
                    var cm = JkManager.plan(stream(buf), "Build", true);
                    cm.setPlanCoord("cc.jumpkick:jk");
                    cm.progress(0, 100);
                    cm.addTask("cc.jumpkick:jk-engine", "compile-java");
                    cm.stepRunning("cc.jumpkick:jk-engine", "compile-java", "compile");
                    cm.stepMessage("cc.jumpkick:jk-engine", "compile-java", "compiling 12 sources");
                    cm.progress(5, 100);
                    cm.addTask("cc.jumpkick:jk-engine", "run-tests");
                    cm.stepRunning("cc.jumpkick:jk-engine", "run-tests", "test");
                    cm.stepMessage("cc.jumpkick:jk-engine", "run-tests", "running 80 tests");
                    cm.notePlainTestTick("cc.jumpkick:jk-engine", "run-tests", 30);
                    cm.progress(9, 100);
                    cm.progress(23, 100);
                    cm.finishModule("cc.jumpkick:jk-engine", true);
                    cm.finishBuildPlanSuccess("Build successful, built 1 module - took 1s", List.of());
                    String out = buf.toString(StandardCharsets.UTF_8);
                    assertThat(out).contains("jk: * Build > initializing...");
                    assertThat(out).contains("jk: * Build > cc.jumpkick:jk :: 0% - prepare");
                    assertThat(out).contains("- compiling 12 sources");
                    assertThat(out).contains("- running 80 tests");
                    // Mid-stage test countdown ticks must not reprint.
                    assertThat(out).doesNotContain("running 50 tests");
                    assertThat(out).doesNotContain(" :: 20% - ");
                    assertThat(out).doesNotContain(" :: 40% - ");
                    assertThat(out).contains("cc.jumpkick:jk-engine :: 23% - built");
                    assertThat(out).contains("jk: * Build > cc.jumpkick:jk :: 100% - done");
                    assertThat(out).contains("jk: + Build > Build successful, built 1 module - took 1s");
                    assertThat(out).doesNotContain("Parsing");
                    assertThat(out).doesNotContain("- work");
                    assertThat(out.split("- compiling 12 sources", -1).length - 1)
                            .isEqualTo(1);
                    assertThat(out.split("- running 80 tests", -1).length - 1).isEqualTo(1);
                });
    }

    @Test
    void plain_progress_heartbeats_long_running_stages_every_30s() {
        var noAnsi = cc.jumpkick.config.JkConfig.empty().withNoAnsi(Optional.of(true));
        cc.jumpkick.config.SessionContext.runWhere(
                cc.jumpkick.config.Session.defaults().withConfig(noAnsi), () -> {
                    var buf = new ByteArrayOutputStream();
                    var cm = JkManager.plan(stream(buf), "Build", true);
                    try {
                        cm.setPlanCoord("cc.jumpkick:jk");
                        cm.setRemainingWorkEstimate(90_000);
                        cm.progress(0, 100);
                        cm.addTask("cc.jumpkick:jk-cli", "run-tests");
                        cm.stepRunning("cc.jumpkick:jk-cli", "run-tests", "test");
                        cm.stepMessage("cc.jumpkick:jk-cli", "run-tests", "running 860 tests");
                        cm.progress(9, 100);
                        String before = buf.toString(StandardCharsets.UTF_8);
                        assertThat(before).contains("- running 860 tests");
                        assertThat(before).doesNotContain("running 759 tests");

                        // Under 30s: still silent even with a fresher remaining count.
                        cm.notePlainTestTick("cc.jumpkick:jk-cli", "run-tests", 101);
                        cm.maybeEmitPlainHeartbeat();
                        assertThat(buf.toString(StandardCharsets.UTF_8)).doesNotContain("running 759 tests");

                        // At/after 30s: reprint with live details (percent may be clock-based).
                        cm.plainLastPrintedNanos = System.nanoTime() - JkManager.PLAIN_HEARTBEAT_MS * 1_000_000L - 1;
                        cm.maybeEmitPlainHeartbeat();
                        String out = buf.toString(StandardCharsets.UTF_8);
                        assertThat(out).contains("cc.jumpkick:jk-cli ::");
                        assertThat(out).contains("- running 759 tests");
                        assertThat(out.split("- running 860 tests", -1).length - 1)
                                .isEqualTo(1);
                        assertThat(out.split("- running 759 tests", -1).length - 1)
                                .isEqualTo(1);
                    } finally {
                        cm.finishBuildPlanSuccess("Build successful, built 1 module - took 1s", List.of());
                    }
                });
    }

    @Test
    void plain_native_detail_is_verbose_only() {
        var noAnsi = cc.jumpkick.config.JkConfig.empty().withNoAnsi(Optional.of(true));
        cc.jumpkick.config.SessionContext.runWhere(
                cc.jumpkick.config.Session.defaults().withConfig(noAnsi), () -> {
                    var buf = new ByteArrayOutputStream();
                    var cm = JkManager.plan(stream(buf), "Build", true);
                    cm.stepRunning("cc.jumpkick:jk-cli", "native-image", "native");
                    cm.progress(60, 100);
                    cm.stepMessage("cc.jumpkick:jk-cli", "native-image", "jk · classpath input size: ~3.8 MiB");
                    String quiet = buf.toString(StandardCharsets.UTF_8);
                    assertThat(quiet).contains("- native compiling");
                    assertThat(quiet).doesNotContain("classpath input size");
                });
        var verbose = cc.jumpkick.config.JkConfig.empty()
                .withNoAnsi(Optional.of(true))
                .withVerbose(Optional.of(true));
        cc.jumpkick.config.SessionContext.runWhere(
                cc.jumpkick.config.Session.defaults().withConfig(verbose), () -> {
                    var buf = new ByteArrayOutputStream();
                    var cm = JkManager.plan(stream(buf), "Build", true);
                    cm.stepRunning("cc.jumpkick:jk-cli", "native-image", "native");
                    cm.progress(61, 100);
                    cm.stepMessage("cc.jumpkick:jk-cli", "native-image", "jk · classpath input size: ~3.8 MiB");
                    String out = buf.toString(StandardCharsets.UTF_8);
                    assertThat(out).contains("- native compiling = jk - classpath input size: ~3.8 MiB");
                });
    }

    @Test
    void plain_process_output_is_suppressed_unless_verbose() {
        var noAnsi = cc.jumpkick.config.JkConfig.empty().withNoAnsi(Optional.of(true));
        cc.jumpkick.config.SessionContext.runWhere(
                cc.jumpkick.config.Session.defaults().withConfig(noAnsi), () -> {
                    var buf = new ByteArrayOutputStream();
                    var cm = JkManager.plan(stream(buf), "Build", true);
                    cm.writeProcessOutput("javac: compiling Foo.java");
                    cm.writeAbove("compiler error: Foo.java:1: error");
                    String out = buf.toString(StandardCharsets.UTF_8);
                    assertThat(out).doesNotContain("javac: compiling Foo.java");
                    assertThat(out).contains("compiler error: Foo.java:1: error");
                });
    }

    @Test
    void plain_process_output_surfaces_on_step_failure() {
        // JK-2163: plain mode has no Ctrl-O and no settle dump — a tool crash must dump the
        // buffered ring, or its only evidence stays invisible.
        var noAnsi = cc.jumpkick.config.JkConfig.empty().withNoAnsi(Optional.of(true));
        cc.jumpkick.config.SessionContext.runWhere(
                cc.jumpkick.config.Session.defaults().withConfig(noAnsi), () -> {
                    var buf = new ByteArrayOutputStream();
                    var cm = JkManager.plan(stream(buf), "Build", true);
                    cm.writeProcessOutput("native-image: Error: Classes that should be initialized");
                    assertThat(buf.toString(StandardCharsets.UTF_8)).doesNotContain("native-image: Error");
                    cm.showProcessFailureOutput();
                    String out = buf.toString(StandardCharsets.UTF_8);
                    assertThat(out).contains("native-image: Error: Classes that should be initialized");
                    // A second failure in the same plan must not duplicate the dump.
                    cm.showProcessFailureOutput();
                    String again = buf.toString(StandardCharsets.UTF_8);
                    assertThat(again.indexOf("native-image: Error"))
                            .isEqualTo(again.lastIndexOf("native-image: Error"));
                });
    }

    @Test
    void window_title_updates_only_when_fill_glyph_changes() {
        var buf = new ByteArrayOutputStream();
        var cm = new JkManager(stream(buf), true, true, 80);
        cm.setWindowTitle("JumpKick - Building g:a:v...");
        buf.reset();
        // setWindowTitle left frame=0 with ○ emitted. FILL_HOLD ticks use frames 0..HOLD-1
        // (same glyph) then leave frame=HOLD; none of those rewrite the title.
        for (int i = 0; i < Spinner.FILL_HOLD; i++) {
            cm.tick();
        }
        assertThat(buf.toString(StandardCharsets.UTF_8)).doesNotContain("\033]0;");
        // This tick paints with frame=HOLD (next phase) → one OSC update.
        cm.tick();
        String out = buf.toString(StandardCharsets.UTF_8);
        String nextGlyph = Spinner.fillGlyph(Spinner.FILL_HOLD);
        assertThat(nextGlyph).isNotEqualTo(Spinner.fillGlyph(0));
        assertThat(out).contains("\033]0;" + nextGlyph + " JumpKick - Building g:a:v...\033\\");
        // Only one OSC 0 in this window (the phase advance).
        assertThat(out.split("\033]0;", -1).length - 1).isEqualTo(1);
        cm.finishBuildPlanSuccess("ok", List.of());
    }

    @Test
    void header_countdown_stays_mid_gray_and_elapsed_stays_dim() {
        Theme t = Theme.active();
        // Seeded ETA with remaining > 0 → dim italic "ETA " + mid-gray "~remaining" · dim elapsed.
        var down = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        down.nerdFont = NerdFontCaps.NONE;
        down.progress(10, 100);
        down.setEtaEstimate(60_000);
        String downHeader = down.renderBuildPlanLines(120, 4_000).get(0);
        assertThat(TestAnsi.strip(downHeader)).contains("ETA ~56s");
        assertThat(TestAnsi.strip(downHeader)).contains("· 4s");
        assertThat(downHeader).contains(Theme.colorize("ETA ", t.darkGray().italic()));
        assertThat(downHeader).contains(Theme.colorize("~56s", t.midGray()));
        assertThat(downHeader).contains(Theme.colorize("4s", t.darkGray()));
        assertThat(downHeader).doesNotContain(Theme.colorize("+4s", t.darkGray()));
        assertThat(downHeader).doesNotContain(Theme.colorize("4s", t.warning()));

        // No seed → elapsed count-up (mid-gray, same as countdown), no ETA prefix, no plus.
        var up = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        up.nerdFont = NerdFontCaps.NONE;
        up.progress(10, 100);
        String upHeader = up.renderBuildPlanLines(120, 12_000).get(0);
        assertThat(TestAnsi.strip(upHeader)).contains("12s");
        assertThat(TestAnsi.strip(upHeader)).doesNotContain("+12s");
        assertThat(TestAnsi.strip(upHeader)).doesNotContain("ETA ");
        assertThat(upHeader).contains(Theme.colorize("12s", t.midGray()));
        assertThat(upHeader).doesNotContain(Theme.colorize("12s", t.warning()));

        // At the deadline: mid-gray 0s, still-dim elapsed.
        var atZero = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        atZero.nerdFont = NerdFontCaps.NONE;
        atZero.progress(90, 100);
        atZero.setEtaEstimate(10_000);
        String zeroHeader = atZero.renderBuildPlanLines(120, 10_000).get(0);
        assertThat(TestAnsi.strip(zeroHeader)).contains("ETA 0s").contains("· 10s");
        assertThat(zeroHeader).contains(Theme.colorize("0s", t.midGray()));
        assertThat(zeroHeader).contains(Theme.colorize("10s", t.darkGray()));

        // Past deadline → mid-gray +overrun on the countdown; elapsed stays dim, no plus.
        var over = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        over.nerdFont = NerdFontCaps.NONE;
        over.progress(90, 100);
        over.setEtaEstimate(10_000);
        String overHeader = over.renderBuildPlanLines(120, 15_000).get(0);
        assertThat(TestAnsi.strip(overHeader)).contains("ETA +5s");
        assertThat(TestAnsi.strip(overHeader)).contains("· 15s");
        assertThat(overHeader).contains(Theme.colorize("ETA ", t.darkGray().italic()));
        assertThat(overHeader).contains(Theme.colorize("+5s", t.midGray()));
        assertThat(overHeader).contains(Theme.colorize("15s", t.darkGray()));
        assertThat(overHeader).doesNotContain(Theme.colorize("15s", t.midGray()));
        assertThat(overHeader).doesNotContain(Theme.colorize("+5s", t.warning()));
    }
}
