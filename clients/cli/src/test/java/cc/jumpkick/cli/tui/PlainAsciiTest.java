// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.NoAnsi;
import cc.jumpkick.cli.theme.Theme;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Unicode chrome → ASCII under plain / --no-ansi. */
class PlainAsciiTest {

    @Test
    void transform_maps_ellipsis_bullet_and_black_circle() {
        assertThat(PlainAscii.transform("Locking g:n…")).isEqualTo("Locking g:n...");
        assertThat(PlainAscii.transform(" • Label: value")).isEqualTo(" - Label: value");
        assertThat(PlainAscii.transform("● working")).isEqualTo("* working");
        assertThat(PlainAscii.transform("building… · 4%")).isEqualTo("building... - 4%");
        assertThat(PlainAscii.transform("□ pending")).isEqualTo("[ ] pending");
    }

    @Test
    void transform_maps_status_glyphs() {
        assertThat(PlainAscii.transform("✓ done")).isEqualTo("+ done");
        assertThat(PlainAscii.transform("✘ fail")).isEqualTo("! fail");
        assertThat(PlainAscii.transform("‼ warn")).isEqualTo("! warn");
        assertThat(PlainAscii.transform("▶ run")).isEqualTo("> run");
        assertThat(PlainAscii.transform("≡ menu")).isEqualTo("= menu");
        assertThat(PlainAscii.transform("□")).isEqualTo("[ ]");
        assertThat(PlainAscii.transform("·")).isEqualTo("-");
        assertThat(PlainAscii.transform("⊛ cancelled")).isEqualTo("o cancelled");
        assertThat(PlainAscii.transform("Tasks — g:n")).isEqualTo("Tasks -- g:n");
    }

    @Test
    void transform_is_idempotent() {
        String once = PlainAscii.transform("Locking… • ●");
        assertThat(PlainAscii.transform(once)).isEqualTo(once);
        assertThat(once).isEqualTo("Locking... - *");
    }

    @Test
    void apply_is_identity_under_ansi() throws Exception {
        // Only assert when the suite is actually in ANSI mode.
        if (!Theme.active().isAnsi()) return;
        assertThat(PlainAscii.apply("Locking…")).isEqualTo("Locking…");
    }

    @Test
    void apply_rewrites_under_no_ansi() throws Exception {
        NoAnsi.forced(() -> {
            assertThat(PlainAscii.apply("Locking g:n…")).isEqualTo("Locking g:n...");
            assertThat(PlainAscii.apply(" • detail")).isEqualTo(" - detail");
            assertThat(PlainAscii.apply("● pulse")).isEqualTo("* pulse");
            return null;
        });
    }

    @Test
    void wrap_stream_rewrites_println() throws Exception {
        NoAnsi.forced(() -> {
            var buf = new ByteArrayOutputStream();
            PrintStream wrapped = PlainAscii.wrap(new PrintStream(buf, true, StandardCharsets.UTF_8));
            wrapped.println("Waiting for authorization…");
            assertThat(buf.toString(StandardCharsets.UTF_8).trim()).isEqualTo("Waiting for authorization...");
            return null;
        });
    }

    @Test
    void plain_wedge_message_rewrites_ellipsis() throws Exception {
        NoAnsi.forced(() -> {
            assertThat(JkWedge.plainWedge("*", "Lock", "Locking g:n…")).isEqualTo("jk: * Lock > Locking g:n...");
            return null;
        });
    }

    @Test
    void bullet_plain_is_dash_not_star() {
        assertThat(Glyphs.BULLET_PLAIN).isEqualTo("-");
        assertThat(Glyphs.PULSE_PLAIN).isEqualTo("*");
        assertThat(Glyphs.CANCELLED_PLAIN).isEqualTo("o");
    }
}
