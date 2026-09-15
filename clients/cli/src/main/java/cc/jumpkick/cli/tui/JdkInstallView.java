// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.config.NerdFontCaps;
import cc.jumpkick.jdk.InstalledJdk;
import cc.jumpkick.jdk.JdkInstallListener;
import java.nio.file.Path;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The terminal's view of a JDK or GraalVM being provisioned — the one rendering for {@code jk jdk
 * install}, the build's pre-flight of a pinned toolchain, a native build's GraalVM, and the JDK
 * that hosts the build engine. Turns {@link JdkInstallListener} events into the animated {@link
 * JdkDownloadBar} (download, then the installing spinner) and the settled chip line:
 *
 * <pre>
 *   ✓ JDK ▶ Temurin 21 has been installed to ~/.jdks/temurin-21.0.12
 * </pre>
 *
 * <p>An optional {@linkplain #header header} says why the install is happening; it is printed only
 * when a download actually starts, so a toolchain that is already on disk costs no output beyond
 * its own "already installed" line.
 *
 * <p>Output modes follow the stream, not the caller: a terminal animates the bar; plain mode (no
 * ANSI) gets phase lines from {@link JdkDownloadBar}; a machine-consumed stdout ({@code --output
 * json}) keeps the human lines on stderr so the JSONL stream stays parseable, while the plan
 * listener carries the structured {@code label} events.
 *
 * <p>{@link AutoCloseable} so a mid-install failure still wipes the active bar (via the caller's
 * try-with-resources).
 */
public final class JdkInstallView implements JdkInstallListener, AutoCloseable {

    private volatile String label;
    private @Nullable String header;
    private @Nullable JdkDownloadBar bar;

    /** @param label the human JDK label ({@code "Temurin 21"}); events carrying a name refine it */
    public JdkInstallView(@Nullable String label) {
        this.label = label == null || label.isBlank() ? "JDK" : label;
    }

    /** The sentence printed above the bar when a download starts — why jk is installing this. */
    public JdkInstallView header(@Nullable String header) {
        this.header = header == null || header.isBlank() ? null : header;
        return this;
    }

    @Override
    public void onAlreadyInstalled(InstalledJdk jdk) {
        line(doneLine(label, jdk.home(), "is already installed at"));
    }

    @Override
    public void onDownloadStart(String displayName, long totalBytes) {
        if (displayName != null && !displayName.isBlank()) label = displayName;
        String why = header;
        if (why != null) {
            header = null;
            line(Objects.requireNonNull(Theme.colorize(why, Theme.active().normalGray())));
        }
        bar = JdkDownloadBar.show(CliOutput.stdout(), label);
    }

    @Override
    public void onDownloadProgress(long readBytes, long totalBytes) {
        JdkDownloadBar b = bar;
        if (b != null) b.update(readBytes, totalBytes);
    }

    @Override
    public void onExtractStart(String displayName) {
        if (displayName != null && !displayName.isBlank()) label = displayName;
        finishBar();
        bar = JdkDownloadBar.showInstalling(CliOutput.stdout(), label);
    }

    @Override
    public void onInstalled(InstalledJdk jdk) {
        // Wipe the spinner first so the done line takes its place on screen.
        finishBar();
        line(doneLine(label, jdk.home(), "has been installed to"));
    }

    /** A degradation the install goes ahead despite, as a human line in the view's own stream. */
    public void warn(String message) {
        line(Objects.requireNonNull(
                Theme.colorize(Glyphs.BANG + " " + message, Theme.active().warning())));
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

    /**
     * A human line of this view. Stdout unless it is machine-consumed, in which case stderr — the
     * JSONL on stdout must stay one object per line. envelopeStart is a no-op once the bar opened it.
     */
    private static void line(String text) {
        if (CliOutput.scriptMode()) {
            CommandWedge.envelopeStartErr();
            CliOutput.err(text);
            return;
        }
        CommandWedge.envelopeStart();
        CliOutput.out(text);
    }

    /**
     * The settled summary line:
     *
     * <pre>
     *   ✓ JDK ▶ {label} {command} {~/path}
     * </pre>
     */
    public static String doneLine(String label, Path home, String command) {
        Theme t = Theme.active();
        NerdFontCaps nerdFont = GlobalConfig.nerdFont();
        String msg = Theme.colorize(label, t.focused())
                + Theme.colorize(" " + command + " ", t.normalGray())
                + Theme.colorize(tildeCollapse(home), t.path());
        return JkWedge.chipLine(Glyphs.CHECK, "JDK", nerdFont, msg);
    }

    /** Render an absolute path with {@code $HOME} collapsed to {@code ~}. */
    public static String tildeCollapse(Path path) {
        String home = System.getProperty("user.home");
        String abs = path.toAbsolutePath().toString();
        if (home != null && !home.isBlank() && abs.startsWith(home)) {
            return "~" + abs.substring(home.length());
        }
        return abs;
    }
}
