// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstalledFontsTest {

    @Test
    void file_names_promise_glyph_families() {
        assertThat(InstalledFonts.fontFileCaps("SymbolsNerdFont-Regular.ttf")).isEqualTo(NerdFontCaps.ALL);
        assertThat(InstalledFonts.fontFileCaps("HackNerdFontMono-Bold.ttf")).isEqualTo(NerdFontCaps.ALL);
        assertThat(InstalledFonts.fontFileCaps("MesloLGS NF Regular.ttf")).isEqualTo(NerdFontCaps.ALL);
        assertThat(InstalledFonts.fontFileCaps("JetBrainsMonoNFM-Regular.ttf")).isEqualTo(NerdFontCaps.ALL);
        assertThat(InstalledFonts.fontFileCaps("Meslo LG M Regular for Powerline.otf"))
                .isEqualTo(NerdFontCaps.WEDGE_ONLY);
    }

    @Test
    void ordinary_fonts_and_other_files_promise_nothing() {
        assertThat(InstalledFonts.fontFileCaps("Menlo.ttc")).isEqualTo(NerdFontCaps.NONE);
        assertThat(InstalledFonts.fontFileCaps("Inconsolata-Regular.ttf")).isEqualTo(NerdFontCaps.NONE);
        assertThat(InstalledFonts.fontFileCaps("NFL Team.ttf")).isEqualTo(NerdFontCaps.NONE);
        assertThat(InstalledFonts.fontFileCaps("HackNerdFont.txt")).isEqualTo(NerdFontCaps.NONE);
        assertThat(InstalledFonts.fontFileCaps("README")).isEqualTo(NerdFontCaps.NONE);
    }

    @Test
    void a_nerd_font_nested_in_family_directories_is_found(@TempDir Path root) throws IOException {
        Path family = Files.createDirectories(root.resolve("truetype/hack"));
        Files.createFile(family.resolve("Menlo.ttf"));
        assertThat(InstalledFonts.scan(List.of(root)).caps()).isEqualTo(NerdFontCaps.NONE);
        Files.createFile(family.resolve("HackNerdFont-Regular.ttf"));
        assertThat(InstalledFonts.scan(List.of(root)).caps()).isEqualTo(NerdFontCaps.ALL);
    }

    @Test
    void the_stamp_changes_when_a_font_is_installed(@TempDir Path root) throws IOException {
        Path dir = Files.createDirectories(root.resolve("fonts"));
        String before = InstalledFonts.scan(List.of(dir)).stamp();
        Files.createFile(dir.resolve("HackNerdFont-Regular.ttf"));
        Files.setLastModifiedTime(
                dir, FileTime.fromMillis(Files.getLastModifiedTime(dir).toMillis() + 5_000));
        assertThat(InstalledFonts.scan(List.of(dir)).stamp()).isNotEqualTo(before);
    }

    @Test
    void missing_roots_scan_as_nothing(@TempDir Path root) {
        var scan = InstalledFonts.scan(List.of(root.resolve("absent")));
        assertThat(scan.caps()).isEqualTo(NerdFontCaps.NONE);
        assertThat(scan.stamp()).isEmpty();
    }

    @Test
    void directories_deeper_than_the_bound_are_not_visited(@TempDir Path root) throws IOException {
        Path deep = Files.createDirectories(root.resolve("a/b/c/d/e"));
        Files.createFile(deep.resolve("HackNerdFont-Regular.ttf"));
        assertThat(InstalledFonts.scan(List.of(root)).caps()).isEqualTo(NerdFontCaps.NONE);
    }
}
