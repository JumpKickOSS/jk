// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.config.NerdFontCaps;
import cc.jumpkick.jdk.JdkInstaller;
import java.io.PrintStream;
import java.time.Duration;

/**
 * JDK download / install progress, styled like the {@code jk build} plan header: the blue plan chip
 * ({@code ✷ JDK ▶}), a block-bar fill, a white percent, and the download label — on one row that is
 * repainted in place.
 *
 * <p>Content only. The live region itself — animator, cursor, OSC taskbar, in-place repaint, cancel
 * teardown — belongs to {@link LiveLine}, and the look belongs to {@link JkWedge} and {@link
 * Progress}. What is left here is the wedge for a frame and the line to settle on when the user
 * cancels, which is the whole of what is specific to downloading a JDK.
 *
 * <p>Thread-safe: the download callback may call {@link #update} from any thread.
 */
public final class JdkDownloadBar implements AutoCloseable {

    private final String displayName; // "Temurin 26"
    private final NerdFontCaps nerdFont;
    private final boolean installing;
    private final long startedAtMillis = System.currentTimeMillis();
    private final LiveLine line;

    private volatile long numerator;
    private volatile long denominator;

    private JdkDownloadBar(PrintStream out, String displayName, boolean installing) {
        this.displayName = displayName;
        this.nerdFont = GlobalConfig.nerdFont();
        this.installing = installing;
        this.line = LiveLine.of(out, this::frame)
                .onCancel(JdkInstaller::reapInFlight)
                .settle(this::cancelledLine)
                .open();
    }

    /**
     * Start a download bar for {@code displayName} (e.g. "Temurin 26"). Silent under
     * {@code --no-progress} or a machine-consumed stdout — {@link LiveLine} owns that rule.
     */
    public static JdkDownloadBar show(PrintStream out, String displayName) {
        return new JdkDownloadBar(out, displayName, false);
    }

    /**
     * Start an "Installing…" chip spinner: the same chip, frame-cycled, with no bar — extraction has
     * no byte count to report.
     */
    public static JdkDownloadBar showInstalling(PrintStream out, String displayName) {
        return new JdkDownloadBar(out, displayName, true);
    }

    /** Report download progress; safe to call from any thread. */
    public void update(long bytesDownloaded, long total) {
        this.numerator = bytesDownloaded;
        this.denominator = total;
        if (total > 0) line.progress((int) Math.min(100, bytesDownloaded * 100L / total));
    }

    /**
     * Wipe the row and restore the cursor. The caller prints the final result line (e.g. "available
     * at …"), which takes the cleared row's place on screen.
     */
    public void finish() {
        line.finish();
    }

    @Override
    public void close() {
        finish();
    }

    /** The animating row: spinner chip, plan bar while downloading, status suffix. */
    private String frame(int tick) {
        Theme t = Theme.active();
        String command = installing ? "Installing " : "Downloading ";
        RichText status = RichText.ansi(Theme.colorize(command + displayName, t.normalGray()));
        JkWedge wedge = new JkWedge(Icon.spinner(), "JDK", installing ? status : RichText.empty())
                .variant(JkWedge.Variant.WORK);
        if (!installing) {
            // Narrow: the trailing text is a product and version we are handed, so the bar yields
            // the columns rather than the label losing them.
            wedge = wedge.progress(new Progress(numerator, denominator)
                    .narrow()
                    .suffix(RichText.of(RichText.parse("[dark-gray]·[/] "), status)));
        }
        return wedge.renderLiveLine(context(tick));
    }

    /**
     * What a cancelled download settles on: the same chrome a cancelled build gets, differing only
     * in the subject. One cancel look for the product, not a second one for downloads.
     */
    private String cancelledLine() {
        String took = ConsoleSpec.took(Duration.ofMillis(Math.max(0L, System.currentTimeMillis() - startedAtMillis)));
        return JkWedge.cancelled("JDK", "JDK download", true, took).renderLine(context(0));
    }

    private RenderContext context(int tick) {
        return RenderContext.current().withCaps(nerdFont).withFrame(tick);
    }
}
