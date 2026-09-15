// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.config.NerdFontCaps;
import cc.jumpkick.host.time.Clock;
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
 * <p>Plain mode (no ANSI) says what is happening in lines instead: {@code Downloading Temurin 21 -
 * working...} when the row opens, one more beat per {@link Spinner#PLAIN_HEARTBEAT_MS} carrying
 * the percent, and {@code Installing …} when extraction starts — so a CI log or a piped installer
 * shows the phases a terminal user watches on the bar.
 *
 * <p>Thread-safe: the download callback may call {@link #update} from any thread.
 */
public final class JdkDownloadBar implements AutoCloseable {

    private final PrintStream out;
    private final String displayName; // "Temurin 26"
    private final NerdFontCaps nerdFont;
    private final boolean installing;
    private final long startedAtMillis = System.currentTimeMillis();
    private final LiveLine line;

    private volatile long numerator;
    private volatile long denominator;
    private final Clock clock = Clock.SYSTEM;
    private long plainLastBeatMs;

    private JdkDownloadBar(PrintStream out, String displayName, boolean installing) {
        this.out = out;
        this.displayName = displayName;
        this.nerdFont = GlobalConfig.nerdFont();
        this.installing = installing;
        this.line = LiveLine.of(out, this::frame)
                .heartbeat(() -> plainBeat(false))
                .onCancel(JdkInstaller::reapInFlight)
                .settle(this::cancelledLine)
                .open();
        if (line.plain()) plainBeat(true);
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
     * A settled line above the bar — a warning that arrives mid-download — so the bar's frames
     * never interleave with it. False when the bar paints nothing ({@code --no-progress}, a
     * machine-consumed stdout, or finished): the caller prints the line itself, as it would with
     * no bar open.
     */
    public boolean printAbove(String text) {
        return line.printAbove(text);
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

    /**
     * The plain-mode line: on open, then no more often than {@link Spinner#PLAIN_HEARTBEAT_MS}.
     * Carries the percent while downloading; extraction has no byte count to report.
     */
    private synchronized void plainBeat(boolean force) {
        long now = clock.millis();
        if (!force && now - plainLastBeatMs < Spinner.PLAIN_HEARTBEAT_MS) return;
        plainLastBeatMs = now;
        String status = (installing ? "Installing " : "Downloading ") + displayName;
        long total = denominator;
        if (!installing && total > 0) status += " " + Math.min(100L, numerator * 100L / total) + "%";
        out.println(JkWedge.plainStatusLine("JDK", status, JkWedge.PlainTail.WORKING));
        out.flush();
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
