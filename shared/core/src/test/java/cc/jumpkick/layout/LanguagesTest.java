// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.Project;
import cc.jumpkick.model.VersionSelector;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LanguagesTest {

    @Test
    void infers_scala_from_simple_hello_scala(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("src"));
        Files.writeString(tmp.resolve("src/Hello.scala"), "object Hello");
        Project project = Project.builder("com.example", "hello", "1.0.0").build();
        Languages langs = Languages.resolve(project, tmp);
        assertThat(langs.scala()).isTrue();
        assertThat(langs.java()).isFalse();
        assertThat(langs.kotlin()).isFalse();
        assertThat(langs.groovy()).isFalse();
    }

    @Test
    void infers_scala_from_traditional_src_main_scala(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("src/main/scala"));
        Files.writeString(tmp.resolve("src/main/scala/Hello.scala"), "object Hello");
        Project project = Project.builder("com.example", "hello", "1.0.0").build();
        assertThat(Languages.resolve(project, tmp).scala()).isTrue();
    }

    @Test
    void explicit_java_without_scala_sources_does_not_enable_scala(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("src"));
        Files.writeString(tmp.resolve("src/Main.java"), "class Main {}");
        Project project =
                Project.builder("com.example", "hello", "1.0.0").java(25).build();
        Languages langs = Languages.resolve(project, tmp);
        assertThat(langs.java()).isTrue();
        assertThat(langs.scala()).isFalse();
    }

    /**
     * A declaration names the languages a module compiles: sources of a language it leaves out are
     * warned about once, by their root and the key that would compile them.
     */
    @Test
    void a_kotlin_only_declaration_over_java_sources_names_the_root_and_the_key(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("src/main/java/demo"));
        Files.writeString(tmp.resolve("src/main/java/demo/Legacy.java"), "package demo; class Legacy {}");
        Files.createDirectories(tmp.resolve("src/main/kotlin"));
        Project project = Project.builder("com.example", "hello", "1.0.0")
                .kotlin(VersionSelector.parse("2.2.0"))
                .build();
        assertThat(Languages.resolve(project, tmp).java()).isFalse();
        assertThat(Languages.undeclaredWithSources(project, tmp))
                .containsExactly("src/main/java has Java sources that are not compiled; add java = 25 to jk.toml");
    }

    /**
     * A {@code java} module (its own key or the workspace root's) with Kotlin sources and no
     * {@code kotlin} key compiles none of them: a real project kept its {@code .kt} under
     * {@code src/main/java}, a Kotlin user's first try is {@code src/main/kotlin}. Both are roots a
     * Kotlin compile collects, so both are rows in a root, and the build fails on them.
     */
    @Test
    void kotlin_sources_in_a_java_module_are_uncompiled_kotlin_in_either_root(@TempDir Path tmp) throws Exception {
        Project javaOnly =
                Project.builder("com.example", "hello", "1.0.0").java(25).build();
        Files.createDirectories(tmp.resolve("src/main/java/demo"));
        Files.writeString(tmp.resolve("src/main/java/demo/GreeterKt.kt"), "package demo\nfun f() = 1\n");
        assertThat(Languages.uncompiled(javaOnly, tmp))
                .containsExactly(new Languages.Uncompiled("kotlin", "src/main/java", ".kt sources", true));

        Files.delete(tmp.resolve("src/main/java/demo/GreeterKt.kt"));
        Files.createDirectories(tmp.resolve("src/main/kotlin/demo"));
        Files.writeString(tmp.resolve("src/main/kotlin/demo/GreeterKt.kt"), "package demo\nfun f() = 1\n");
        Languages.Uncompiled row = Languages.uncompiled(javaOnly, tmp).getFirst();
        assertThat(row.java()).isFalse();
        assertThat(row.inRoot()).isTrue();
        assertThat(row.row("kotlin = \"2.4.10\""))
                .isEqualTo(
                        "src/main/kotlin has Kotlin sources that are not compiled; add kotlin = \"2.4.10\" to jk.toml");
    }

    /**
     * A test suite's roots are compile roots too: {@code .kt} under {@code src/test/kotlin} or a
     * suite's {@code src/it/java} would compile with the key, so the row names that root.
     */
    @Test
    void kotlin_sources_in_a_test_suites_root_are_rows_in_that_root(@TempDir Path tmp) throws Exception {
        Project javaOnly =
                Project.builder("com.example", "hello", "1.0.0").java(25).build();
        write(tmp, "src/main/java/demo/App.java", "package demo; class App {}");
        write(tmp, "src/it/java/demo/AppIT.kt", "package demo\nclass AppIT\n");
        assertThat(Languages.uncompiled(javaOnly, tmp))
                .containsExactly(new Languages.Uncompiled("kotlin", "src/it/java", ".kt sources", true));
        write(tmp, "src/test/kotlin/demo/AppTest.kt", "package demo\nclass AppTest\n");
        assertThat(Languages.uncompiled(javaOnly, tmp))
                .containsExactly(new Languages.Uncompiled("kotlin", "src/test/kotlin", "Kotlin sources", true));
    }

    /**
     * Files no compile collects, such as a Java module's test data under {@code src/test/data} or
     * {@code src/test/fixtures}, would not compile with the key either: the row is {@code src}'s,
     * not in a root, and the build warns of it as it always did.
     */
    @Test
    void kotlin_groovy_or_scala_outside_every_compile_root_is_a_row_not_in_a_root(@TempDir Path tmp) throws Exception {
        Project javaOnly =
                Project.builder("com.example", "hello", "1.0.0").java(25).build();
        write(tmp, "src/main/java/demo/App.java", "package demo; class App {}");
        write(tmp, "src/test/data/Sample.kt", "fun sample() = 1\n");
        write(tmp, "src/test/fixtures/parser/input/Script.groovy", "println 'hi'\n");
        write(tmp, "src/it/samples/Model.scala", "case class Model(id: Int)\n");
        assertThat(Languages.uncompiled(javaOnly, tmp))
                .containsExactly(
                        new Languages.Uncompiled("kotlin", "src", ".kt sources", false),
                        new Languages.Uncompiled("groovy", "src", ".groovy sources", false),
                        new Languages.Uncompiled("scala", "src", ".scala sources", false));
        assertThat(Languages.undeclaredWithSources(javaOnly, tmp))
                .first()
                .isEqualTo("src has .kt sources that are not compiled; add kotlin = \"<version>\" to jk.toml");
    }

    /** A compact module compiles every {@code .kt} under {@code src}, and its tests' under {@code test/src}. */
    @Test
    void a_compact_module_s_roots_are_src_and_its_suites_src(@TempDir Path tmp) throws Exception {
        Project javaOnly =
                Project.builder("com.example", "hello", "1.0.0").java(25).build();
        write(tmp, "test/src/AppTest.kt", "class AppTest\n");
        assertThat(Languages.uncompiled(javaOnly, tmp))
                .containsExactly(new Languages.Uncompiled("kotlin", "test/src", ".kt sources", true));
        write(tmp, "src/data/Sample.kt", "fun sample() = 1\n");
        assertThat(Languages.uncompiled(javaOnly, tmp))
                .containsExactly(new Languages.Uncompiled("kotlin", "src", ".kt sources", true));
    }

    private static void write(Path dir, String rel, String text) throws Exception {
        Path file = dir.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    @Test
    void a_declaration_that_covers_every_source_root_and_an_inferred_module_warn_nothing(@TempDir Path tmp)
            throws Exception {
        Files.createDirectories(tmp.resolve("src/main/java"));
        Files.writeString(tmp.resolve("src/main/java/A.java"), "class A {}");
        Files.createDirectories(tmp.resolve("src/main/kotlin"));
        Files.writeString(tmp.resolve("src/main/kotlin/B.kt"), "class B");
        Project both = Project.builder("com.example", "hello", "1.0.0")
                .java(25)
                .kotlin(VersionSelector.parse("2.2.0"))
                .build();
        assertThat(Languages.undeclaredWithSources(both, tmp)).isEmpty();
        Project inferred = Project.builder("com.example", "hello", "1.0.0").build();
        assertThat(Languages.undeclaredWithSources(inferred, tmp)).isEmpty();
        // A root that exists but holds no source of its language is not a dropped language.
        Files.delete(tmp.resolve("src/main/kotlin/B.kt"));
        Project javaOnly =
                Project.builder("com.example", "hello", "1.0.0").java(25).build();
        assertThat(Languages.undeclaredWithSources(javaOnly, tmp)).isEmpty();
    }

    @Test
    void a_simple_layout_names_src_and_the_extension(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("src"));
        Files.writeString(tmp.resolve("src/Main.kt"), "fun main() {}");
        Files.writeString(tmp.resolve("src/Util.groovy"), "class Util {}");
        Project project = Project.builder("com.example", "hello", "1.0.0")
                .java(21)
                .kotlin(VersionSelector.parse("2.2.0"))
                .build();
        assertThat(Languages.undeclaredWithSources(project, tmp))
                .containsExactly(
                        "src has .groovy sources that are not compiled; add groovy = \"<version>\" to jk.toml");
    }

    @Test
    void explicit_scala_pin_enables_scala_without_sources(@TempDir Path tmp) {
        Project project = Project.builder("com.example", "hello", "1.0.0")
                .scala(VersionSelector.parse("3"))
                .build();
        assertThat(Languages.resolve(project, tmp).scala()).isTrue();
        assertThat(project.languageName()).isEqualTo("scala");
    }

    @Test
    void kotlin_or_groovy_under_src_main_resources_are_not_undeclared_sources(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("src/main/java"));
        Files.writeString(tmp.resolve("src/main/java/App.java"), "class App {}");
        Files.createDirectories(tmp.resolve("src/main/resources/templates"));
        Files.writeString(tmp.resolve("src/main/resources/templates/Hello.kt"), "fun unused() {}");
        Files.writeString(tmp.resolve("src/main/resources/templates/Util.groovy"), "class Util {}");
        Project project =
                Project.builder("com.example", "hello", "1.0.0").java(25).build();
        assertThat(Languages.undeclaredWithSources(project, tmp)).isEmpty();
        assertThat(Languages.resolve(project, tmp).kotlin()).isFalse();
        assertThat(Languages.resolve(project, tmp).groovy()).isFalse();
    }

    @Test
    void kotlin_under_compact_resources_is_not_a_source(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("src"));
        Files.writeString(tmp.resolve("src/App.java"), "class App {}");
        Files.createDirectories(tmp.resolve("resources"));
        Files.writeString(tmp.resolve("resources/Snippet.kt"), "fun unused() {}");
        Project project =
                Project.builder("com.example", "hello", "1.0.0").java(25).build();
        assertThat(Languages.anySourceUnder(tmp, ".kt")).isFalse();
        assertThat(Languages.undeclaredWithSources(project, tmp)).isEmpty();
    }

    @Test
    void package_named_resources_under_java_still_counts_as_sources(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("src/main/java/com/resources"));
        Files.writeString(tmp.resolve("src/main/java/com/resources/Lib.kt"), "package com.resources; class Lib");
        Project project =
                Project.builder("com.example", "hello", "1.0.0").java(25).build();
        assertThat(Languages.undeclaredWithSources(project, tmp))
                .containsExactly(
                        "src/main/java has .kt sources that are not compiled; add kotlin = \"<version>\" to jk.toml");
    }

    @Test
    void a_checkout_under_a_resources_shaped_path_still_infers_its_languages(@TempDir Path tmp) throws Exception {
        // The module lives at .../src/work/resources/app — a shape the traditional rule must not
        // read from above the walk root.
        Path module = tmp.resolve("src/work/resources/app");
        Files.createDirectories(module.resolve("src/main/kotlin"));
        Files.writeString(module.resolve("src/main/kotlin/App.kt"), "fun main() {}");
        Project project = Project.builder("com.example", "hello", "1.0.0").build();
        assertThat(Languages.resolve(project, module).kotlin()).isTrue();
        assertThat(Languages.anySourceUnder(module.resolve("src"), ".kt")).isTrue();
    }

    @Test
    void a_package_named_resources_under_a_language_root_is_a_source(@TempDir Path tmp) throws Exception {
        Path kotlin = tmp.resolve("src/main/kotlin");
        Files.createDirectories(kotlin.resolve("com/resources"));
        Files.writeString(kotlin.resolve("com/resources/Lib.kt"), "package com.resources; class Lib");
        assertThat(Languages.anySourceUnder(kotlin, ".kt")).isTrue();
        assertThat(Languages.anySourceUnder(tmp.resolve("src"), ".kt")).isTrue();
        Files.createDirectories(tmp.resolve("src/main/resources"));
        Files.writeString(tmp.resolve("src/main/resources/Tpl.kt"), "fun unused() {}");
        assertThat(Languages.anySourceUnder(tmp.resolve("src/main/resources"), ".kt"))
                .as("a walk rooted inside the resource tree sees files, not sources")
                .isTrue();
        Project project =
                Project.builder("com.example", "hello", "1.0.0").java(25).build();
        assertThat(Languages.undeclaredWithSources(project, tmp))
                .containsExactly(
                        "src/main/kotlin has Kotlin sources that are not compiled; add kotlin = \"<version>\" to jk.toml");
    }
}
