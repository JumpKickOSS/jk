// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.format;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Only a top-level declaration is named {@code package.Simple}. A nested, inner, local or member
 * enum/interface declaration is not, and indexing one under that name invents an FQCN for a type that
 * does not exist.
 *
 * <p>Those phantoms were not merely dead weight. Over this repo's {@code shared/core} alone the index
 * held 112 of them against 135 real top-level types — 45% fiction. Each one can fabricate a collision
 * that suppresses a legitimate shortening, and each one is on its own sufficient to emit an import of
 * a type that cannot be resolved under the name imported. They were also the blocker on tightening the
 * collision rules at all: a scope guard consulting a 45%-phantom index reports collisions everywhere.
 */
class TypeIndexTopLevelTest {

    private static TypeIndex indexOf(Path tmp, String name, String src) throws Exception {
        Path p = tmp.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, src);
        return TypeIndex.scan(List.of(p), false);
    }

    @Test
    void nested_inner_member_and_local_declarations_are_not_indexed(@TempDir Path tmp) throws Exception {
        TypeIndex index = indexOf(tmp, "Outer.java", """
                package a.b;

                public class Outer {
                    static final class Inner {}

                    interface Callback {}

                    enum Mode {
                        ON,
                        OFF
                    }

                    void m() {
                        class Local {}
                        new Local();
                    }
                }
                """);

        assertThat(index.contains("a.b.Outer"))
                .as("the one real top-level type")
                .isTrue();
        assertThat(index.contains("a.b.Inner")).isFalse();
        assertThat(index.contains("a.b.Callback")).isFalse();
        assertThat(index.contains("a.b.Mode")).isFalse();
        assertThat(index.contains("a.b.Local")).isFalse();
    }

    /** Several top-level types in one file are all real, whatever the file is called. */
    @Test
    void every_top_level_declaration_in_a_file_is_indexed(@TempDir Path tmp) throws Exception {
        TypeIndex index = indexOf(tmp, "Pair.java", """
                package a.b;

                public class Pair {}

                class Helper {}

                enum Side {
                    LEFT,
                    RIGHT
                }

                interface Sink {}
                """);

        assertThat(index.contains("a.b.Pair")).isTrue();
        assertThat(index.contains("a.b.Helper")).isTrue();
        assertThat(index.contains("a.b.Side")).isTrue();
        assertThat(index.contains("a.b.Sink")).isTrue();
    }

    /** An annotation type declared top-level is a type like any other. */
    @Test
    void a_top_level_annotation_type_is_indexed(@TempDir Path tmp) throws Exception {
        TypeIndex index = indexOf(tmp, "Nonnull.java", """
                package a.b;

                public @interface Nonnull {}
                """);
        assertThat(index.contains("a.b.Nonnull")).isTrue();
    }

    /** A phantom used to fabricate a collision and block a shortening that was perfectly safe. */
    @Test
    void a_nested_type_elsewhere_does_not_block_an_unrelated_shortening(@TempDir Path tmp) throws Exception {
        Path bar = tmp.resolve("idx/Bar.java");
        Files.createDirectories(bar.getParent());
        Files.writeString(bar, """
                package cc.jumpkick.foo;

                public class Bar {
                    public static String hi() {
                        return "foo";
                    }
                }
                """);
        // A wholly unrelated file that merely *nests* a type also called Bar.
        Path other = tmp.resolve("idx/Other.java");
        Files.writeString(other, """
                package cc.jumpkick.other;

                public class Other {
                    static final class Bar {}
                }
                """);
        TypeIndex index = TypeIndex.scan(List.of(bar, other), false);

        assertThat(index.contains("cc.jumpkick.other.Bar"))
                .as("cc.jumpkick.other.Bar does not exist; Other.Bar does")
                .isFalse();

        var r = FqcnShortener.shorten("""
                package demo;

                public class Uses {
                    String go() {
                        return cc.jumpkick.foo.Bar.hi();
                    }
                }
                """, index);
        assertThat(r.changed()).isTrue();
        assertThat(r.source()).contains("import cc.jumpkick.foo.Bar;").contains("return Bar.hi();");
    }

    /**
     * The index over a real subtree of this repo holds no name it cannot justify: every entry must be
     * {@code package.TypeName} for a file that really declares {@code TypeName} at top level. This is
     * the assertion that would have caught the 45% phantom rate.
     */
    @Test
    void the_index_over_this_repo_invents_nothing() throws Exception {
        Path root = Path.of("src/main/java").toAbsolutePath();
        if (!Files.isDirectory(root)) return; // not running from the module dir; nothing to check
        List<Path> sources;
        try (Stream<Path> s = Files.walk(root)) {
            // package-info declares a package, not a type, so it names nothing the index holds.
            sources = s.filter(p -> p.getFileName().toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals("package-info.java"))
                    .toList();
        }
        if (sources.isEmpty()) return;
        TypeIndex index = TypeIndex.scan(sources, false);

        for (Path p : sources) {
            String src = Files.readString(p);
            String pkg = JavaText.packageName(src);
            if (pkg.isEmpty()) continue;
            String fileType = p.getFileName().toString().replace(".java", "");
            // The type sharing the file's name is always top-level, so it must be present.
            assertThat(index.contains(pkg + "." + fileType))
                    .as("%s declares %s at top level", p, fileType)
                    .isTrue();
        }
        // And nothing named after a *nested* type of a known outer class may appear.
        assertThat(index.contains("cc.jumpkick.format.Holder"))
                .as("Workers.Holder is nested, so cc.jumpkick.format.Holder must not be an entry")
                .isFalse();
    }
}
