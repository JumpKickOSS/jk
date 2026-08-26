// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CompilerLocusTest {

    @Test
    void javac_header_without_column_uses_the_caret() {
        String raw = String.join(
                "\n", "/ws/Foo.java:42: error: cannot find symbol", "    @Test", "     ^", "  symbol: class Test");
        CompilerLocus loc = CompilerLocus.parse(raw);
        assertThat(loc).isNotNull();
        assertThat(loc.file()).isEqualTo("/ws/Foo.java");
        assertThat(loc.line()).isEqualTo(42);
        assertThat(loc.col()).isEqualTo(6);
    }

    @Test
    void kotlinc_header_column_wins_over_a_later_caret() {
        String raw = "src/Baz.kt:2:5: error: unresolved reference: Test\n    @Test\n     ^";
        CompilerLocus loc = CompilerLocus.parse(raw);
        assertThat(loc.file()).isEqualTo("src/Baz.kt");
        assertThat(loc.line()).isEqualTo(2);
        assertThat(loc.col()).isEqualTo(5);
    }

    @Test
    void groovy_header_is_recognised() {
        CompilerLocus loc = CompilerLocus.parse("app/Main.groovy:8: error: unexpected token");
        assertThat(loc.file()).isEqualTo("app/Main.groovy");
        assertThat(loc.line()).isEqualTo(8);
        assertThat(loc.col()).isZero();
    }

    @Test
    void caret_scan_stops_at_the_next_header() {
        // Multi-unit blob: the first unit has no caret, so the second unit's caret
        // must not supply the first unit's column.
        String raw = String.join(
                "\n", "src/A.kt:3: error: something is wrong", "src/B.kt:9: error: other problem", "   bad()", "   ^");
        CompilerLocus loc = CompilerLocus.parse(raw);
        assertThat(loc.file()).isEqualTo("src/A.kt");
        assertThat(loc.line()).isEqualTo(3);
        assertThat(loc.col()).isZero();
    }

    @Test
    void non_compiler_text_is_null() {
        assertThat(CompilerLocus.parse("just some text")).isNull();
        assertThat(CompilerLocus.parse(null)).isNull();
        assertThat(CompilerLocus.parse("")).isNull();
    }

    @Test
    void real_groovyc_header_with_space_and_column_trailer_parses() {
        // JK-2113: groovyc writes "path: 5: message @ line 5, column 1." — space after the
        // first colon, column only in the trailer. This never matched on either surface.
        CompilerLocus locus =
                CompilerLocus.parse("/w/src/main/groovy/Foo.groovy: 5: unexpected token: } @ line 5, column 1.");
        org.assertj.core.api.Assertions.assertThat(locus).isNotNull();
        org.assertj.core.api.Assertions.assertThat(locus.file()).isEqualTo("/w/src/main/groovy/Foo.groovy");
        org.assertj.core.api.Assertions.assertThat(locus.line()).isEqualTo(5);
        org.assertj.core.api.Assertions.assertThat(locus.col()).isEqualTo(1);
    }
}
