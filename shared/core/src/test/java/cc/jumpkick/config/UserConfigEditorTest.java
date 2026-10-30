// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UserConfigEditorTest {

    @Test
    void creates_root_level_key() {
        String out = UserConfigEditor.upsertRoot("", "nerd-font", "true");
        assertThat(out).isEqualTo("nerd-font = true\n");
        assertThat(out).doesNotContain("[global]");
    }

    @Test
    void replaces_existing_line() {
        String in = "nerd-font = true\nother = 1\n";
        String out = UserConfigEditor.upsertRoot(in, "nerd-font", "false");
        assertThat(out).contains("nerd-font = false");
        assertThat(out).doesNotContain("nerd-font = true");
        assertThat(out).contains("other = 1");
    }

    /** A copy under a table is one the reader ignores: it is removed and the key lands at the root. */
    @Test
    void a_copy_under_a_legacy_table_moves_to_the_root() {
        String in = "color = auto\n\n[global]\nnerd-font = true\nother = 1\n";
        String out = UserConfigEditor.upsertRoot(in, "nerd-font", "\"pill\"");
        assertThat(out).startsWith("nerd-font = \"pill\"\n");
        assertThat(out).doesNotContain("nerd-font = true");
        assertThat(out).contains("[global]\nother = 1");
        assertThat(out.indexOf("nerd-font")).isLessThan(out.indexOf("[global]"));
    }

    /** A root copy and a table copy: the root one is replaced, the table one deleted. */
    @Test
    void a_root_copy_wins_and_the_table_copy_is_dropped() {
        String in = "nerd-font = true\n\n[global]\nnerd-font = false\n";
        String out = UserConfigEditor.upsertRoot(in, "nerd-font", "\"wedge\"");
        assertThat(out).isEqualTo("nerd-font = \"wedge\"\n\n[global]\n");
    }

    @Test
    void inserts_at_top_when_missing() {
        String in = "color = auto\n";
        String out = UserConfigEditor.upsertRoot(in, "nerd-font", "false");
        assertThat(out).startsWith("nerd-font = false\n");
        assertThat(out).contains("color = auto");
    }

    @Test
    void replaces_a_quoted_mode_word_rather_than_duplicating_the_key() {
        // Re-running setup-terminal must not leave two nerd-font lines: the second would win on
        // re-read and the first would be a silent lie in the file the user edits.
        String in = "nerd-font = \"wedge\"\n";
        String out = UserConfigEditor.upsertRoot(in, "nerd-font", "\"pill\"");
        assertThat(out).contains("nerd-font = \"pill\"");
        assertThat(out).doesNotContain("wedge");
        assertThat(countOccurrences(out, "nerd-font")).isEqualTo(1);
    }

    @Test
    void round_trips_every_mode_through_the_writer_and_the_reader() {
        for (NerdFontMode mode : NerdFontMode.values()) {
            String out = UserConfigEditor.upsertRoot("", "nerd-font", mode.toToml());
            String value = out.substring(out.indexOf('=') + 1).strip();
            assertThat(NerdFontMode.parse(value)).as("%s -> %s", mode, out).contains(mode);
        }
    }

    @Test
    void switching_between_boolean_and_word_forms_replaces_in_place() {
        String toWord = UserConfigEditor.upsertRoot("nerd-font = false\n", "nerd-font", "\"auto\"");
        assertThat(toWord).contains("nerd-font = \"auto\"").doesNotContain("false");
        String toBool = UserConfigEditor.upsertRoot("nerd-font = \"auto\"\n", "nerd-font", "true");
        assertThat(toBool).contains("nerd-font = true").doesNotContain("auto");
    }

    @Test
    void preserves_unrelated_tables_and_keys() {
        String in = "[cache]\nauto-prune = true\n\n[toolchain]\njdk = \"temurin-25\"\n";
        String out = UserConfigEditor.upsertRoot(in, "nerd-font", "\"auto\"");
        assertThat(out)
                .startsWith("nerd-font = \"auto\"\n")
                .contains("[cache]")
                .contains("auto-prune = true")
                .contains("[toolchain]")
                .contains("jdk = \"temurin-25\"");
    }

    @Test
    void self_update_is_upserted_beside_nerd_font_without_touching_it(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("config.toml");
        Files.writeString(cfg, "nerd-font = \"auto\"\n\n[cache]\nauto-prune = true\n");

        UserConfigEditor.setSelfUpdate(cfg, SelfUpdatePolicy.Mode.NEVER);
        UserConfigEditor.setSelfUpdate(cfg, SelfUpdatePolicy.Mode.ASK);

        String out = Files.readString(cfg);
        assertThat(countOccurrences(out, "self-update")).isEqualTo(1);
        assertThat(out).contains("self-update = \"ask\"").contains("nerd-font = \"auto\"");
        assertThat(out.indexOf("self-update")).isLessThan(out.indexOf("[cache]"));
        assertThat(GlobalConfig.selfUpdate(cfg)).contains("ask");
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) n++;
        return n;
    }
}
