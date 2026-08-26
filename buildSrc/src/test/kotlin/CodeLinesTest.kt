// SPDX-License-Identifier: Apache-2.0

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CodeLinesTest {

    @Test
    fun empty_and_blanks_are_zero() {
        assertThat(CodeLines.count("")).isZero()
        assertThat(CodeLines.count("\n\n\n")).isZero()
        assertThat(CodeLines.count("   \n\t\n")).isZero()
    }

    @Test
    fun comments_and_javadoc_are_zero() {
        assertThat(CodeLines.count("// SPDX-License-Identifier: Apache-2.0\n")).isZero()
        assertThat(CodeLines.count("/**\n * class-level javadoc\n */\n")).isZero()
        assertThat(CodeLines.count("    // indented\n")).isZero()
        assertThat(CodeLines.count("/* block\nacross lines */\n")).isZero()
    }

    @Test
    fun package_and_import_are_zero() {
        assertThat(CodeLines.count("package cc.jumpkick.foo;\n")).isZero()
        assertThat(CodeLines.count("package cc.jumpkick.foo\n", "kt")).isZero()
        assertThat(CodeLines.count("import java.util.List;\n")).isZero()
        assertThat(CodeLines.count("import static java.util.List.of;\n")).isZero()
        assertThat(CodeLines.count("import cc.jumpkick.foo.Bar as Baz\n", "kt")).isZero()
        assertThat(CodeLines.count("import { test } from 'node:test';\n", "js")).isZero()
    }

    @Test
    fun a_statement_counts_once_even_with_a_trailing_comment() {
        assertThat(CodeLines.count("class Foo {}\n")).isEqualTo(1)
        assertThat(CodeLines.count("class Foo {} // trailing\n")).isEqualTo(1)
        assertThat(CodeLines.count("@Override\nvoid f() {}\n")).isEqualTo(2)
    }

    @Test
    fun envelope_then_one_type_is_one() {
        val src =
            """
            // SPDX-License-Identifier: Apache-2.0
            package cc.jumpkick.foo;

            import java.util.List;

            /** A type. */
            class Foo {
                void f() {}
            }
            """
                .trimIndent() + "\n"
        assertThat(CodeLines.count(src)).isEqualTo(3) // class Foo { / void f() {} / }
    }

    @Test
    fun string_contents_count_including_comment_lookalikes() {
        assertThat(CodeLines.count("String s = \"// not a comment\";\n")).isEqualTo(1)
        val block =
            """
            String s = ${"\"\"\""}
            // looks like a comment
            ${"\"\"\""};
            """
                .trimIndent() + "\n"
        assertThat(CodeLines.count(block)).isEqualTo(3)
    }

    @Test
    fun a_string_line_that_looks_like_an_import_still_counts() {
        val block =
            """
            String s = ${"\"\"\""}
            import java.util.List;
            ${"\"\"\""};
            """
                .trimIndent() + "\n"
        assertThat(CodeLines.count(block)).isEqualTo(3)
    }

    @Test
    fun js_template_literal_does_not_let_a_comment_eat_the_rest_of_the_file() {
        val src =
            """
            const s = ${'`'}
            /* not a comment
            import would-be-eaten
            ${'`'};
            function bar() { return 1; }
            """
                .trimIndent() + "\n"
        assertThat(CodeLines.count(src, "js")).isEqualTo(5)
        assertThat(CodeLines.count(src, "mjs")).isEqualTo(5)
    }
}
