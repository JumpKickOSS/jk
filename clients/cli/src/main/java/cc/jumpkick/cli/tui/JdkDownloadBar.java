// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.Ansi;
import cc.jumpkick.cli.theme.Theme;
import java.io.PrintStream;
import org.jline.utils.AttributedStyle;

/**
 * Animated JDK download progress bar styled like the {@code jk build} plan header: the blue plan
 * chip ({@code ✷ JDK ▶}), a block-bar fill, a white percent, and the download label — all on one
 * carriage-returned line.
 *
 * <p>On completion, the bar is wiped and replaced with a {@link BuildPlanWedge} check-chip result line:
 * {@code ✓ JDK ▶ Finished downloading [bold]Eclipse Temurin 26[/]}.
 *
 * <p>Registers as the active {@link LiveRegion} so the global Ctrl-C handler can repaint it on
 * cancel. Thread-safe: the download callback may call {@link #update} from any thread.
 */
public final class JdkDownloadBar implements AutoCloseable, LiveRegion {

    private final PrintStream out;
    private final String displayName; // "Eclipse Temurin 26"
    private final boolean nerdfont;
    private final boolean silent;
    private final AttributedStyle[] failColors;

    private int frame;
    private long numerator;
    private long denominator;
    private boolean drawn;
    private boolean closed;
    private boolean installing;
    private Thread animator;

    private JdkDownloadBar(PrintStream out, String displayName, boolean nerdfont, boolean silent) {
        this.out = out;
        this.displayName = displayName;
        this.nerdfont = nerdfont;
        this.silent = silent;
        this.failColors = SpinnerProgressBar.buildGradient(
                ProgressBar.SEGMENTS, Theme.active().failureGradient());
    }

    /**
     * Start a new download bar for {@code displayName} (e.g. "Eclipse Temurin 26"). If {@code
     * --no-progress} is set, returns a silent no-op instance.
     */
    public static JdkDownloadBar show(PrintStream out, String displayName) {
        boolean silent = cc.jumpkick.config.SessionContext.current().config().noProgressOr(false);
        boolean nerdfont = cc.jumpkick.config.GlobalConfig.nerdfont();
        JdkDownloadBar db = new JdkDownloadBar(out, displayName, nerdfont, silent);
        LiveRegion.setActive(db);
        if (!silent) {
            out.print(Ansi.HIDE_CURSOR);
            out.flush();
            db.startAnimator();
        }
        return db;
    }

    /** Report download progress; safe to call from any thread. */
    public synchronized void update(long bytesDownloaded, long total) {
        if (closed || silent) return;
        this.numerator = bytesDownloaded;
        this.denominator = total;
        if (total > 0) {
            out.print(Ansi.taskbarProgress((int) Math.min(100, bytesDownloaded * 100L / total)));
        }
    }

    /**
     * Start an "Installing…" chip spinner using the same chip-with-frame pattern as the download bar
     * — the spinner frame cycles inside the {@code ✸ JDK ▶} chip. Call {@link #close()} on the
     * returned handle when installation completes.
     */
    public static JdkDownloadBar showInstalling(PrintStream out, String displayName) {
        boolean silent = cc.jumpkick.config.SessionContext.current().config().noProgressOr(false);
        boolean nerdfont = cc.jumpkick.config.GlobalConfig.nerdfont();
        JdkDownloadBar db = new JdkDownloadBar(out, displayName, nerdfont, silent);
        db.installing = true;
        LiveRegion.setActive(db);
        if (!silent) {
            out.print(Ansi.HIDE_CURSOR);
            out.flush();
            db.startAnimator();
        }
        return db;
    }

    /**
     * Wipe the bar line and restore the cursor. The caller is responsible for printing the final
     * result line (e.g. "available at …"), which naturally takes the place of the cleared bar on
     * screen.
     */
    public synchronized void finish() {
        if (closed) return;
        closed = true;
        stopAnimator();
        LiveRegion.clearActive(this);
        if (silent) return;
        if (drawn) out.print(Ansi.CLEAR_LINE);
        out.print(Ansi.taskbarClear());
        out.print(Ansi.SHOW_CURSOR);
        out.flush();
    }

    @Override
    public void close() {
        finish();
    }

    @Override
    public boolean renderCanceled() {
        if (closed) return false;
        closed = true;
        stopAnimator();
        LiveRegion.clearActive(this);
        if (silent) return false;
        // Repaint every segment in failure red.
        out.print("\r");
        for (int i = 0; i < ProgressBar.SEGMENTS; i++) {
            out.print(Theme.colorize(String.valueOf(ProgressBar.FILLED_CHAR), failColors[i]));
        }
        out.print(Ansi.taskbarClear());
        out.print(Ansi.SHOW_CURSOR);
        out.flush();
        return true;
    }

    // ── animation ──────────────────────────────────────────────────────────

    private void startAnimator() {
        animator = new Thread(
                () -> {
                    while (!closed) {
                        try {
                            Thread.sleep(Spinner.FRAME_MS);
                        } catch (InterruptedException e) {
                            break;
                        }
                        synchronized (this) {
                            if (!closed) {
                                repaint();
                                frame = (frame + 1) % Spinner.PULSE_FRAMES;
                            }
                        }
                    }
                },
                "jk-jdk-download");
        animator.setDaemon(true);
        animator.start();
    }

    private void stopAnimator() {
        if (animator != null) {
            animator.interrupt();
            animator = null;
        }
    }

    private void repaint() {
        out.print("\r");
        out.print(buildLine());
        out.print(Ansi.ERASE_LINE_TO_END);
        out.flush();
        drawn = true;
    }

    private String buildLine() {
        Theme t = Theme.active();
        String command = installing ? "Installing " : "Downloading ";
        RichText status = RichText.ansi(Theme.colorize(command + displayName, t.normalGray()));
        RenderContext ctx = RenderContext.current().withNerd(nerdfont).withFrame(frame);
        JkWedge wedge = new JkWedge(Icon.spinner(), "JDK", installing ? status : RichText.empty())
                .variant(JkWedge.Variant.WORK);
        if (!installing) {
            wedge = wedge.progress(new Progress(numerator, denominator)
                    .suffix(RichText.of(RichText.parse("[dark-gray]·[/] "), status)));
        }
        return wedge.renderLine(ctx);
    }
}
