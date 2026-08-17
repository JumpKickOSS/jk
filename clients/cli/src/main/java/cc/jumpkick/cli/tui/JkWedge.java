// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.theme.JkDarkTheme;
import cc.jumpkick.cli.theme.Rgb;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.NerdFontCaps;
import java.util.List;
import java.util.Locale;
import org.jline.utils.AttributedStyle;

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
        // Plan bar sits flush against the nerd cap so the powerline blends into the bar lead.
        if (progress != null && progress.look() == Progress.Look.PLAN && ctx.wedge()) {
            return chip + cap + tail;
        }
        return chip + cap + " " + tail;
    }

    private String paintChip(RenderContext ctx, ChipColors colors, String glyph) {
        if (!(icon instanceof Icon.Spinner) || !ctx.ansi()) {
            return chip(glyph, title, colors.chip, ctx.wedge());
        }
        AttributedStyle[] pulseFg = Spinner.buildChipPulseStyles(Spinner.PULSE_FRAMES, colors.cap);
        AttributedStyle pulse =
                ctx.theme().withBackground(pulseFg[Math.floorMod(ctx.frame(), pulseFg.length)], colors.cap);
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
            Rgb lead = ProgressBar.shared().leadColor(progress.numerator(), Math.max(1L, progress.denominator()));
            return Theme.colorize(
                    Glyphs.SEGMENT_END_NERD,
                    ctx.theme().withBackground(ctx.theme().bright(colors.cap), lead));
        }
        return cap(colors.cap, true);
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

    private record ChipColors(AttributedStyle chip, Rgb cap) {}

    /**
     * Chip body + trailing pad. One trailing space when the wedge cap follows; two spaces when it
     * does not, the extra pad standing in for the missing cap.
     */
    public static String chip(String glyph, String name, AttributedStyle chip, boolean wedge) {
        String body = " " + glyph + (name == null || name.isEmpty() ? "" : " " + name);
        String trail = wedge ? " " : "  ";
        return Theme.colorize(body + trail, chip);
    }

    /** The wedge cap {@code U+E0B0} with FG = {@code chipColor}. Empty without the wedge axis. */
    public static String cap(Rgb chipColor, boolean wedge) {
        if (!wedge) return "";
        return Theme.colorize(Glyphs.SEGMENT_END_NERD, Theme.active().bright(chipColor));
    }

    /** {@code " {ascii-icon} {command} >"} optionally followed by {@code " " + message}. */
    public static String plainWedge(String asciiIcon, String command, String message) {
        String cmd = PlainAscii.transform(command == null ? "" : command);
        String icon = asciiIcon == null || asciiIcon.isEmpty() ? Glyphs.BULLET_PLAIN : asciiIcon;
        String head = " " + icon + " " + cmd + " >";
        if (message == null || message.isEmpty()) return head;
        return head + " " + PlainAscii.transform(message);
    }

    public static JkWedge cancelled(String title, boolean byUser, String tookTail) {
        String took = tookTail == null || tookTail.isBlank() ? "" : " " + tookTail;
        String by = byUser ? " by user" : "";
        if (!Theme.active().isAnsi()) {
            return new JkWedge(
                    Icon.cancelled(), title, RichText.plain("job was cancelled" + by + took), Variant.CANCELLED, null);
        }
        String styled = "job was "
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
