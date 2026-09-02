// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.theme.JkDarkTheme;
import cc.jumpkick.cli.theme.Rgb;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.NerdFontCaps;
import cc.jumpkick.terminal.Style;
import java.util.List;
import java.util.Locale;

/**
 * One line of command chrome: icon + title chip + optional message / progress. Nerd, ANSI, and
 * plain each have their own geometry (powerline cap / two-space pill / {@code >} ASCII).
 */
public final class JkWedge implements Widget {

    public enum Variant {
        OK,
        FAIL,
        WORK,
        MENU,
        WARNING,
        /** Settled user/remote cancel — black on explain-pill gray, not the red fail chip. */
        CANCELLED
    }

    private final Icon icon;
    private final String title;
    private final RichText message;
    private final Variant variant;
    private final Progress progress;

    public JkWedge(Icon icon, String title, RichText message) {
        this(icon, title, message, icon == null ? Variant.WORK : icon.defaultVariant(), null);
    }

    public JkWedge(Icon icon, String title, String message) {
        this(icon, title, RichText.plain(message == null ? "" : message));
    }

    private JkWedge(Icon icon, String title, RichText message, Variant variant, Progress progress) {
        this.icon = icon == null ? Icon.menu() : icon;
        this.title = title == null ? "" : title;
        this.message = message == null ? RichText.empty() : message;
        this.variant = variant == null ? this.icon.defaultVariant() : variant;
        this.progress = progress;
    }

    public JkWedge variant(Variant v) {
        return new JkWedge(icon, title, message, v, progress);
    }

    public JkWedge progress(Progress p) {
        return new JkWedge(icon, title, message, variant, p);
    }

    public Icon icon() {
        return icon;
    }

    public String title() {
        return title;
    }

    public RichText message() {
        return message;
    }

    public Variant variant() {
        return variant;
    }

    public Progress progress() {
        return progress;
    }

    public static JkWedge ok(String title, RichText message) {
        return new JkWedge(Icon.check(), title, message, Variant.OK, null);
    }

    public static JkWedge ok(String title, String message) {
        return ok(title, RichText.plain(message == null ? "" : message));
    }

    public static JkWedge fail(String title, RichText message) {
        return new JkWedge(Icon.cross(), title, message, Variant.FAIL, null);
    }

    public static JkWedge fail(String title, String message) {
        return fail(title, RichText.plain(message == null ? "" : message));
    }

    public static JkWedge work(String title, RichText message) {
        return new JkWedge(Icon.play(), title, message, Variant.OK, null);
    }

    public static JkWedge work(String title, String message) {
        return work(title, RichText.plain(message == null ? "" : message));
    }

    public static JkWedge menu(String title, RichText message) {
        return new JkWedge(Icon.menu(), title, message, Variant.MENU, null);
    }

    public static JkWedge menu(String title) {
        return menu(title, RichText.empty());
    }

    public static JkWedge warning(String title, RichText message) {
        return new JkWedge(Icon.bang(), title, message, Variant.WARNING, null);
    }

    public static JkWedge warning(String title) {
        return warning(title, RichText.empty());
    }

    /** Single painted line (wedge is always one row). */
    public String renderLine(RenderContext ctx) {
        String glyph = icon.paint(ctx);
        if (!ctx.ansi()) {
            String msg = tailPlain(ctx);
            return plainWedge(glyph, title, msg.isEmpty() ? null : msg);
        }
        ChipColors colors = colors(ctx.theme());
        String chip = paintChip(ctx, colors, glyph);
        String cap = paintCap(ctx, colors);
        String tail = tailAnsi(ctx);
        if (tail.isEmpty()) return chip + cap;
        // The plan bar always starts flush against the badge — no separator space.
        //
        // With the nerd axis the reason is the powerline cap blending into the bar's lead colour.
        // Without it there is no cap, and this branch used to fall through to the generic
        // `chip + " " + tail`, which put THREE spaces before the bar: the chip's own two-space pill
        // trail (chip() uses "  " where the wedge uses " ") plus the separator. The bar looked
        // detached from its badge in every no-nerd-font terminal. The chip's trail is painted on the
        // chip background, so dropping only the separator leaves the bar flush against the badge in
        // both modes rather than merely closer.
        if (progress != null && progress.look() == Progress.Look.PLAN) {
            return chip + cap + tail;
        }
        return chip + cap + " " + tail;
    }

