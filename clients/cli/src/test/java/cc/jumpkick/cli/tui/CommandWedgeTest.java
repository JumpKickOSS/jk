// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.testing.MainSources;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.NerdFontCaps;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class CommandWedgeTest {

    @Test
    void plain_mode_chip_line_shape() {
        // When Theme is non-ANSI (CI / NO_COLOR), BuildPlanWedge uses ASCII prefixes.
        // Force the plain branch via the public chipLine path used by CommandWedge:
        String ok = JkWedge.chipLine(Glyphs.CHECK, "Build", NerdFontCaps.NONE, "done");
        String fail = JkWedge.failureLineCustom("Build", NerdFontCaps.NONE, "boom");
        // Under CI (this suite), isAnsi is typically false → plain
        if (ok.startsWith("jk: +") || ok.startsWith(" +") || ok.startsWith("+")) {
            assertThat(ok).isEqualTo("jk: + Build > done");
            assertThat(fail).isEqualTo("jk: ! Build > boom");
        } else {
            // ANSI-enabled developer machine: still must carry command + message
            assertThat(ok).contains("Build").contains("done");
            assertThat(fail).contains("Build").contains("boom");
        }
    }

    @Test
    void nerd_cap_only_when_nerdfont_flag() {
        // With ANSI on: the wedge axis uses U+E0B0; without it, two trailing chip spaces (no PUA).
        String nerd = JkWedge.chipLine(Glyphs.CHECK, "Clean", NerdFontCaps.ALL, "ok");
        String ansi = JkWedge.chipLine(Glyphs.CHECK, "Clean", NerdFontCaps.NONE, "ok");
        if (!nerd.contains(" > ")) {
            assertThat(nerd).contains(Glyphs.SEGMENT_END_NERD);
            assertThat(ansi).doesNotContain(Glyphs.SEGMENT_END_NERD);
            // Non-nerd chip ends with two spaces on the success chip bg (before the message).
            String body = Theme.colorize(
                    " " + Glyphs.CHECK + " Clean  ", Theme.active().planSuccessChip());
            assertThat(ansi).contains(body);
        }
        assertThat(nerd).contains("Clean").contains("ok");
        assertThat(ansi).contains("Clean").contains("ok");
    }

    @Test
    void command_wedge_delegates() {
        assertThat(CommandWedge.ok("X", "y", NerdFontCaps.NONE)).contains("X").contains("y");
        assertThat(CommandWedge.fail("X", "y", NerdFontCaps.NONE)).contains("X").contains("y");
        assertThat(CommandWedge.working("X", "y")).contains("X").contains("y");
    }

    @Test
    void analyzing_stdout_is_suppressed_when_stdout_is_machine_consumed() {
        // JK-2330: `jk bsp serve` hands stdout to BspServer as the JSON-RPC frame channel, and
        // `jk explain --graph` writes graph source there. A spinner would put cursor ANSI in both.
        var out = new ByteArrayOutputStream();
        var prev = System.out;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            CliOutput.beginCommand(true);
            assertThat(CommandWedge.analyzingStdout("BSP", "Locking…")).isNull();
        } finally {
            System.setOut(prev);
        }
        assertThat(out.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(CliOutput.envelopeStarted()).isFalse();
        CliOutput.beginCommand(false);
    }

    @Test
    void no_source_animates_straight_onto_cli_output_stdout() {
        // The interactivity gate callers rely on asks whether stdout is a *terminal*, which a
        // pty-allocating IDE or CI runner answers yes to while still parsing every byte. Only
        // analyzingStdout consults scriptMode, so routing around it reopens JK-2330.
        Optional<Path> mainOpt = MainSources.locate();
        assumeTrue(mainOpt.isPresent(), "cli main sources not adjacent to test classpath — skip scan");
        Path main = mainOpt.get();
        Pattern anti = Pattern.compile("analyzing\\(\\s*CliOutput\\.stdout\\(\\)");
        Path wedge = main.resolve("cc/jumpkick/cli/tui/CommandWedge.java");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(main)) {
            walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.equals(wedge))
                    .forEach(p -> {
                        try {
                            if (anti.matcher(Files.readString(p)).find()) {
                                offenders.add(main.relativize(p).toString());
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        assertThat(offenders)
                .as("call CommandWedge.analyzingStdout(command, message) instead")
                .isEmpty();
    }

    @Test
    void envelope_start_is_idempotent_until_reset() {
        CliOutput.beginCommand(false);
        assertThat(CliOutput.envelopeStarted()).isFalse();
        CommandWedge.envelopeStart();
        assertThat(CliOutput.envelopeStarted()).isTrue();
        CommandWedge.envelopeStart(); // no second blank side effect on flag
        assertThat(CliOutput.envelopeStarted()).isTrue();
        CliOutput.beginCommand(false);
        assertThat(CliOutput.envelopeStarted()).isFalse();
    }

    @Test
    void printFail_opens_stderr_envelope_once() {
        CliOutput.beginCommand(false);
        var err = new ByteArrayOutputStream();
        var prev = System.err;
        try {
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            CommandWedge.printFail("Build", "boom");
            CommandWedge.printFail("Build", "again");
        } finally {
            System.setErr(prev);
        }
        String out = err.toString(StandardCharsets.UTF_8);
        assertThat(out).startsWith("\n");
        assertThat(out).doesNotStartWith("\n\n");
        assertThat(out).contains("Build").contains("boom").contains("again");
        // Exactly one leading blank for the command (second printFail does not re-blank).
        assertThat(out.indexOf('\n', 1)).isGreaterThan(0);
        long leadingBlanks = 0;
        for (int i = 0; i < out.length() && out.charAt(i) == '\n'; i++) leadingBlanks++;
        assertThat(leadingBlanks).isEqualTo(1);
    }

    @Test
    void printOk_opens_stdout_envelope_once() {
        CliOutput.beginCommand(false);
        var buf = new ByteArrayOutputStream();
        var prev = System.out;
        try {
            System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
            CommandWedge.printOk("Add", "ok");
            CommandWedge.printOk("Add", "again");
        } finally {
            System.setOut(prev);
        }
        String out = buf.toString(StandardCharsets.UTF_8);
        assertThat(out).startsWith("\n");
        assertThat(out).doesNotStartWith("\n\n");
        assertThat(out).contains("Add").contains("ok");
    }

    @Test
    void printLine_opens_stdout_envelope_once() {
        CliOutput.beginCommand(false);
        var buf = new ByteArrayOutputStream();
        var prev = System.out;
        try {
            System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
            CommandWedge.printLine("wedge-one");
            CommandWedge.printLine("wedge-two");
        } finally {
            System.setOut(prev);
        }
        String out = buf.toString(StandardCharsets.UTF_8);
        assertThat(out).startsWith("\n");
        assertThat(out).doesNotStartWith("\n\n");
        assertThat(out).contains("wedge-one").contains("wedge-two");
        long leadingBlanks = 0;
        for (int i = 0; i < out.length() && out.charAt(i) == '\n'; i++) leadingBlanks++;
        assertThat(leadingBlanks).isEqualTo(1);
    }

    @Test
    void analyzing_returns_live_wedge_spinner() {
        var buf = new ByteArrayOutputStream();
        try (Spinner s = CommandWedge.analyzing(
                new PrintStream(buf, true, StandardCharsets.UTF_8), "Status", "Analyzing status...")) {
            assertThat(s).isNotNull();
        }
        // Closed without throwing; silent under --no-progress is fine.
    }

    @Test
    void cancelled_job_line_remote_vs_by_user() {
        String remote = JkWedge.cancelledJobLine("Build", NerdFontCaps.NONE, false, "took 1.6s")
                .replaceAll("\u001B\\[[0-9;]*m", "");
        // The chip names the plan; the body must not repeat it ("Build Build job…",.
        assertThat(remote).contains("Build").contains("job was cancelled");
        assertThat(remote).containsOnlyOnce("Build");
        assertThat(remote).contains("took 1.6s");
        assertThat(remote).doesNotContain("by user");

        String local = JkWedge.cancelledJobLine("Build", NerdFontCaps.NONE, true, "took 1.6s")
                .replaceAll("\u001B\\[[0-9;]*m", "");
        assertThat(local).contains("job was cancelled by user");
        assertThat(local).containsOnlyOnce("Build");
        assertThat(local).contains("took 1.6s");
    }

    @Test
    void cancelled_job_line_uses_explain_pill_gray_and_circled_asterisk_not_the_red_fail_chip() {
        String raw = JkWedge.cancelledJobLine("Build", NerdFontCaps.ALL, true, "took 1.2s");
        String visible = raw.replaceAll("\u001B\\[[0-9;]*m", "");
        assertThat(visible).contains("Build").contains("job was cancelled by user");
        assertThat(visible).doesNotContain(Glyphs.CROSS);
        assertThat(visible).doesNotContain(Glyphs.BANG);
        Theme t = Theme.active();
        if (t.isAnsi()) {
            assertThat(visible).contains(Glyphs.CANCELLED);
            assertThat(raw).contains(Theme.colorize("cancelled", t.brightWhite().bold()));
            assertThat(raw).contains(Theme.colorize(" " + Glyphs.CANCELLED + " Build ", t.scopeBadge()));
        } else {
            assertThat(visible).contains(Glyphs.CANCELLED_PLAIN);
        }
    }
}
