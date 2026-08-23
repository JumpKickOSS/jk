// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal;

import java.util.ArrayList;
import java.util.List;

/** Styled span list. {@link #toAnsi()} concatenates {@link Style#render} — no glyph rewrite. */
public final class Styled {
    private final List<Span> spans;

    Styled(List<Span> spans) {
        this.spans = List.copyOf(spans);
    }

    public static Styled of(String text, Style style) {
        return new Styled(List.of(new Span(text, style)));
    }

    public Styled append(String text, Style style) {
        List<Span> next = new ArrayList<>(spans);
        next.add(new Span(text, style));
        return new Styled(next);
    }

    public Styled append(Styled other) {
        List<Span> next = new ArrayList<>(spans);
        next.addAll(other.spans);
        return new Styled(next);
    }

    public String toAnsi() {
        StringBuilder sb = new StringBuilder();
        for (Span s : spans) {
            sb.append(s.style.render(s.text));
        }
        return sb.toString();
    }

    public String plain() {
        StringBuilder sb = new StringBuilder();
        for (Span s : spans) {
            sb.append(s.text);
        }
        return sb.toString();
    }

    public int columns() {
        return Width.columns(toAnsi());
    }

    @Override
    public String toString() {
        return plain();
    }

    List<Span> spans() {
        return spans;
    }

    record Span(String text, Style style) {}
}
