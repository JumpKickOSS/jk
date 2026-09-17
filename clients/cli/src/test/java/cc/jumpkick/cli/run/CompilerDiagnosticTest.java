// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.terminal.Width;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link CompilerDiagnostic} paints aligned keys and an editor-style source window. */
class CompilerDiagnosticTest {

    @AfterEach
    void clearLinkCache() {
        DashboardCodeLink.clearHttpCache();
    }

    private static String plain(String rendered) {
        return Width.stripAnsi(rendered == null ? "" : rendered);
    }

    @Test
    void aligns_keys_and_drops_the_caret(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("Foo.java");
        Files.writeString(src, String.join("\n", "class Foo {", "    @Test", "    void t() {}", "}", ""));
        String raw = String.join(
                "\n",
                src + ":2: error: cannot find symbol",
                "    @Test",
                "     ^",
                "  symbol:   class Test",
                "  location: class Foo");
        String rendered = CompilerDiagnostic.render(raw);
        String p = plain(rendered);
        assertThat(p).contains("error: cannot find symbol");
        assertThat(p).contains("symbol: class Test");
        assertThat(p).contains("location: class Foo");
        assertThat(p).doesNotContain("\n     ^");
        assertThat(p).contains("Foo.java:2:");
        assertThat(p).contains("@Test");
        assertThat(p).contains("void t()");
        // Colons of the trailer keys line up with "error:" (column, not string index).
        int errorColon = -1;
        int symbolColon = -1;
        int locColon = -1;
        for (String row : p.split("\n")) {
            if (row.contains("error:") && errorColon < 0) errorColon = row.indexOf(':');
            if (row.contains("symbol:")) symbolColon = row.indexOf(':');
            if (row.contains("location:")) locColon = row.indexOf(':');
        }
        assertThat(errorColon).isGreaterThan(0);
        assertThat(symbolColon).isEqualTo(errorColon);
        assertThat(locColon).isEqualTo(errorColon);
        if (Theme.active().isAnsi()) {
            assertThat(rendered).contains("\u001b");
        }
    }

    @Test
    void kotlin_header_column_marks_the_token(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("Baz.kt");
        Files.writeString(src, "fun f() {\n    @Test\n}\n");
        String raw = src + ":2:5: error: unresolved reference: Test\n    @Test\n     ^";
        String p = plain(CompilerDiagnostic.render(raw));
        assertThat(p).contains("error: unresolved reference: Test");
        assertThat(p).contains("Baz.kt:2:");
        assertThat(p).contains("@Test");
        assertThat(p).doesNotContain("^");
    }

    @Test
    void k2_header_keeps_the_column_out_of_the_message(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("Baz.kt");
        Files.writeString(src, "fun f() {\n    @Test\n}\n");
        String raw = src.toUri() + ":2:5 Unresolved reference 'Test'.";
        String p = plain(CompilerDiagnostic.render(raw));
        assertThat(p).contains("error: Unresolved reference 'Test'.");
        assertThat(p).doesNotContain("error: 5 ");
        assertThat(p).contains("Baz.kt:2:");
        assertThat(p).contains("@Test");
    }

    @Test
    void single_line_diagnostic_with_no_snippet_keeps_the_path() {
        String raw = "Bar.java:3: error: package org.junit.jupiter.api does not exist";
        String p = plain(CompilerDiagnostic.render(raw));
        assertThat(p).contains("error: package org.junit.jupiter.api does not exist");
        assertThat(p).contains("Bar.java:3");
    }

    @Test
    void non_diagnostic_text_passes_through_untouched() {
        String raw = "just some text\nwith no header";
        assertThat(CompilerDiagnostic.render(raw)).isEqualTo(raw);
    }

    @Test
    void source_path_is_osc8_deep_link_with_column(@TempDir Path tmp) throws Exception {
        if (!Theme.active().isAnsi()) return;
        Path src = tmp.resolve("Main.java");
        Files.writeString(src, "class Main {\n    void x() { y(); }\n}\n");
        DashboardCodeLink.putHttpCache("http://127.0.0.1:8910/");
        DashboardCodeLink.putProjectId("proj");
        String raw = src + ":2:18: error: cannot find symbol\n    void x() { y(); }\n                 ^";
        String rendered;
        try (var scope = DashboardCodeLink.open(tmp, tmp)) {
            rendered = CompilerDiagnostic.render(raw);
        }
        assertThat(rendered).contains("?line=2");
        assertThat(rendered).contains("&col=18");
        assertThat(rendered).contains("&err=true");
        assertThat(rendered).contains("&msg=");
        assertThat(rendered).contains("cannot%20find%20symbol");
        assertThat(plain(rendered)).contains("Main.java:2:18");
    }

