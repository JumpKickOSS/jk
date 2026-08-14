// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.NerdFontCaps;
import org.junit.jupiter.api.Test;

/**
 * The point of the two axes: each one must emit <em>only</em> its own glyph family.
 *
 * <p>This is the regression guard for the bug the split exists to fix. A font carrying the classic
 * Powerline set has the solid triangles but not the solid semi-circles, so granting {@code wedge}
 * must never leak a pill cap — that would be tofu on exactly the fonts {@code wedge} is for.
 * Asserted on rendered output rather than on the config value, since the config is only correct
 * insofar as the glyphs follow it (JK-1970).
 */
class NerdFontAxisTest {

    private static final String WEDGE_GLYPH = Glyphs.SEGMENT_END_NERD;
    private static final String PILL_LEFT = Glyphs.PILL_LEFT_NERD;
    private static final String PILL_RIGHT = Glyphs.PILL_RIGHT_NERD;

    @Test
    void wedge_only_emits_triangles_and_no_half_circles() {
        if (!Theme.active().isAnsi()) return; // plain mode has no PUA at all; covered elsewhere
        assertThat(wedgeLine(NerdFontCaps.WEDGE_ONLY)).contains(WEDGE_GLYPH);
        assertThat(pillText(NerdFontCaps.WEDGE_ONLY)).doesNotContain(PILL_LEFT).doesNotContain(PILL_RIGHT);
    }

    @Test
    void pill_only_emits_half_circles_and_no_triangles() {
        if (!Theme.active().isAnsi()) return;
        assertThat(pillText(NerdFontCaps.PILL_ONLY)).contains(PILL_LEFT).contains(PILL_RIGHT);
        assertThat(wedgeLine(NerdFontCaps.PILL_ONLY)).doesNotContain(WEDGE_GLYPH);
    }

    @Test
    void all_emits_both_families() {
        if (!Theme.active().isAnsi()) return;
        assertThat(wedgeLine(NerdFontCaps.ALL)).contains(WEDGE_GLYPH);
        assertThat(pillText(NerdFontCaps.ALL)).contains(PILL_LEFT).contains(PILL_RIGHT);
    }

    @Test
    void none_emits_neither_family() {
        if (!Theme.active().isAnsi()) return;
        assertThat(wedgeLine(NerdFontCaps.NONE)).doesNotContain(WEDGE_GLYPH);
        assertThat(pillText(NerdFontCaps.NONE)).doesNotContain(PILL_LEFT).doesNotContain(PILL_RIGHT);
    }

    @Test
    void the_render_context_reports_each_axis_independently() {
        var ctx = RenderContext.current().withCaps(NerdFontCaps.WEDGE_ONLY);
        if (!ctx.ansi()) return;
        assertThat(ctx.wedge()).isTrue();
        assertThat(ctx.pill()).isFalse();

        var flipped = ctx.withCaps(NerdFontCaps.PILL_ONLY);
        assertThat(flipped.wedge()).isFalse();
        assertThat(flipped.pill()).isTrue();
    }

    @Test
    void no_ansi_revokes_both_axes_however_they_were_granted() {
        // The color/ANSI gate is absolute: PUA without color misaligns or renders as blank boxes.
        var ctx = RenderContext.current().withCaps(NerdFontCaps.ALL).withAnsi(false);
        assertThat(ctx.wedge()).isFalse();
        assertThat(ctx.pill()).isFalse();
        assertThat(ctx.mode()).isEqualTo(RenderContext.Mode.PLAIN);
        // and granting caps while ANSI is off must not resurrect them
        assertThat(ctx.withCaps(NerdFontCaps.ALL).nerd()).isEqualTo(NerdFontCaps.NONE);
    }

    @Test
    void mode_reports_nerd_for_either_axis_alone() {
        var ctx = RenderContext.current();
        if (!ctx.ansi()) return;
        assertThat(ctx.withCaps(NerdFontCaps.WEDGE_ONLY).mode()).isEqualTo(RenderContext.Mode.NERD);
        assertThat(ctx.withCaps(NerdFontCaps.PILL_ONLY).mode()).isEqualTo(RenderContext.Mode.NERD);
        assertThat(ctx.withCaps(NerdFontCaps.ALL).mode()).isEqualTo(RenderContext.Mode.NERD);
        assertThat(ctx.withCaps(NerdFontCaps.NONE).mode()).isEqualTo(RenderContext.Mode.ANSI);
    }

    /** A wedge-bearing chip line, which is where {@code U+E0B0} would appear. */
    private static String wedgeLine(NerdFontCaps caps) {
        return JkWedge.chipLine(Glyphs.CHECK, "Build", caps, "done");
    }

    /** A pill-bearing badge, which is where {@code U+E0B6}/{@code U+E0B4} would appear. */
    private static String pillText(NerdFontCaps caps) {
        return Badge.pill("scope", caps.pill());
    }
}
