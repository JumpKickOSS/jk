// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compile.JavadocTool;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.model.JavadocMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The step's decision table over javadoc's exit. Only strict mode fails the step; the lenient
 * default turns an error — the shape an older JDK's javadoc reports for a malformed comment with
 * doclint off — into warnings and still writes a jar.
 */
class PlannerJavadocTest {

    private static final String MALFORMED =
            "src/main/java/com/xxl/job/admin/core/trigger/JobTrigger.java:52:" + " error: malformed HTML";

    private static final JavadocTool.Result MALFORMED_HTML =
            new JavadocTool.Result(1, List.of(), List.of(MALFORMED), MALFORMED + "\n * a < b\n1 error\n");

    @Test
    void a_lenient_javadoc_error_is_a_warning_and_the_jar_is_what_javadoc_wrote() {
        assertThat(PlannerJavadoc.verdict(JavadocMode.LENIENT, MALFORMED_HTML, true))
                .isEqualTo(PlannerJavadoc.Verdict.LENIENT_TREE);
        assertThat(PlannerJavadoc.verdict(JavadocMode.LENIENT, MALFORMED_HTML, false))
                .isEqualTo(PlannerJavadoc.Verdict.LENIENT_README);
        assertThat(PlannerJavadoc.errorLines("javadoc", MALFORMED_HTML)).containsExactly(MALFORMED);
    }

    @Test
    void only_strict_mode_fails_the_step() {
        assertThat(PlannerJavadoc.verdict(JavadocMode.STRICT, MALFORMED_HTML, true))
                .isEqualTo(PlannerJavadoc.Verdict.FAIL);
        assertThat(PlannerJavadoc.verdict(JavadocMode.STRICT, MALFORMED_HTML, false))
                .isEqualTo(PlannerJavadoc.Verdict.FAIL);
    }

    @Test
    void a_clean_exit_documents_and_a_source_set_without_api_is_the_readme_jar_in_both_modes() {
        JavadocTool.Result clean =
                new JavadocTool.Result(0, List.of("Foo.java:6: warning: unknown tag"), List.of(), "");
        JavadocTool.Result noApi = new JavadocTool.Result(
                1, List.of(), List.of(), "javadoc: error - No public or protected classes found to document.\n");

        assertThat(PlannerJavadoc.verdict(JavadocMode.LENIENT, clean, true))
                .isEqualTo(PlannerJavadoc.Verdict.DOCUMENTED);
        assertThat(PlannerJavadoc.verdict(JavadocMode.STRICT, clean, true))
                .isEqualTo(PlannerJavadoc.Verdict.DOCUMENTED);
        assertThat(PlannerJavadoc.verdict(JavadocMode.LENIENT, noApi, false)).isEqualTo(PlannerJavadoc.Verdict.NO_API);
        assertThat(PlannerJavadoc.verdict(JavadocMode.STRICT, noApi, false)).isEqualTo(PlannerJavadoc.Verdict.NO_API);
    }

    /** Scala sources make scaladoc the tool unless the module has Kotlin, which Dokka documents. */
    @Test
    void scala_sources_are_scaladocs_unless_kotlin_makes_it_a_dokka_run() {
        List<Path> javaAndScala = List.of(Path.of("A.java"), Path.of("B.scala"));
        assertThat(PlannerJavadoc.scaladocSources(javaAndScala, List.of())).containsExactly(Path.of("B.scala"));
        assertThat(PlannerJavadoc.scaladocSources(javaAndScala, List.of(Path.of("C.kt"))))
                .isEmpty();
        assertThat(PlannerJavadoc.scaladocSources(List.of(Path.of("A.java")), List.of()))
                .isEmpty();
    }

    /** A failure javadoc did not locate still names its exit and shows its output. */
    @Test
    void an_unlocated_failure_reports_the_exit_and_the_output() {
        JavadocTool.Result crashed = new JavadocTool.Result(2, List.of(), List.of(), "javadoc: error - cannot read");

        assertThat(PlannerJavadoc.errorLines("javadoc", crashed))
                .containsExactly("javadoc exited 2\njavadoc: error - cannot read");
    }

