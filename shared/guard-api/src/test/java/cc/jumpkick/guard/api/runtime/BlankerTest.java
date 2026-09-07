// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BlankerTest {

    @Test
    void every_view_keeps_the_text_length_and_its_newlines() {
        String src = "int a = 1; // one\n/* two\n lines */ String s = \"x\\\"y\";\nchar c = '\\'';\n";
        for (String view : new String[] {
            Blanker.blank(src, true, false, false),
            Blanker.blank(src, true, true, false),
            Blanker.blank(src, false, true, true)
        }) {
            assertThat(view).hasSize(src.length());
            assertThat(view.chars().filter(ch -> ch == '\n').count()).isEqualTo(4);
        }
    }

    @Test
    void the_comments_view_blanks_line_and_block_comments_and_keeps_code_and_strings() {
        String src = "a = \"// not a comment\"; // real\n/* block */ b = 2;";
        String out = Blanker.blank(src, true, false, false);
        assertThat(out).startsWith("a = \"// not a comment\";");
        assertThat(out).doesNotContain("// real").doesNotContain("/* block */");
        assertThat(out).contains("b = 2;");
        assertThat(out.indexOf("b = 2")).isEqualTo(src.indexOf("b = 2"));
    }

    @Test
    void the_compiler_view_blanks_string_contents_but_keeps_the_quotes() {
        String out = Blanker.blank("x = \"abc\" + 'q';", true, true, false);
        assertThat(out).isEqualTo("x = \"   \" + ' ';");
    }

    @Test
    void escaped_quotes_do_not_end_a_literal() {
        String src = "s = \"a\\\"b\"; t = 1;";
        String out = Blanker.blank(src, true, true, false);
        assertThat(out)
                .as("four characters between the quotes: a, backslash, quote, b")
                .isEqualTo("s = \"    \"; t = 1;");
    }

    @Test
    void an_unterminated_string_ends_at_the_line_and_an_unterminated_block_comment_at_the_text() {
        String src = "s = \"open\nnext = 1;";
        String out = Blanker.blank(src, true, true, false);
        assertThat(out).startsWith("s = \"    ");
        assertThat(out).endsWith("next = 1;");
        String comment = "a = 1; /* never closed\nb = 2;";
        assertThat(Blanker.blank(comment, true, false, false))
                .isEqualTo("a = 1; " + " ".repeat("/* never closed".length()) + "\n" + " ".repeat("b = 2;".length()));
    }

    @Test
    void text_blocks_blank_their_body_between_the_triple_quotes() {
        String src = "s = \"\"\"\n  hello \"quoted\"\n  \"\"\";";
        String out = Blanker.blank(src, true, true, false);
        assertThat(out).startsWith("s = \"\"\"\n");
        assertThat(out).endsWith("\"\"\";");
        assertThat(out).doesNotContain("hello");
        assertThat(out).hasSize(src.length());
    }

    @Test
    void the_code_view_keeps_only_comments() {
        String src = "int a = 1; // keep me\n/* and me */ String s = \"gone\";";
        String out = Blanker.blank(src, false, true, true);
        assertThat(out).contains("// keep me").contains("/* and me */");
        assertThat(out).doesNotContain("int a").doesNotContain("gone").doesNotContain("String");
        assertThat(out).hasSize(src.length());
    }

    @Test
    void literals_are_listed_in_order_with_escapes_resolved_and_comments_skipped() {
        String src = "a(\"one\"); // \"not this\"\n/* \"nor this\" */ b(\"two\\n\", \"q\\\"uote\"); c('x');";
        assertThat(Blanker.literals(src)).containsExactly("one", "two\n", "q\"uote");
    }

    @Test
    void text_block_literals_are_stripped_of_their_indentation_and_newlines() {
        String src = "s = \"\"\"\n    hello\n    world\n    \"\"\";";
        assertThat(Blanker.literals(src)).containsExactly("hello\n    world");
        assertThat(Blanker.literals("no strings here; int x = 'c';")).isEmpty();
        assertThat(Blanker.literals("")).isEmpty();
    }
}
