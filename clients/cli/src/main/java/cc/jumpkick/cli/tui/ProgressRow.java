// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.config.NerdFontCaps;
import cc.jumpkick.host.time.Clock;
import java.io.PrintStream;
import java.time.Duration;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * One bounded client-side job on one live row, styled like the {@code jk build} plan header: the
 * blue chip with a pulsing icon, a block-bar fill with a white percent, and a gray status after it
 * — {@code ● JDK ▶ ████░░░░ 42% · Downloading Temurin 26}. Until {@link #update} reports a positive
 * total the row is the chip and the status alone, so a caller may open it before it knows how much
 * work there is.
 *
 * <p>Content only. The live region — animator, cursor, OSC taskbar, in-place repaint, Ctrl-C
 * teardown — is {@link LiveLine}'s, and the look is {@link JkWedge}'s and {@link Progress}'s. What
 * is here is the row for a frame, the plain-mode cadence, and the line to settle on when the user
 * cancels; what varies between a JDK download, an engine fetch and {@code jk clean} is a handful
 * of strings, which is why this is one type and its callers are constructor calls.
 *
 * <p>Plain mode (no ANSI) says what is happening in lines instead: {@code jk: * JDK > Downloading
 * Temurin 26 42% - working...} when the row opens and once more per {@link
 * Spinner#PLAIN_HEARTBEAT_MS} while it runs. The caller's settle line is the {@code done}; the row
 * prints none of its own.
 *
 * <p>Thread-safe: {@link #update} and {@link #status} may be called from any thread.
 */
public final class ProgressRow implements AutoCloseable {

    private final PrintStream out;
    private final String chip;
    private final String cancelSubject;
    private final NerdFontCaps nerdFont;
    private final long startedAtMillis;
    private final LiveLine line;

    private volatile Clock clock;
    private volatile String status;
    private volatile long numerator;
    private volatile long denominator;
    private volatile @Nullable LongSupplier live;
    private long plainLastBeatMs;

    private ProgressRow(Builder b) {
        this.out = b.out;
        this.chip = b.chip;
        this.status = b.status;
        this.cancelSubject = b.cancelSubject;
        this.clock = b.clock;
        this.nerdFont = GlobalConfig.nerdFont();
        this.startedAtMillis = clock.millis();
        LiveLine.Builder region =
                LiveLine.of(out, this::frame).heartbeat(() -> plainBeat(false)).settle(this::cancelledLine);
        if (b.onCancel != null) region.onCancel(b.onCancel);
        this.line = region.open();
        if (line.plain()) plainBeat(true);
    }

    /** A row under {@code chip} on {@code out}; {@link Builder#open()} takes the row. */
    public static Builder of(PrintStream out, String chip) {
        return new Builder(out, chip);
    }

    /** What a caller may say about the row before opening it; the chip is the only required part. */
    public static final class Builder {
        private final PrintStream out;
        private final String chip;
        private String status = "";
        private String cancelSubject = "job";
        private @Nullable Runnable onCancel;
        private Clock clock = Clock.SYSTEM;

        private Builder(PrintStream out, String chip) {
            this.out = out;
            this.chip = chip == null ? "" : chip;
        }

        /** The gray text after the bar ({@code Downloading Temurin 26}); may change later via {@link ProgressRow#status}. */
        public Builder status(String status) {
            this.status = status == null ? "" : status;
            return this;
        }

        /** What the cancel line says was cancelled: {@code "JDK download"}, {@code "clean"}. Default {@code "job"}. */
        public Builder cancelSubject(String subject) {
            this.cancelSubject = subject == null || subject.isBlank() ? "job" : subject;
            return this;
        }

        /** Teardown to run on Ctrl-C whether or not anything was painted. */
        public Builder onCancel(Runnable onCancel) {
            this.onCancel = onCancel;
            return this;
        }

        /** Test seam: the clock the plain heartbeat and the cancel {@code took} read. */
        Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        /** Take the row. Silent under {@code --no-progress} or a machine-consumed stdout — {@link LiveLine} owns that rule. */
        public ProgressRow open() {
            return new ProgressRow(this);
        }
    }

    /** Report progress; a {@code total} of zero or less keeps the row in its spinner-only state. */
    public void update(long done, long total) {
        this.live = null;
        this.numerator = done;
        this.denominator = total;
        if (total > 0) line.progress((int) Math.min(100, done * 100L / total));
    }

    /**
     * Read the count done from {@code done} on every frame instead of waiting for {@link #update}
     * calls — for work whose tally grows on other threads, such as a delete's running file count.
     */
    public void follow(LongSupplier done, long total) {
        this.denominator = total;
        this.live = done;
        refreshLive();
    }

    /** The followed count into the row and the taskbar; a no-op when nothing is followed. */
    private void refreshLive() {
        LongSupplier source = live;
        long total = denominator;
        if (source == null || total <= 0) return;
        long done = Math.max(0L, Math.min(total, source.getAsLong()));
        numerator = done;
        line.progress((int) (done * 100L / total));
    }

    /** Change the status text under the bar; safe from any thread. */
    public void status(String status) {
        this.status = status == null ? "" : status;
    }

    /**
     * A settled line above the row — a warning that arrives mid-job — so the frames never
     * interleave with it. False when the row paints nothing ({@code --no-progress}, a
     * machine-consumed stdout, or finished): the caller prints the line itself.
     */
    public boolean printAbove(String text) {
        return line.printAbove(text);
    }

    /**
     * Wipe the row and restore the cursor. The caller prints the final result line, which takes
     * the cleared row's place on screen.
     */
    public void finish() {
        line.finish();
    }

    @Override
    public void close() {
        finish();
    }

    /** The plain-mode line: on open, then no more often than {@link Spinner#PLAIN_HEARTBEAT_MS}. */
    private synchronized void plainBeat(boolean force) {
        long now = clock.millis();
        if (!force && now - plainLastBeatMs < Spinner.PLAIN_HEARTBEAT_MS) return;
        plainLastBeatMs = now;
        refreshLive();
        out.println(JkWedge.plainStatusLine(chip, plainStatus(), JkWedge.PlainTail.WORKING));
        out.flush();
    }

    /** The status with the percent appended once a total is known. */
    private String plainStatus() {
        String text = status;
        long total = denominator;
        if (total > 0) text += " " + Math.min(100L, numerator * 100L / total) + "%";
        return text;
    }

    /** One plain-mode beat, as the animator would take it (tests). */
    void plainBeatForTests() {
        plainBeat(false);
    }

    /** The row for animator frame {@code tick} under {@code ctx} (tests). */
    String frameForTests(int tick, RenderContext ctx) {
        return frame(ctx.withFrame(tick));
    }

    private String frame(int tick) {
        return frame(context(tick));
    }

    /** The animating row: spinner chip, the bar once a total is known, the status after it. */
    private String frame(RenderContext ctx) {
        refreshLive();
        Theme t = Theme.active();
        String text = status;
        RichText gray = text.isEmpty() ? RichText.empty() : RichText.ansi(Theme.colorize(text, t.normalGray()));
        long total = denominator;
        JkWedge wedge;
        if (total > 0) {
            // Narrow: the trailing text is a label we are handed, so the bar yields the columns
            // rather than the label losing them.
            RichText suffix = gray.isEmpty() ? RichText.empty() : RichText.of(RichText.parse("[dark-gray]·[/] "), gray);
            wedge = new JkWedge(Icon.spinner(), chip, RichText.empty())
                    .variant(JkWedge.Variant.WORK)
                    .progress(new Progress(numerator, total).narrow().suffix(suffix));
        } else {
            wedge = new JkWedge(Icon.spinner(), chip, gray).variant(JkWedge.Variant.WORK);
        }
        return wedge.renderLiveLine(ctx);
    }

    /** The cancel settle: the same chrome a cancelled build gets, differing only in the subject. */
    private String cancelledLine() {
        String took = ConsoleSpec.took(Duration.ofMillis(Math.max(0L, clock.millis() - startedAtMillis)));
        return JkWedge.cancelled(chip, cancelSubject, true, took).renderLine(context(0));
    }

    private RenderContext context(int tick) {
        return RenderContext.current().withCaps(nerdFont).withFrame(tick);
    }
}
