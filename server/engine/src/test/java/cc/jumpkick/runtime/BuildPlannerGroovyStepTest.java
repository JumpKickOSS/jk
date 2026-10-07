// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.Task;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Groovy lane composition{@code jdk}+{@code groovy} ⇒ Groovy only; {@code jdk}+{@code
 * java}+{@code groovy} ⇒ both, compile-groovy first (joint mode), then javac, then the assembler.
 * Groovy+Kotlin: Kotlin first, Groovy against its output, then javac when the module has Java.
 */
class BuildPlannerGroovyStepTest {

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
        BuildPlan plan = plan(dir);
        assertThat(plan.steps().stream().map(Task::name))
                .contains(
                        "compile-groovy",
                        "compile-java",
                        "write-stamp",
                        "write-stamp-groovy",
                        // mixed modules add the assembler that merges both outputs
                        "assemble-classes");
        // Joint mode: groovyc first, javac against its output.
        Task compileJava = plan.steps().stream()
                .filter(s -> s.name().equals("compile-java"))
                .findFirst()
                .orElseThrow();
        assertThat(compileJava.requires()).contains("compile-groovy");
        Task assemble = plan.steps().stream()
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

    // ---- groovy+kotlin ------------------------------------------------------

    @Test
    void declared_groovy_plus_kotlin_compiles_kotlin_then_groovy(@TempDir Path dir) throws Exception {
        writeManifest(
                dir,
                "group=\"com.example\"\nname=\"x\"\nversion=\"0.1.0\"\njdk=25\nkotlin=\"2.3.21\"\ngroovy=\"5.0.4\"\n");
        BuildPlan plan = plan(dir);
        assertThat(plan.steps().stream().map(Task::name))
                .contains("compile-kotlin", "compile-groovy", "write-stamp-kotlin", "write-stamp-groovy")
                .doesNotContain("compile-java", "assemble-classes");
        assertThat(step(plan, "compile-groovy").requires()).contains("compile-kotlin");
        assertThat(step(plan, "compile-kotlin").requires()).doesNotContain("compile-groovy");
        // What reads the main classes waits for the last compile, Groovy.s.
        assertThat(step(plan, "package-jar").requires()).contains("write-stamp-groovy");
        assertThat(step(plan, "write-stamp-groovy").requires()).contains("compile-groovy");
        assertThat(step(plan, "build-logic-after-compile").requires()).contains("compile-groovy");
    }

    @Test
    void detected_groovy_plus_kotlin_plans_both(@TempDir Path dir) throws Exception {
        writeManifest(dir, "group=\"com.example\"\nname=\"x\"\nversion=\"0.1.0\"\njdk=25\n");
        Path src = Files.createDirectories(dir.resolve("src/app"));
        Files.writeString(src.resolve("A.kt"), "class A");
        Files.writeString(src.resolve("B.groovy"), "class B {}");
        assertThat(stepNames(dir)).contains("compile-kotlin", "compile-groovy");
    }

    @Test
    void java_kotlin_and_groovy_compile_kotlin_groovy_then_javac(@TempDir Path dir) throws Exception {
        writeManifest(
                dir,
                "group=\"com.example\"\nname=\"x\"\nversion=\"0.1.0\"\njdk=25\njava=25\nkotlin=\"2.3.21\"\ngroovy=\"5.0.4\"\n");
        BuildPlan plan = plan(dir);
        assertThat(step(plan, "compile-groovy").requires()).contains("compile-kotlin");
        assertThat(step(plan, "compile-java").requires()).contains("compile-kotlin", "compile-groovy");
        assertThat(step(plan, "assemble-classes").requires())
                .contains("compile-kotlin", "compile-groovy", "compile-java");
    }

    @Test
    void groovy_reads_kotlin_output_only_when_kotlin_compiled(@TempDir Path dir) throws Exception {
        writeManifest(
                dir,
                "group=\"com.example\"\nname=\"x\"\nversion=\"0.1.0\"\njdk=25\nkotlin=\"2.3.21\"\ngroovy=\"5.0.4\"\n");
        BuildLayout layout = BuildLayout.of(dir, JkBuildParser.parse(dir.resolve("jk.toml")));
        List<Path> cp = List.of(dir.resolve("a.jar"));
        assertThat(PlannerCompile.groovyClasspath(cp, layout, false)).isEqualTo(cp);
        assertThat(PlannerCompile.groovyClasspath(cp, layout, true))
                .containsExactly(cp.get(0), layout.kotlinClassesDir());
    }

    private static Task step(BuildPlan plan, String name) {
        return plan.steps().stream()
                .filter(s -> s.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(name + " not planned"));
    }

    private static void writeManifest(Path dir, String projectBody) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), "" + projectBody);
    }

    private static List<String> stepNames(Path dir) {
        return plan(dir).steps().stream().map(Task::name).toList();
    }

    private static BuildPlan plan(Path dir) {
        BuildPlanner.Inputs in = new BuildPlanner.Inputs(
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
                Set.of(),
                SessionContext.current());
        return BuildPlanner.fullPlan(in);
    }
}
