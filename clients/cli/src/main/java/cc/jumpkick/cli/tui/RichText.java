// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.theme.Rgb;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.terminal.Ansi;
import cc.jumpkick.terminal.Style;
import cc.jumpkick.terminal.Width;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Styled inline text as a span list — <em>what</em> to show, not SGR. Markup is a constructor
 * ({@link #parse}); the stored form is spans.
 *
 * <p>Markup (Spectre-style): {@code [success]ok[/]}, {@code [bold yellow]x[/]}, {@code [#3D9BFF]x[/]},
 * {@code [link https://example]text[/]}. {@code [[} is a literal {@code [}. Unknown tags throw
 * {@link ParseException}.
 */
public final class RichText {

    private static final RichText EMPTY = new RichText(List.of());

    private final List<Span> spans;

    private RichText(List<Span> spans) {
        this.spans = List.copyOf(spans);
    }

    public static RichText empty() {
        return EMPTY;
    }

    /** Escape {@code [} so user text can be embedded in markup. */
    public static String escape(String text) {
        if (text == null || text.isEmpty()) return "";
        return text.replace("[", "[[");
    }

    /** One run of text in a named theme/CSS color. */
    public static RichText styled(String text, String colorName) {
        if (text == null || text.isEmpty()) return EMPTY;
        return parse("[" + colorName + "]" + escape(text) + "[/]");
    }

    /** Unstyled text. Does not parse markup — use {@link #parse} for tags. */
    public static RichText plain(String text) {
        if (text == null || text.isEmpty()) return EMPTY;
        return new RichText(List.of(new Span(text, MarkupStyle.EMPTY, null, false)));
    }

    /**
     * Already-styled ANSI (or OSC-8) from a leftover {@code Theme.colorize} call. Width still
     * strips CSI. Prefer {@link #parse} for new call sites. Never pass untrusted user text here.
     */
    public static RichText ansi(String alreadyStyled) {
        if (alreadyStyled == null || alreadyStyled.isEmpty()) return EMPTY;
        return new RichText(List.of(new Span(alreadyStyled, MarkupStyle.EMPTY, null, true)));
    }

    public static RichText of(RichText... parts) {
        if (parts == null || parts.length == 0) return EMPTY;
        var spans = new ArrayList<Span>();
        for (RichText part : parts) {
            if (part != null) spans.addAll(part.spans);
        }
        return spans.isEmpty() ? EMPTY : new RichText(spans);
    }

    public RichText plus(RichText other) {
        if (other == null || other.spans.isEmpty()) return this;
        if (spans.isEmpty()) return other;
        var out = new ArrayList<Span>(spans.size() + other.spans.size());
        out.addAll(spans);
        out.addAll(other.spans);
        return new RichText(out);
    }

    public boolean isEmpty() {
        return spans.isEmpty() || plainText().isEmpty();
    }

    /** Visible text with markup / CSI / OSC stripped (hyperlink URLs are not visible text). */
    public String plainText() {
        var sb = new StringBuilder();
        for (Span span : spans) {
            sb.append(RenderContext.stripAnsi(span.text));
        }
        return sb.toString();
    }

    public int visibleWidth() {
        return RenderContext.visibleWidth(plainText());
    }

    public String render() {
        return render(RenderContext.current());
    }

    public String render(RenderContext ctx) {
        if (spans.isEmpty()) return "";
        var sb = new StringBuilder();
        for (Span span : spans) {
            sb.append(paint(span, ctx));
        }
        return sb.toString();
    }

    private static String paint(Span span, RenderContext ctx) {
        String text = span.text;
        if (span.prestyled) {
            if (!ctx.ansi()) return PlainAscii.transform(Width.stripAnsi(text));
            return text;
        }
        if (!ctx.ansi()) {
            return PlainAscii.transform(text);
        }
        Style style = span.style.toAttributed(ctx.theme());
        String painted = Theme.colorize(text, style);
        if (span.linkUrl != null && !span.linkUrl.isBlank()) {
            return Ansi.hyperlink(span.linkUrl, painted);
        }
        return painted;
    }

    public static RichText parse(String markup) {
        if (markup == null || markup.isEmpty()) return EMPTY;
        var spans = new ArrayList<Span>();
        var stack = new ArrayList<Tag>();
        var buf = new StringBuilder();
        int i = 0;
        while (i < markup.length()) {
            char c = markup.charAt(i);
            if (c == '[' && i + 1 < markup.length() && markup.charAt(i + 1) == '[') {
                buf.append('[');
                i += 2;
                continue;
            }
            if (c == '[') {
                flush(buf, stack, spans);
                int close = markup.indexOf(']', i + 1);
                if (close < 0) {
                    throw new ParseException(i, markup.substring(i), "unclosed '['");
                }
                String body = markup.substring(i + 1, close);
                int tagAt = i;
                i = close + 1;
                if (body.equals("/")) {
                    if (stack.isEmpty()) {
                        throw new ParseException(tagAt, "[/]", "unmatched closer");
                    }
                    stack.removeLast();
                    continue;
                }
                if (body.startsWith("/")) {
                    throw new ParseException(tagAt, "[" + body + "]", "use [/] to close, not a named closer");
                }
                stack.add(parseTag(body, tagAt));
                continue;
            }
            buf.append(c);
            i++;
        }
        flush(buf, stack, spans);
        if (!stack.isEmpty()) {
            Tag open = stack.getLast();
            throw new ParseException(markup.length(), open.raw, "unclosed tag");
        }
        return spans.isEmpty() ? EMPTY : new RichText(spans);
    }

    private static void flush(StringBuilder buf, List<Tag> stack, List<Span> spans) {
        if (buf.isEmpty()) return;
        MarkupStyle style = MarkupStyle.EMPTY;
        String link = null;
        for (Tag tag : stack) {
            style = style.merge(tag.style);
            if (tag.linkUrl != null) link = tag.linkUrl;
        }
        spans.add(new Span(buf.toString(), style, link, false));
        buf.setLength(0);
    }

    private static Tag parseTag(String body, int index) {
        String raw = "[" + body + "]";
        String trimmed = body.trim();
        if (trimmed.isEmpty()) {
            throw new ParseException(index, raw, "empty tag");
        }
        String[] parts = trimmed.split("\\s+");
        if (parts[0].equalsIgnoreCase("link")) {
            if (parts.length < 2) {
                throw new ParseException(index, raw, "[link] requires a URL");
            }
            String url = trimmed.substring(parts[0].length()).trim();
            return new Tag(raw, MarkupStyle.EMPTY, url);
        }
        MarkupStyle style = MarkupStyle.EMPTY;
        for (String part : parts) {
            style = applyToken(style, part, index, raw);
        }
        return new Tag(raw, style, null);
    }

    private static MarkupStyle applyToken(MarkupStyle style, String token, int index, String raw) {
        String key = token.toLowerCase(Locale.ROOT);
        return switch (key) {
            case "bold" -> style.withBold();
            case "italic" -> style.withItalic();
            case "underline" -> style.withUnderline();
            case "dim" -> style.withDim();
            case "strike", "strikethrough" -> style.withStrike();
            default -> {
                if (key.startsWith("#")) {
                    yield style.withFg(new HexColor(parseHex(token, index, raw)));
                }
                if (Theme.active().styleNamedOrNull(key) == null) {
                    throw new ParseException(index, token, "unknown style name");
                }
                yield style.withFg(new NamedColor(key));
            }
        };
    }

    private static int parseHex(String token, int index, String raw) {
        String hex = token.startsWith("#") ? token.substring(1) : token;
        if (hex.length() == 3) {
            char r = hex.charAt(0), g = hex.charAt(1), b = hex.charAt(2);
            hex = "" + r + r + g + g + b + b;
        }
        if (hex.length() != 6 || !hex.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
            throw new ParseException(index, token, "expected #RGB or #RRGGBB");
        }
        return Integer.parseInt(hex, 16);
    }

    private record Span(String text, MarkupStyle style, String linkUrl, boolean prestyled) {}

    private record Tag(String raw, MarkupStyle style, String linkUrl) {}

    private sealed interface Color permits NamedColor, HexColor {}

    private record NamedColor(String token) implements Color {}

    private record HexColor(int rgb) implements Color {}

    private record MarkupStyle(Color fg, boolean bold, boolean italic, boolean underline, boolean dim, boolean strike) {

        static final MarkupStyle EMPTY = new MarkupStyle(null, false, false, false, false, false);

        MarkupStyle withFg(Color color) {
            return new MarkupStyle(color, bold, italic, underline, dim, strike);
        }

        MarkupStyle withBold() {
            return new MarkupStyle(fg, true, italic, underline, dim, strike);
        }

        MarkupStyle withItalic() {
            return new MarkupStyle(fg, bold, true, underline, dim, strike);
        }

        MarkupStyle withUnderline() {
            return new MarkupStyle(fg, bold, italic, true, dim, strike);
        }

        MarkupStyle withDim() {
            return new MarkupStyle(fg, bold, italic, underline, true, strike);
        }

        MarkupStyle withStrike() {
            return new MarkupStyle(fg, bold, italic, underline, dim, true);
        }

        MarkupStyle merge(MarkupStyle over) {
            return new MarkupStyle(
                    over.fg != null ? over.fg : fg,
                    bold || over.bold,
                    italic || over.italic,
                    underline || over.underline,
                    dim || over.dim,
                    strike || over.strike);
        }

        Style toAttributed(Theme theme) {
            Style s =
                    switch (fg) {
                        case HexColor(int rgb) -> theme.bright(Rgb.hex(rgb));
                        case NamedColor(String token) -> theme.styleNamed(token);
                        case null -> Style.EMPTY;
                    };
            if (bold) s = s.bold();
            if (italic) s = s.italic();
            if (underline) s = s.underline();
            if (dim) s = s.faint();
            if (strike) s = s.crossedOut();
            return s;
        }
    }

    public static final class ParseException extends IllegalArgumentException {
        private final int index;
        private final String token;

        ParseException(int index, String token, String message) {
            super(message + " at " + index + " (" + token + ")");
            this.index = index;
            this.token = token;
        }

        public int index() {
            return index;
        }

        public String token() {
            return token;
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RichText other && spans.equals(other.spans);
    }

    @Override
    public int hashCode() {
        return Objects.hash(spans);
    }

    @Override
    public String toString() {
        return plainText();
    }
}
