// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.cli.testing.NoAnsi;
import cc.jumpkick.terminal.Ansi;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class JdkDownloadBarTest {

    @Test
    void show_prints_leading_envelope_blank() throws Exception {
        NoAnsi.noProgress(() -> {
            CliOutput.beginCommand(false);
            var buf = new ByteArrayOutputStream();
            var ps = new PrintStream(buf, true, StandardCharsets.UTF_8);
            try (JdkDownloadBar bar = JdkDownloadBar.show(ps, "Eclipse Temurin 25")) {
                assertThat(CliOutput.envelopeStarted()).isTrue();
            }
            String out = Capture.lf(buf.toString(StandardCharsets.UTF_8));
            assertThat(out).startsWith("\n");
            assertThat(out).doesNotStartWith("\n\n");
            return null;
        });
    }

    @Test
    void showInstalling_reuses_envelope_without_second_blank() throws Exception {
        NoAnsi.noProgress(() -> {
            CliOutput.beginCommand(false);
            var buf = new ByteArrayOutputStream();
            var ps = new PrintStream(buf, true, StandardCharsets.UTF_8);
            try (JdkDownloadBar download = JdkDownloadBar.show(ps, "Eclipse Temurin 25")) {
                download.finish();
            }
            try (JdkDownloadBar installing = JdkDownloadBar.showInstalling(ps, "Eclipse Temurin 25")) {
                assertThat(CliOutput.envelopeStarted()).isTrue();
            }
            String out = Capture.lf(buf.toString(StandardCharsets.UTF_8));
            assertThat(out).startsWith("\n");
            assertThat(out).doesNotStartWith("\n\n");
            return null;
        });
    }

    @Test
    void no_progress_emits_no_cursor_no_osc_and_no_frames() throws Exception {
        // : --no-progress and a machine-consumed stdout get the envelope blank and
        // nothing else. No cursor games (a killed CLI would leave the terminal without one), no OSC
        // taskbar sequences, and no animated frames to be mistaken for output by whatever is reading.
        NoAnsi.noProgress(() -> {
            CliOutput.beginCommand(false);
            var buf = new ByteArrayOutputStream();
            var ps = new PrintStream(buf, true, StandardCharsets.UTF_8);
            try (JdkDownloadBar bar = JdkDownloadBar.show(ps, "Temurin 26")) {
                bar.update(1_000, 10_000);
                bar.update(9_999, 10_000);
            }
            String out = buf.toString(StandardCharsets.UTF_8);
            assertThat(out).isEqualTo("\n"); // the envelope blank, and only that
            assertThat(out).doesNotContain(Ansi.HIDE_CURSOR).doesNotContain(Ansi.SHOW_CURSOR);
            assertThat(out).doesNotContain("\u001b]9;4;"); // OSC 9;4 progress
            assertThat(out).doesNotContain("\r");
            return null;
        });
    }

    @Test
    void no_progress_cancel_still_runs_teardown_but_paints_nothing() throws Exception {
        // The reap of in-flight scratch must not be conditional on anything having been painted:
        // Ctrl-C ends in Runtime.halt, so this is the only chance to unlink it. It returns
        // false so GlobalCancel prints its own generic notice instead of a line nobody rendered.
        NoAnsi.noProgress(() -> {
            CliOutput.beginCommand(false);
            var buf = new ByteArrayOutputStream();
            var ps = new PrintStream(buf, true, StandardCharsets.UTF_8);
            JdkDownloadBar.show(ps, "Temurin 26");
            LiveRegion active = LiveRegion.active();
            assertThat(active)
                    .as("a silent region still registers, so cancel reaches it")
                    .isNotNull();

            assertThat(active.renderCanceled()).isFalse();

            assertThat(buf.toString(StandardCharsets.UTF_8)).isEqualTo("\n");
            return null;
        });
    }

    @Test
    void plain_mode_cancel_settles_on_one_line_with_no_ansi() throws Exception {
        // --no-ansi is plain, not silent: the cancel still says what happened, in ASCII chrome.
        NoAnsi.forced(() -> {
            CliOutput.beginCommand(false);
            var buf = new ByteArrayOutputStream();
            var ps = new PrintStream(buf, true, StandardCharsets.UTF_8);
            JdkDownloadBar.show(ps, "Temurin 26");
            LiveRegion.active().renderCanceled();
            String out = Capture.lf(buf.toString(StandardCharsets.UTF_8));
            assertThat(out).contains("JDK download was cancelled by user");
            assertThat(out).doesNotContain("\u001b["); // no CSI at all in plain mode
            return null;
        });
    }
}
