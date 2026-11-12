// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.changes.Change;
import cc.jumpkick.changes.ChangeActor;
import cc.jumpkick.changes.ChangeJournal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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

    private static final UserConfigEditor.CredentialTable GITLAB = new UserConfigEditor.CredentialTable(
            "gitlab",
            URI.create("https://gitlab.example/api/v4/projects/7/packages/maven/"),
            Map.of("token", "${ACME_PULL_TOKEN}"));

    @Test
    void a_credential_only_table_is_appended_after_what_the_file_holds() throws Exception {
        String out = UserConfigEditor.appendCredential("nerd-font = true", GITLAB);

        assertThat(out).isEqualTo("""
                nerd-font = true

                [repositories.gitlab]
                url = "https://gitlab.example/api/v4/projects/7/packages/maven/"
                credential-only = true
                token = "${ACME_PULL_TOKEN}"
                """);
        assertThat(UserConfigEditor.declaresRepository(out, "gitlab")).isTrue();
    }

    /** A repository the file already declares, as a table or as a key of [repositories], is left alone. */
    @Test
    void a_repository_the_file_declares_is_left_as_it_is(@TempDir Path home) throws Exception {
        Path config = home.resolve("config.toml");
        Files.writeString(config, "[repositories]\ngitlab = \"https://elsewhere.example/\"\n");

        assertThat(UserConfigEditor.addCredential(config, GITLAB)).isFalse();
        assertThat(Files.readString(config)).isEqualTo("[repositories]\ngitlab = \"https://elsewhere.example/\"\n");
    }

    @Test
    void a_missing_file_is_created_with_the_table(@TempDir Path home) throws Exception {
        Path config = home.resolve("jk").resolve("config.toml");

        assertThat(UserConfigEditor.addCredential(config, GITLAB)).isTrue();
        assertThat(Files.readString(config)).startsWith("[repositories.gitlab]\n");
    }

    /** A re-import finds the table it wrote; the same name at another URL is not that table. */
    @Test
    void a_repository_declared_at_the_same_url_is_recognised(@TempDir Path home) throws Exception {
        Path config = home.resolve("config.toml");
        UserConfigEditor.addCredential(config, GITLAB);

        assertThat(UserConfigEditor.declaresRepositoryAt(config, GITLAB)).isTrue();
        Files.writeString(config, "[repositories]\ngitlab = \"https://elsewhere.example/\"\n");
        assertThat(UserConfigEditor.declaresRepositoryAt(config, GITLAB)).isFalse();
    }

    @Test
    void setting_a_key_is_journaled_with_its_diff_and_who_ran_it(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("config.toml");
        Files.writeString(config, "other = 1\n");
        Path journal = dir.resolve("changes");
        ChangeActor shell = ChangeActor.shell(List.of("self", "setup-terminal", "--mode", "pill"));

        ChangeJournal.into(
                journal,
                () -> Request.actingAs(
                        shell,
                        () -> UserConfigEditor.setNerdFont(
                                config, NerdFontMode.parse("pill").orElseThrow())));

        Change entry = new ChangeJournal(journal).page(null, 10).entries().getFirst();
        assertThat(entry.action()).isEqualTo(Change.EDIT);
        assertThat(entry.what()).endsWith("config.toml");
        assertThat(entry.diff()).contains("+nerd-font = \"pill\"").contains(" other = 1");
        assertThat(entry.actor().label()).isEqualTo("jk self setup-terminal --mode");
    }

    @Test
    void an_added_credential_is_journaled_with_its_secret_masked(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("config.toml");
        Path journal = dir.resolve("changes");
        var table = new UserConfigEditor.CredentialTable(
                "nexus",
                URI.create("https://nexus.example/repo"),
                Map.of("username", "deploy", "password", "pa55-literal"));

        ChangeJournal.into(journal, () -> UserConfigEditor.addCredential(config, table));

        Change entry = new ChangeJournal(journal).page(null, 10).entries().getFirst();
        assertThat(entry.summary()).contains("nexus");
        assertThat(entry.diff())
                .contains("+[repositories.nexus]")
                .contains("+password = \"***\"")
                .doesNotContain("pa55-literal");
        assertThat(Files.readString(config)).contains("pa55-literal");
    }

    /**
     * An edit publishes a new file by rename instead of rewriting the old one in place, so a reader
     * holding the old file (here a second name for its inode) never sees it truncated or half
     * written.
     */
    @Test
    void an_edit_replaces_the_file_rather_than_rewriting_it_in_place(@TempDir Path tmp) throws Exception {
        Path config = Files.writeString(tmp.resolve("config.toml"), "color = \"auto\"\n");
        Path reader = Files.createLink(tmp.resolve("reader-view"), config);

        ChangeJournal.into(tmp.resolve("changes"), () -> UserConfigEditor.setNerdFont(config, NerdFontMode.ON));

        assertThat(Files.readString(config)).contains("nerd-font");
        assertThat(Files.readString(reader)).isEqualTo("color = \"auto\"\n");
    }
}
