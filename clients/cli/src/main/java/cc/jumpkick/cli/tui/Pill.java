// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.theme.JkDarkTheme;
import cc.jumpkick.cli.theme.Rgb;
import cc.jumpkick.cli.theme.Theme;
import java.util.List;
import org.jline.utils.AttributedStyle;

/**
 * A small chip label. Nerd: powerline half-circles. ANSI: padded background. Plain: {@code
 * [label]}.
 */
public final class Pill implements Widget {

    public enum Look {
        /** Gray scope chip ({@code Fully Cached} / {@code Rebuild} / {@code jk tree} scopes). */
        DEFAULT,
        /** White on plan blue — module name chips. */
        BRANDED,
        /** Black on success green — settled {@code jk jobs} rows. */
        SUCCESS,
        /** Black on failure red. */
        FAIL,
        /** Black on warning amber — cancelled jobs. */
        WARNING,
        /** White on plan blue — running jobs. */
        RUNNING
    }

    private final RichText label;
    private final AttributedStyle body;
    private final AttributedStyle caps;
    private final Look look;

    public Pill(RichText label) {
        this(label, null, null, Look.DEFAULT);
    }

    public Pill(RichText label, AttributedStyle body, AttributedStyle caps) {
        this(label, body, caps, Look.DEFAULT);
    }

    private Pill(RichText label, AttributedStyle body, AttributedStyle caps, Look look) {
        this.label = label == null ? RichText.empty() : label;
        this.body = body;
        this.caps = caps;
        this.look = look == null ? Look.DEFAULT : look;
    }

    public static Pill of(String label) {
        return new Pill(RichText.plain(label == null ? "" : label));
    }

    public static Pill of(RichText label) {
        return new Pill(label);
    }

    /** Name-only module chip: white on plan blue, nerd half-circle caps. */
    public static Pill branded(String label) {
        return new Pill(RichText.plain(label == null ? "" : label), null, null, Look.BRANDED);
    }

    public static Pill branded(RichText label) {
        return new Pill(label, null, null, Look.BRANDED);
    }

    public static Pill success(String label) {
        return new Pill(RichText.plain(label == null ? "" : label), null, null, Look.SUCCESS);
    }

    public static Pill fail(String label) {
        return new Pill(RichText.plain(label == null ? "" : label), null, null, Look.FAIL);
    }

    public static Pill warning(String label) {
        return new Pill(RichText.plain(label == null ? "" : label), null, null, Look.WARNING);
    }

    public static Pill running(String label) {
        return new Pill(RichText.plain(label == null ? "" : label), null, null, Look.RUNNING);
    }

    public Look look() {
        return look;
    }

    /** Single-line form for embedding in another widget. */
    public String renderInline(RenderContext ctx) {
        String text = label.plainText();
        if (ctx.mode() == RenderContext.Mode.PLAIN) {
            return "[" + PlainAscii.transform(text) + "]";
        }
        Theme theme = ctx.theme();
        AttributedStyle chip;
        AttributedStyle ends;
        if (body != null) {
            chip = body;
            ends = caps != null ? caps : theme.gray();
        } else {
            Fill fill = fill(look, theme);
            chip = fill.chip();
            ends = fill.caps();
        }
        return Badge.pill(text, ctx.pill(), chip, ends);
    }

    private record Fill(AttributedStyle chip, AttributedStyle caps) {}

    private static Fill fill(Look look, Theme theme) {
        return switch (look) {
            case DEFAULT -> new Fill(theme.scopeBadge(), theme.gray());
            case BRANDED, RUNNING -> new Fill(theme.planChip(), theme.bright(theme.planBadgeColor()));
            case SUCCESS -> filled(theme, theme.planChipColor(), false);
            case FAIL -> filled(theme, theme.planFailColor(), false);
            case WARNING -> filled(theme, JkDarkTheme.NORMAL_YELLOW, false);
        };
    }

    private static Fill filled(Theme theme, Rgb rgb, boolean whiteInk) {
        AttributedStyle ink = whiteInk ? theme.bright(255, 255, 255) : theme.bright(0, 0, 0);
        return new Fill(theme.withBackground(ink, rgb), theme.bright(rgb));
    }

    @Override
    public List<String> render(RenderContext ctx) {
        return List.of(renderInline(ctx));
    }
}
