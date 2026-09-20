// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.cli.testing.NoAnsi;
import cc.jumpkick.terminal.Ansi;
import cc.jumpkick.testing.FakeClock;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class ProgressRowTest {

    private static ProgressRow download(PrintStream ps, String label) {
        return ProgressRow.of(ps, "JDK")
                .status("Downloading " + label)
                .cancelSubject("JDK download")
                .open();
    }

    @Test
    void open_prints_leading_envelope_blank() throws Exception {
        NoAnsi.noProgress(() -> {
            CliOutput.beginCommand(false);
            var buf = new ByteArrayOutputStream();
            var ps = new PrintStream(buf, true, StandardCharsets.UTF_8);
            try (ProgressRow row = download(ps, "Eclipse Temurin 25")) {
                assertThat(CliOutput.envelopeStarted()).isTrue();
            }
            String out = Capture.lf(buf.toString(StandardCharsets.UTF_8));
            assertThat(out).startsWith("\n");
            assertThat(out).doesNotStartWith("\n\n");
            return null;
        });
    }

    @Test
    void a_second_row_reuses_the_envelope_without_a_second_blank() throws Exception {
        NoAnsi.noProgress(() -> {
            CliOutput.beginCommand(false);
            var buf = new ByteArrayOutputStream();
            var ps = new PrintStream(buf, true, StandardCharsets.UTF_8);
            try (ProgressRow download = download(ps, "Eclipse Temurin 25")) {
                download.finish();
            }
            try (ProgressRow installing = ProgressRow.of(ps, "JDK")
                    .status("Installing Eclipse Temurin 25")
                    .open()) {
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
        // --no-progress and a machine-consumed stdout get the envelope blank and nothing else: no
        // cursor games (a killed CLI would leave the terminal without one), no OSC taskbar
        // sequences, and no animated frames to be mistaken for output by whatever is reading.
        NoAnsi.noProgress(() -> {
            CliOutput.beginCommand(false);
            var buf = new ByteArrayOutputStream();
            var ps = new PrintStream(buf, true, StandardCharsets.UTF_8);
            try (ProgressRow row = download(ps, "Temurin 26")) {
                row.update(1_000, 10_000);
                row.update(9_999, 10_000);
            }
            String out = Capture.lf(buf.toString(StandardCharsets.UTF_8));
            assertThat(out).isEqualTo("\n"); // the envelope blank, and only that
            assertThat(out).doesNotContain(Ansi.HIDE_CURSOR).doesNotContain(Ansi.SHOW_CURSOR);
            assertThat(out).doesNotContain("\u001b]9;4;"); // OSC 9;4 progress
            assertThat(out).doesNotContain("\r");
            return null;
        });
    }

    @Test
    void no_progress_cancel_still_runs_teardown_but_paints_nothing() throws Exception {
        // The teardown must not be conditional on anything having been painted: Ctrl-C ends in
        // Runtime.halt, so this is the only chance to run it. renderCanceled returns false so
        // GlobalCancel prints its own generic notice instead of a line nobody rendered.
        NoAnsi.noProgress(() -> {
            CliOutput.beginCommand(false);
            var buf = new ByteArrayOutputStream();
            var ps = new PrintStream(buf, true, StandardCharsets.UTF_8);
            boolean[] tornDown = {false};
            ProgressRow.of(ps, "JDK")
                    .status("Downloading Temurin 26")
                    .onCancel(() -> tornDown[0] = true)
                    .open();
            LiveRegion active = LiveRegion.active();
            assertThat(active)
                    .as("a silent region still registers, so cancel reaches it")
                    .isNotNull();

            assertThat(Objects.requireNonNull(active).renderCanceled()).isFalse();
            assertThat(tornDown[0]).isTrue();

            assertThat(Capture.lf(buf.toString(StandardCharsets.UTF_8))).isEqualTo("\n");
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
            download(ps, "Temurin 26");
            LiveRegion active = LiveRegion.active();
            assertThat(active).isNotNull();
            Objects.requireNonNull(active).renderCanceled();
            String out = Capture.lf(buf.toString(StandardCharsets.UTF_8));
            assertThat(out).contains("JDK download was cancelled by user");
            assertThat(out).doesNotContain("\u001b["); // no CSI at all in plain mode
            return null;
        });
    }

    @Test
    void plain_mode_says_the_status_on_open_and_the_percent_once_a_total_is_known() throws Exception {
        NoAnsi.forced(() -> {
            CliOutput.beginCommand(false);
            var buf = new ByteArrayOutputStream();
            var ps = new PrintStream(buf, true, StandardCharsets.UTF_8);
            var clock = new FakeClock();
            try (ProgressRow row =
                    ProgressRow.of(ps, "Clean").status("Counting…").clock(clock).open()) {
                row.status("Removing target");
                row.update(37, 100);
                clock.advance(Duration.ofMillis(Spinner.PLAIN_HEARTBEAT_MS + 1));
                row.plainBeatForTests();
            }
            String out = Capture.lf(buf.toString(StandardCharsets.UTF_8));
            assertThat(out).contains("jk: * Clean > Counting... - working...");
            assertThat(out).contains("jk: * Clean > Removing target 37% - working...");
            assertThat(out).doesNotContain("done.");
            return null;
        });
    }

    @Test
    void the_bar_appears_only_once_a_positive_total_is_reported() {
        var ps = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        ProgressRow row = ProgressRow.of(ps, "Clean").status("Counting…").open();
        try {
            RenderContext ansi = RenderContext.current().withAnsi(true);
            String spinnerOnly = row.frameForTests(0, ansi);
            assertThat(RenderContext.stripAnsi(spinnerOnly)).doesNotContain("%").contains("Counting…");

            row.status("Removing target");
            row.update(50, 100);
            String bar = RenderContext.stripAnsi(row.frameForTests(0, ansi));
            assertThat(bar).contains("50%").contains("Removing target");
            assertThat(bar.indexOf("50%")).isLessThan(bar.indexOf("Removing target"));
        } finally {
            row.finish();
        }
    }
}