    private String paintChip(RenderContext ctx, ChipColors colors, String glyph) {
        if (!(icon instanceof Icon.Spinner) || !ctx.ansi()) {
            return chip(glyph, title, colors.chip, ctx.wedge());
        }
        Style[] pulseFg = Spinner.buildChipPulseStyles(Spinner.PULSE_FRAMES, colors.cap);
        Style pulse = ctx.theme().withBackground(pulseFg[Math.floorMod(ctx.frame(), pulseFg.length)], colors.cap);
        var sb = new StringBuilder();
        sb.append(Theme.colorize(" ", colors.chip));
        sb.append(Theme.colorize(glyph, pulse));
        if (ctx.wedge()) {
            if (title.isEmpty()) {
                sb.append(Theme.colorize(" ", colors.chip));
            } else {
                sb.append(Theme.colorize(" ", colors.chip));
                sb.append(Theme.colorize(title, colors.chip));
                sb.append(Theme.colorize(" ", colors.chip));
            }
        } else {
            sb.append(Theme.colorize(title.isEmpty() ? "  " : " " + title + "  ", colors.chip));
        }
        return sb.toString();
    }

    private String paintCap(RenderContext ctx, ChipColors colors) {
        if (!ctx.wedge() || !ctx.ansi()) return "";
        if (progress != null && progress.look() == Progress.Look.PLAN) {
            // The cap blends into the bar's lead, so it must ask at the bar's OWN width: cell 0's
            // gradient index depends on the cell count, so a narrow bar asked at the default width
            // gets a subtly wrong cap.
            Rgb lead = ProgressBar.shared()
                    .leadColor(progress.numerator(), Math.max(1L, progress.denominator()), progress.segments());
            return Theme.colorize(
                    Glyphs.SEGMENT_END_NERD,
                    ctx.theme().withBackground(ctx.theme().bright(colors.cap), lead));
        }
        return cap(colors.cap, true);
    }

    /**
     * The wedge as one row of a live region: {@link #renderLine} clipped so it cannot wrap.
     *
     * <p><strong>Why live lines must be clipped and static ones need not be.</strong> A live region
     * repaints with {@code \r}, which rewinds the cursor to the start of the <em>current physical
     * row</em> only. Let the line exceed the terminal width and the terminal wraps it onto a second
     * row; {@code \r} then rewinds the wrong row and every subsequent frame lands on a new line, so
     * an 80 ms animator turns into a screenful of stacked half-frames. That is exactly what
     * {@code jk jdk install lts} did: a 40-cell bar plus the {@code JDK} chip plus
     * {@code NN% · Downloading Eclipse Temurin} is wider than an 80-column terminal.
     *
     * <p>A static line, printed once with a newline, may wrap harmlessly — which is why the clip
     * lives here rather than inside {@link #renderLine}: truncating a settled result would throw
     * away the tail of a long install path for no gain.
     *
     * <p>Every live painter should route through this, so the geometry rule has one owner and a
     * chrome change cannot fix the wrap in one command and leave it in the next.
     */
    public String renderLiveLine(RenderContext ctx) {
        return RenderContext.truncateVisible(renderLine(ctx), RenderContext.rowColumnBudget(ctx.width()));
    }

    /**
     * Title bar for a box table: chip + {@code ─…╮} (or plain {@code -…+}) filling {@code
     * totalWidth} visible columns.
     */
    public String renderTitleBar(RenderContext ctx, int totalWidth) {
        Theme theme = ctx.theme();
        String glyph = icon.paint(ctx);
        if (!ctx.ansi()) {
            String head = plainWedge(glyph, title, null) + " ";
            int fill = Math.max(1, totalWidth - head.length() - 1);
            return head + "-".repeat(fill) + "+";
        }
        ChipColors colors = colors(theme);
        String wedge = chip(glyph, title, colors.chip, ctx.wedge()) + cap(colors.cap, ctx.wedge());
        int vis = RenderContext.visibleWidth(wedge);
        int fill = Math.max(1, totalWidth - vis - 1);
        return wedge + Theme.colorize("─".repeat(fill) + "╮", theme.darkGray());
    }

