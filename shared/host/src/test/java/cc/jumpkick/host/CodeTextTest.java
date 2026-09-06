// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.CodeText.Blank;
import java.util.List;
import org.junit.jupiter.api.Test;

class CodeTextTest {

    private static final String SRC = String.join(
            "\n",
            "package a.b;",
            "import java.util.List;",
            "/** javadoc mentions Hashing.sha256Hex(x) */",
            "class T {",
            "  String s = \"quoted // not a comment /* nor this */\"; // trailing used to",
            "  char q = '\\'';",
            "  String t = \"\"\"",
            "      text \\\"\"\" block",
            "      \"\"\";",
            "  int n = List.of().size();",
            "}",
            "");

    private static void assertShape(String projected) {
        assertThat(projected).hasSize(SRC.length());
        for (int i = 0; i < SRC.length(); i++) {
            if (SRC.charAt(i) == '\n') assertThat(projected.charAt(i)).isEqualTo('\n');
        }
    }

    @Test
    void comments_mode_blanks_comments_and_keeps_code_and_literals() {
        String out = CodeText.blank(SRC, Blank.COMMENTS);
        assertShape(out);
        assertThat(out).doesNotContain("javadoc").doesNotContain("trailing");
        assertThat(out).contains("quoted // not a comment /* nor this */").contains("List.of().size()");
    }

    @Test
    void comments_and_strings_mode_keeps_only_code() {
        String out = CodeText.blank(SRC, Blank.COMMENTS_AND_STRINGS);
        assertShape(out);
        assertThat(out).doesNotContain("javadoc").doesNotContain("quoted").doesNotContain("block");
        assertThat(out).contains("String s =").contains("List.of().size()");
    }

    @Test
    void code_mode_keeps_only_comments() {
        String out = CodeText.blank(SRC, Blank.CODE);
        assertShape(out);
        assertThat(out).contains("javadoc mentions Hashing.sha256Hex(x)").contains("// trailing used to");
        assertThat(out).doesNotContain("String s").doesNotContain("quoted").doesNotContain("List.of");
    }

    @Test
    void none_mode_is_the_source() {
        assertThat(CodeText.blank(SRC, Blank.NONE)).isSameAs(SRC);
    }

    @Test
    void a_text_block_may_escape_a_quote_without_closing() {
        String out = CodeText.blank(SRC, Blank.COMMENTS_AND_STRINGS);
        // The `\"""` inside the block did not end it: `block` and the following line are blanked.
        assertThat(out).doesNotContain("block");
        assertThat(out).contains("int n");
    }

    @Test
    void an_escaped_quote_in_a_char_literal_does_not_end_it() {
        String out = CodeText.blank("char q = '\\''; int z;", Blank.COMMENTS_AND_STRINGS);
        assertThat(out).isEqualTo("char q =     ; int z;");
    }

    @Test
    void a_comment_opener_inside_a_line_comment_is_just_text() {
        String out = CodeText.blank("// a /* b\nint x;", Blank.COMMENTS);
        assertThat(out).isEqualTo("         \nint x;");
    }

    @Test
    void an_unterminated_block_comment_blanks_to_the_end() {
        String out = CodeText.blank("int x; /* open\nmore", Blank.COMMENTS);
        assertThat(out).isEqualTo("int x;        \n    ");
    }

    @Test
    void backticks_are_literals_only_for_js() {
        String js = "const s = `a /* not a comment`; // c";
        assertThat(CodeText.blank(js, Blank.COMMENTS, true)).isEqualTo("const s = `a /* not a comment`;     ");
        assertThat(CodeText.blank(js, Blank.COMMENTS_AND_STRINGS, true)).startsWith("const s =  ");
        // Without template literals the `/*` opens a comment that never closes.
        assertThat(CodeText.blank(js, Blank.COMMENTS, false)).isEqualTo("const s = `a                        ");
    }

    @Test
    void squash_drops_whitespace_between_tokens_but_not_inside_literals() {
        assertThat(CodeText.squashBetweenLiterals("foo(\n  \"a b\",\n  'c')")).isEqualTo("foo(\"a b\",'c')");
    }

    @Test
    void guardText_drops_imports_and_comments_then_squashes() {
        assertThat(CodeText.guardText(SRC)).startsWith("classT{Strings=\"quoted // not a comment /* nor this */\";");
    }

    @Test
    void stringLiterals_skips_comments_and_char_literals_and_keeps_escapes() {
        List<String> lits = CodeText.stringLiterals(SRC);
        assertThat(lits).hasSize(2);
        assertThat(lits.get(0)).isEqualTo("quoted // not a comment /* nor this */");
        assertThat(lits.get(1)).contains("text \\\"\"\" block");
    }

    @Test
    void codeLines_excludes_comments_blanks_and_the_envelope() {
        // package, import, javadoc and the trailing "" are not code; the text block's lines are.
        assertThat(CodeText.codeLines(SRC, "java")).isEqualTo(8);
        assertThat(CodeText.codeLines("", "java")).isZero();
        assertThat(CodeText.codeLines("const s = `a\n/* b */\nb`;\n", "js")).isEqualTo(3);
    }

    @Test
    void lineAt_is_one_based() {
        assertThat(CodeText.lineAt("a\nb\nc", 0)).isEqualTo(1);
        assertThat(CodeText.lineAt("a\nb\nc", 2)).isEqualTo(2);
        assertThat(CodeText.lineAt("a\nb\nc", 4)).isEqualTo(3);
    }

    @Test
    void fqcn_matches_two_or_more_lowercase_segments_then_a_type() {
        assertThat(CodeText.FQCN.matcher("x = java.util.List.of();").find()).isTrue();
        assertThat(CodeText.FQCN.matcher("x = util.List.of();").find()).isFalse();
        assertThat(CodeText.FQCN.matcher("a.b.c.lower").find()).isFalse();
    }
}