    /**
     * The real tool over a comment with a bare {@code <} and an unclosed {@code <p>}: with doclint
     * off the lenient default documents it (javadoc prints a warning and exits 0) and never reaches
     * {@link PlannerJavadoc.Verdict#FAIL}; strict keeps doclint on, and the malformed HTML error fails
     * the step. Runs on the JDK the engine runs on, and on every other installed JDK line under
     * {@code ~/.jdks} whose javadoc is present (a 21 sits beside the 25 on a dev machine).
     */
    @Test
    @Tag("integration")
    void a_malformed_comment_documents_under_the_lenient_default_and_fails_only_strict(@TempDir Path tmp)
            throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src/com/ex"));
        Path source = src.resolve("Foo.java");
        Files.writeString(source, """
                package com.ex;
                /**
                 * Summary.
                 * <p>
                 * a < b and an unclosed paragraph
                 */
                public class Foo { public void x() {} }
                """);
        for (Path javaHome : javaHomes()) {
            for (JavadocMode mode : List.of(JavadocMode.LENIENT, JavadocMode.STRICT)) {
                Path out = Files.createDirectories(tmp.resolve(javaHome.getFileName() + "-" + mode));
                JavadocTool.Result r = JavadocTool.run(
                        javaHome, out, List.of(source), List.of(), JavadocTool.options(mode, 21, List.of()), tmp);
                PlannerJavadoc.Verdict verdict = PlannerJavadoc.verdict(mode, r, true);
                if (mode == JavadocMode.LENIENT) {
                    assertThat(verdict)
                            .as("%s lenient: %s", javaHome, r.output())
                            .isEqualTo(PlannerJavadoc.Verdict.DOCUMENTED);
                } else {
                    assertThat(r.errors())
                            .as("%s strict: %s", javaHome, r.output())
                            .anyMatch(e -> e.contains("malformed HTML"));
                    assertThat(verdict).isEqualTo(PlannerJavadoc.Verdict.FAIL);
                }
            }
        }
    }

    /**
     * A module with a descriptor documents against the modules it requires: the sibling's classes
     * tree and its descriptor are found on the module path, so strict javadoc passes instead of
     * failing every {@code requires} with "module not found".
     */
    @Test
    @Tag("integration")
    void a_modular_module_documents_strictly_against_the_module_it_requires(@TempDir Path tmp) throws Exception {
        Path depSrc = Files.createDirectories(tmp.resolve("dep/src/dep"));
        Files.writeString(depSrc.resolve("Dep.java"), """
                package dep;
                /** A dependency. */
                public class Dep {
                    /** Makes one. */
                    public Dep() {}
                }
                """);
        Path depInfo = Files.writeString(tmp.resolve("dep/src/module-info.java"), """
                /** The dependency. */
                module ex.dep { exports dep; }
                """);
        Path depClasses = Files.createDirectories(tmp.resolve("dep/classes"));
        assertThat(ToolProvider.getSystemJavaCompiler()
                        .run(
                                null,
                                null,
                                null,
                                "-d",
                                depClasses.toString(),
                                depInfo.toString(),
                                depSrc.resolve("Dep.java").toString()))
                .isZero();
        Path libSrc = Files.createDirectories(tmp.resolve("lib/src/lib"));
        Path libType = Files.writeString(libSrc.resolve("Lib.java"), """
                package lib;
                /** Uses the dependency. */
                public class Lib {
                    /** Makes one. */
                    public Lib() {}
                    /** @return a dependency */
                    public dep.Dep dep() { return new dep.Dep(); }
                }
                """);
        Path libInfo = Files.writeString(tmp.resolve("lib/src/module-info.java"), """
                /** The library. */
                module ex.lib { requires transitive ex.dep; exports lib; }
                """);
        Path out = Files.createDirectories(tmp.resolve("out"));

        JavadocTool.Result r = JavadocTool.run(
                JavaHomes.runningJavaHome(),
                out,
                List.of(libInfo, libType),
                List.of(depClasses),
                JavadocTool.options(JavadocMode.STRICT, 0, List.of()),
                tmp);

        assertThat(r.success()).as(r.output()).isTrue();
        assertThat(PlannerJavadoc.verdict(JavadocMode.STRICT, r, true)).isEqualTo(PlannerJavadoc.Verdict.DOCUMENTED);
    }

    /**
     * A named module documents against the types an annotation processor generated into its own
     * classes: javadoc builds the module from source, so they are reached by patching the module
     * with its compiled classes, and strict javadoc passes without documenting them.
     */
    @Test
    @Tag("integration")
    void a_modular_module_documents_against_types_only_its_compiled_classes_hold(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("lib/src/lib"));
        Path info = Files.writeString(tmp.resolve("lib/src/module-info.java"), """
                /** The library. */
                module ex.gen { exports lib; }
                """);
        Path generated = Files.writeString(
                Files.createDirectories(tmp.resolve("gen/lib")).resolve("Made.java"), """
                package lib;
                /** Generated. */
                public class Made {
                    /** Makes one. */
                    public Made() {}
                }
                """);
        Path type = Files.writeString(src.resolve("Uses.java"), """
                package lib;
                /** Uses a generated type. */
                public class Uses {
                    /** Makes one. */
                    public Uses() {}
                    /** @return a generated type */
                    public Made made() { return new Made(); }
                }
                """);
        Path classes = Files.createDirectories(tmp.resolve("lib/classes"));
        assertThat(ToolProvider.getSystemJavaCompiler()
                        .run(
                                null,
                                null,
                                null,
                                "-d",
                                classes.toString(),
                                info.toString(),
                                type.toString(),
                                generated.toString()))
                .isZero();
        Path out = Files.createDirectories(tmp.resolve("out-gen"));

        JavadocTool.Result r = JavadocTool.run(
                JavaHomes.runningJavaHome(),
                out,
                List.of(info, type),
                List.of(classes),
                JavadocTool.options(JavadocMode.STRICT, 0, List.of()),
                tmp);

        assertThat(r.success()).as(r.output()).isTrue();
    }

    /** The running JDK plus every {@code ~/.jdks/<vendor>-<major>} line that ships a javadoc. */
    private static List<Path> javaHomes() {
        List<Path> homes = new ArrayList<>();
        homes.add(JavaHomes.runningJavaHome());
        Path jdks = Path.of(System.getProperty("user.home"), ".jdks");
        for (String line : List.of("temurin-21", "temurin-25")) {
            Path home = jdks.resolve(line);
            if (Files.isRegularFile(home.resolve("bin/javadoc")) && !homes.contains(home)) homes.add(home);
        }
        return homes;
    }
}
