// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The declaration digest of a Java source moves with what a declaration-reading compiler can see
 * — signatures at every access level, constant initializers, imports, annotations — and holds
 * across everything it cannot: method bodies, initializer blocks, non-final initializers,
 * comments and layout.
 */
class JavaSourceApiTest {

    private static final String BASE = """
            package com.example;

            import java.util.List;

            public final class Lib {
                public static final int FACTOR = 2;
                private static long calls = 0L;
                private final String name;

                public Lib(String name) {
                    this.name = name;
                }

                public static int twice(int n) {
                    calls++;
                    return n * FACTOR;
                }

                private List<String> names() {
                    return List.of(name);
                }
            }
            """;

    @Test
    void a_body_only_edit_keeps_the_digest(@TempDir Path dir) throws IOException {
        String base = digest(dir, BASE);
        assertThat(JavaSourceApi.parsed(base)).isTrue();
        // Another body for twice(), another private initializer, a comment and re-flowed layout.
        String edited = BASE.replace("return n * FACTOR;", "return n + n; // same answer")
                .replace("private static long calls = 0L;", "private static long calls = 1L;")
                .replace("return List.of(name);", "return List.of(name, name);")
                .replace("\n\n", "\n");
        assertThat(digest(dir, edited)).isEqualTo(base);
    }

    @Test
    void a_signature_edit_moves_the_digest(@TempDir Path dir) throws IOException {
        String base = digest(dir, BASE);
        assertThat(digest(dir, BASE.replace("public static int twice(int n)", "public static long twice(int n)")))
                .as("return type")
                .isNotEqualTo(base);
        assertThat(digest(dir, BASE.replace("private List<String> names()", "private List<String> names(int n)")))
                .as("a private member is a declaration too: a processor may read it")
                .isNotEqualTo(base);
        assertThat(digest(dir, BASE.replace("private final String name;", "private final CharSequence name;")))
                .as("field type")
                .isNotEqualTo(base);
        assertThat(digest(dir, BASE.replace("public final class Lib", "public class Lib")))
                .as("modifiers")
                .isNotEqualTo(base);
        assertThat(digest(dir, BASE.replace("import java.util.List;", "import java.util.*;")))
                .as("imports decide what a written name resolves to")
                .isNotEqualTo(base);
        assertThat(digest(
                        dir,
                        BASE.replace(
                                "    public static int twice(int n) {",
                                "    @Deprecated\n    public static int twice(int n) {")))
                .as("annotations")
                .isNotEqualTo(base);
    }

    @Test
    void the_exported_view_leaves_private_members_out_but_keeps_record_components(@TempDir Path dir)
            throws IOException {
        String exported = digest(dir, BASE, JavaSourceApi.View.EXPORTED);
        String withPrivate = BASE.replace(
                "private static long calls = 0L;", "private static long calls = 0L;\n    private int extra;");
        assertThat(digest(dir, withPrivate, JavaSourceApi.View.EXPORTED)).isEqualTo(exported);
        assertThat(digest(dir, withPrivate, JavaSourceApi.View.DECLARATIONS)).isNotEqualTo(digest(dir, BASE));
        assertThat(digest(
                        dir,
                        BASE.replace("public static int twice", "public static long twice"),
                        JavaSourceApi.View.EXPORTED))
                .isNotEqualTo(exported);

        String record = "package com.example;\n\npublic record Point(int x, int y) {}\n";
        assertThat(digest(dir, record.replace("int y", "long y"), JavaSourceApi.View.EXPORTED))
                .as("a record component is the record's API")
                .isNotEqualTo(digest(dir, record, JavaSourceApi.View.EXPORTED));
    }

    @Test
    void a_constant_value_is_part_of_the_declaration(@TempDir Path dir) throws IOException {
        String base = digest(dir, BASE);
        assertThat(digest(dir, BASE.replace("FACTOR = 2;", "FACTOR = 3;")))
                .as("a final field's initializer is copied into consumers")
                .isNotEqualTo(base);
    }

