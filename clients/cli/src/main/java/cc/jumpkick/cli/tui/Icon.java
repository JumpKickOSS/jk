// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

/**
 * A wedge / table icon: a static glyph pair, or a live spinner that paints one pulse frame.
 */
public sealed interface Icon {

    record Glyph(String unicode, String ascii) implements Icon {
        public Glyph {
            unicode = unicode == null ? "" : unicode;
            ascii = ascii == null || ascii.isEmpty() ? Glyphs.BULLET_PLAIN : ascii;
        }
    }

    /** Animated pulse ({@code ●} / {@code *}). Frame comes from {@link RenderContext#frame()}. */
    record Spinner() implements Icon {}

    static Icon check() {
        return new Glyph(Glyphs.CHECK, Glyphs.CHECK_PLAIN);
    }

    static Icon cross() {
        return new Glyph(Glyphs.CROSS, Glyphs.CROSS_PLAIN);
    }

    static Icon bang() {
        return new Glyph(Glyphs.BANG, Glyphs.BANG_PLAIN);
    }

    static Icon cancelled() {
        return new Glyph(Glyphs.CANCELLED, Glyphs.CANCELLED_PLAIN);
    }

    static Icon play() {
        return new Glyph(Glyphs.PLAY, Glyphs.PLAY_PLAIN);
    }

    static Icon menu() {
        return new Glyph(Glyphs.MENU, Glyphs.MENU_PLAIN);
    }

    static Icon pulse() {
        return new Glyph(Glyphs.PULSE, Glyphs.PULSE_PLAIN);
    }

    static Icon stop() {
        return new Glyph(Glyphs.STOP, Glyphs.STOP_PLAIN);
    }

    static Icon spinner() {
        return new Spinner();
    }

    /** Map a historical {@link Glyphs} / ASCII constant onto the typed icon. */
    static Icon fromGlyph(String glyph) {
        if (glyph == null || glyph.isEmpty()) return new Glyph(Glyphs.BULLET, Glyphs.BULLET_PLAIN);
        if (Glyphs.CHECK.equals(glyph) || Glyphs.CHECK_PLAIN.equals(glyph)) return check();
        if (Glyphs.CROSS.equals(glyph) || Glyphs.CROSS_PLAIN.equals(glyph)) return cross();
        if (Glyphs.BANG.equals(glyph) || Glyphs.BANG_PLAIN.equals(glyph)) return bang();
        if (Glyphs.CANCELLED.equals(glyph)) return cancelled();
        if (Glyphs.PLAY.equals(glyph) || Glyphs.PLAY_PLAIN.equals(glyph)) return play();
        if (Glyphs.MENU.equals(glyph) || Glyphs.MENU_PLAIN.equals(glyph)) return menu();
        if (Glyphs.PULSE.equals(glyph) || Glyphs.PULSE_PLAIN.equals(glyph)) return pulse();
        if (Glyphs.STOP.equals(glyph) || Glyphs.STOP_PLAIN.equals(glyph)) return stop();
        return new Glyph(glyph, Glyphs.BULLET_PLAIN);
    }

    /** Visible character for this context (spinner uses the pulse glyph, not a fill-circle). */
    default String paint(RenderContext ctx) {
        return switch (this) {
            case Glyph(var unicode, var ascii) -> ctx.ansi() ? unicode : ascii;
            case Spinner() -> ctx.ansi() ? Glyphs.PULSE : Glyphs.PULSE_PLAIN;
        };
    }

    default JkWedge.Variant defaultVariant() {
        return switch (this) {
            case Glyph(var unicode, var ascii) -> {
                if (Glyphs.CHECK.equals(unicode)
                        || Glyphs.CHECK_PLAIN.equals(ascii)
                        || Glyphs.PLAY.equals(unicode)
                        || Glyphs.PLAY_PLAIN.equals(ascii)) {
                    yield JkWedge.Variant.OK;
                }
                if (Glyphs.CROSS.equals(unicode) || Glyphs.CROSS_PLAIN.equals(ascii)) {
                    yield JkWedge.Variant.FAIL;
                }
                if (Glyphs.BANG.equals(unicode) || Glyphs.BANG_PLAIN.equals(ascii)) {
                    yield JkWedge.Variant.WARNING;
                }
                if (Glyphs.CANCELLED.equals(unicode) || Glyphs.CANCELLED_PLAIN.equals(ascii)) {
                    yield JkWedge.Variant.CANCELLED;
                }
                if (Glyphs.MENU.equals(unicode) || Glyphs.MENU_PLAIN.equals(ascii)) {
                    yield JkWedge.Variant.MENU;
                }
                yield JkWedge.Variant.WORK;
            }
            case Spinner() -> JkWedge.Variant.WORK;
        };
    }
}
