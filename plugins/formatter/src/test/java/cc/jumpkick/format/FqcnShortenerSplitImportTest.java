// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.format;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An import written over several lines is still an import. Java allows whitespace and comments
 * around every dot; Kotlin allows a line break before a dot but not after one; Groovy allows neither.
 * The shortener reads all three the same way, because reading a non-compiling split as an import
 * only makes it more careful.
 */
class FqcnShortenerSplitImportTest {

    @Test
    void a_java_import_split_before_a_dot_is_not_added_again(@TempDir Path tmp) throws Exception {
        FqcnShortener.Result r = shorten(tmp, FqcnShortener.Syntax.JAVA, """
                package demo;

                import cc.jumpkick
                        .foo.Bar;

                public class Uses {
                    String go() {
                        return cc.jumpkick.foo.Bar.hi();
                    }
                }
                """);
        assertThat(r.source()).contains("return Bar.hi();").doesNotContain("import cc.jumpkick.foo.Bar;");
    }

    @Test
    void a_java_import_split_after_a_dot_is_not_added_again(@TempDir Path tmp) throws Exception {
        FqcnShortener.Result r = shorten(tmp, FqcnShortener.Syntax.JAVA, """
                package demo;

                import cc.
                        jumpkick.foo.Bar;

                public class Uses {
                    String go() {
                        return cc.jumpkick.foo.Bar.hi();
                    }
                }
                """);
        assertThat(r.source())
                .contains("import cc.\n        jumpkick.foo.Bar;")
                .contains("return Bar.hi();")
                .doesNotContain("import cc.jumpkick.foo.Bar;");
    }

    @Test
    void a_split_java_import_of_another_bar_keeps_the_reference_qualified(@TempDir Path tmp) throws Exception {
        FqcnShortener.Result r = shorten(tmp, FqcnShortener.Syntax.JAVA, """
                package demo;

                import other.pkg /* the other one */
                        .Bar;

                public class Uses {
                    Object go() {
                        return cc.jumpkick.foo.Bar.hi();
                    }
                }
                """);
        assertThat(r.changed()).isFalse();
    }

    @Test
    void a_split_java_star_import_is_opaque(@TempDir Path tmp) throws Exception {
        FqcnShortener.Result r = shorten(tmp, FqcnShortener.Syntax.JAVA, """
                package demo;

                import other.pkg
                        .*;

                public class Uses {
                    Object go() {
                        return cc.jumpkick.foo.Bar.hi();
                    }
                }
                """);
        assertThat(r.changed()).isFalse();
    }

    @Test
    void a_name_alone_on_the_line_after_import_is_not_rewritten(@TempDir Path tmp) throws Exception {
        FqcnShortener.Result r = shorten(tmp, FqcnShortener.Syntax.JAVA, """
                package demo;

                import
                        cc.jumpkick.foo.Bar;

                public class Uses {
                    String go() {
                        return cc.jumpkick.foo.Bar.hi();
                    }
                }
                """);
        assertThat(r.source())
                .contains("import\n        cc.jumpkick.foo.Bar;")
                .contains("return Bar.hi();")
                .doesNotContain("import cc.jumpkick.foo.Bar;");
    }

    @Test
    void a_name_alone_on_the_line_after_a_star_import_is_not_rewritten(@TempDir Path tmp) throws Exception {
        FqcnShortener.Result r = shorten(tmp, FqcnShortener.Syntax.JAVA, """
                package cc.jumpkick.foo;

                import static
                        cc.jumpkick.foo.Bar.*;

                public class Uses {
                    Object go() {
                        return cc.jumpkick.foo.Bar.hi();
                    }
                }
                """);
        assertThat(r.source()).contains("import static\n        cc.jumpkick.foo.Bar.*;");
    }

    @Test
    void a_new_import_goes_after_a_split_one(@TempDir Path tmp) throws Exception {
        FqcnShortener.Result r = shorten(tmp, FqcnShortener.Syntax.JAVA, """
                package demo;

                import java.util
                        .List;

                public class Uses {
                    List<Object> go() {
                        return List.of(cc.jumpkick.foo.Bar.hi());
                    }
                }
                """);
        assertThat(r.source()).contains("import java.util\n        .List;\nimport cc.jumpkick.foo.Bar;\n");
    }

    /** kotlinc compiles a line break before a dot in an import. */
    @Test
    void a_kotlin_import_split_before_a_dot_is_not_added_again(@TempDir Path tmp) throws Exception {
        FqcnShortener.Result r = shorten(tmp, FqcnShortener.Syntax.KOTLIN, """
                package demo

                import cc.jumpkick
                    .foo.Bar

                class Uses {
                    fun go() = cc.jumpkick.foo.Bar.hi()
                }
                """);
        assertThat(r.source()).contains("fun go() = Bar.hi()").doesNotContain("import cc.jumpkick.foo.Bar\n");
    }

    /** Groovy rejects any split import; the shortener still reads it as one. */
    @Test
    void a_groovy_import_split_after_a_dot_is_not_added_again(@TempDir Path tmp) throws Exception {
        FqcnShortener.Result r = shorten(tmp, FqcnShortener.Syntax.GROOVY, """
                package demo

                import cc.
                    jumpkick.foo.Bar

                class Uses {
                    def go() { cc.jumpkick.foo.Bar.hi() }
                }
                """);
        assertThat(r.source())
                .contains("import cc.\n    jumpkick.foo.Bar\n")
                .doesNotContain("import cc.jumpkick.foo.Bar\n");
    }

    private static FqcnShortener.Result shorten(Path tmp, FqcnShortener.Syntax syntax, String source) throws Exception {
        Path bar = tmp.resolve("Bar.java");
        Files.writeString(bar, """
                package cc.jumpkick.foo;

                public class Bar {
                    public static String hi() {
                        return "hi";
                    }
                }
                """);
        return FqcnShortener.shorten(source, TypeIndex.scan(List.of(bar), false), syntax);
    }
}