    @Test
    void an_enum_constants_arguments_and_body_are_implementation(@TempDir Path dir) throws IOException {
        String enumV1 = """
                package com.example;

                public enum Mode {
                    FAST(1) {
                        @Override
                        int cost() {
                            return 1;
                        }
                    },
                    SLOW(2);

                    private final int weight;

                    Mode(int weight) {
                        this.weight = weight;
                    }

                    int cost() {
                        return weight;
                    }
                }
                """;
        String base = digest(dir, enumV1);
        assertThat(digest(dir, enumV1.replace("FAST(1)", "FAST(10)").replace("return 1;", "return 10;")))
                .isEqualTo(base);
        assertThat(digest(dir, enumV1.replace("SLOW(2);", "SLOW(2),\n    SLOWER(3);")))
                .as("a new constant is API")
                .isNotEqualTo(base);
    }

    @Test
    void a_file_javac_cannot_parse_keys_on_its_content(@TempDir Path dir) throws IOException {
        String broken = digest(dir, "package com.example; public class Lib { public static int twice(int n) {");
        assertThat(JavaSourceApi.parsed(broken)).isFalse();
        assertThat(broken).startsWith(JavaSourceApi.CONTENT_PREFIX);
        // Every content is its own token: a re-edit still moves the key.
        assertThat(digest(dir, "package com.example; public class Lib { public static int twice(int n) { {"))
                .isNotEqualTo(broken);
    }

    @Test
    void digests_answer_every_file_by_its_absolute_path(@TempDir Path dir) throws IOException {
        Path a = Files.writeString(dir.resolve("A.java"), "package p; public class A {}");
        Path b = Files.writeString(dir.resolve("B.java"), "package p; public class B { public int x; }");
        Map<Path, String> digests = JavaSourceApi.digests(List.of(a, b, a));
        assertThat(digests)
                .containsOnlyKeys(
                        a.toAbsolutePath().normalize(), b.toAbsolutePath().normalize());
        assertThat(digests.get(a)).isNotEqualTo(digests.get(b));
        assertThat(digests.get(a)).isEqualTo(JavaSourceApi.digest(a));
    }

    @Test
    void a_module_is_parsed_in_batches_bounded_by_source_bytes(@TempDir Path dir) throws IOException {
        Path a = Files.writeString(dir.resolve("A.java"), "package p; public class A {}".repeat(1));
        Path b = Files.writeString(dir.resolve("B.java"), "package p; public class B { public int x; }");
        Path c = Files.writeString(dir.resolve("C.java"), "package p; public class C { public int y; }");
        long each = Files.size(b);
        // Two files fit under the bound together; the third opens a batch of its own.
        assertThat(JavaSourceApi.batches(List.of(a, b, c), each * 2)).containsExactly(List.of(a, b), List.of(c));
        // A file above the bound is never dropped: it is a batch of one.
        assertThat(JavaSourceApi.batches(List.of(a, b, c), 1)).containsExactly(List.of(a), List.of(b), List.of(c));
        assertThat(JavaSourceApi.batches(List.of(), 1)).isEmpty();
    }

    @Test
    void a_digest_does_not_depend_on_the_batch_its_file_was_parsed_in(@TempDir Path dir) throws IOException {
        Path a = Files.writeString(dir.resolve("A.java"), "package p; public class A { public void a() {} }");
        Path b = Files.writeString(dir.resolve("B.java"), "package p; public class B { public int x; }");
        Path c = Files.writeString(dir.resolve("C.java"), BASE);
        Map<Path, String> together =
                JavaSourceApi.parse(List.of(a, b, c), JavaSourceApi.View.DECLARATIONS, Long.MAX_VALUE);
        Map<Path, String> apart = JavaSourceApi.parse(List.of(a, b, c), JavaSourceApi.View.DECLARATIONS, 1);
        assertThat(apart).containsOnlyKeys(a, b, c).isEqualTo(together);
    }

    private static String digest(Path dir, String source) throws IOException {
        return digest(dir, source, JavaSourceApi.View.DECLARATIONS);
    }

    private static String digest(Path dir, String source, JavaSourceApi.View view) throws IOException {
        Path file = dir.resolve("Lib.java");
        Files.writeString(file, source);
        return JavaSourceApi.digest(file, view);
    }
}
