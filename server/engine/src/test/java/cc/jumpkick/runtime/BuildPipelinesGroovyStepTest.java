// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.run.Pipeline;
import cc.jumpkick.run.Step;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Groovy lane composition{@code jdk}+{@code groovy} ⇒ Groovy only; {@code jdk}+{@code
 * java}+{@code groovy} ⇒ both, compile-groovy first (joint mode), then javac, then the assembler.
 * Groovy+Kotlin in one module is rejected at pipeline construction.
 */
class BuildPipelinesGroovyStepTest {

    @Test
    void jdk_plus_groovy_is_groovy_only(@TempDir Path dir) throws Exception {
        writeManifest(dir, "group=\"com.example\"\nname=\"g\"\nversion=\"0.1.0\"\njdk=25\ngroovy=\"5.0.4\"\n");
        assertThat(stepNames(dir))
                .contains("compile-groovy", "write-stamp-groovy")
                // no Java ⇒ no javac step and no assembler (Groovy publishes directly)
                .doesNotContain("compile-java", "write-stamp", "assemble-classes", "compile-kotlin");
    }

    @Test
    void jdk_plus_java_plus_groovy_enables_both(@TempDir Path dir) throws Exception {
        writeManifest(dir, "group=\"com.example\"\nname=\"b\"\nversion=\"0.1.0\"\njdk=25\njava=25\ngroovy=\"5.0.4\"\n");
        Pipeline pipeline = pipeline(dir);
        assertThat(pipeline.steps().stream().map(Step::name))
                .contains(
                        "compile-groovy",
                        "compile-java",
                        "write-stamp",
                        "write-stamp-groovy",
                        // mixed modules add the assembler that merges both outputs
                        "assemble-classes");
        // Joint mode: groovyc first, javac against its output.
        Step compileJava = pipeline.steps().stream()
                .filter(s -> s.name().equals("compile-java"))
                .findFirst()
                .orElseThrow();
        assertThat(compileJava.requires()).contains("compile-groovy");
        Step assemble = pipeline.steps().stream()
                .filter(s -> s.name().equals("assemble-classes"))
                .findFirst()
                .orElseThrow();
        assertThat(assemble.requires()).contains("compile-java", "compile-groovy");
    }

    // ---- source detection when no language is declared ----------------------

    @Test
    void detects_groovy_from_src_main_groovy(@TempDir Path dir) throws Exception {
        writeManifest(dir, "group=\"com.example\"\nname=\"g\"\nversion=\"0.1.0\"\njdk=25\n");
        Files.createDirectories(dir.resolve("src/main/groovy"));
        assertThat(stepNames(dir)).contains("compile-groovy").doesNotContain("compile-java", "compile-kotlin");
    }

    @Test
    void detects_both_from_a_stray_groovy_and_java_file(@TempDir Path dir) throws Exception {
        writeManifest(dir, "group=\"com.example\"\nname=\"b\"\nversion=\"0.1.0\"\njdk=25\n");
        Path gv = dir.resolve("src/app/Foo.groovy");
        Files.createDirectories(gv.getParent());
        Files.writeString(gv, "class Foo {}");
        Files.writeString(dir.resolve("src/app/Bar.java"), "class Bar {}");
        assertThat(stepNames(dir)).contains("compile-java", "compile-groovy", "assemble-classes");
    }

    @Test
    void explicit_java_ignores_groovy_sources(@TempDir Path dir) throws Exception {
        writeManifest(dir, "group=\"com.example\"\nname=\"j\"\nversion=\"0.1.0\"\njava=25\n");
        Files.createDirectories(dir.resolve("src/main/groovy"));
        assertThat(stepNames(dir)).contains("compile-java").doesNotContain("compile-groovy");
    }

    // ---- groovy+kotlin rejection --------------------------------------------

    @Test
    void declared_groovy_plus_kotlin_fails_the_build(@TempDir Path dir) throws Exception {
        writeManifest(
                dir,
                "group=\"com.example\"\nname=\"x\"\nversion=\"0.1.0\"\njdk=25\nkotlin=\"2.3.21\"\ngroovy=\"5.0.4\"\n");
        assertThatThrownBy(() -> pipeline(dir))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("groovy+kotlin in one module is not supported yet");
    }

    @Test
    void detected_groovy_plus_kotlin_fails_the_build(@TempDir Path dir) throws Exception {
        writeManifest(dir, "group=\"com.example\"\nname=\"x\"\nversion=\"0.1.0\"\njdk=25\n");
        Path src = Files.createDirectories(dir.resolve("src/app"));
        Files.writeString(src.resolve("A.kt"), "class A");
        Files.writeString(src.resolve("B.groovy"), "class B {}");
        assertThatThrownBy(() -> pipeline(dir))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("groovy+kotlin in one module is not supported yet");
    }

    private static void writeManifest(Path dir, String projectBody) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), "[project]\n" + projectBody);
    }

    private static List<String> stepNames(Path dir) {
        return pipeline(dir).steps().stream().map(Step::name).toList();
    }

    private static Pipeline pipeline(Path dir) {
        BuildPipelines.Inputs in = new BuildPipelines.Inputs(
                dir,
                dir.resolve("cache"),
                dir.resolve("jk.toml"),
                dir.resolve("jk-lock.toml"),
                dir,
                1,
                0,
                null,
                null,
                true,
                false,
                false,
                false,
                java.util.Set.of(),
                cc.jumpkick.config.SessionContext.current());
        return BuildPipelines.coreBuilder(in).build();
    }
}
