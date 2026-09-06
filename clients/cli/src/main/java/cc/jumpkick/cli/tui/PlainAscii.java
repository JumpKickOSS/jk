// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.theme.Theme;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;

/**
 * Unicode → ASCII rewrites for {@code --no-ansi} / plain human chrome.
 *
 * <p>Wedges already pick ASCII icons via {@link Glyphs} helpers, but free-form messages still
 * embed ellipsis, bullets, and pulse circles (e.g. {@code Locking g:n…}, {@code building…}).
 * Call {@link #apply(String)} at print boundaries so every human line is ASCII-clean without
 * each call site hand-substituting.
 *
 * <h2>Mapping</h2>
 *
 * <table>
 * <caption>Glyph fallbacks</caption>
 * <tr><th>Unicode</th><th>ASCII</th></tr>
 * <tr><td>… (ellipsis)</td><td>{@code ...}</td></tr>
 * <tr><td>• (bullet)</td><td>{@code -}</td></tr>
 * <tr><td>● (black circle / pulse)</td><td>{@code *}</td></tr>
 * <tr><td>· (middle dot)</td><td>{@code -}</td></tr>
 * <tr><td>□ (unchecked box)</td><td>{@code [ ]}</td></tr>
 * <tr><td>⊛ (circled asterisk / cancelled)</td><td>{@code o}</td></tr>
 * <tr><td>— (em dash)</td><td>{@code --}</td></tr>
 * <tr><td>✓ / ✘ / ‼ / ▶ / ■ / ≡</td><td>{@code +} / {@code !} / {@code !} / {@code >} /
 * {@code x} / {@code =}</td></tr>
 * </table>
 *
 * <p>When {@link Theme#isAnsi()} is true, {@link #apply} is identity. Use {@link #wrap(PrintStream)}
 * for APIs that need a stream (JkManager, Spinner).
 */
public final class PlainAscii {

    private PlainAscii() {}

    /**
     * Rewrite known Unicode chrome to ASCII when the active theme is plain; otherwise return
     * {@code text} unchanged. Null-safe.
     */
    public static @Nullable String apply(@Nullable String text) {
        if (text == null || text.isEmpty()) return text;
        if (Theme.active().isAnsi()) return text;
        return transform(text);
    }

    /**
     * Unconditional rewrite (for tests and for call sites that already know they are in plain
     * mode). Null-safe.
     */
    public static @Nullable String transform(@Nullable String text) {
        return text == null ? null : rewrite(text);
    }

    /** As {@link #transform} for text the caller already has — present in, present out. */
    public static String rewrite(String text) {
        if (text.isEmpty()) return text;
        // Fast path: most lines are already pure ASCII.
        if (isAscii(text)) return text;
        StringBuilder sb = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            switch (cp) {
                case 0x2026 -> sb.append("..."); // …
                case 0x2022 -> sb.append('-'); // • bullet
                case 0x25CF -> sb.append('*'); // ● black circle / pulse
                case 0x25CB -> sb.append('o'); // ○ white circle / radio off
                case 0x203A -> sb.append('>'); // › single right angle quote / separator
                case 0x2502 -> sb.append('|'); // │ light vertical bar / gutter rail
                case 0x2713, 0x2714 -> sb.append(Glyphs.CHECK_PLAIN); // ✓ ✔
                case 0x2718, 0x2717 -> sb.append(Glyphs.CROSS_PLAIN); // ✘ ✗
                case 0x203C -> sb.append(Glyphs.BANG_PLAIN); // ‼
                case 0x25B6 -> sb.append(Glyphs.PLAY_PLAIN); // ▶
                case 0x25A0 -> sb.append(Glyphs.STOP_PLAIN); // ■
                case 0x2261 -> sb.append(Glyphs.MENU_PLAIN); // ≡
                case 0x25A1 -> sb.append(Glyphs.PENDING_PLAIN); // □ → [ ]
                case 0x00B7 -> sb.append('-'); // · middle dot (header separators)
                case 0x2812 -> sb.append('-'); // ⠒ braille dots-25 (Ctrl-O peek rule)
                case 0x2191 -> sb.append('^'); // ↑ up arrow (Ctrl-O "↑ output ↑" caption)
                case 0x229B -> sb.append(Glyphs.CANCELLED_PLAIN); // ⊛ circled asterisk (cancelled)
                case 0x2014 -> sb.append("--"); // — em dash (table titles, n/a durations)
                default -> sb.appendCodePoint(cp);
            }
        }
        return sb.toString();
    }

    private static boolean isAscii(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 0x7F) return false;
        }
        return true;
    }

    /** Marker for streams that already ASCII-rewrite their string writes (see {@link #wrap}). */
    public interface Rewriting {}

    /**
     * Return {@code out} unchanged under ANSI, or when it already rewrites its own string writes;
     * otherwise a stream that runs {@link #apply} on every {@code print(String)} /
     * {@code println(String)} so JkManager and Spinner do not need per-call transforms. Wrapping is
     * idempotent by design: a second layer would re-encode the bytes as well as re-transform them.
     */
    public static @Nullable PrintStream wrap(@Nullable PrintStream out) {
        return out == null ? null : wrapping(out);
    }

    /** As {@link #wrap} for a stream the caller already has — present in, present out. */
    public static PrintStream wrapping(PrintStream out) {
        if (Theme.active().isAnsi()) return out;
        if (out instanceof Rewriting) return out;
        return new PlainPrintStream(out);
    }

    /** PrintStream that ASCII-rewrites string writes under plain mode. */
    private static final class PlainPrintStream extends PrintStream implements Rewriting {
        PlainPrintStream(@Nullable PrintStream delegate) {
            super(delegate, true, StandardCharsets.UTF_8);
        }

        @Override
        public void print(@Nullable String s) {
            super.print(apply(s));
        }

        @Override
        public void println(@Nullable String s) {
            // Subclass path: print + newline (avoid double-apply via super.println → print).
            print(s);
            println();
        }
    }
}
