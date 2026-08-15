// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UserConfigEditorTest {

    @Test
    void creates_root_level_key() {
        String out = UserConfigEditor.upsertNerdFont("", "true");
        assertThat(out).isEqualTo("nerd-font = true\n");
        assertThat(out).doesNotContain("[global]");
    }

    @Test
    void replaces_existing_line() {
        String in = "nerd-font = true\nother = 1\n";
        String out = UserConfigEditor.upsertNerdFont(in, "false");
        assertThat(out).contains("nerd-font = false");
        assertThat(out).doesNotContain("nerd-font = true");
        assertThat(out).contains("other = 1");
    }

    @Test
    void inserts_at_top_when_missing() {
        String in = "color = auto\n";
        String out = UserConfigEditor.upsertNerdFont(in, "false");
        assertThat(out).startsWith("nerd-font = false\n");
        assertThat(out).contains("color = auto");
    }

    @Test
    void replaces_a_quoted_mode_word_rather_than_duplicating_the_key() {
        // Re-running setup-terminal must not leave two nerd-font lines: the second would win on
        // re-read and the first would be a silent lie in the file the user edits.
        String in = "nerd-font = \"wedge\"\n";
        String out = UserConfigEditor.upsertNerdFont(in, "\"pill\"");
        assertThat(out).contains("nerd-font = \"pill\"");
        assertThat(out).doesNotContain("wedge");
        assertThat(countOccurrences(out, "nerd-font")).isEqualTo(1);
    }

    @Test
    void round_trips_every_mode_through_the_writer_and_the_reader() {
        for (NerdFontMode mode : NerdFontMode.values()) {
            String out = UserConfigEditor.upsertNerdFont("", mode.toToml());
            String value = out.substring(out.indexOf('=') + 1).strip();
            assertThat(NerdFontMode.parse(value)).as("%s -> %s", mode, out).contains(mode);
        }
    }

    @Test
    void switching_between_boolean_and_word_forms_replaces_in_place() {
        String toWord = UserConfigEditor.upsertNerdFont("nerd-font = false\n", "\"auto\"");
        assertThat(toWord).contains("nerd-font = \"auto\"").doesNotContain("false");
        String toBool = UserConfigEditor.upsertNerdFont("nerd-font = \"auto\"\n", "true");
        assertThat(toBool).contains("nerd-font = true").doesNotContain("auto");
    }

    @Test
    void preserves_unrelated_tables_and_keys() {
        String in = "[cache]\nauto-prune = true\n\n[toolchain]\njdk = \"temurin-25\"\n";
        String out = UserConfigEditor.upsertNerdFont(in, "\"auto\"");
        assertThat(out)
                .startsWith("nerd-font = \"auto\"\n")
                .contains("[cache]")
                .contains("auto-prune = true")
                .contains("[toolchain]")
                .contains("jdk = \"temurin-25\"");
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) n++;
        return n;
    }
}
