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
                .containsExactly("src/main/java holds Java sources this module does not compile: jk.toml declares"
                        + " kotlin and not java — add java = 25 to compile them");
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
                .containsExactly("src holds .groovy sources this module does not compile: jk.toml declares java,"
                        + " kotlin and not groovy — add groovy = \"<version>\" to compile them");
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
                .containsExactly("src holds .kt sources this module does not compile: jk.toml declares java and not"
                        + " kotlin — add kotlin = \"<version>\" to compile them");
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
                .containsExactly("src/main/kotlin holds Kotlin sources this module does not compile: jk.toml declares"
                        + " java and not kotlin — add kotlin = \"<version>\" to compile them");
    }
}
