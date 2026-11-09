// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.changes;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextDiffTest {

    @Test
    void a_change_in_a_long_file_shows_three_lines_of_context() {
        String before = "1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n";
        String after = "1\n2\n3\n4\n5\nsix\n7\n8\n9\n10\n";
        assertThat(TextDiff.unified("f", before, after))
                .isEqualTo("--- f\n+++ f\n@@ -3,7 +3,7 @@\n 3\n 4\n 5\n-6\n+six\n 7\n 8\n 9\n");
    }

    @Test
    void two_distant_changes_are_two_hunks() {
        StringBuilder a = new StringBuilder();
        for (int i = 1; i <= 20; i++) a.append(i).append('\n');
        String b = a.toString().replace("2\n", "two\n").replace("19\n", "nineteen\n");
        String diff = TextDiff.unified("f", a.toString(), b);
        assertThat(diff.lines().filter(l -> l.startsWith("@@"))).hasSize(2);
    }

    @Test
    void a_new_file_is_all_additions() {
        assertThat(TextDiff.unified("f", "", "a\nb\n")).isEqualTo("--- f\n+++ f\n@@ -1,0 +1,2 @@\n+a\n+b\n");
    }

    @Test
    void a_password_outside_a_repositories_table_is_not_guessed_at() {
        assertThat(TextDiff.unified("f", "", "[network]\npassword = \"proxy\"\n"))
                .contains("+password = \"proxy\"");
    }
}
