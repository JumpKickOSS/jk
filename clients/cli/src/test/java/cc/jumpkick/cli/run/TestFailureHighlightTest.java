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
        String all = String.join("\n", painted.stream().map(TestFailureHighlightTest::plain).toList());

        assertThat(all).contains("✘ Test failure in cc.jumpkick:jk-engine › 1 test failed");
        assertThat(all)
                .contains("FAILED DogfoodFailureSnippetTest.deliberately_fails_to_show_source_snippet()");
        assertThat(all).contains("AssertionFailedError thrown at line 23");
        assertThat(all).contains("\"dogfood: hello\"");
        assertThat(all).doesNotContain("[dogfood:");
        assertThat(all).contains("Expected: 42");
        assertThat(all).contains("But Was: 41");
        assertThat(all).contains("src/test/java/cc/jumpkick/runtime/DogfoodFailureSnippetTest.java");
        assertThat(all).contains("isEqualTo");
        assertThat(all).doesNotContain("org.opentest4j");
        assertThat(all).doesNotContain("@@source");
        // No thick rail or corner footer
        assertThat(all).doesNotContain("┃");
        assertThat(all).doesNotContain(DiagnosticReport.FOOTER);
        // Order: FAILED → Expected → source → thrown-at
        int failedAt = all.indexOf("FAILED Dogfood");
        int expectedAt = all.indexOf("Expected: 42");
        int pathAt = all.indexOf("src/test/java");
        int thrownAt = all.indexOf("AssertionFailedError thrown at line 23");
        assertThat(failedAt).isGreaterThan(0);
        assertThat(expectedAt).isGreaterThan(failedAt);
        assertThat(pathAt).isGreaterThan(expectedAt);
        assertThat(thrownAt).isGreaterThan(pathAt);

        if (Theme.active().isAnsi()) {
            Theme t = Theme.active();
            assertThat(String.join("", painted)).contains(Coords.ga("cc.jumpkick", "jk-engine"));
            assertThat(String.join("", painted)).contains(Theme.colorize("✘", t.error()));
            assertThat(String.join("", painted)).contains(Theme.colorize("FAILED", t.error().bold()));
        }
    }

    private static String plain(String s) {
        return AttributedString.stripAnsi(s == null ? "" : s);
    }

    @Test
    void header_is_cross_plus_test_failure() {
        String h = TestFailureHighlight.paintHeader();
        assertThat(plain(h)).contains("✘").contains("Test failure");
        if (Theme.active().isAnsi()) {
            assertThat(h).contains("\u001b");
            assertThat(h).contains(Theme.colorize("✘", Theme.active().error()));
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
        assertThat(all).doesNotContain(DiagnosticReport.FOOTER);
        if (Theme.active().isAnsi()) {
            Theme t = Theme.active();
            assertThat(String.join("", painted)).contains(Theme.colorize("21670L", t.error()));
            assertThat(String.join("", painted)).contains(Theme.colorize("[28000L, 45000L]", t.success()));
        }
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
        assertThat(TestFailureHighlight.shortDisplayLabel(
                        "cc.jumpkick.runtime.FooTest.freshen(java.nio.file.Path)"))
                .isEqualTo("FooTest.freshen(Path)");
        assertThat(TestFailureHighlight.shortDisplayLabel("FooTest.bar(java.lang.String, java.util.List)"))
                .isEqualTo("FooTest.bar(String, List)");
        assertThat(TestFailureHighlight.shortDisplayLabel("org.opentest4j.AssertionFailedError"))
                .isEqualTo("AssertionFailedError");
        assertThat(TestFailureHighlight.shortDisplayLabel("FooTest.bar(Path)  [w2]"))
                .isEqualTo("FooTest.bar(Path)  [w2]");
        assertThat(TestFailureHighlight.shortDisplayLabel("bar(java.nio.file.Path)"))
                .isEqualTo("bar(Path)");
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
                "cc.jumpkick.runtime.DogfoodFailureSnippetTest.deliberately_fails(java.nio.file.Path)",
                Theme.active());
        String p = plain(painted);
        assertThat(p).isEqualTo("DogfoodFailureSnippetTest.deliberately_fails(Path)");
        assertThat(p).doesNotContain("java.nio");
        assertThat(p).doesNotContain("cc.jumpkick");
    }
}
