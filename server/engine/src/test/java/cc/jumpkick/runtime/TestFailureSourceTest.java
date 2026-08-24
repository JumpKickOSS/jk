// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.TestSummary;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestFailureSourceTest {

    @Test
    void parses_java_stack_frame() {
        var f = TestFailureSource.parseFrame("\tat cc.jumpkick.runtime.FooTest.bar(FooTest.java:42)")
                .orElseThrow();
        assertThat(f.className()).isEqualTo("cc.jumpkick.runtime.FooTest");
        assertThat(f.method()).isEqualTo("bar");
        assertThat(f.fileName()).isEqualTo("FooTest.java");
        assertThat(f.line()).isEqualTo(42);
    }

    @Test
    void parses_kotlin_and_groovy_file_names() {
        assertThat(TestFailureSource.parseFrame("\tat c.F.m(F.kt:9)")
                        .orElseThrow()
                        .fileName())
                .isEqualTo("F.kt");
        assertThat(TestFailureSource.parseFrame("\tat c.F.m(F.groovy:3)")
                        .orElseThrow()
                        .fileName())
                .isEqualTo("F.groovy");
    }

    @Test
    void parses_named_module_and_classloader_frames() {
        var mod = TestFailureSource.parseFrame("\tat demo.mod/cc.jumpkick.runtime.FooTest.bar(FooTest.java:15)")
                .orElseThrow();
        assertThat(mod.className()).isEqualTo("cc.jumpkick.runtime.FooTest");
        assertThat(mod.method()).isEqualTo("bar");
        assertThat(mod.fileName()).isEqualTo("FooTest.java");
        assertThat(mod.line()).isEqualTo(15);

        var loader = TestFailureSource.parseFrame("\tat app//cc.jumpkick.runtime.FooTest.bar(FooTest.java:9)")
                .orElseThrow();
        assertThat(loader.className()).isEqualTo("cc.jumpkick.runtime.FooTest");
        assertThat(loader.line()).isEqualTo(9);
    }

    @Test
    void prefers_test_class_frame_over_framework() {
        String stack = """
                org.opentest4j.AssertionFailedError: nope
                	at org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:100)
                	at cc.jumpkick.runtime.FooTest.bar(FooTest.java:15)
                	at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)
                """;
        var f = TestFailureSource.primaryFrame(stack, "cc.jumpkick.runtime.FooTest")
                .orElseThrow();
        assertThat(f.line()).isEqualTo(15);
        assertThat(f.fileName()).isEqualTo("FooTest.java");
    }

    @Test
    void non_utf8_bytes_before_the_window_do_not_drop_the_snippet(@TempDir Path mod) throws Exception {
        // a Latin-1 'é' (0xE9) anywhere in the file used to throw MalformedInputException
        // in the strict decoder and lose the whole snippet; substitution keeps the window.
        Path src = mod.resolve("src/test/java/cc/jumpkick/runtime");
        Files.createDirectories(src);
        byte[] latin1Comment = "// café note\n".getBytes(StandardCharsets.ISO_8859_1);
        byte[] rest = """
                package cc.jumpkick.runtime;
                class FooTest {
                    void bar() {
                        int a = 20;
                        int b = 21;
                        int result = a + b;
                        assertEquals(42, result);
                    }
                }
                """.getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[latin1Comment.length + rest.length];
        System.arraycopy(latin1Comment, 0, all, 0, latin1Comment.length);
        System.arraycopy(rest, 0, all, latin1Comment.length, rest.length);
        Files.write(src.resolve("FooTest.java"), all);

        String stack =
                "org.opentest4j.AssertionFailedError: x\n" + "\tat cc.jumpkick.runtime.FooTest.bar(FooTest.java:7)\n";
        var snip = new TestFailureSource.Cache()
                .resolve(mod, "cc.jumpkick.runtime.FooTest", stack)
                .orElseThrow();
        assertThat(snip.errorLine()).isEqualTo(7);
        assertThat(String.join("\n", snip.lines())).contains("assertEquals(42, result);");
    }

    @Test
    void hostile_class_names_return_empty_instead_of_throwing(@TempDir Path mod) throws Exception {
        // the class name is worker wire input; NUL / '..' segments must degrade to
        // no-snippet, never throw InvalidPathException into the worker-drain thread.
        Files.createDirectories(mod.resolve("src/test/java"));
        String stack = "java.lang.AssertionError: x\n\tat evil.Foo.t(Foo.java:3)\n";
        assertThat(new TestFailureSource.Cache().resolve(mod, "a\u0000b.Foo", stack))
                .isEmpty();
        assertThat(new TestFailureSource.Cache().resolve(mod, "x...y.Foo", stack))
                .isEmpty();
        assertThat(new TestFailureSource.Cache().resolve(mod, "a\\b.Foo", stack))
                .isEmpty();
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
        Files.writeString(src.resolve("FooTest.java"), """
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
        String stack =
                "org.opentest4j.AssertionFailedError: x\n" + "\tat cc.jumpkick.runtime.FooTest.bar(FooTest.java:9)\n";
        var snip = new TestFailureSource.Cache()
                .resolve(mod, "cc.jumpkick.runtime.FooTest", stack)
                .orElseThrow();
        assertThat(snip.errorLine()).isEqualTo(9);
        assertThat(snip.lines()).hasSize(7);
        assertThat(snip.relativePath()).contains("FooTest.java");
        assertThat(snip.language()).isEqualTo("java");
        // Error line is among the seven lines
        assertThat(snip.lines().stream().anyMatch(l -> l.contains("assertEquals")))
                .isTrue();
        List<String> markers = TestFailureSource.encodeMarkers(snip);
        assertThat(markers.get(0)).startsWith("@@source ");
        assertThat(markers.stream().anyMatch(l -> l.contains("*|") && l.contains("assertEquals")))
                .isTrue();
        assertThat(markers.getLast()).isEqualTo("@@src-end");
    }

    @Test
    void resolves_simple_layout_test_src(@TempDir Path mod) throws Exception {
        Path src = mod.resolve("test/src/cc/jumpkick");
        Files.createDirectories(src);
        Files.writeString(src.resolve("BarTest.java"), """
                package cc.jumpkick;
                class BarTest {
                    void t() {
                        throw new AssertionError("x");
                    }
                }
                """);
        String stack = "java.lang.AssertionError: x\n\tat cc.jumpkick.BarTest.t(BarTest.java:4)\n";
        var snip = new TestFailureSource.Cache()
                .resolve(mod, "cc.jumpkick.BarTest", stack)
                .orElseThrow();
        assertThat(snip.relativePath()).contains("test/src/");
        assertThat(snip.errorLine()).isEqualTo(4);
    }

    @Test
    void renderFailures_embeds_snippet_markers(@TempDir Path mod) throws Exception {
        Path src = mod.resolve("src/test/java/demo");
        Files.createDirectories(src);
        Files.writeString(src.resolve("ZTest.java"), """
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
        var f = new TestSummary.Failure(
                "d()",
                "org.opentest4j.AssertionFailedError",
                "expected: <1> but was: <2>",
                "org.opentest4j.AssertionFailedError: expected: <1> but was: <2>\n" + "\tat demo.ZTest.d(ZTest.java:7)",
                "g:a",
                "demo.ZTest",
                0);
        var lines = TestSupport.renderFailures(new TestSummary(1, 0, 1, 0, List.of(f)), mod);
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

    @Test
    void resolves_integration_suite(@TempDir Path mod) throws Exception {
        Path src = mod.resolve("src/integration/java/cc/jumpkick");
        Files.createDirectories(src);
        Files.writeString(src.resolve("ItTest.java"), """
                package cc.jumpkick;
                class ItTest {
                    void t() {
                        throw new AssertionError("x");
                    }
                }
                """);
        String stack = "java.lang.AssertionError: x\n\tat cc.jumpkick.ItTest.t(ItTest.java:4)\n";
        var snip = new TestFailureSource.Cache()
                .resolve(mod, "cc.jumpkick.ItTest", stack)
                .orElseThrow();
        assertThat(snip.relativePath()).contains("src/integration/");
        assertThat(snip.errorLine()).isEqualTo(4);
    }

    @Test
    void resolves_simple_layout_integration_suite(@TempDir Path mod) throws Exception {
        Path src = mod.resolve("integration/src/cc/jumpkick");
        Files.createDirectories(src);
        Files.writeString(src.resolve("ItTest.java"), """
                package cc.jumpkick;
                class ItTest {
                    void t() {
                        throw new AssertionError("x");
                    }
                }
                """);
        String stack = "java.lang.AssertionError: x\n\tat cc.jumpkick.ItTest.t(ItTest.java:4)\n";
        var snip = new TestFailureSource.Cache()
                .resolve(mod, "cc.jumpkick.ItTest", stack)
                .orElseThrow();
        assertThat(snip.relativePath()).contains("integration/src/");
        assertThat(snip.errorLine()).isEqualTo(4);
    }

    @Test
    void out_of_range_line_yields_no_snippet(@TempDir Path mod) throws Exception {
        Path src = mod.resolve("src/test/java/cc/jumpkick");
        Files.createDirectories(src);
        Files.writeString(src.resolve("FooTest.java"), "class FooTest {\n  void t() {}\n}\n");
        String stack = "err\n\tat cc.jumpkick.FooTest.t(FooTest.java:999)\n";
        assertThat(new TestFailureSource.Cache().resolve(mod, "cc.jumpkick.FooTest", stack))
                .isEmpty();
    }

    @Test
    void path_escape_file_name_is_rejected(@TempDir Path mod) throws Exception {
        Path outside = mod.getParent().resolve("secret.txt");
        Files.writeString(outside, "do not read\n");
        String stack = "err\n\tat cc.jumpkick.FooTest.t(../secret.txt:1)\n";
        assertThat(new TestFailureSource.Cache().resolve(mod, "cc.jumpkick.FooTest", stack))
                .isEmpty();
        assertThat(TestFailureSource.insideModule(mod, mod.resolve("../../secret.txt")))
                .isEmpty();
    }

    @Test
    void shared_cache_resolves_once_and_does_not_walk_when_candidates_hit(@TempDir Path mod) throws Exception {
        Path src = mod.resolve("src/test/java/cc/jumpkick");
        Files.createDirectories(src);
        Files.writeString(src.resolve("FooTest.java"), """
                package cc.jumpkick;
                class FooTest {
                    void a() { throw new AssertionError("a"); }
                    void b() { throw new AssertionError("b"); }
                }
                """);
        var cache = new TestFailureSource.Cache();
        String stackA = "err\n\tat cc.jumpkick.FooTest.a(FooTest.java:3)\n";
        String stackB = "err\n\tat cc.jumpkick.FooTest.b(FooTest.java:4)\n";
        assertThat(cache.resolve(mod, "cc.jumpkick.FooTest", stackA)).isPresent();
        assertThat(cache.resolve(mod, "cc.jumpkick.FooTest", stackA)).isPresent();
        assertThat(cache.resolve(mod, "cc.jumpkick.FooTest", stackB)).isPresent();
        assertThat(cache.walkCount()).isEqualTo(0);
    }

    @Test
    void shared_cache_walks_once_when_package_path_misses(@TempDir Path mod) throws Exception {
        Path src = mod.resolve("src/test/java/elsewhere");
        Files.createDirectories(src);
        Files.writeString(src.resolve("FooTest.java"), "class FooTest {\n  void t() {}\n  void u() {}\n}\n");
        var cache = new TestFailureSource.Cache();
        String stackA = "err\n\tat cc.jumpkick.FooTest.t(FooTest.java:2)\n";
        String stackB = "err\n\tat cc.jumpkick.FooTest.u(FooTest.java:3)\n";
        assertThat(cache.resolve(mod, "cc.jumpkick.FooTest", stackA)).isPresent();
        assertThat(cache.resolve(mod, "cc.jumpkick.FooTest", stackB)).isPresent();
        assertThat(cache.walkCount()).isEqualTo(1);
    }

    @Test
    void readWindow_loads_only_the_slice(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("Big.java");
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 200; i++) sb.append("line ").append(i).append('\n');
        Files.writeString(f, sb);
        List<String> slice = TestFailureSource.readWindow(f, 96, 102);
        assertThat(slice)
                .containsExactly("line 97", "line 98", "line 99", "line 100", "line 101", "line 102", "line 103");
    }
}
