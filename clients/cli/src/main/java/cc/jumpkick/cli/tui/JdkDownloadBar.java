// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.Osc;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.config.NerdFontCaps;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.jdk.JdkInstaller;
import cc.jumpkick.terminal.Ansi;
import java.io.PrintStream;
import java.time.Duration;

/**
 * Animated JDK download progress bar styled like the {@code jk build} plan header: the blue plan
 * chip ({@code ✷ JDK ▶}), a block-bar fill, a white percent, and the download label — all on one
 * carriage-returned line.
 *
 * <p>On completion, the bar is wiped and replaced with a {@link JkWedge} check-chip result line:
 * {@code ✓ JDK ▶ Finished downloading [bold]Eclipse Temurin 26[/]}.
 *
 * <p>Registers as the active {@link LiveRegion} so the global Ctrl-C handler can repaint it on
 * cancel. Thread-safe: the download callback may call {@link #update} from any thread.
 */
public final class JdkDownloadBar implements AutoCloseable, LiveRegion {

    private final PrintStream out;
    private final String displayName; // "Eclipse Temurin 26"
    private final NerdFontCaps nerdFont;
    private final boolean silent;

    private final long startedAtMillis = System.currentTimeMillis();

    private int frame;
    private long numerator;
    private long denominator;
    private boolean drawn;
    private boolean closed;
    private boolean installing;
    private Thread animator;

    private JdkDownloadBar(PrintStream out, String displayName, NerdFontCaps nerdFont, boolean silent) {
        this.out = out;
        this.displayName = displayName;
        this.nerdFont = nerdFont;
        this.silent = silent;
    }

    /**
     * Start a new download bar for {@code displayName} (e.g. "Eclipse Temurin 26"). If {@code
     * --no-progress} is set, returns a silent no-op instance.
     */
    public static JdkDownloadBar show(PrintStream out, String displayName) {
        // Script mode is no-progress — see the same rule on Spinner's constructor (JK-2330).
        boolean silent = SessionContext.current().config().noProgressOr(false) || CliOutput.scriptMode();
        NerdFontCaps nerdFont = GlobalConfig.nerdFont();
        JdkDownloadBar db = new JdkDownloadBar(out, displayName, nerdFont, silent);
        // Leading blank of the human chrome envelope (idempotent per command).
        CommandWedge.envelopeStart(out);
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
            out.print(Osc.taskbarProgress((int) Math.min(100, bytesDownloaded * 100L / total)));
        }
    }

    /**
     * Start an "Installing…" chip spinner using the same chip-with-frame pattern as the download bar
     * — the spinner frame cycles inside the {@code ✸ JDK ▶} chip. Call {@link #close()} on the
     * returned handle when installation completes.
     */
    public static JdkDownloadBar showInstalling(PrintStream out, String displayName) {
        // Script mode is no-progress — see the same rule on Spinner's constructor (JK-2330).
        boolean silent = SessionContext.current().config().noProgressOr(false) || CliOutput.scriptMode();
        NerdFontCaps nerdFont = GlobalConfig.nerdFont();
        JdkDownloadBar db = new JdkDownloadBar(out, displayName, nerdFont, silent);
        db.installing = true;
        // Same envelope as show() — first chrome may be the installing chip alone.
        CommandWedge.envelopeStart(out);
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
        out.print(Osc.taskbarClear());
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
        // Before the silent check: --no-progress and script mode cancel too, and the bytes on disk
        // do not care whether anything was painted. Ctrl-C ends in Runtime.halt, which runs no
        // shutdown hook and unwinds no stack, so the installer's own finally blocks never fire —
        // this is the only moment left to unlink what it was streaming into.
        JdkInstaller.reapInFlight();
        if (silent) return false;
        // The same settle a cancelled build gets — gray scope chip, ⊛, bold "cancelled", dark-gray
        // italic took — differing only in the subject. Wipe the live row first so the bar it
        // replaces leaves nothing behind, exactly as JkManager does in plan mode.
        out.print("\r");
        out.print(Ansi.ERASE_LINE_TO_END);
        String took = ConsoleSpec.took(Duration.ofMillis(Math.max(0L, System.currentTimeMillis() - startedAtMillis)));
        out.println(JkWedge.cancelled("JDK", "JDK download", true, took).renderLine(context()));
        out.print(Osc.taskbarClear());
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

    /** The live row, through the shared wedge geometry so it can never wrap. */
    private String buildLine() {
        return wedge().renderLiveLine(context());
    }

    private RenderContext context() {
        return RenderContext.current().withCaps(nerdFont).withFrame(frame);
    }

    /** The live wedge: spinner chip, plan bar while downloading, status suffix. */
    private JkWedge wedge() {
        Theme t = Theme.active();
        String command = installing ? "Installing " : "Downloading ";
        RichText status = RichText.ansi(Theme.colorize(command + displayName, t.normalGray()));
        JkWedge wedge = new JkWedge(Icon.spinner(), "JDK", installing ? status : RichText.empty())
                .variant(JkWedge.Variant.WORK);
        if (!installing) {
            // Narrow: the trailing text is a JDK product and version we are handed, so the bar
            // yields the columns rather than the label losing them.
            wedge = wedge.progress(new Progress(numerator, denominator)
                    .narrow()
                    .suffix(RichText.of(RichText.parse("[dark-gray]·[/] "), status)));
        }
        return wedge;
    }
}
