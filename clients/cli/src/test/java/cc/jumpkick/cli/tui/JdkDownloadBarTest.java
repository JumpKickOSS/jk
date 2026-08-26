// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.cli.testing.NoAnsi;
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
}
