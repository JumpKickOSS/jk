// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Ansi;
import cc.jumpkick.cli.theme.Coords;
import cc.jumpkick.cli.theme.Theme;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** {@link TestFailureHighlight} turns the engine's plain failure block into a styled report. */
class TestFailureHighlightTest {

    @AfterEach
    void clearLinkCache() {
        DashboardCodeLink.clearHttpCache();
    }

    @Test
    void paints_header_with_module_and_count() {
        List<String> raw = List.of(
                "Test Failure",
                "module: cc.jumpkick:jk-engine",
                "1 test failed",
                "",
                "FAILED DogfoodFailureSnippetTest.deliberately_fails_to_show_source_snippet()",
                "",
                "[dogfood: hello]",
                "expected: \"42\"",
                " but was: \"41\"",
                "",
                "@@source path=src/test/java/cc/jumpkick/runtime/DogfoodFailureSnippetTest.java line=23 start=19 lang=java",
                "@@src 19|",
                "@@src 20|        // comment",
                "@@src 23*|                .isEqualTo(42);",
                "@@src 24|    }",
                "@@src 25|}",
                "@@src-end",
                "    AssertionFailedError thrown at line 23");
        List<String> painted = TestFailureHighlight.paintLines(raw);
        String all = String.join(
                "\n", painted.stream().map(TestFailureHighlightTest::plain).toList());

        assertThat(all).contains("Failure");
        assertThat(all).contains("cc.jumpkick:jk-engine");
        assertThat(all).contains("1 test failed");
        assertThat(all).contains("FAILED DogfoodFailureSnippetTest.deliberately_fails_to_show_source_snippet()");
        assertThat(all).contains("AssertionFailedError thrown at line 23");
        assertThat(all).contains("\"dogfood: hello\"");
        assertThat(all).doesNotContain("[dogfood:");
        assertThat(all).contains("Expected: 42");
        assertThat(all).contains("But Was: 41");
        assertThat(all).contains("src/test/java/cc/jumpkick/runtime/DogfoodFailureSnippetTest.java:23");
        assertThat(all).contains("isEqualTo");
        assertThat(all).doesNotContain("org.opentest4j");
        assertThat(all).doesNotContain("@@source");
        // Order: FAILED → Expected → source → thrown-at → footer
        int failedAt = all.indexOf("FAILED Dogfood");
        int expectedAt = all.indexOf("Expected: 42");
        int pathAt = all.indexOf("src/test/java");
        int thrownAt = all.indexOf("AssertionFailedError thrown at line 23");
        int footerAt = all.indexOf(DiagnosticReport.FOOTER);
        assertThat(failedAt).isGreaterThan(0);
        assertThat(expectedAt).isGreaterThan(failedAt);
        assertThat(pathAt).isGreaterThan(expectedAt);
        assertThat(thrownAt).isGreaterThan(pathAt);
        assertThat(footerAt).isGreaterThan(thrownAt);

        assertThat(afterRail("Expected: 42", painted)).isEqualTo(" Expected: 42");
        assertThat(afterRail("But Was: 41", painted)).isEqualTo("  But Was: 41");
        assertThat(afterRail("\"dogfood: hello\"", painted)).isEqualTo("\"dogfood: hello\"");

        if (Theme.active().isAnsi()) {
            Theme t = Theme.active();
            assertThat(String.join("", painted)).contains(Coords.ga("cc.jumpkick", "jk-engine"));
            assertThat(String.join("", painted)).contains(Theme.colorize("Failure", t.midGray()));
            assertThat(String.join("", painted))
                    .contains(Theme.colorize("FAILED", t.error().bold()));
            assertThat(String.join("", painted)).contains(Theme.colorize(TestFailureHighlight.RAIL, t.error()));
            assertThat(String.join("", painted)).contains(Theme.colorize("›", t.darkGray()));
            assertThat(String.join("", painted)).contains(Theme.colorize("1", t.focused()));
        }
    }

    private static String plain(String s) {
        return AttributedString.stripAnsi(s == null ? "" : s);
    }

