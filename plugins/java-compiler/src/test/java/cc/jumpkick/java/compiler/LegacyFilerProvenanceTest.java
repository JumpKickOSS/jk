// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * On JDK 17 javac's Filer opens processor output through the plain output calls, with no origins:
 * what it writes is still recorded, so the compile goes whole and the next one deletes it.
 */
class LegacyFilerProvenanceTest {

    @Test
    void a_processor_resource_and_source_written_through_the_plain_calls_are_recorded(@TempDir Path dir)
            throws Exception {
        Path classes = Files.createDirectories(dir.resolve("classes"));
        Path gen = Files.createDirectories(dir.resolve("gen"));
        StandardJavaFileManager std =
                ToolProvider.getSystemJavaCompiler().getStandardFileManager(null, null, StandardCharsets.UTF_8);
        std.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classes));
        std.setLocationFromPaths(StandardLocation.SOURCE_OUTPUT, List.of(gen));
        ApProvenance provenance = new ApProvenance();
        var fm = new ProvenanceFileManager(
                std, provenance, null, p -> std.getJavaFileObjects(p).iterator().next(), true);

        FileObject services =
                requireNonNull(fm.getFileForOutput(StandardLocation.CLASS_OUTPUT, "", "META-INF/services/a.B", null));
        try (Writer w = services.openWriter()) {
            w.write("a.Impl\n");
        }
        JavaFileObject source = requireNonNull(
                fm.getJavaFileForOutput(StandardLocation.SOURCE_OUTPUT, "a.Gen", JavaFileObject.Kind.SOURCE, null));
        try (Writer w = source.openWriter()) {
            w.write("package a; class Gen {}\n");
        }
        JavaFileObject cls = requireNonNull(
                fm.getJavaFileForOutput(StandardLocation.CLASS_OUTPUT, "a.Gen", JavaFileObject.Kind.CLASS, null));

        assertThat(provenance.generated.keySet())
                .contains(classes.resolve("META-INF/services/a.B"), gen.resolve("a/Gen.java"));
        assertThat(provenance.aggregating())
                .as("no origins: the compile goes whole")
                .isTrue();
        assertThat(cls.getClass().getSimpleName())
                .as("javac's own class files are not the Filer's")
                .isNotEqualTo("FilerJavaFile");
    }
}