    @Override
    public List<String> render(RenderContext ctx) {
        return List.of(renderLine(ctx));
    }

    private String tailAnsi(RenderContext ctx) {
        String msg = message.isEmpty() ? "" : message.render(ctx);
        if (progress == null) return msg;
        String bar = progress.render(ctx);
        if (msg.isEmpty()) return bar;
        return bar + " " + msg;
    }

    private String tailPlain(RenderContext ctx) {
        String msg = message.isEmpty() ? "" : message.render(ctx);
        if (progress == null) return msg;
        String bar = progress.render(ctx);
        if (msg.isEmpty()) return bar;
        return bar + " " + msg;
    }

    private ChipColors colors(Theme theme) {
        return switch (variant) {
            case OK -> new ChipColors(theme.planSuccessChip(), theme.planChipColor());
            case FAIL -> new ChipColors(theme.planFailureChip(), theme.planFailColor());
            case WARNING ->
                new ChipColors(
                        theme.withBackground(theme.bright(0, 0, 0), JkDarkTheme.NORMAL_YELLOW),
                        JkDarkTheme.NORMAL_YELLOW);
            case CANCELLED -> new ChipColors(theme.scopeBadge(), theme.grayColor());
            case MENU, WORK -> new ChipColors(theme.planChip(), theme.planBadgeColor());
        };
    }

    private record ChipColors(Style chip, Rgb cap) {}

    /**
     * Chip body + trailing pad. One trailing space when the wedge cap follows; two spaces when it
     * does not, the extra pad standing in for the missing cap.
     */
    public static String chip(String glyph, String name, Style chip, boolean wedge) {
        String body = " " + glyph + (name == null || name.isEmpty() ? "" : " " + name);
        String trail = wedge ? " " : "  ";
        return Theme.colorize(body + trail, chip);
    }

    /** The wedge cap {@code U+E0B0} with FG = {@code chipColor}. Empty without the wedge axis. */
    public static String cap(Rgb chipColor, boolean wedge) {
        if (!wedge) return "";
        return Theme.colorize(Glyphs.SEGMENT_END_NERD, Theme.active().bright(chipColor));
    }

    /** Prefix on every plain ({@code --no-ansi}) chrome line so users can tell jk from tool output. */
    public static final String PLAIN_LINE_PREFIX = "jk: ";

    /**
     * Tail of a plain ({@code --no-ansi}) one-line status — the deliberate variants, named, so the
     * spinner and the plan view share one line writer instead of diverging copies.
     */
    public enum PlainTail {
        /** {@code message - working...} (spinner start / heartbeat). */
        WORKING,
        /** {@code message - done.} (settled spinner). */
        DONE,
        /** The bare message (live plan line before progress is known). */
        BARE,
        /** {@code 100% - done} (settled plan line; the message is dropped). */
        PERCENT_DONE
    }

    /**
     * The plain one-line status: {@code "jk: * message - tail"} without a command, {@code "jk: *
     * Command > message - tail"} with one. A blank message falls back to {@code "working"}
     * ({@link PlainTail#PERCENT_DONE} drops the message entirely).
     */
    public static String plainStatusLine(String command, String message, PlainTail tail) {
        String msg = message == null || message.isBlank() ? "working" : message;
        String body =
                switch (tail) {
                    case WORKING -> msg + " - working...";
                    case DONE -> msg + " - done.";
                    case BARE -> msg;
                    case PERCENT_DONE -> "100% - done";
                };
        if (command == null || command.isEmpty()) {
            return PLAIN_LINE_PREFIX + Glyphs.PULSE_PLAIN + " " + body;
        }
        return plainWedge(Glyphs.PULSE_PLAIN, command, body);
    }

