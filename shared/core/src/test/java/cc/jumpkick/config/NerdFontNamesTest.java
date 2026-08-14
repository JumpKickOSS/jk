// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Font name → glyph families. The two-tier rule is the whole reason {@code wedge} exists. */
class NerdFontNamesTest {

    @Test
    void nerd_fonts_grant_both_axes() {
        for (String font : new String[] {
            "JetBrainsMono Nerd Font",
            "JetBrainsMono Nerd Font Mono",
            "Symbols Nerd Font",
            "Hack Nerd Font Mono",
            "MesloLGS NF",
            "CaskaydiaCove NFM",
            "MonaspiceNe NFP",
            "Iosevka Nerd Font"
        }) {
            assertThat(NerdFontNames.caps(font)).as(font).isEqualTo(NerdFontCaps.ALL);
        }
    }

    @Test
    void postscript_names_with_a_concatenated_suffix_are_recognised() {
        // macOS preferences store PostScript names, so this is the form we actually read from
        // iTerm2 — and there is no delimiter before the NF. Missing these would defeat the one
        // config source we consult on macOS.
        for (String font : new String[] {
            "JetBrainsMonoNFM-Regular",
            "JetBrainsMonoNFM-Regular 12",
            "MesloLGSNF-Regular",
            "HackNFM-Regular 13",
            "JetBrainsMonoNerdFont-Regular"
        }) {
            assertThat(NerdFontNames.caps(font)).as(font).isEqualTo(NerdFontCaps.ALL);
        }
    }

    @Test
    void powerline_patched_fonts_grant_only_the_wedge() {
        // These carry the solid triangles but not the solid semi-circles, so granting the pill axis
        // would render tofu. This is the case a single boolean cannot express.
        for (String font : new String[] {
            "Cascadia Mono PL", "Cascadia Code PL", "CascadiaMonoPL-Regular", "Fira Code Powerline", "Meslo LG S DZ PL"
        }) {
            assertThat(NerdFontNames.caps(font)).as(font).isEqualTo(NerdFontCaps.WEDGE_ONLY);
        }
    }

    @Test
    void plain_fonts_grant_nothing() {
        for (String font : new String[] {
            "Menlo",
            "Menlo-Regular 13",
            "Monaco",
            "Monaco 12",
            "SF Mono",
            "SFMono-Regular",
            "SFMonoTerminal-Regular",
            "Courier New",
            "Fira Code",
            "JetBrains Mono",
            "Hack",
            "Consolas",
            "Cascadia Mono",
            "IBM Plex Mono",
            "Inconsolata",
            "PragmataPro Mono Liga",
            "Helvetica Neue",
            "Arial"
        }) {
            assertThat(NerdFontNames.caps(font)).as(font).isEqualTo(NerdFontCaps.NONE);
        }
    }

    @Test
    void a_standalone_nf_lookalike_is_not_a_nerd_font() {
        // The delimiter + uppercase requirements exist to keep these out.
        for (String font : new String[] {"NFL Team Mono", "CONFLUENCE Sans", "Info Display", "Confluent Mono"}) {
            assertThat(NerdFontNames.caps(font)).as(font).isEqualTo(NerdFontCaps.NONE);
        }
    }

    @Test
    void matching_is_case_insensitive_for_the_spelled_out_form() {
        // The reference implementation is case-sensitive here and misses real config values.
        assertThat(NerdFontNames.caps("jetbrainsmono nerd font")).isEqualTo(NerdFontCaps.ALL);
        assertThat(NerdFontNames.caps("JETBRAINSMONO NERD FONT")).isEqualTo(NerdFontCaps.ALL);
        assertThat(NerdFontNames.caps("fira code powerline")).isEqualTo(NerdFontCaps.WEDGE_ONLY);
    }

    @Test
    void a_font_stack_takes_the_union_across_entries() {
        // A terminal will find the glyph in a later family, so the stack is nerd-capable even when
        // its first entry is not.
        assertThat(NerdFontNames.caps("Menlo, Symbols Nerd Font")).isEqualTo(NerdFontCaps.ALL);
        assertThat(NerdFontNames.caps("'Monaco', 'Menlo', monospace")).isEqualTo(NerdFontCaps.NONE);
        assertThat(NerdFontNames.caps("Cascadia Mono, Cascadia Mono PL")).isEqualTo(NerdFontCaps.WEDGE_ONLY);
        // wedge from one entry + both from another still unions to both
        assertThat(NerdFontNames.caps("Cascadia Mono PL, MesloLGS NF")).isEqualTo(NerdFontCaps.ALL);
    }

    @Test
    void quotes_and_blanks_are_tolerated() {
        assertThat(NerdFontNames.caps("\"MesloLGS NF\"")).isEqualTo(NerdFontCaps.ALL);
        assertThat(NerdFontNames.caps("'MesloLGS NF'")).isEqualTo(NerdFontCaps.ALL);
        assertThat(NerdFontNames.caps("  MesloLGS NF  ")).isEqualTo(NerdFontCaps.ALL);
        assertThat(NerdFontNames.caps(null)).isEqualTo(NerdFontCaps.NONE);
        assertThat(NerdFontNames.caps("")).isEqualTo(NerdFontCaps.NONE);
        assertThat(NerdFontNames.caps("   ")).isEqualTo(NerdFontCaps.NONE);
        assertThat(NerdFontNames.caps(",,")).isEqualTo(NerdFontCaps.NONE);
    }
}
