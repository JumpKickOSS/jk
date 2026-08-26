// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.format;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A formatter may not change what a name means. Shortening {@code a.b.C} to {@code C} plus an import
 * only preserves meaning when {@code C} is not already bound in that file — and a simple name can be
 * bound by things no regex over the reference itself can see: a type declared in the same file, a
 * type in the file's own package, {@code java.lang}, or an on-demand import.
 *
 * <p>Every case here was a real defect that wrote a file which no longer compiled, or which compiled
 * and silently meant something else. The damage was rarely to the rewritten reference — a new
 * single-type import legally shadows a package member — but to the file's <em>other, pre-existing</em>
 * uses of that simple name. That is why the guard is "is this name already spoken for in this file",
 * not "does this one reference resolve".
 *
 * <p>Where the question is whether the result still compiles, these run a real {@code javac} over the
 * before and after rather than asserting on text: the point is the code, not the string.
 */
class FqcnShortenerScopeTest {

    /** Compile a relative-path → source map; return javac error messages (empty means it compiled). */
    private static List<String> javac(Path root, Map<String, String> files) throws Exception {
        List<Path> paths = new ArrayList<>();
        for (var e : files.entrySet()) {
            Path p = root.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.writeString(p, e.getValue());
            paths.add(p);
        }
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        assertThat(jc).as("system java compiler available").isNotNull();
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        Path out = root.resolve("classes");
        Files.createDirectories(out);
        try (StandardJavaFileManager fm = jc.getStandardFileManager(diags, null, null)) {
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(out.toFile()));
            jc.getTask(null, fm, diags, List.of(), null, fm.getJavaFileObjectsFromPaths(paths))
                    .call();
        }
        List<String> errors = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR) errors.add(d.getMessage(null));
        }
        return errors;
    }

    private static Map<String, String> files(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private static final String FOO_BAR = """
            package cc.jumpkick.foo;

            public class Bar {
                public static String hi() {
                    return "foo";
                }
            }
            """;

    /** An index over {@code sources}, project types only — no JDK, so tests stay hermetic. */
    private static TypeIndex indexOf(Path tmp, String... nameAndSource) throws Exception {
        List<Path> paths = new ArrayList<>();
        for (int i = 0; i < nameAndSource.length; i += 2) {
            Path p = tmp.resolve("index").resolve(nameAndSource[i]);
            Files.createDirectories(p.getParent());
            Files.writeString(p, nameAndSource[i + 1]);
            paths.add(p);
        }
        return TypeIndex.scan(paths, false);
    }

    // ---------------------------------------------------------------- the name is already bound

    /**
     * A top-level type of the same name in the same compilation unit. Importing {@code Bar} beside a
     * top-level {@code class Bar} is illegal outright (JLS 7.5.1), so this must stay qualified.
     */
    @Test
    void a_same_file_top_level_type_of_the_same_name_blocks_shortening(@TempDir Path tmp) throws Exception {
        String before = """
                package demo;

                class Bar {
                    static String hi() {
                        return "local";
                    }
                }

                public class Uses {
                    String go() {
                        return cc.jumpkick.foo.Bar.hi();
                    }
                }
                """;
        var r = FqcnShortener.shorten(before, indexOf(tmp, "Bar.java", FOO_BAR));

        assertThat(r.changed()).isFalse();
        assertThat(javac(tmp.resolve("out"), files("cc/jumpkick/foo/Bar.java", FOO_BAR, "demo/Uses.java", r.source())))
                .isEmpty();
    }

    /** A nested member type shadows the import inside the class body, rebinding every bare use. */
    @Test
    void a_nested_type_of_the_same_name_blocks_shortening(@TempDir Path tmp) throws Exception {
        String before = """
                package demo;

                public class Uses {
                    static final class Bar {
                        static String hi() {
                            return "nested";
                        }
                    }

                    String go() {
                        return cc.jumpkick.foo.Bar.hi();
                    }
                }
                """;
        var r = FqcnShortener.shorten(before, indexOf(tmp, "Bar.java", FOO_BAR));

        assertThat(r.changed()).isFalse();
        assertThat(javac(tmp.resolve("out"), files("cc/jumpkick/foo/Bar.java", FOO_BAR, "demo/Uses.java", r.source())))
                .isEmpty();
    }

    /**
     * An on-demand import is already supplying simple names this pass cannot enumerate, so a new
     * single-type import could steal one. Here {@code java.util.*} supplies {@code List}, and the
     * qualified {@code java.awt.List} is the author disambiguating on purpose.
     */
    @Test
    void a_star_import_blocks_a_new_import_that_would_steal_a_name(@TempDir Path tmp) throws Exception {
        String awtList = """
                package java.awt;

                public class List {}
                """;
        String before = """
                package demo;

                import java.util.*;

                public class Uses {
                    List<String> names = new ArrayList<>();

                    void widget(java.awt.List w) {}
                }
                """;
        var r = FqcnShortener.shorten(before, indexOf(tmp, "List.java", awtList));

        assertThat(r.changed())
                .as("java.util.* may already bind List, so introducing `import java.awt.List` is unsafe")
                .isFalse();
    }

    /** A type of the same name in the file's own package is in scope with no import at all. */
    @Test
    void a_same_named_type_in_the_files_own_package_blocks_shortening(@TempDir Path tmp) throws Exception {
        String demoTimer = """
                package demo;

                public class Timer {
                    public void tick() {}
                }
                """;
        String before = """
                package demo;

                public class Uses {
                    Timer local = new Timer();

                    void go() {
                        local.tick();
                    }

                    java.util.Timer sched;
                }
                """;
        var r = FqcnShortener.shorten(before, indexOf(tmp, "Timer.java", demoTimer, "UtilTimer.java", """
                package java.util;

                public class Timer {}
                """));

        assertThat(r.changed()).isFalse();
        assertThat(javac(tmp.resolve("out"), files("demo/Timer.java", demoTimer, "demo/Uses.java", r.source())))
                .isEmpty();
    }

    /** {@code java.lang} is imported implicitly, so its simple names are taken everywhere. */
    @Test
    void a_java_lang_simple_name_blocks_shortening(@TempDir Path tmp) throws Exception {
        String fooString = """
                package cc.jumpkick.foo;

                public class String {}
                """;
        String before = """
                package demo;

                public class Uses {
                    java.lang.String label = "hi";

                    cc.jumpkick.foo.String custom;
                }
                """;
        var r = FqcnShortener.shorten(before, TypeIndex.scan(indexPaths(tmp, "String.java", fooString), true));

        assertThat(r.source())
                .as("shortening cc.jumpkick.foo.String to String would rebind `label`")
                .contains("cc.jumpkick.foo.String custom");
    }

    private static List<Path> indexPaths(Path tmp, String name, String src) throws Exception {
        Path p = tmp.resolve("index").resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, src);
        return List.of(p);
    }

    /** An explicit single-type import of the very same type is the one case that is always safe. */
    @Test
    void an_explicit_import_of_the_same_type_still_shortens(@TempDir Path tmp) throws Exception {
        String before = """
                package demo;

                import cc.jumpkick.foo.Bar;

                public class Uses {
                    String a() {
                        return Bar.hi();
                    }

                    String b() {
                        return cc.jumpkick.foo.Bar.hi();
                    }
                }
                """;
        var r = FqcnShortener.shorten(before, indexOf(tmp, "Bar.java", FOO_BAR));

        assertThat(r.changed()).isTrue();
        assertThat(r.source()).doesNotContain("cc.jumpkick.foo.Bar.hi()");
        assertThat(r.source().split("import cc\\.jumpkick\\.foo\\.Bar;", -1))
                .as("no duplicate import")
                .hasSize(2);
        assertThat(javac(tmp.resolve("out"), files("cc/jumpkick/foo/Bar.java", FOO_BAR, "demo/Uses.java", r.source())))
                .isEmpty();
    }

    // ------------------------------------------------- structure is read off the blanked copy

    /**
     * {@code package} and {@code import} are matched against the comment/string-blanked copy. Read
     * from raw text, a commented-out import looked like a real one and suppressed the import the
     * shortened reference actually needed.
     */
    @Test
    void a_commented_out_import_does_not_suppress_the_real_one(@TempDir Path tmp) throws Exception {
        String before = """
                package demo;

                /*
                import cc.jumpkick.foo.Bar;
                */

                public class Uses {
                    String go() {
                        return cc.jumpkick.foo.Bar.hi();
                    }
                }
                """;
        var r = FqcnShortener.shorten(before, indexOf(tmp, "Bar.java", FOO_BAR));

        assertThat(r.changed()).isTrue();
        assertThat(javac(tmp.resolve("out"), files("cc/jumpkick/foo/Bar.java", FOO_BAR, "demo/Uses.java", r.source())))
                .as("after: " + r.source())
                .isEmpty();
    }

    /** The insertion point may never be an {@code import} that only exists inside a comment. */
    @Test
    void the_import_block_is_not_inserted_into_a_block_comment(@TempDir Path tmp) throws Exception {
        String before = """
                package demo;

                /*
                import legacy.Thing;
                */

                public class Uses {
                    String go() {
                        return cc.jumpkick.foo.Bar.hi();
                    }
                }
                """;
        var r = FqcnShortener.shorten(before, indexOf(tmp, "Bar.java", FOO_BAR));

        assertThat(r.changed()).isTrue();
        assertThat(r.source()).contains("import cc.jumpkick.foo.Bar;");
        assertThat(javac(tmp.resolve("out"), files("cc/jumpkick/foo/Bar.java", FOO_BAR, "demo/Uses.java", r.source())))
                .as("after: " + r.source())
                .isEmpty();
    }

    /**
     * Nor inside a text block. This one corrupted twice over: the file lost the import it needed, and
     * a string literal silently gained an {@code import} line.
     */
    @Test
    void the_import_block_is_not_inserted_into_a_text_block(@TempDir Path tmp) throws Exception {
        String before = """
                package demo;

                public class Uses {
                    static final String TEMPLATE = \"""
                import a.b.C;
                \""";

                    String go() {
                        return cc.jumpkick.foo.Bar.hi();
                    }
                }
                """;
        var r = FqcnShortener.shorten(before, indexOf(tmp, "Bar.java", FOO_BAR));

        assertThat(r.changed()).isTrue();
        assertThat(r.source()).as("the template's contents must be untouched").contains("import a.b.C;\n\"\"\"");
        assertThat(javac(tmp.resolve("out"), files("cc/jumpkick/foo/Bar.java", FOO_BAR, "demo/Uses.java", r.source())))
                .as("after: " + r.source())
                .isEmpty();
    }

    /** A {@code package} line inside a string is not this file's package declaration. */
    @Test
    void a_package_line_inside_a_text_block_is_not_the_files_package(@TempDir Path tmp) throws Exception {
        String before = """
                public class Uses {
                    static final String T = \"""
                package cc.jumpkick.foo;
                \""";

                    String go() {
                        return cc.jumpkick.foo.Bar.hi();
                    }
                }
                """;
        var r = FqcnShortener.shorten(before, indexOf(tmp, "Bar.java", FOO_BAR));

        assertThat(r.changed()).isTrue();
        assertThat(r.source())
                .as("the file is in the default package, so the import is required")
                .contains("import cc.jumpkick.foo.Bar;");
    }

    /**
     * An escaped quote keeps a {@code """} from closing a text block. Missing that, the blanker ended
     * the block early and the FQCN matcher rewrote the literal's own contents — output that still
     * compiled, with only the string's value changed.
     */
    @Test
    void an_escaped_triple_quote_does_not_expose_string_contents(@TempDir Path tmp) throws Exception {
        // Built from plain literals rather than a text block on purpose: an escaped `"""` written
        // literally here would trip the very same defect in `checkNoFqcn`'s own blanking pass, which
        // would then count this fixture's contents as real references.
        String qualified = "cc.jumpkick." + "foo.Bar";
        String before = "package demo;\n\n"
                + "public class Uses {\n"
                + "    static final String DOC = \"\"\"\n"
                + "        a delimiter is \\\"\"\" and\n"
                + "        " + qualified + " is only a name here\n"
                + "        \"\"\";\n"
                + "}\n";
        var r = FqcnShortener.shorten(before, indexOf(tmp, "Bar.java", FOO_BAR));

        assertThat(r.changed())
                .as("everything of interest is inside a text block; after: " + r.source())
                .isFalse();
        assertThat(r.source()).contains(qualified + " is only a name here");
    }

    /** A trailing comment on the package line must not push the import above {@code package}. */
    @Test
    void a_trailing_comment_on_the_package_line_keeps_the_import_below_it(@TempDir Path tmp) throws Exception {
        String before = """
                package demo; // the demo package

                public class Uses {
                    String go() {
                        return cc.jumpkick.foo.Bar.hi();
                    }
                }
                """;
        var r = FqcnShortener.shorten(before, indexOf(tmp, "Bar.java", FOO_BAR));

        assertThat(r.changed()).isTrue();
        assertThat(r.source().indexOf("import cc.jumpkick.foo.Bar;"))
                .as("import must follow the package declaration; after: " + r.source())
                .isGreaterThan(r.source().indexOf("package demo;"));
        assertThat(javac(tmp.resolve("out"), files("cc/jumpkick/foo/Bar.java", FOO_BAR, "demo/Uses.java", r.source())))
                .isEmpty();
    }

    /**
     * The inserted block goes immediately after the last real import, on its own line. It used to be
     * placed after the class javadoc — because the blanked copy turns a javadoc into a rectangle of
     * spaces and the pattern's trailing {@code \\s*} ran the match end straight through it — which
     * palantir-java-format rejects outright as "Imports not contiguous".
     */
    @Test
    void the_import_block_lands_directly_after_the_last_import(@TempDir Path tmp) throws Exception {
        String before = """
                package demo;

                import java.util.List;

                /**
                 * Doc comment between the imports and the type.
                 */
                public class Uses {
                    List<String> names;

                    String go() {
                        return cc.jumpkick.foo.Bar.hi();
                    }
                }
                """;
        var r = FqcnShortener.shorten(before, indexOf(tmp, "Bar.java", FOO_BAR));

        assertThat(r.changed()).isTrue();
        assertThat(r.source())
                .as("contiguous with the existing import block; after: " + r.source())
                .contains("import java.util.List;\nimport cc.jumpkick.foo.Bar;");
        assertThat(r.source().indexOf("import cc.jumpkick.foo.Bar;"))
                .isLessThan(r.source().indexOf("/**"));
    }

    // ---------------------------------------------------------------- whole-pass properties

    /** Running the pass over its own output must be a no-op. */
    @Test
    void the_pass_is_idempotent(@TempDir Path tmp) throws Exception {
        String before = """
                package demo;

                public class Uses {
                    String go() {
                        return cc.jumpkick.foo.Bar.hi();
                    }
                }
                """;
        TypeIndex index = indexOf(tmp, "Bar.java", FOO_BAR);
        var once = FqcnShortener.shorten(before, index);
        assertThat(once.changed()).isTrue();
        assertThat(FqcnShortener.shorten(once.source(), index).changed()).isFalse();
    }

    /** A name only reachable through a comment is not a reference. */
    @Test
    void a_javadoc_link_is_not_shortened(@TempDir Path tmp) throws Exception {
        String before = """
                package demo;

                /** See {@link cc.jumpkick.foo.Bar}. */
                public class Uses {}
                """;
        assertThat(FqcnShortener.shorten(before, indexOf(tmp, "Bar.java", FOO_BAR))
                        .changed())
                .isFalse();
    }
}
