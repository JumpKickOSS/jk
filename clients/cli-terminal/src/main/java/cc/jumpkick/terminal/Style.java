// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Immutable SGR style. Attribute-leading emission only; never rewrites glyphs.
 *
 * <p>A class, not a record: fluent {@code bold()}/{@code dim()}/{@code italic()} must return
 * {@code Style}, which cannot coexist with record accessors of the same names.
 */
public final class Style {
    public static final Style EMPTY = new Style(null, null, false, false, false, false, false);

    private final @Nullable RgbInts fg;
    private final @Nullable RgbInts bg;
    private final boolean bold;
    private final boolean dim;
    private final boolean italic;
    private final boolean underline;
    private final boolean strike;

    Style(
            @Nullable RgbInts fg,
            @Nullable RgbInts bg,
            boolean bold,
            boolean dim,
            boolean italic,
            boolean underline,
            boolean strike) {
        this.fg = fg;
        this.bg = bg;
        this.bold = bold;
        this.dim = dim;
        this.italic = italic;
        this.underline = underline;
        this.strike = strike;
    }

    public boolean isBold() {
        return bold;
    }

    public boolean isDim() {
        return dim;
    }

    public boolean isItalic() {
        return italic;
    }

    public boolean isUnderline() {
        return underline;
    }

    public boolean isStrike() {
        return strike;
    }

    public Style bold() {
        return new Style(fg, bg, true, dim, italic, underline, strike);
    }

    public Style faint() {
        return new Style(fg, bg, bold, true, italic, underline, strike);
    }

    /** Same flag as {@link #faint()}; Theme call sites chain {@code dim().crossedOut()}. */
    public Style dim() {
        return faint();
    }

    public Style italic() {
        return new Style(fg, bg, bold, dim, true, underline, strike);
    }

    public Style underline() {
        return new Style(fg, bg, bold, dim, italic, true, strike);
    }

    public Style crossedOut() {
        return new Style(fg, bg, bold, dim, italic, underline, true);
    }

    public Style foreground(int r, int g, int b) {
        return new Style(new RgbInts(r, g, b), bg, bold, dim, italic, underline, strike);
    }

    public Style background(int r, int g, int b) {
        return new Style(fg, new RgbInts(r, g, b), bold, dim, italic, underline, strike);
    }

    /** Non-null color from {@code over} wins; attribute flags OR. */
    public Style merge(Style over) {
        return new Style(
                over.fg != null ? over.fg : fg,
                over.bg != null ? over.bg : bg,
                bold || over.bold,
                dim || over.dim,
                italic || over.italic,
                underline || over.underline,
                strike || over.strike);
    }

    /** SGR body, attribute-leading: 1, 2, 3, 4, 9, then fg, then bg. */
    public String sgrBody() {
        StringBuilder sb = new StringBuilder();
        if (bold) {
            append(sb, "1");
        }
        if (dim) {
            append(sb, "2");
        }
        if (italic) {
            append(sb, "3");
        }
        if (underline) {
            append(sb, "4");
        }
        if (strike) {
            append(sb, "9");
        }
        if (fg != null) {
            append(sb, "38;2;" + fg.r() + ";" + fg.g() + ";" + fg.b());
        }
        if (bg != null) {
            append(sb, "48;2;" + bg.r() + ";" + bg.g() + ";" + bg.b());
        }
        return sb.toString();
    }

    /**
     * {@code CSI + sgrBody + m + text + RESET}. Identity on {@code text} when body is empty. Never
     * rewrites box-drawing. Never requires a Terminal.
     */
    public String render(String text) {
        String body = sgrBody();
        if (body.isEmpty()) {
            return text;
        }
        return Ansi.sgr(body) + text + Ansi.RESET;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Style other)) {
            return false;
        }
        return bold == other.bold
                && dim == other.dim
                && italic == other.italic
                && underline == other.underline
                && strike == other.strike
                && Objects.equals(fg, other.fg)
                && Objects.equals(bg, other.bg);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fg, bg, bold, dim, italic, underline, strike);
    }

    @Override
    public String toString() {
        return "Style[" + sgrBody() + "]";
    }

    private static void append(StringBuilder sb, String part) {
        if (!sb.isEmpty()) {
            sb.append(';');
        }
        sb.append(part);
    }
}
