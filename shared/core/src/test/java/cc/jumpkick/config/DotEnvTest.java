// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * the {@code.env} dialect, pinned. There is no {@code.env} specification and
 * implementations disagree on quoting, escapes, {@code export}, and inner expansion — so jk's
 * choices are asserted rather than left to be discovered.
 */
class DotEnvTest {

    private static Map<String, String> parse(String... lines) {
        return DotEnv.parse(List.of(lines));
    }

    @Test
    void plain_assignments_and_whitespace() {
        assertThat(parse("FOO=bar", "  BAZ = qux  ", "EMPTY="))
                .containsEntry("FOO", "bar")
                .containsEntry("BAZ", "qux")
                .containsEntry("EMPTY", "");
    }

    @Test
    void comments_and_blank_lines_are_ignored() {
        assertThat(parse("# a comment", "", "   ", "FOO=bar", "# FOO=shadowed"))
                .containsExactly(Map.entry("FOO", "bar"));
    }

    @Test
    void a_trailing_inline_comment_is_stripped_from_an_unquoted_value() {
        assertThat(parse("FOO=bar # note")).containsEntry("FOO", "bar");
        // A '#' with no preceding space is part of the value — it is a legal password character.
        assertThat(parse("PASS=abc#123")).containsEntry("PASS", "abc#123");
    }

    @Test
    void export_is_tolerated_so_the_file_can_also_be_sourced() {
        assertThat(parse("export FOO=bar")).containsEntry("FOO", "bar");
    }

    @Test
    void double_quotes_honour_escapes() {
        assertThat(parse("A=\"line1\\nline2\"")).containsEntry("A", "line1\nline2");
        assertThat(parse("B=\"say \\\"hi\\\"\"")).containsEntry("B", "say \"hi\"");
        assertThat(parse("C=\"back\\\\slash\"")).containsEntry("C", "back\\slash");
    }

    @Test
    void single_quotes_are_literal() {
        assertThat(parse("A='line1\\nline2'")).containsEntry("A", "line1\\nline2");
        // Quotes also protect a '#' that would otherwise read as a comment.
        assertThat(parse("B='has # hash'")).containsEntry("B", "has # hash");
    }

    @Test
    void there_is_no_expansion_inside_the_file() {
        // Deliberate: the file stays dumb. Expansion happens in jk.toml, at whitelisted positions.
        assertThat(parse("FOO=base", "BAR=${FOO}/x")).containsEntry("BAR", "${FOO}/x");
    }

    @Test
    void the_last_assignment_wins() {
        assertThat(parse("FOO=first", "FOO=second")).containsEntry("FOO", "second");
    }

    @Test
    void malformed_lines_are_skipped_rather_than_failing_the_build() {
        assertThat(parse("no-equals-here", "=novalue", "9BAD=x", "GOOD=yes")).containsExactly(Map.entry("GOOD", "yes"));
    }

    @Test
    void a_missing_file_is_empty_not_an_error(@TempDir Path tmp) {
        assertThat(DotEnv.read(tmp.resolve(".env"))).isEmpty();
    }

    @Test
    void reads_from_disk(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve(".env"), "# header\nTOKEN=\"abc\\ndef\"\nexport MODE=ci\n");
        assertThat(DotEnv.read(tmp.resolve(".env")))
                .containsEntry("TOKEN", "abc\ndef")
                .containsEntry("MODE", "ci");
    }
}
