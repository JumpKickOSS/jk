// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.TerminalIdentity.Terminal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The file-backed readers, over a fake home. */
class TerminalFontsRealTest {

    private static TerminalFonts real(Path home) {
        Map<String, String> env = Map.of("HOME", home.toString());
        return TerminalFonts.real(env::get);
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    @Test
    void konsole_follows_the_default_profile(@TempDir Path home) throws IOException {
        write(home.resolve(".config/konsolerc"), "[Desktop Entry]\nDefaultProfile=Dev.profile\n");
        write(
                home.resolve(".local/share/konsole/Dev.profile"),
                "[General]\nName=Dev\n\n[Appearance]\nFont=Hack Nerd Font,12,-1,5,50,0,0,0,0,0\n");
        var l = real(home).lookup(Terminal.KONSOLE);
        assertThat(l.font()).contains("Hack Nerd Font");
        assertThat(l.stamp()).contains("konsolerc").contains("Dev.profile");
    }

    @Test
    void foot_drops_size_attributes_and_keeps_the_stack(@TempDir Path home) throws IOException {
        write(
                home.resolve(".config/foot/foot.ini"),
                "# comment\nfont=MesloLGS NF:size=12, Noto Color Emoji:size=11\n[colors]\nfont=ignored\n");
        assertThat(real(home).lookup(Terminal.FOOT).font()).contains("MesloLGS NF, Noto Color Emoji");
        assertThat(NerdFontNames.caps("MesloLGS NF, Noto Color Emoji")).isEqualTo(NerdFontCaps.ALL);
    }

    @Test
    void foot_reads_the_main_section_when_present(@TempDir Path home) throws IOException {
        write(home.resolve(".config/foot/foot.ini"), "[main]\nfont = Fira Code:size=10\n");
        assertThat(real(home).lookup(Terminal.FOOT).font()).contains("Fira Code");
    }

    @Test
    void rio_reads_its_fonts_table(@TempDir Path home) throws IOException {
        write(home.resolve(".config/rio/config.toml"), "[fonts]\nfamily = \"Cascadia Code NF\"\n");
        assertThat(real(home).lookup(Terminal.RIO).font()).contains("Cascadia Code NF");
    }

    @Test
    void hyper_reads_a_literal_font_family(@TempDir Path home) throws IOException {
        write(
                home.resolve(".hyper.js"),
                "module.exports = { config: { fontSize: 12, fontFamily: '\"FiraCode Nerd Font\", Menlo', } };");
        assertThat(real(home).lookup(Terminal.HYPER).font()).contains("FiraCode Nerd Font, Menlo");
    }

    @Test
    void absent_or_malformed_configs_read_as_empty(@TempDir Path home) throws IOException {
        for (Terminal t : new Terminal[] {Terminal.KONSOLE, Terminal.FOOT, Terminal.RIO, Terminal.HYPER}) {
            assertThat(real(home).lookup(t).font()).as(t.id()).isEmpty();
        }
        write(home.resolve(".config/foot/foot.ini"), "\u0000\u0001 = = [[");
        write(home.resolve(".config/rio/config.toml"), "[[[[");
        assertThat(real(home).lookup(Terminal.FOOT).font()).isEmpty();
        assertThat(real(home).lookup(Terminal.RIO).font()).isEmpty();
    }

    @Test
    void terminals_that_need_no_reader_report_nothing(@TempDir Path home) {
        assertThat(real(home).lookup(Terminal.GHOSTTY)).isEqualTo(TerminalFonts.Lookup.EMPTY);
        assertThat(real(home).lookup(Terminal.VTE)).isEqualTo(TerminalFonts.Lookup.EMPTY);
    }
}