    @Test
    // The nulls are deliberate: a diagnostic with no key-values and no extra lines.
    @SuppressWarnings("NullAway")
    void formatNote_joins_keys_and_extras() {
        String note = CompilerDiagnostic.formatNote(
                List.of(
                        new CompilerDiagnostic.Kv("error", "cannot find symbol"),
                        new CompilerDiagnostic.Kv("symbol", "class Test")),
                List.of("  extra line  "));
        assertThat(note).isEqualTo("error: cannot find symbol\nsymbol: class Test\nextra line");
        assertThat(CompilerDiagnostic.formatNote(null, null)).isEmpty();
    }

    @Test
    void identifier_end_covers_the_token() {
        assertThat(TestFailureHighlight.identifierEnd("    b.key(x)", 6)).isEqualTo(9);
        assertThat(TestFailureHighlight.identifierEnd("    @Test", 5)).isEqualTo(9);
        assertThat(TestFailureHighlight.identifierEnd("foo + bar", 4)).isEqualTo(5);
    }

    @Test
    void trailer_keys_reject_source_like_colons() {
        assertThat(CompilerDiagnostic.looksLikeTrailer("  symbol")).isTrue();
        assertThat(CompilerDiagnostic.looksLikeTrailer("location")).isTrue();
        assertThat(CompilerDiagnostic.looksLikeTrailer("    foo(a")).isFalse();
    }

    @Test
    void split_kv_uses_the_first_colon_only() {
        CompilerDiagnostic.Kv kv = CompilerDiagnostic.splitKv("error: unresolved reference: Test", "warning");
        assertThat(kv.key()).isEqualTo("error");
        assertThat(kv.value()).isEqualTo("unresolved reference: Test");
    }

    @Test
    void colon_less_rest_keys_under_the_block_severity() {
        CompilerDiagnostic.Kv kv = CompilerDiagnostic.splitKv("deprecated API usage", "warning");
        assertThat(kv.key()).isEqualTo("warning");
        assertThat(kv.value()).isEqualTo("deprecated API usage");
        String p = plain(CompilerDiagnostic.render("Bar.java:3: deprecated API usage", "warning"));
        assertThat(p).contains("warning: deprecated API usage");
        assertThat(p).doesNotContain("error:");
    }

    @Test
    void source_reads_are_memoized_per_render_pass(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("Memo.java");
        Files.writeString(src, "class Memo { X x; }\n");
        var memo = new HashMap<String, List<String>>();
        List<String> first = CompilerDiagnostic.readSource(src.toString(), memo);
        assertThat(first).containsExactly("class Memo { X x; }");
        // A later unit in the same pass never touches disk again.
        Files.delete(src);
        assertThat(CompilerDiagnostic.readSource(src.toString(), memo)).isSameAs(first);
        // Failed reads are memoized too.
        var missMemo = new HashMap<String, List<String>>();
        assertThat(CompilerDiagnostic.readSource(src.toString(), missMemo)).isNull();
        assertThat(missMemo).containsKey(src.toString());
    }

    @Test
    void oversized_sources_are_not_slurped(@TempDir Path tmp) throws Exception {
        Path big = tmp.resolve("Big.java");
        byte[] bytes = new byte[(int) CompilerDiagnostic.MAX_SOURCE_BYTES + 1];
        Arrays.fill(bytes, (byte) 'x');
        Files.write(big, bytes);
        assertThat(CompilerDiagnostic.readSource(big.toString())).isNull();
    }

    @Test
    void two_diagnostics_in_one_block_each_get_a_window(@TempDir Path tmp) throws Exception {
        Path a = tmp.resolve("A.java");
        Path b = tmp.resolve("B.java");
        Files.writeString(a, "class A { X x; }\n");
        Files.writeString(b, "class B { Y y; }\n");
        String raw = String.join(
                "\n",
                a + ":1: error: cannot find symbol",
                "class A { X x; }",
                "          ^",
                "  symbol: class X",
                b + ":1: error: cannot find symbol",
                "class B { Y y; }",
                "          ^",
                "  symbol: class Y");
        String p = plain(CompilerDiagnostic.render(raw));
        assertThat(p).contains("A.java:1:");
        assertThat(p).contains("B.java:1:");
        assertThat(p).contains("class X");
        assertThat(p).contains("class Y");
    }
}
