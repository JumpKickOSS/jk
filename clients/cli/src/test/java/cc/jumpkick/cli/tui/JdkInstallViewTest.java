// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.cli.testing.NoAnsi;
import cc.jumpkick.jdk.InstalledJdk;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class JdkInstallViewTest {

    private static final Path HOME = Path.of("/opt/jdks/temurin-21.0.12");

    @Test
    void plain_mode_says_each_phase_as_a_line_then_the_done_line() throws Exception {
        String out = NoAnsi.forced(() -> Capture.stdout(() -> {
            CliOutput.beginCommand(false);
            try (JdkInstallView view =
                    new JdkInstallView(null).header("app/jk.toml pins JDK temurin-21 — installing it")) {
                view.onDownloadStart("Temurin 21", 1_000);
                view.onDownloadProgress(500, 1_000);
                view.onExtractStart("Temurin 21");
                view.onInstalled(new InstalledJdk("temurin-21.0.12", HOME));
            }
        }));
        assertThat(out)
                .contains("app/jk.toml pins JDK temurin-21 -- installing it")
                .contains("JDK > Downloading Temurin 21 - working...")
                .contains("JDK > Installing Temurin 21 - working...")
                .contains("Temurin 21 has been installed to " + HOME.toAbsolutePath());
        assertThat(out.indexOf("pins JDK")).isLessThan(out.indexOf("Downloading"));
        assertThat(out.indexOf("Downloading")).isLessThan(out.indexOf("Installing"));
        assertThat(out.indexOf("Installing")).isLessThan(out.indexOf("has been installed"));
    }

    @Test
    void a_warning_during_the_download_is_a_line_between_the_phases_in_plain_mode() throws Exception {
        String out = NoAnsi.forced(() -> Capture.stdout(() -> {
            CliOutput.beginCommand(false);
            try (JdkInstallView view = new JdkInstallView(null)) {
                view.onDownloadStart("Temurin 21", 1_000);
                view.onDownloadProgress(500, 1_000);
                view.warn("the feed is unreachable; using the cached index");
                view.onExtractStart("Temurin 21");
                view.onInstalled(new InstalledJdk("temurin-21.0.12", HOME));
            }
        }));
        assertThat(out.split("\\n"))
                .contains("! the feed is unreachable; using the cached index")
                .as("the warning is a line of its own, not a fragment of a status line");
        assertThat(out.indexOf("Downloading")).isLessThan(out.indexOf("the feed is unreachable"));
        assertThat(out.indexOf("the feed is unreachable")).isLessThan(out.indexOf("Installing"));
        assertThat(out.indexOf("Installing")).isLessThan(out.indexOf("has been installed"));
    }

    @Test
    void a_warning_during_a_live_bar_settles_above_it_and_the_bar_repaints_intact() throws Exception {
        String out = NoAnsi.forcedAnsi(() -> Capture.stdout(() -> {
            CliOutput.beginCommand(false);
            try (JdkInstallView view = new JdkInstallView(null)) {
                view.onDownloadStart("Temurin 21", 1_000);
                view.onDownloadProgress(500, 1_000);
                view.warn("the feed is unreachable; using the cached index");
                view.onDownloadProgress(1_000, 1_000);
                view.onInstalled(new InstalledJdk("temurin-21.0.12", HOME));
            }
        }));
        // Rows are what the newline separates; frames repaint one row with \r and never end it.
        List<String> rows = List.of(out.split("\\n"));
        int warning = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).contains("the feed is unreachable")) warning = i;
        }
        assertThat(warning).as("the warning ends a row of its own\n%s", out).isNotNegative();
        String warningRow = TestAnsi.strip(rows.get(warning));
        // Whatever the row held before the wipe was erased in place; the settled text is the warning alone.
        assertThat(warningRow.substring(warningRow.lastIndexOf('\r') + 1))
                .endsWith(" the feed is unreachable; using the cached index")
                .doesNotContain("Downloading");
        String after = String.join("\n", rows.subList(warning + 1, rows.size()));
        assertThat(TestAnsi.strip(after))
                .as("the bar repaints below the warning and the done line settles last")
                .contains("Downloading Temurin 21")
                .contains("Temurin 21 has been installed to " + HOME.toAbsolutePath());
        assertThat(TestAnsi.strip(after).indexOf("Downloading"))
                .isLessThan(TestAnsi.strip(after).indexOf("has been installed"));
    }

    @Test
    void already_installed_prints_no_header_and_no_phase() throws Exception {
        String out = NoAnsi.forced(() -> Capture.stdout(() -> {
            CliOutput.beginCommand(false);
            try (JdkInstallView view = new JdkInstallView("Temurin 21").header("why")) {
                view.onAlreadyInstalled(new InstalledJdk("temurin-21.0.12", HOME));
            }
        }));
        assertThat(out).doesNotContain("why").doesNotContain("Downloading");
        assertThat(out).contains("Temurin 21 is already installed at " + HOME.toAbsolutePath());
    }

    @Test
    void machine_consumed_stdout_keeps_the_human_lines_on_stderr() throws Exception {
        Capture.Streams streams = NoAnsi.forced(() -> Capture.both(() -> {
            CliOutput.beginCommand(true);
            try (JdkInstallView view = new JdkInstallView(null).header("why")) {
                view.onDownloadStart("Temurin 21", 1_000);
                view.onDownloadProgress(1_000, 1_000);
                view.onExtractStart("Temurin 21");
                view.onInstalled(new InstalledJdk("temurin-21.0.12", HOME));
            }
        }));
        assertThat(streams.out()).isEmpty();
        assertThat(streams.err()).contains("why").contains("Temurin 21 has been installed to");
        // Script mode silences the live region; the phases belong to the plan's structured events.
        assertThat(streams.err()).doesNotContain("Downloading");
    }

    @Test
    void the_event_name_refines_the_label() throws Exception {
        String out = NoAnsi.forced(() -> Capture.stdout(() -> {
            CliOutput.beginCommand(false);
            try (JdkInstallView view = new JdkInstallView("JDK")) {
                view.onDownloadStart("GraalVM 25", 0);
                view.onInstalled(new InstalledJdk("graalvm-25.0.1", Path.of("/opt/jdks/graalvm-25.0.1")));
            }
        }));
        assertThat(out)
                .contains("GraalVM 25 has been installed to "
                        + Path.of("/opt/jdks/graalvm-25.0.1").toAbsolutePath());
    }
}
