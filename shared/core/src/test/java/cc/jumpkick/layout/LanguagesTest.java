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

    @Test
    void explicit_scala_pin_enables_scala_without_sources(@TempDir Path tmp) {
        Project project = Project.builder("com.example", "hello", "1.0.0")
                .scala(VersionSelector.parse("3"))
                .build();
        assertThat(Languages.resolve(project, tmp).scala()).isTrue();
        assertThat(project.languageName()).isEqualTo("scala");
    }
}
