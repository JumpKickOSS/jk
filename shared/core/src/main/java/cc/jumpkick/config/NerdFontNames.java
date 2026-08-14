// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.util.Locale;

/**
 * Maps a configured font name to the glyph families it can be trusted to render.
 *
 * <p>Two tiers, mirroring the {@link NerdFontCaps} axes: a Nerd Font carries both the triangles and
 * the semi-circles, whereas a classic Powerline patch carries only the triangles. Calling the latter
 * "no nerd font" (as a binary detector must) needlessly throws away chrome that would render fine.
 *
 * <p>Two deliberate improvements over the reference implementation ({@code has-nerd-font}):
 *
 * <ul>
 *   <li>The {@code Nerd Font} substring test is case-<em>insensitive</em>. The reference is
 *       case-sensitive and so misses lowercased names like {@code jetbrainsmono nerd font} that
 *       occur in real config files.
 *   <li>The {@code NF} suffix test accepts a camel-case boundary, so the concatenated
 *       <em>PostScript</em> names that macOS preferences actually store — {@code
 *       JetBrainsMonoNFM-Regular}, {@code MesloLGSNF-Regular} — are recognised. The reference
 *       requires a space or hyphen on one side and misses all of them, which matters because
 *       PostScript names are precisely what the iTerm2 domain hands us.
 * </ul>
 */
public final class NerdFontNames {

    private NerdFontNames() {}

    /** Nerd Font family suffixes: bare, Mono, and Propo. Checked longest-first so {@code NFM} wins. */
    private static final String[] NERD_TOKENS = {"NFM", "NFP", "NF"};

    /**
     * Caps implied by a font setting, which may be a comma-separated stack. Returns the <em>union</em>
     * across entries: a stack naming {@code Symbols Nerd Font} as a fallback is nerd-capable even
     * when its first entry is a plain font, because the terminal will find the glyph there.
     */
    public static NerdFontCaps caps(String fontSetting) {
        if (fontSetting == null || fontSetting.isBlank()) return NerdFontCaps.NONE;
        NerdFontCaps caps = NerdFontCaps.NONE;
        for (String entry : fontSetting.split(",")) {
            caps = caps.max(capsForSingle(entry));
            if (caps.equals(NerdFontCaps.ALL)) return caps; // cannot improve
        }
        return caps;
    }

    /** Caps for one font name — no comma splitting. */
    private static NerdFontCaps capsForSingle(String font) {
        if (font == null) return NerdFontCaps.NONE;
        // Drop CSS-ish quoting. A trailing point size ("Menlo-Regular 13") is harmless to every
        // test below, so it is left in place rather than parsed off.
        String s = font.replace('"', ' ').replace('\'', ' ').trim();
        if (s.isEmpty()) return NerdFontCaps.NONE;
        String lower = s.toLowerCase(Locale.ROOT);

        if (lower.contains("nerd font") || lower.contains("nerdfont")) return NerdFontCaps.ALL;
        for (String token : NERD_TOKENS) {
            if (hasSuffixToken(s, token)) return NerdFontCaps.ALL;
        }
        // Classic Powerline patch: triangles yes, semi-circles no.
        if (lower.contains("powerline")) return NerdFontCaps.WEDGE_ONLY;
        if (hasSuffixToken(s, "PL")) return NerdFontCaps.WEDGE_ONLY;
        return NerdFontCaps.NONE;
    }

    /**
     * True when the uppercase {@code token} appears in {@code original} as a family-suffix marker:
     * uppercase in the source string, and ending at a delimiter or end-of-string.
     *
     * <p>Accepts {@code MesloLGS NF}, {@code Cascadia Mono PL}, {@code JetBrainsMonoNFM-Regular},
     * and {@code MesloLGSNF-Regular}. Nothing is required of the character <em>before</em> the
     * token, because real PostScript names concatenate the suffix onto both lowercase
     * ({@code MonoNFM}) and uppercase ({@code LGSNF}) runs — so a left-edge rule either rejects
     * half of them or is vacuous.
     *
     * <p>The right edge plus the uppercase requirement carry the discrimination: {@code CONFLUENCE}
     * and {@code NFL Team} both have a non-delimiter after {@code NF}, and {@code Inconsolata} /
     * {@code Info} / {@code IBM Plex} never spell the token in caps at all.
     */
    private static boolean hasSuffixToken(String original, String token) {
        int from = 0;
        while (true) {
            int i = original.indexOf(token, from);
            if (i < 0) return false;
            int end = i + token.length();
            if (end == original.length() || isDelimiter(original.charAt(end))) return true;
            from = i + 1;
        }
    }

    private static boolean isDelimiter(char c) {
        return c == ' ' || c == '-' || c == '_' || c == '.';
    }
}
