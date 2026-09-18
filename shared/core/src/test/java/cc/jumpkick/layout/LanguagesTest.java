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
}
