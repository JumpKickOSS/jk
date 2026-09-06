// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.terminal.Style;

/**
 * A small "chip" / "pill" label — black text on a gray background, used for {@code jk tree}'s scope
 * sections and {@code jk explain}'s Fully Cached / Rebuild labels so the two read consistently.
 *
 * <p>When the <em>pill</em> axis is granted (see {@link cc.jumpkick.config.NerdFontCaps}) the chip is
 * rounded into a pill: powerline half-circle caps {@code U+E0B6} / {@code U+E0B4} (drawn in the
 * chip's background color) flank the bare label. Otherwise the label is space-padded to give the
 * chip width. Those two codepoints exist only in Nerd Font v2+ / Powerline-Extra, which is why they
 * are gated separately from the wedge triangles.
 */
public final class Badge {

    private Badge() {}

    /** The shared gray scope/index chip. */
    public static String pill(String label, boolean pillCaps) {
        Theme t = Theme.active();
        return pill(label, pillCaps, t.scopeBadge(), t.gray());
    }

    /**
     * A chip styled with {@code body} (its background defines the chip color); the Nerd Font pill
     * caps are painted with {@code caps} — pass a style whose <em>foreground</em> matches the chip's
     * background so they read as rounded edges.
     */
    public static String pill(String label, boolean pillCaps, Style body, Style caps) {
        if (pillCaps) {
            return Theme.paint(Glyphs.PILL_LEFT_NERD, caps)
                    + Theme.paint(label, body)
                    + Theme.paint(Glyphs.PILL_RIGHT_NERD, caps);
        }
        return Theme.paint(" " + label + " ", body);
    }
}
