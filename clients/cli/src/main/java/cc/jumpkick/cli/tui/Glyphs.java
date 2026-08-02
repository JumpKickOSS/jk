// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.theme.Theme;

/**
 * Single source of truth for the status/marker glyphs used across jk's terminal UI. These are just
 * the codepoints — color is applied separately via the theme (e.g. {@code
 * Theme.colorize(Glyphs.CHECK, Theme.active().success())}).
 *
 * <p>Unicode forms are for ANSI/nerd modes. Prefer {@link #check()}, {@link #cross()}, etc. when
 * emitting markers outside {@link PipelineWedge} so {@code --no-ansi} stays ASCII-only. Free-form
 * message text is rewritten at print time by {@link PlainAscii} (ellipsis, bullets, pulse).
 */
public final class Glyphs {

    private Glyphs() {}

    /** Success marker — heavy check mark. Paint with {@code Theme.success()}. */
    public static final String CHECK = "✓";

    /** Error marker — U+2718 heavy ballot X. Paint with {@code Theme.error()}. */
    public static final String CROSS = "✘";

    /** Warning marker — U+203C double exclamation. Paint with {@code Theme.warning()}. */
    public static final String BANG = "‼";

    /** Pending / active step-row marker — white square (Neutral East Asian Width; avoids the
     * Ambiguous-width medium square, which some terminal/font combos render double-wide). */
    public static final String PENDING = "□";

    /** Run/exec marker — right-pointing triangle. Paint with {@code Theme.brightGreen()}. */
    public static final String PLAY = "▶";

    /** Stop marker — black square (the counterpart to {@link #PLAY}). */
    public static final String STOP = "■";

    /** List-item bullet — for detail lines under a wedge. Paint dim. */
    public static final String BULLET = "•";

    /**
     * Table / menu marker — U+2261 identical to (triple bar). Used on blue {@link CommandWedge}
     * chips that title a box-drawn table ({@link BoxTable#titleBar}).
     */
    public static final String MENU = "≡";

    /** Pulse / spinner circle (U+25CF) — open and chip spinners. */
    public static final String PULSE = "●";

    // ASCII fallbacks for --no-ansi / plain mode (JK-1376).
    public static final String CHECK_PLAIN = "+";
    public static final String CROSS_PLAIN = "!";
    public static final String BANG_PLAIN = "!";
    /** Unchecked / pending box under plain — bracket form so it still reads as a checkbox. */
    public static final String PENDING_PLAIN = "[ ]";
    public static final String PLAY_PLAIN = ">";
    public static final String STOP_PLAIN = "x";
    /** List bullet under plain — dash, distinct from pulse {@link #PULSE_PLAIN} {@code *}. */
    public static final String BULLET_PLAIN = "-";
    public static final String MENU_PLAIN = "=";
    /** Spinner / black-circle under plain. */
    public static final String PULSE_PLAIN = "*";

    /** Progress bar filled cell (plain ASCII). */
    public static final char BAR_FULL_PLAIN = '#';

    /** Progress bar empty cell (plain ASCII). */
    public static final char BAR_EMPTY_PLAIN = '-';

    // Nerd Font powerline pill caps for badges (gated on [global].nerdfont).
    // Paint the cap in the badge's *background* color (as foreground) so it reads
    // as the chip's rounded edge. Without a Nerd Font there's no good half-circle,
    // so badges fall back to a plain padded chip (no caps).
    /** Nerd Font powerline left solid half-circle (U+E0B6). */
    public static final String PILL_LEFT_NERD = "";

    /** Nerd Font powerline right solid half-circle (U+E0B4). */
    public static final String PILL_RIGHT_NERD = "";

    /** Nerd Font powerline right-pointing segment terminator / arrow (U+E0B0). */
    public static final String SEGMENT_END_NERD = "";

    /** Nerd Font powerline left-pointing segment terminator / arrow (U+E0B2). */
    public static final String SEGMENT_BACK_NERD = "";

    /** {@link #CHECK} or {@link #CHECK_PLAIN} depending on {@link Theme#isAnsi()}. */
    public static String check() {
        return Theme.active().isAnsi() ? CHECK : CHECK_PLAIN;
    }

    /** {@link #CROSS} or {@link #CROSS_PLAIN}. */
    public static String cross() {
        return Theme.active().isAnsi() ? CROSS : CROSS_PLAIN;
    }

    /** {@link #BANG} or {@link #BANG_PLAIN}. */
    public static String bang() {
        return Theme.active().isAnsi() ? BANG : BANG_PLAIN;
    }

    /** {@link #PLAY} or {@link #PLAY_PLAIN}. */
    public static String play() {
        return Theme.active().isAnsi() ? PLAY : PLAY_PLAIN;
    }

    /** {@link #BULLET} or {@link #BULLET_PLAIN}. */
    public static String bullet() {
        return Theme.active().isAnsi() ? BULLET : BULLET_PLAIN;
    }

    /** {@link #MENU} or {@link #MENU_PLAIN}. */
    public static String menu() {
        return Theme.active().isAnsi() ? MENU : MENU_PLAIN;
    }

    /** {@link #PULSE} or {@link #PULSE_PLAIN}. */
    public static String pulse() {
        return Theme.active().isAnsi() ? PULSE : PULSE_PLAIN;
    }
}