    /** {@code "jk: {ascii-icon} {command} >"} optionally followed by {@code " " + message}. */
    public static String plainWedge(String asciiIcon, String command, String message) {
        String cmd = PlainAscii.transform(command == null ? "" : command);
        String icon = asciiIcon == null || asciiIcon.isEmpty() ? Glyphs.BULLET_PLAIN : asciiIcon;
        String head = PLAIN_LINE_PREFIX + icon + " " + cmd + " >";
        if (message == null || message.isEmpty()) return head;
        return head + " " + PlainAscii.transform(message);
    }

    public static JkWedge cancelled(String title, boolean byUser, String tookTail) {
        return cancelled(title, "job", byUser, tookTail);
    }

    /**
     * The cancelled settle line, with {@code subject} naming what was cancelled — {@code "job"} for
     * a build, {@code "JDK download"} for a download.
     *
     * <p>One chrome for every cancel: the gray scope-badge chip, the {@code ⊛} icon, {@code cancelled}
     * in bold, and whatever {@code tookTail} the caller measured (already dark-gray italic if it came
     * from {@code ConsoleSpec.took}). The subject is the only thing that varies, which is why it is a
     * parameter and not a second renderer — the JDK download had grown its own red wedge and red bar,
     * a look that appeared nowhere else in the product.
     */
    public static JkWedge cancelled(String title, String subject, boolean byUser, String tookTail) {
        String what = subject == null || subject.isBlank() ? "job" : subject;
        String took = tookTail == null || tookTail.isBlank() ? "" : " " + tookTail;
        String by = byUser ? " by user" : "";
        if (!Theme.active().isAnsi()) {
            return new JkWedge(
                    Icon.cancelled(),
                    title,
                    RichText.plain(what + " was cancelled" + by + took),
                    Variant.CANCELLED,
                    null);
        }
        String styled = what + " was "
                + Theme.colorize("cancelled", Theme.active().brightWhite().bold())
                + by
                + took;
        return new JkWedge(Icon.cancelled(), title, RichText.ansi(styled), Variant.CANCELLED, null);
    }

    public static String chipLine(String glyph, String command, NerdFontCaps caps, String message) {
        return new JkWedge(Icon.fromGlyph(glyph), command, RichText.ansi(message == null ? "" : message))
                .renderLine(RenderContext.current().withCaps(caps));
    }

    public static String cancelledJobLine(String name, NerdFontCaps caps, boolean byUser, String tookTail) {
        return cancelled(name, byUser, tookTail)
                .renderLine(RenderContext.current().withCaps(caps));
    }

    public static String cancelledJobLine(String name, NerdFontCaps caps, String tookTail) {
        return cancelledJobLine(name, caps, false, tookTail);
    }

    public static String failureLine(String name, NerdFontCaps caps, String tail) {
        return failedTo(name, tail).renderLine(RenderContext.current().withCaps(caps));
    }

    public static String failureLineCustom(String name, NerdFontCaps caps, String sentence) {
        return fail(name, RichText.ansi(sentence == null ? "" : sentence))
                .renderLine(RenderContext.current().withCaps(caps));
    }

    public static String planChip(String glyph, String title, NerdFontCaps caps) {
        return new JkWedge(Icon.fromGlyph(glyph), title, RichText.empty())
                .variant(Variant.MENU)
                .renderLine(RenderContext.current().withCaps(caps));
    }

    public static String plainIconFor(String glyph) {
        return Icon.fromGlyph(glyph).paint(RenderContext.current().withAnsi(false));
    }

    public static JkWedge failedTo(String title, String tail) {
        String verb = title == null || title.isEmpty() ? "" : " to " + title.toLowerCase(Locale.ROOT);
        if (!Theme.active().isAnsi()) {
            return fail(title, "Failed" + verb + " " + tail);
        }
        String body = Theme.colorize("Failed", Theme.active().error()) + verb + " " + tail;
        return fail(title, RichText.ansi(body));
    }
}
