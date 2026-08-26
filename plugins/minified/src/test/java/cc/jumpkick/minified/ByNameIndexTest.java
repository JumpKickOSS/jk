// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.minified;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.surface.DynamicSurface;
import cc.jumpkick.surface.KeepRuleEmitter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Recognising the two by-name conventions, and nothing else. */
class ByNameIndexTest {

    @Test
    void service_files_and_marker_indexes_both_name_classes(@TempDir Path dir) throws Exception {
        Path jar = jar(
                dir.resolve("in.jar"),
                Map.of(
                        "META-INF/services/com.acme.Spi", "com.acme.Impl\ncom.acme.Other # trailing\n",
                        "META-INF/micronaut/io.micronaut.inject.BeanDefinitionReference/com.example.$Foo$Definition",
                                "",
                        "com/acme/Impl.class", "x"));

        assertThat(ByNameIndex.referencedClasses(List.of(jar)))
                .containsExactlyInAnyOrder("com.acme.Impl", "com.acme.Other", "com.example.$Foo$Definition");
    }

    @Test
    void comments_and_blank_lines_in_a_service_file_are_ignored(@TempDir Path dir) throws Exception {
        Path jar = jar(
                dir.resolve("in.jar"),
                Map.of("META-INF/services/com.acme.Spi", "# a comment\n\n  com.acme.Impl  \n#com.acme.Commented\n"));

        assertThat(ByNameIndex.referencedClasses(List.of(jar))).containsExactly("com.acme.Impl");
    }

    @Test
    void data_files_beside_an_index_are_not_class_names(@TempDir Path dir) throws Exception {
        Path jar = jar(
                dir.resolve("in.jar"),
                Map.of(
                        "META-INF/micronaut-configuration-schemas/io.micronaut.http.Config.json", "{}",
                        "META-INF/maven/com.acme/thing/pom.xml", "<x/>",
                        "META-INF/maven/com.acme/thing/pom.properties", "a=b",
                        "META-INF/INDEX.LIST", "JarIndex-Version: 1.0\n"));

        assertThat(ByNameIndex.referencedClasses(List.of(jar))).isEmpty();
    }

    @Test
    void a_marker_index_needs_four_segments_and_two_class_names() {
        assertThat(ByNameIndex.markerClassName("META-INF/micronaut/com.acme.Spi/com.acme.Impl"))
                .contains("com.acme.Impl");
        // Three segments is a service file or a data dir, not a marker index.
        assertThat(ByNameIndex.markerClassName("META-INF/services/com.acme.Spi"))
                .isEmpty();
        // Five segments is a nested data layout.
        assertThat(ByNameIndex.markerClassName("META-INF/maven/com.acme/thing/pom.xml"))
                .isEmpty();
        // A leaf that is not a class name.
        assertThat(ByNameIndex.markerClassName("META-INF/vendor/com.acme.Spi/notes.txt"))
                .isEmpty();
        assertThat(ByNameIndex.markerClassName("META-INF/vendor/plain/com.acme.Impl"))
                .isEmpty();
    }

    @Test
    void class_name_recognition_is_strict() {
        assertThat(ByNameIndex.isClassName("com.acme.Impl")).isTrue();
        assertThat(ByNameIndex.isClassName("com.example.$Foo$Definition")).isTrue();
        assertThat(ByNameIndex.isClassName("Unqualified")).isFalse(); // no package, ambiguous with a filename
        assertThat(ByNameIndex.isClassName("com..acme")).isFalse();
        assertThat(ByNameIndex.isClassName(".leading")).isFalse();
        assertThat(ByNameIndex.isClassName("trailing.")).isFalse();
        assertThat(ByNameIndex.isClassName("com.acme.thing.json")).isFalse();
        assertThat(ByNameIndex.isClassName("has space.Impl")).isFalse();
        assertThat(ByNameIndex.isClassName("")).isFalse();
        assertThat(ByNameIndex.isClassName(null)).isFalse();
    }

    @Test
    void classes_are_read_across_several_jars(@TempDir Path dir) throws Exception {
        Path a = jar(dir.resolve("a.jar"), Map.of("com/acme/A.class", "x"));
        Path b = jar(dir.resolve("b.jar"), Map.of("com/acme/sub/B.class", "x", "not/a/class.txt", "x"));

        assertThat(ByNameIndex.classesIn(List.of(a, b))).containsExactlyInAnyOrder("com.acme.A", "com.acme.sub.B");
    }

    @Test
    void missing_classes_are_counted_once_each() {
        String output = """
                Warning: Missing class org.jetbrains.annotations.NotNull (referenced from: void a.B.c() and 31 other contexts)
                Warning: Missing class io.netty.channel.epoll.Epoll (referenced from: void d.E.f())
                Warning: Missing class org.jetbrains.annotations.NotNull (referenced from: void g.H.i())
                Info: something unrelated
                """;

        assertThat(ByNameIndex.countMissingClasses(output)).isEqualTo(2);
    }

    @Test
    void output_without_missing_classes_counts_zero() {
        assertThat(ByNameIndex.countMissingClasses("")).isZero();
        assertThat(ByNameIndex.countMissingClasses(null)).isZero();
        assertThat(ByNameIndex.countMissingClasses("Info: all good\n")).isZero();
    }

    @Test
    void names_become_service_implementation_entries_the_emitter_turns_into_keeps() {
        var surface = ByNameIndex.surface(List.of("com.acme.A", "com.acme.B"));

        assertThat(surface.entries())
                .allMatch(e -> e.kind() == DynamicSurface.Kind.SERVICE_IMPLEMENTATION)
                .allMatch(e -> e.origin().equals("index"));
        assertThat(KeepRuleEmitter.emit(surface))
                .contains("-keep class com.acme.A { *; }")
                .contains("-keep class com.acme.B { *; }");
    }

    private static Path jar(Path path, Map<String, String> entries) throws IOException {
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                jos.putNextEntry(new JarEntry(e.getKey()));
                jos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                jos.closeEntry();
            }
        }
        return path;
    }
}
