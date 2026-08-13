// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.theme.Coords;
import cc.jumpkick.cli.theme.Theme;
import java.util.List;
import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;

/** {@link TestFailureHighlight} turns the engine's plain failure block into a styled report. */
class TestFailureHighlightTest {

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
        assertThat(all).contains("src/test/java/cc/jumpkick/runtime/DogfoodFailureSnippetTest.java");
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

    @Test
    void header_is_test_pill_plus_failure() {
        String h = TestFailureHighlight.paintHeader();
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
        if (Theme.active().isAnsi()) {
            Theme t = Theme.active();
            assertThat(String.join("", painted)).contains(Theme.colorize("21670L", t.error()));
            assertThat(String.join("", painted)).contains(Theme.colorize("[28000L, 45000L]", t.success()));
        }
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
        assertThat(all).contains("Foo.java");
        assertThat(all).contains(DiagnosticReport.FOOTER);
        assertThat(all).contains("Note: leftover output");
        assertThat(all).doesNotContain("Test Failure end");
        assertThat(all.indexOf("Note: leftover output")).isGreaterThan(all.indexOf(DiagnosticReport.FOOTER));
    }

    @Test
    void source_paths_with_spaces_render_intact() {
        // JK-1905: the emitter puts path= last (to end-of-line); old mid-line form still parses.
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
        assertThat(all).contains("src/test/groovy/My Specs/FooSpec.groovy");

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
        assertThat(oldAll).contains("Foo.java");
        assertThat(oldAll).doesNotContain("Foo.java line=");
    }

    @Test
    void no_snippet_failure_keeps_assertj_reformat_and_type_colored_exception() {
        // The engine's no-snippet shape (escape-rejected, moved/generated, inherited test): bare
        // "    ExceptionClass" between assertion body and frames — it must flush the assertion
        // buffer, not ride into it and defeat the AssertJ reformat (JK-1883).
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
