// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestFailureSourceTest {

    @Test
    void parses_java_stack_frame() {
        var f = TestFailureSource.parseFrame(
                        "\tat cc.jumpkick.runtime.FooTest.bar(FooTest.java:42)")
                .orElseThrow();
        assertThat(f.className()).isEqualTo("cc.jumpkick.runtime.FooTest");
        assertThat(f.method()).isEqualTo("bar");
        assertThat(f.fileName()).isEqualTo("FooTest.java");
        assertThat(f.line()).isEqualTo(42);
    }

    @Test
    void parses_kotlin_and_groovy_file_names() {
        assertThat(TestFailureSource.parseFrame("\tat c.F.m(F.kt:9)").orElseThrow().fileName())
                .isEqualTo("F.kt");
        assertThat(TestFailureSource.parseFrame("\tat c.F.m(F.groovy:3)").orElseThrow().fileName())
                .isEqualTo("F.groovy");
    }

    @Test
    void prefers_test_class_frame_over_framework() {
        String stack = """
                org.opentest4j.AssertionFailedError: nope
                	at org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:100)
                	at cc.jumpkick.runtime.FooTest.bar(FooTest.java:15)
                	at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)
                """;
        var f = TestFailureSource.primaryFrame(stack, "cc.jumpkick.runtime.FooTest").orElseThrow();
        assertThat(f.line()).isEqualTo(15);
        assertThat(f.fileName()).isEqualTo("FooTest.java");
    }

    @Test
    void window_keeps_seven_lines_and_clips_at_edges() {
        assertThat(TestFailureSource.window(20, 1, 7)).containsExactly(0, 6);
        assertThat(TestFailureSource.window(20, 20, 7)).containsExactly(13, 19);
        assertThat(TestFailureSource.window(20, 10, 7)).containsExactly(6, 12);
        assertThat(TestFailureSource.window(5, 3, 7)).containsExactly(0, 4);
    }

    @Test
    void resolves_traditional_layout_and_snippet(@TempDir Path mod) throws Exception {
        Path src = mod.resolve("src/test/java/cc/jumpkick/runtime");
        Files.createDirectories(src);
        Files.writeString(
                src.resolve("FooTest.java"),
                """
                package cc.jumpkick.runtime;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class FooTest {
                    void bar() {
                        int a = 20;
                        int b = 21;
                        int result = a + b;

                        assertEquals(42, result);
                    }
                }
                """);
        String stack = "org.opentest4j.AssertionFailedError: x\n"
                + "\tat cc.jumpkick.runtime.FooTest.bar(FooTest.java:9)\n";
        var snip = TestFailureSource.resolve(mod, "cc.jumpkick.runtime.FooTest", stack).orElseThrow();
        assertThat(snip.errorLine()).isEqualTo(9);
        assertThat(snip.lines()).hasSize(7);
        assertThat(snip.relativePath()).contains("FooTest.java");
        assertThat(snip.language()).isEqualTo("java");
        // Error line is among the seven lines
        assertThat(snip.lines().stream().anyMatch(l -> l.contains("assertEquals"))).isTrue();
        List<String> markers = TestFailureSource.encodeMarkers(snip);
        assertThat(markers.get(0)).startsWith("@@source ");
        assertThat(markers.stream().anyMatch(l -> l.contains("*|") && l.contains("assertEquals"))).isTrue();
        assertThat(markers.getLast()).isEqualTo("@@src-end");
    }

    @Test
    void resolves_simple_layout_test_src(@TempDir Path mod) throws Exception {
        Path src = mod.resolve("test/src/cc/jumpkick");
        Files.createDirectories(src);
        Files.writeString(
                src.resolve("BarTest.java"),
                """
                package cc.jumpkick;
                class BarTest {
                    void t() {
                        throw new AssertionError("x");
                    }
                }
                """);
        String stack = "java.lang.AssertionError: x\n\tat cc.jumpkick.BarTest.t(BarTest.java:4)\n";
        var snip = TestFailureSource.resolve(mod, "cc.jumpkick.BarTest", stack).orElseThrow();
        assertThat(snip.relativePath()).contains("test/src/");
        assertThat(snip.errorLine()).isEqualTo(4);
    }

    @Test
    void renderFailures_embeds_snippet_markers(@TempDir Path mod) throws Exception {
        Path src = mod.resolve("src/test/java/demo");
        Files.createDirectories(src);
        Files.writeString(
                src.resolve("ZTest.java"),
                """
                package demo;
                class ZTest {
                    void a() { int x = 1; }
                    void b() { int y = 2; }
                    void c() { int z = 3; }
                    void d() {
                        org.junit.jupiter.api.Assertions.assertEquals(1, 2);
                    }
                    void e() { int w = 4; }
                    void f() { int v = 5; }
                    void g() { int u = 6; }
                }
                """);
        var f = new cc.jumpkick.run.TestSummary.Failure(
                "d()",
                "org.opentest4j.AssertionFailedError",
                "expected: <1> but was: <2>",
                "org.opentest4j.AssertionFailedError: expected: <1> but was: <2>\n"
                        + "\tat demo.ZTest.d(ZTest.java:7)",
                "g:a",
                "demo.ZTest",
                0);
        var lines = TestSupport.renderFailures(
                new cc.jumpkick.run.TestSummary(1, 0, 1, 0, List.of(f)), mod);
        String text = String.join("\n", lines);
        assertThat(text).contains("@@source ");
        assertThat(text).contains("AssertionFailedError thrown at line 7");
        assertThat(text).contains("expected: <1> but was: <2>");
        assertThat(text).contains("FAILED ZTest.d()");
        // Thrown-at before assertion body / source
        // Thrown-at sits after the source markers
        assertThat(text.indexOf("@@src-end")).isLessThan(text.indexOf("thrown at line 7"));
        // Stack frames omitted when snippet present
        assertThat(text).doesNotContain("\tat demo.ZTest.d");
        assertThat(lines.getLast()).isEqualTo("Test Failure end");
    }
}
