// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Matcher;
import org.junit.jupiter.api.Test;

class CompilerLocusTest {

    /**
     * {@code file:///ws/app/src/Main.kt} as this host spells that path. {@link CompilerLocus#fileName}
     * resolves the URI to a real {@link Path}, so the separator is the platform's — a POSIX literal
     * here passes everywhere but Windows, where the same file reads {@code \ws\app\src\Main.kt}.
     */
    private static final String MAIN_KT = Path.of("/ws/app/src/Main.kt").toString();

    @Test
    void javac_header_without_column_uses_the_caret() {
        String raw = String.join(
                "\n", "/ws/Foo.java:42: error: cannot find symbol", "    @Test", "     ^", "  symbol: class Test");
        CompilerLocus loc = parsed(raw);
        assertThat(loc.file()).isEqualTo("/ws/Foo.java");
        assertThat(loc.line()).isEqualTo(42);
        assertThat(loc.col()).isEqualTo(6);
    }

    @Test
    void kotlinc_header_column_wins_over_a_later_caret() {
        String raw = "src/Baz.kt:2:5: error: unresolved reference: Test\n    @Test\n     ^";
        CompilerLocus loc = parsed(raw);
        assertThat(loc.file()).isEqualTo("src/Baz.kt");
        assertThat(loc.line()).isEqualTo(2);
        assertThat(loc.col()).isEqualTo(5);
    }

    @Test
    void groovy_header_is_recognised() {
        CompilerLocus loc = parsed("app/Main.groovy:8: error: unexpected token");
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
        CompilerLocus loc = parsed(raw);
        assertThat(loc.file()).isEqualTo("src/A.kt");
        assertThat(loc.line()).isEqualTo(3);
        assertThat(loc.col()).isZero();
    }

    /** The header the engine synthesises for a scalac problem: the Scala source, its line and column. */
    @Test
    void scalac_header_parses_file_line_and_column() {
        CompilerLocus locus = CompilerLocus.parse("/w/proj/src/main/scala/demo/App.scala:5:10: error: Not found: foo");
        assertThat(locus).isEqualTo(new CompilerLocus("/w/proj/src/main/scala/demo/App.scala", 5, 10));
        assertThat(CompilerLocus.parse("/w/build.sc:2: error: expected class or object definition"))
                .isEqualTo(new CompilerLocus("/w/build.sc", 2, 0));
    }

    @Test
    void non_compiler_text_is_null() {
        assertThat(CompilerLocus.parse("just some text")).isNull();
        assertThat(CompilerLocus.parse(null)).isNull();
        assertThat(CompilerLocus.parse("")).isNull();
    }

    @Test
    void real_groovyc_header_with_space_and_column_trailer_parses() {
        // : groovyc writes "path: 5: message @ line 5, column 1." — space after the
        // first colon, column only in the trailer. This never matched on either surface.
        CompilerLocus locus = parsed("/w/src/main/groovy/Foo.groovy: 5: unexpected token: } @ line 5, column 1.");
        assertThat(locus.file()).isEqualTo("/w/src/main/groovy/Foo.groovy");
        assertThat(locus.line()).isEqualTo(5);
        assertThat(locus.col()).isEqualTo(1);
    }

    @Test
    void k2_header_with_a_space_after_the_column_parses_to_a_path() {
        CompilerLocus locus = parsed("file:///ws/app/src/Main.kt:3:5 Unresolved reference 'missing'.");
        assertThat(locus.file()).isEqualTo(MAIN_KT);
        assertThat(locus.line()).isEqualTo(3);
        assertThat(locus.col()).isEqualTo(5);
    }

    @Test
    void k2_header_rest_is_the_message_without_the_column() {
        Matcher m = CompilerLocus.HEADER.matcher("file:///ws/app/src/Main.kt:3:5 Unresolved reference 'missing'.");
        assertThat(m.matches()).isTrue();
        assertThat(m.group("col")).isEqualTo("5");
        assertThat(m.group("rest")).isEqualTo("Unresolved reference 'missing'.");
        assertThat(CompilerLocus.fileName(m.group("file"))).isEqualTo(MAIN_KT);
    }

    @Test
    void a_plain_path_is_its_own_file_name() {
        assertThat(CompilerLocus.fileName("/ws/Foo.java")).isEqualTo("/ws/Foo.java");
        assertThat(CompilerLocus.fileName("src/Foo.kt")).isEqualTo("src/Foo.kt");
    }

    /** {@link CompilerLocus#parse}, failing the test when the text is not a compiler diagnostic. */
    private static CompilerLocus parsed(String raw) {
        return Objects.requireNonNull(CompilerLocus.parse(raw), () -> "no compiler locus in: " + raw);
    }
}
