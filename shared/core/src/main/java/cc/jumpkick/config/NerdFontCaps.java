// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

/**
 * Which Nerd Font glyph families may be painted. Two axes, because jk's four PUA codepoints
 * split into two groups with materially different availability:
 *
 * <ul>
 *   <li>{@code wedge} — solid triangles {@code U+E0B0} / {@code U+E0B2}, present in essentially
 *       every Powerline-patched font.
 *   <li>{@code pill} — solid semi-circles {@code U+E0B6} / {@code U+E0B4}, present only in Nerd
 *       Font v2+ and Powerline-Extra.
 * </ul>
 *
 * <p>A font carrying only the classic Powerline set renders the triangles perfectly and the
 * semi-circles as tofu, so one boolean cannot describe it: the honest answer is {@link #WEDGE_ONLY}.
 * That is the whole reason this is a pair of flags rather than a flag.
 */
public record NerdFontCaps(boolean wedge, boolean pill) {

    /** No PUA at all — ASCII/ANSI chrome only. */
    public static final NerdFontCaps NONE = new NerdFontCaps(false, false);

    /** Both families — a full Nerd Font. */
    public static final NerdFontCaps ALL = new NerdFontCaps(true, true);

    /** Triangles only — a classic Powerline-patched font. */
    public static final NerdFontCaps WEDGE_ONLY = new NerdFontCaps(true, false);

    /** Semi-circles only — the inverse, selectable but not something a font naturally is. */
    public static final NerdFontCaps PILL_ONLY = new NerdFontCaps(false, true);

    /** True when any PUA glyph is permitted. Coarse; widgets must ask for a specific axis. */
    public boolean any() {
        return wedge || pill;
    }

    /**
     * Union of two capability sets. Used when a font stack names several families — a stack whose
     * fallback is {@code Symbols Nerd Font} is nerd-capable even if its first entry is not.
     */
    public NerdFontCaps max(NerdFontCaps other) {
        if (other == null) return this;
        return new NerdFontCaps(wedge || other.wedge, pill || other.pill);
    }
}
