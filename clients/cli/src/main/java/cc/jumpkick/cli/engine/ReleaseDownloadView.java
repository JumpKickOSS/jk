// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.JdkDownloadBar;
import cc.jumpkick.cli.tui.JdkInstallView;
import cc.jumpkick.cli.tui.JkWedge;
import cc.jumpkick.config.GlobalConfig;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * The terminal's view of a release artifact the client fetches for itself — the build engine jar,
 * the Maven spy jar — rendered as {@code jk jdk install} renders a JDK: the one bar, phase lines
 * and settled chip line, under the chip of the thing being fetched:
 *
 * <pre>
 *   ✓ Engine ▶ build engine 0.13.7 has been downloaded to ~/.jk/lib/jk-engine/jk-engine-0.13.7.jar
 * </pre>
 *
 * <p>Output modes follow the stream, as {@link JdkInstallView}'s do: a terminal animates the bar;
 * plain mode (no ANSI) gets a {@code Downloading …} line from {@link JdkDownloadBar}; a
 * machine-consumed stdout ({@code --output json}) keeps the human line on stderr so the JSONL
 * stream stays parseable. {@link AutoCloseable} so a failed fetch still wipes the active bar.
 */
public final class ReleaseDownloadView implements ReleaseArtifacts.Progress, AutoCloseable {

    /** The chip the engine's own lines carry, matching the {@code Engine} chip of start and stop. */
    static final String ENGINE_CHIP = "Engine";

    private final String chip;
    private final String label;
    private @Nullable JdkDownloadBar bar;

    /** The engine jar's view: {@code version} is the client's, which the jar is paired with. */
    static ReleaseDownloadView engine(String version) {
        return new ReleaseDownloadView(ENGINE_CHIP, "build engine " + version);
    }

    /**
     * @param chip the chip the lines carry ({@code Engine}, {@code Maven})
     * @param label what is being fetched, as the bar and the done line name it
     */
    public ReleaseDownloadView(String chip, String label) {
        this.chip = chip;
        this.label = label;
    }

    @Override
    public void start(String jarName, long totalBytes) {
        bar = JdkDownloadBar.show(CliOutput.stdout(), chip, label);
    }

    @Override
    public void progress(long readBytes, long totalBytes) {
        JdkDownloadBar b = bar;
        if (b != null) b.update(readBytes, totalBytes);
    }

    @Override
    public void done(Path artifact) {
        // Wipe the bar first so the done line takes its place on screen.
        finishBar();
        line(doneLine(artifact));
    }

    @Override
    public void close() {
        finishBar();
    }

    private void finishBar() {
        JdkDownloadBar b = bar;
        if (b != null) {
            b.finish();
            bar = null;
        }
    }

    /** {@code ✓ Engine ▶ build engine {version} has been downloaded to {~/path}}. */
    String doneLine(Path artifact) {
        Theme t = Theme.active();
        String msg = Theme.colorize(label, t.focused())
                + Theme.colorize(" has been downloaded to ", t.normalGray())
                + Theme.colorize(JdkInstallView.tildeCollapse(artifact), t.path());
        return JkWedge.chipLine(Glyphs.CHECK, chip, GlobalConfig.nerdFont(), msg);
    }

    /** A human line of this view: stdout, or stderr when stdout is machine-consumed. */
    private static void line(String text) {
        if (CliOutput.scriptMode()) {
            CommandWedge.envelopeStartErr();
            CliOutput.err(text);
            return;
        }
        CommandWedge.envelopeStart();
        CliOutput.out(text);
    }
}