    /** Text after the rail on the first painted line that contains {@code needle}. Must be ANSI-free. */
    private static String afterRail(String needle, List<String> painted) {
        for (String line : painted) {
            if (!plain(line).contains(needle)) continue;
            String rest = extractAfterRail(line);
            assertThat(rest)
                    .as("assertion body must not be colorized: %s", rest)
                    .doesNotContain("\u001b");
            return rest;
        }
        throw new AssertionError("no painted line contained: " + needle);
    }

    private static String extractAfterRail(String painted) {
        if (painted == null) return "";
        String rail = TestFailureHighlight.RAIL;
        int idx = painted.indexOf(rail);
        int glyph = rail.length();
        if (idx < 0) {
            idx = painted.indexOf('|');
            glyph = 1;
            if (idx < 0) return painted;
        }
        return stripLeadingSgrAndOneSpace(painted.substring(idx + glyph));
    }

    /** {@code colorize(RAIL)} leaves an SGR reset immediately after the glyph, then a space. */
    private static String stripLeadingSgrAndOneSpace(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == '\u001b') {
            int m = s.indexOf('m', i);
            if (m < 0) break;
            i = m + 1;
        }
        if (i < s.length() && s.charAt(i) == ' ') i++;
        return s.substring(i);
    }

    @Test
    void source_path_is_osc8_deep_link_when_dashboard_is_known() {
        if (!Theme.active().isAnsi()) return;
        DashboardCodeLink.putHttpCache("http://127.0.0.1:8910/");
        DashboardCodeLink.putProjectId("proj");
        String path = "src/test/java/Foo.java";
        String expectedUrl = "http://127.0.0.1:8910#project/proj/files/src/test/java/Foo.java?line=9&err=true";
        String painted;
        try (var scope = DashboardCodeLink.open(Path.of("/ws"), Path.of("/ws"))) {
            painted = TestFailureHighlight.paintSourcePath(
                    path, "@@source path=" + path + " line=9 lang=java", Theme.active());
        }
        // AttributedString.stripAnsi leaves OSC-8; visible text is path:line for copy-paste.
        assertThat(painted).contains(Ansi.OSC + "8;;" + expectedUrl);
        assertThat(painted).contains(path);
        assertThat(cc.jumpkick.cli.tui.RenderContext.stripAnsi(painted)).isEqualTo(path + ":9");
        // Full failure block also carries the OSC-8 target on the path line.
        List<String> block;
        try (var scope = DashboardCodeLink.open(Path.of("/ws"), Path.of("/ws"))) {
            block = TestFailureHighlight.paintLines(List.of(
                    "Test Failure",
                    "1 test failed",
                    "",
                    "FAILED Foo.bar()",
                    "",
                    "@@source path=" + path + " line=9 start=7 lang=java",
                    "@@src 7|  x();",
                    "@@src 9*|  assertThat(1).isEqualTo(2);",
                    "@@src-end",
                    "    AssertionFailedError thrown at line 9"));
        }
        assertThat(String.join("\n", block)).contains(Ansi.OSC + "8;;" + expectedUrl);
    }

    @Test
    void header_is_test_pill_plus_failure() {
        String h = TestFailureHighlight.paintHeaderLine(null, 1, false);
        assertThat(plain(h)).contains("Test").contains("Failure");
        assertThat(plain(h)).contains("1 test failed");
        if (Theme.active().isAnsi()) {
            assertThat(h).contains("\u001b");
            Theme t = Theme.active();
            assertThat(h).contains(Theme.colorize("Failure", t.midGray()));
            assertThat(h).contains(Theme.colorize("1", t.focused()));
        }
    }

    @Test
    void paints_legacy_expecting_actual_layout() {
        List<String> raw = List.of(
                "Test Failure",
                "1 test failed",
                "",
                "FAILED NativeEffortTest.size_model()",
                "",
                "Expecting actual:",
                "  21670L",
                "to be between:",
                "  [28000L, 45000L]");
        List<String> painted = TestFailureHighlight.paintLines(raw);
        String all = plain(String.join("\n", painted));
        assertThat(all).contains("FAILED NativeEffortTest.size_model()");
        assertThat(all).contains("21670L");
        assertThat(all).contains(DiagnosticReport.FOOTER);
        assertThat(afterRail("Expecting actual:", painted)).isEqualTo("Expecting actual:");
        assertThat(afterRail("21670L", painted)).isEqualTo("  21670L");
        assertThat(afterRail("to be between:", painted)).isEqualTo("to be between:");
        assertThat(afterRail("[28000L, 45000L]", painted)).isEqualTo("  [28000L, 45000L]");
    }

    @Test
    void expecting_actual_body_is_uncolored() {
        List<String> raw = List.of(
                "Test Failure",
                "1 test failed",
                "",
                "FAILED AssemblyPackagerTest.merges()",
                "",
                "Expecting actual:",
                "  \"app.Provider",
                "lib.Provider\"",
                "to contain:",
                "  \"lib.Prooovider\"");
        List<String> painted = TestFailureHighlight.paintLines(raw);
        String all = plain(String.join("\n", painted));
        assertThat(all).contains("Expecting actual:");
        assertThat(all).contains("to contain:");
        assertThat(all).contains("lib.Prooovider");
        assertThat(afterRail("Expecting actual:", painted)).isEqualTo("Expecting actual:");
        assertThat(afterRail("app.Provider", painted)).isEqualTo("  \"app.Provider");
        assertThat(afterRail("lib.Provider\"", painted)).isEqualTo("lib.Provider\"");
        assertThat(afterRail("to contain:", painted)).isEqualTo("to contain:");
        assertThat(afterRail("lib.Prooovider", painted)).isEqualTo("  \"lib.Prooovider\"");
    }

    @Test
    void blank_inside_assertion_body_does_not_leak_source_markers() {
        List<String> raw = List.of(
                "Test Failure",
                "1 test failed",
                "",
                "FAILED Foo.bar()",
                "",
                "expected:",
                "",
                "<42>",
                "but was:",
                "",
                "<41>",
                "",
                "@@source path=src/test/java/Foo.java line=9 start=6 lang=java",
                "@@src 6|    void bar() {",
                "@@src 9*|        assertEquals(42, 41);",
                "@@src 10|    }",
                "@@src-end",
                "    AssertionFailedError thrown at line 9",
                "Test Failure end",
                "Note: leftover output");
        List<String> painted = TestFailureHighlight.paintLines(raw);
        String all = plain(String.join("\n", painted));
        assertThat(all).doesNotContain("@@source");
        assertThat(all).doesNotContain("@@src");
        assertThat(all).contains("AssertionFailedError thrown at line 9");
        assertThat(all).contains("Expected: 42");
        assertThat(all).contains("But Was: 41");
        assertThat(all).contains("Foo.java:9");
        assertThat(all).contains(DiagnosticReport.FOOTER);
        assertThat(all).contains("Note: leftover output");
        assertThat(all).doesNotContain("Test Failure end");
        assertThat(all.indexOf("Note: leftover output")).isGreaterThan(all.indexOf(DiagnosticReport.FOOTER));
    }

    @Test
    void snippet_rows_clamp_to_terminal_width_and_expand_tabs() {
        // one over-long source line must not pad every row past the terminal; tabs
        // expand so the band pad math is column-based.
        String longLine = "        assertThat(x)" + ".describedAs(\"padding\")".repeat(20) + ";";
        List<String> raw = List.of(
                "Test Failure",
                "1 test failed",
                "",
                "FAILED Foo.bar()",
                "",
                "@@source line=2 start=1 lang=java path=Foo.java",
                "@@src 1|\tint tabbed = 1;",
                "@@src 2*|" + longLine,
                "@@src-end",
                "Test Failure end");
        List<String> painted = TestFailureHighlight.paintLines(raw);
        for (String line : painted) {
            assertThat(plain(line)).doesNotContain("\t");
        }
        int widest = painted.stream()
                .map(TestFailureHighlightTest::plain)
                .mapToInt(String::length)
                .max()
                .orElse(0);
        // Terminal defaults to >= 80 in tests; rows must stay within the detected width.
        assertThat(widest).isLessThanOrEqualTo(cc.jumpkick.cli.tui.TerminalSize.columns());
        assertThat(String.join(
                        "\n",
                        painted.stream().map(TestFailureHighlightTest::plain).toList()))
                .contains("…");
    }

    @Test
    void cjk_snippet_rows_clamp_by_columns_not_code_units() {
        // A CJK comment measures ~half its real width in UTF-16 code units: measured by
        // code units it escaped the clamp, padded every row past the terminal, and wrapped
        // the band without the rail.
        String cjkLine = "        int x = 1; // " + "构建工具诊断".repeat(20);
        List<String> raw = List.of(
                "Test Failure",
                "1 test failed",
                "",
                "FAILED Foo.bar()",
                "",
                "@@source line=2 start=1 lang=java path=Foo.java",
                "@@src 1|int ok = 1;",
                "@@src 2*|" + cjkLine,
                "@@src-end",
                "Test Failure end");
        List<String> painted = TestFailureHighlight.paintLines(raw);
        int widest = painted.stream()
                .map(TestFailureHighlightTest::plain)
                .mapToInt(cc.jumpkick.cli.tui.RenderContext::visibleWidth)
                .max()
                .orElse(0);
        assertThat(widest).isLessThanOrEqualTo(cc.jumpkick.cli.tui.TerminalSize.columns());
        // No lone surrogate survives the cut.
        for (String line : painted) {
            String p = plain(line);
            for (int i = 0; i < p.length(); i++) {
                if (Character.isHighSurrogate(p.charAt(i))) {
                    assertThat(i + 1 < p.length() && Character.isLowSurrogate(p.charAt(i + 1)))
                            .as("lone high surrogate in: %s", p)
                            .isTrue();
                }
            }
        }
    }

    @Test
    void plain_mode_snippet_clamp_stays_pure_ascii() throws Exception {
        // clampCode appended U+2026 before the ANSI/plain fork, re-leaking a non-ASCII
        // char into output  had just made pure ASCII on CI/dumb terminals.
        String longLine = "        assertThat(x)" + ".describedAs(\"padding\")".repeat(20) + ";";
        List<String> raw = List.of(
                "Test Failure",
                "1 test failed",
                "",
                "FAILED Foo.bar()",
                "",
                "@@source line=2 start=1 lang=java path=Foo.java",
                "@@src 1|int ok = 1;",
                "@@src 2*|" + longLine,
                "@@src-end",
                "Test Failure end");
        cc.jumpkick.config.JkConfig noAnsi = cc.jumpkick.config.JkConfig.empty().withNoAnsi(Optional.of(true));
        cc.jumpkick.config.Session original = cc.jumpkick.config.SessionContext.current();
        List<String> painted;
        try {
            painted = cc.jumpkick.config.SessionContext.where(
                    original.withConfig(noAnsi), () -> TestFailureHighlight.paintLines(raw));
        } finally {
            cc.jumpkick.config.SessionContext.install(original);
        }
        String all = String.join("\n", painted);
        assertThat(all.chars().allMatch(c -> c < 128))
                .as("plain mode output must be pure ASCII, got: %s", all)
                .isTrue();
        assertThat(all).contains("...");
    }

    @Test
    void source_paths_with_spaces_render_intact() {
        // the emitter puts path= last (to end-of-line); old mid-line form still parses.
        List<String> raw = List.of(
                "Test Failure",
                "1 test failed",
                "",
                "FAILED FooSpec.works()",
                "",
                "@@source line=3 start=1 lang=groovy path=src/test/groovy/My Specs/FooSpec.groovy",
                "@@src 1|class FooSpec {",
                "@@src 3*|  def works() {}",
                "@@src-end",
                "    Error thrown at line 3",
                "Test Failure end");
        List<String> painted = TestFailureHighlight.paintLines(raw);
        String all = String.join(
                "\n", painted.stream().map(TestFailureHighlightTest::plain).toList());
        assertThat(all).contains("src/test/groovy/My Specs/FooSpec.groovy:3");

        // Old-format header (path mid-line, no spaces) keeps parsing.
        List<String> old = List.of(
                "Test Failure",
                "1 test failed",
                "",
                "FAILED Foo.bar()",
                "",
                "@@source path=Foo.java line=3 start=1 lang=java",
                "@@src 3*|  void bar() {}",
                "@@src-end",
                "Test Failure end");
        String oldAll = String.join(
                "\n",
                TestFailureHighlight.paintLines(old).stream()
                        .map(TestFailureHighlightTest::plain)
                        .toList());
        assertThat(oldAll).contains("Foo.java:3");
        assertThat(oldAll).doesNotContain("Foo.java line=");
    }

    @Test
    void locusLabel_appends_line_and_column() {
        assertThat(TestFailureHighlight.locusLabel("", 1, 1)).isEmpty();
        assertThat(TestFailureHighlight.locusLabel("src/Main.java", 0, 7)).isEqualTo("src/Main.java");
        assertThat(TestFailureHighlight.locusLabel("src/Main.java", 12, 0)).isEqualTo("src/Main.java:12");
        assertThat(TestFailureHighlight.locusLabel("src/Main.java", 12, 7)).isEqualTo("src/Main.java:12:7");
    }

    @Test
    void no_snippet_failure_keeps_assertj_reformat_and_type_colored_exception() {
        // The engine's no-snippet shape (escape-rejected, moved/generated, inherited test): bare
        // "    ExceptionClass" between assertion body and frames — it must flush the assertion
        // buffer, not ride into it and defeat the AssertJ reformat.
        List<String> raw = List.of(
                "Test Failure",
                "module: cc.jumpkick:jk-engine",
                "1 test failed",
                "",
                "FAILED FooTest.bar()",
                "",
                "expected: \"42\"",
                " but was: \"41\"",
                "",
                "    AssertionFailedError",
                "",
                "\tat cc.jumpkick.FooTest.bar(FooTest.java:9)",
                "Test Failure end");
        List<String> painted = TestFailureHighlight.paintLines(raw);
        String all = String.join(
                "\n", painted.stream().map(TestFailureHighlightTest::plain).toList());

        assertThat(all).contains("Expected: 42");
        assertThat(all).contains("But Was: 41");
        assertThat(all).contains("AssertionFailedError");
        assertThat(all).contains("at cc.jumpkick.FooTest.bar(FooTest.java:9)");
        // The exception is a locus line, not part of the assertion body.
        int expectedAt = all.indexOf("Expected: 42");
        int exAt = all.indexOf("AssertionFailedError");
        assertThat(exAt).isGreaterThan(expectedAt);
    }

    @Test
    void indented_capitalized_token_inside_an_assertion_value_stays_in_the_body() {
        List<String> raw = List.of(
                "Test Failure",
                "1 test failed",
                "",
                "FAILED FooTest.bar()",
                "",
                "expected:",
                "    Alpha",
                " but was:",
                "    Beta",
                "",
                "    AssertionFailedError",
                "Test Failure end");
        List<String> painted = TestFailureHighlight.paintLines(raw);
        String all = String.join(
                "\n", painted.stream().map(TestFailureHighlightTest::plain).toList());
        // "Alpha" follows a non-blank line — it belongs to the assertion body, not the locus.
        assertThat(all).contains("Alpha");
        assertThat(all).contains("Beta");
        assertThat(all).contains("AssertionFailedError");
    }

    @Test
    void non_failure_output_passes_through_stack_highlight() {
        List<String> raw = List.of("Note: something", "\tat cc.jumpkick.Foo.bar(Foo.java:1)");
        List<String> painted = TestFailureHighlight.paintLines(raw);
        assertThat(plain(painted.get(0))).isEqualTo("Note: something");
        assertThat(plain(painted.get(1))).isEqualTo("\tat cc.jumpkick.Foo.bar(Foo.java:1)");
    }

    @Test
    void expandedCol_translates_raw_indexes_through_tab_stops() {
        // "\tfoo.bar()" expands to "    foo.bar()": raw index 1 ('f') displays at column 4.
        assertThat(TestFailureHighlight.expandedCol("\tfoo.bar()", 1)).isEqualTo(4);
        // Two tabs, then code: raw index 2 displays at column 8.
        assertThat(TestFailureHighlight.expandedCol("\t\tbar()", 2)).isEqualTo(8);
        // A mid-line tab pads to the next 4-column stop, not a fixed width.
        assertThat(TestFailureHighlight.expandedCol("ab\tcd", 3)).isEqualTo(4);
        // Tab-free lines and unset marks pass through untouched.
        assertThat(TestFailureHighlight.expandedCol("    foo()", 4)).isEqualTo(4);
        assertThat(TestFailureHighlight.expandedCol("\tfoo()", -1)).isEqualTo(-1);
        assertThat(TestFailureHighlight.expandedCol("\tfoo()", 0)).isZero();
    }

    @Test
    void tab_indented_error_line_underlines_the_right_token() {
        if (!Theme.active().isAnsi()) return;
        // Raw column 1 is 'f'; after tab expansion the underline must cover "foo", not drift
        // into the indent.
        List<String> window = TestFailureHighlight.paintSourceWindow(
                "Foo.java", List.of("\tfoo.bar();"), 1, 1, SyntaxHighlight.Language.JAVA);
        AttributedString row = AttributedString.fromAnsi(window.get(1));
        long underlineBit = AttributedStyle.DEFAULT.underline().getStyle();
        StringBuilder marked = new StringBuilder();
        for (int i = 0; i < row.length(); i++) {
            if ((row.styleAt(i).getStyle() & underlineBit) != 0) marked.append(row.charAt(i));
        }
        assertThat(marked.toString()).isEqualTo("foo");
    }

    @Test
    void shortDisplayLabel_strips_package_fqcns() {
        assertThat(TestFailureHighlight.shortDisplayLabel("cc.jumpkick.runtime.FooTest.freshen(java.nio.file.Path)"))
                .isEqualTo("FooTest.freshen(Path)");
        assertThat(TestFailureHighlight.shortDisplayLabel("FooTest.bar(java.lang.String, java.util.List)"))
                .isEqualTo("FooTest.bar(String, List)");
        assertThat(TestFailureHighlight.shortDisplayLabel("org.opentest4j.AssertionFailedError"))
                .isEqualTo("AssertionFailedError");
        assertThat(TestFailureHighlight.shortDisplayLabel("FooTest.bar(Path)  [w2]"))
                .isEqualTo("FooTest.bar(Path)  [w2]");
        assertThat(TestFailureHighlight.shortDisplayLabel("bar(java.nio.file.Path)"))
                .isEqualTo("bar(Path)");
        assertThat(TestFailureHighlight.shortDisplayLabel("foo(java.lang.String)[#2]"))
                .isEqualTo("foo(String)[#2]");
        // Prose / versions / jars must not be mangled.
        assertThat(TestFailureHighlight.shortDisplayLabel("package jk-engine-0.12.0.jar"))
                .isEqualTo("package jk-engine-0.12.0.jar");
        assertThat(TestFailureHighlight.shortDisplayLabel("compiling 12 sources"))
                .isEqualTo("compiling 12 sources");
        assertThat(TestFailureHighlight.shortDisplayLabel("1.2.3")).isEqualTo("1.2.3");
    }

    @Test
    void paintShortLabel_never_renders_package_fqcns() {
        String painted = TestFailureHighlight.paintShortLabel(
                "cc.jumpkick.runtime.DogfoodFailureSnippetTest.deliberately_fails(java.nio.file.Path)", Theme.active());
        String p = plain(painted);
        assertThat(p).isEqualTo("DogfoodFailureSnippetTest.deliberately_fails(Path)");
        assertThat(p).doesNotContain("java.nio");
        assertThat(p).doesNotContain("cc.jumpkick");
    }
}
