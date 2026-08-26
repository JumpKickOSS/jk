// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.format;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FqcnShortenerTest {

    @Test
    void shortens_a_method_body_fqcn_from_the_source_index(@TempDir Path tmp) throws Exception {
        Path lib = tmp.resolve("Bar.java");
        Files.writeString(lib, """
                package cc.jumpkick.foo;

                public final class Bar {
                    public static String hi() {
                        return "hi";
                    }
                }
                """);
        String caller = """
                package demo;

                public class Uses {
                    public String go() {
                        return cc.jumpkick.foo.Bar.hi();
                    }
                }
                """;
        TypeIndex index = TypeIndex.scan(List.of(lib), false);
        FqcnShortener.Result r = FqcnShortener.shorten(caller, index);
        assertThat(r.changed()).isTrue();
        assertThat(r.source())
                .contains("import cc.jumpkick.foo.Bar;")
                .contains("return Bar.hi();")
                .doesNotContain("cc.jumpkick.foo.Bar.hi()");
    }

    @Test
    void does_not_rewrite_comments_or_strings(@TempDir Path tmp) throws Exception {
        Path lib = tmp.resolve("Bar.java");
        Files.writeString(lib, "package cc.jumpkick.foo;\npublic class Bar {}\n");
        String src = """
                package demo;

                public class Uses {
                    // cc.jumpkick.foo.Bar in a comment
                    String s = "cc.jumpkick.foo.Bar";
                    Object x = cc.jumpkick.foo.Bar.class;
                }
                """;
        FqcnShortener.Result r = FqcnShortener.shorten(src, TypeIndex.scan(List.of(lib), false));
        assertThat(r.source()).contains("// cc.jumpkick.foo.Bar in a comment");
        assertThat(r.source()).contains("\"cc.jumpkick.foo.Bar\"");
        assertThat(r.source()).contains("Bar.class");
        assertThat(r.source()).doesNotContain("Object x = cc.jumpkick.foo.Bar.class");
    }

    @Test
    void colliding_simple_names_stay_qualified(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("A.java"), "package aa.one;\npublic class List {}\n");
        Files.writeString(tmp.resolve("B.java"), "package aa.two;\npublic class List {}\n");
        String src = """
                package demo;

                public class Uses {
                    aa.one.List a;
                    aa.two.List b;
                }
                """;
        FqcnShortener.Result r = FqcnShortener.shorten(
                src, TypeIndex.scan(List.of(tmp.resolve("A.java"), tmp.resolve("B.java")), false));
        assertThat(r.changed()).isFalse();
        assertThat(r.source()).contains("aa.one.List").contains("aa.two.List");
    }

    @Test
    void same_package_needs_no_import(@TempDir Path tmp) throws Exception {
        Path lib = tmp.resolve("Bar.java");
        Files.writeString(lib, "package demo;\npublic class Bar {}\n");
        String src = """
                package demo;

                public class Uses {
                    demo.Bar b;
                }
                """;
        FqcnShortener.Result r = FqcnShortener.shorten(src, TypeIndex.scan(List.of(lib), false));
        assertThat(r.source()).contains("Bar b;").doesNotContain("import demo.Bar;");
    }

    @Test
    void unknown_names_are_left_alone() {
        String src = """
                package demo;

                public class Uses {
                    com.notindexed.Ghost g;
                }
                """;
        FqcnShortener.Result r = FqcnShortener.shorten(src, TypeIndex.scan(List.of(), false));
        assertThat(r.changed()).isFalse();
        assertThat(r.source()).contains("com.notindexed.Ghost");
    }

    @Test
    void existing_import_of_a_different_type_blocks_shortening(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("Bar.java"), "package cc.jumpkick.foo;\npublic class Bar {}\n");
        String src = """
                package demo;

                import other.Bar;

                public class Uses {
                    cc.jumpkick.foo.Bar b;
                }
                """;
        FqcnShortener.Result r = FqcnShortener.shorten(src, TypeIndex.scan(List.of(tmp.resolve("Bar.java")), false));
        assertThat(r.changed()).isFalse();
        assertThat(r.source()).contains("cc.jumpkick.foo.Bar");
    }

    @Test
    void jdk_types_shorten_without_project_sources() {
        String src = """
                package demo;

                public class Uses {
                    java.util.concurrent.ConcurrentHashMap<String, Integer> m;
                }
                """;
        FqcnShortener.Result r = FqcnShortener.shorten(src, TypeIndex.jdkOnly());
        assertThat(r.changed()).isTrue();
        assertThat(r.source())
                .contains("import java.util.concurrent.ConcurrentHashMap;")
                .contains("ConcurrentHashMap<String, Integer> m")
                .doesNotContain("java.util.concurrent.ConcurrentHashMap<");
    }

    @Test
    void kotlin_shortens_without_semicolons(@TempDir Path tmp) throws Exception {
        Files.writeString(
                tmp.resolve("Bar.kt"), "package cc.jumpkick.foo\n\nclass Bar { companion object { fun hi() = 1 } }\n");
        String src = """
                package demo

                class Uses {
                    fun go() = cc.jumpkick.foo.Bar.hi()
                }
                """;
        FqcnShortener.Result r = FqcnShortener.shorten(
                src, TypeIndex.scan(List.of(tmp.resolve("Bar.kt")), false), FqcnShortener.Syntax.KOTLIN);
        assertThat(r.changed()).isTrue();
        assertThat(r.source()).contains("import cc.jumpkick.foo.Bar\n").doesNotContain("import cc.jumpkick.foo.Bar;");
        assertThat(r.source()).contains("Bar.hi()").doesNotContain("cc.jumpkick.foo.Bar.hi()");
    }

    @Test
    void groovy_shortens_without_semicolons(@TempDir Path tmp) throws Exception {
        Files.writeString(
                tmp.resolve("Bar.groovy"), "package cc.jumpkick.foo\n\nclass Bar { static int hi() { 1 } }\n");
        String src = """
                package demo

                class Uses {
                    def go() { cc.jumpkick.foo.Bar.hi() }
                }
                """;
        FqcnShortener.Result r = FqcnShortener.shorten(
                src, TypeIndex.scan(List.of(tmp.resolve("Bar.groovy")), false), FqcnShortener.Syntax.GROOVY);
        assertThat(r.changed()).isTrue();
        assertThat(r.source()).contains("import cc.jumpkick.foo.Bar\n").doesNotContain("import cc.jumpkick.foo.Bar;");
        assertThat(r.source()).contains("Bar.hi()").doesNotContain("cc.jumpkick.foo.Bar.hi()");
    }

    @Test
    void scala_shortens_a_trait_from_the_source_index(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("Bar.scala"), "package cc.jumpkick.foo\n\ntrait Bar\n");
        String src = """
                package demo

                class Uses {
                  def go: cc.jumpkick.foo.Bar = ???
                }
                """;
        FqcnShortener.Result r = FqcnShortener.shorten(
                src, TypeIndex.scan(List.of(tmp.resolve("Bar.scala")), false), FqcnShortener.Syntax.SCALA);
        assertThat(r.changed()).isTrue();
        assertThat(r.source()).contains("import cc.jumpkick.foo.Bar\n").doesNotContain("import cc.jumpkick.foo.Bar;");
        assertThat(r.source()).contains("def go: Bar = ???").doesNotContain("def go: cc.jumpkick.foo.Bar");
    }

    @Test
    void kotlin_object_is_indexed(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("Bar.kt"), "package cc.jumpkick.foo\n\nobject Bar\n");
        String src = """
                package demo

                class Uses {
                    val x = cc.jumpkick.foo.Bar
                }
                """;
        FqcnShortener.Result r = FqcnShortener.shorten(
                src, TypeIndex.scan(List.of(tmp.resolve("Bar.kt")), false), FqcnShortener.Syntax.KOTLIN);
        assertThat(r.changed()).isTrue();
        assertThat(r.source()).contains("val x = Bar").doesNotContain("val x = cc.jumpkick.foo.Bar");
    }

    @Test
    void java_lang_needs_no_import() {
        String src = """
                package demo;

                public class Uses {
                    java.lang.String s;
                }
                """;
        FqcnShortener.Result r = FqcnShortener.shorten(src, TypeIndex.jdkOnly());
        assertThat(r.source()).contains("String s;").doesNotContain("import java.lang.String;");
    }
}
