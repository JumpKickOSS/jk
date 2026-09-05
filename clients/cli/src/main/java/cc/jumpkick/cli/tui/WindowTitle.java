// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.Osc;
import cc.jumpkick.cli.theme.Theme;
import java.io.PrintStream;
import org.jspecify.annotations.Nullable;

/**
 * The terminal window/tab title (OSC 0) for the life of a live region, prefixed with a half-circle
 * spinner glyph that swaps on its own 500 ms cadence — independent of the frame clock, so a frame
 * paint re-emits the title only when the glyph actually changed.
 *
 * <p>Needs no lock of its own. The package convention: an owner extracted from {@link JkManager}
 * either keeps no state a paint could tear — this one's callers all hold the manager's monitor
 * already — or receives that monitor and synchronizes on it exactly where the manager did. Only an
 * interactive ANSI terminal gets OSC 0: under pipes, {@code --quiet} or {@code --no-ansi} the
 * escapes would land verbatim in the output stream, so {@link #set} is a no-op there.
 */
final class WindowTitle {

    /** First half-circle glyph of the spinner. */
    static final String GLYPH_A = "◐";

    /** Second half-circle glyph of the spinner. */
    static final String GLYPH_B = "◑";

    /** Glyph swap interval. */
    static final long SWAP_MS = 500L;

    private final PrintStream out;
    private final boolean animate;

    /** Title base (no glyph), e.g. {@code JumpKick - Building g:a:v...}. */
    private String base = "";

    /** Last glyph written; null until the first emit. Package-private: the OSC test rewinds it. */
    String lastGlyph;

    /** Wall time of the last glyph swap; reset by {@link #set}. Package-private for the same test. */
    long lastSwapMs;

    /** True after {@link #set} until {@link #clear}. */
    private boolean active;

    WindowTitle(PrintStream out, boolean animate) {
        this.out = out;
        this.animate = animate;
    }

    /** Set the title and emit it now with the first glyph; cleared on settle / dismiss / cancel / close. */
    void set(@Nullable String title) {
        if (!animate || !Theme.active().isAnsi() || !Osc.oscEnabled()) return;
        base = title == null ? "" : title;
        active = !base.isEmpty();
        lastGlyph = null; // force immediate emit with the first glyph
        lastSwapMs = System.currentTimeMillis();
        emitIfDue(lastSwapMs);
        out.flush();
    }

    /** Emit OSC 0 with {@code glyph + " " + base} when the half-circle phase swaps. */
    void emitIfDue(long nowMs) {
        if (!active || base.isEmpty()) return;
        if (lastGlyph != null && nowMs - lastSwapMs < SWAP_MS) return;
        String glyph = GLYPH_A.equals(lastGlyph) ? GLYPH_B : GLYPH_A;
        lastGlyph = glyph;
        lastSwapMs = nowMs;
        out.print(Osc.windowTitle(glyph + " " + base));
    }

    /** Clear a title set by {@link #set}, if any. */
    void clear() {
        if (!active) return;
        active = false;
        base = "";
        lastGlyph = null;
        lastSwapMs = 0L;
        out.print(Osc.windowTitleClear());
    }
}
