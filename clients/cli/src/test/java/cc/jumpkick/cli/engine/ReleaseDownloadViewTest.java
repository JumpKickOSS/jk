// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.cli.testing.NoAnsi;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** A release artifact's download renders as the JDK's does: a phase line and a done line, on the right stream. */
class ReleaseDownloadViewTest {

    private static final Path JAR = Path.of("/opt/jk/lib/jk-engine/jk-engine-1.2.3.jar");

    @Test
    void plain_mode_says_the_download_as_a_line_then_the_done_line() throws Exception {
        String out = NoAnsi.forced(() -> Capture.stdout(() -> {
            CliOutput.beginCommand(false);
            try (ReleaseDownloadView view = ReleaseDownloadView.engine("1.2.3")) {
                view.start("jk-engine-1.2.3.jar", 1_000);
                view.progress(500, 1_000);
                view.done(JAR);
            }
        }));
        assertThat(out)
                .contains("Engine > Downloading build engine 1.2.3 - working...")
                .contains("build engine 1.2.3 has been downloaded to " + JAR.toAbsolutePath());
        assertThat(out.indexOf("Downloading")).isLessThan(out.indexOf("has been downloaded"));
    }

    @Test
    void a_machine_consumed_stdout_stays_clean_and_the_done_line_goes_to_stderr() throws Exception {
        Capture.Streams streams = NoAnsi.forced(() -> Capture.both(() -> {
            CliOutput.beginCommand(true);
            try (ReleaseDownloadView view = ReleaseDownloadView.engine("1.2.3")) {
                view.start("jk-engine-1.2.3.jar", 1_000);
                view.progress(1_000, 1_000);
                view.done(JAR);
            }
        }));
        assertThat(streams.out())
                .as("nothing but JSONL may reach a machine-consumed stdout")
                .isEmpty();
        assertThat(streams.err()).contains("build engine 1.2.3 has been downloaded to");
    }
}
