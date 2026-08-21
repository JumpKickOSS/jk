// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.JkBuild;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceLayoutTest {

    @Test
    void empty_tree_is_simple(@TempDir Path tmp) {
        assertThat(SourceLayout.isSimpleLayout(tmp)).isTrue();
        assertThat(SourceLayout.looksTraditional(tmp)).isFalse();
        assertThat(SourceLayout.isSimpleLayout(autoProject(), tmp)).isTrue();
    }

    @Test
    void mill_src_is_simple(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("src"));
        Files.writeString(tmp.resolve("src/Main.java"), "class Main {}");
        Files.createDirectories(tmp.resolve("test/src"));
        assertThat(SourceLayout.isSimpleLayout(tmp)).isTrue();
        assertThat(SourceLayout.isSimpleLayout(autoProject(), tmp)).isTrue();
    }

    @Test
    void src_main_java_dir_is_traditional(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("src/main/java"));
        assertThat(SourceLayout.looksTraditional(tmp)).isTrue();
        assertThat(SourceLayout.isSimpleLayout(tmp)).isFalse();
        assertThat(SourceLayout.isSimpleLayout(autoProject(), tmp)).isFalse();
    }

    @Test
    void src_main_kotlin_dir_is_traditional(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("src/main/kotlin"));
        assertThat(SourceLayout.looksTraditional(tmp)).isTrue();
    }

    @Test
    void src_main_scala_dir_is_traditional(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("src/main/scala"));
        assertThat(SourceLayout.looksTraditional(tmp)).isTrue();
    }

    @Test
    void src_main_groovy_dir_is_traditional(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("src/main/groovy"));
        assertThat(SourceLayout.looksTraditional(tmp)).isTrue();
    }

    @Test
    void src_main_resources_dir_is_traditional(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("src/main/resources"));
        assertThat(SourceLayout.looksTraditional(tmp)).isTrue();
        assertThat(SourceLayout.isSimpleLayout(tmp)).isFalse();
    }

    @Test
    void src_test_java_alone_is_traditional(@TempDir Path tmp) throws Exception {
        // A test-only module (integration-test workspace members). Classified simple, its tests
        // would compile as MAIN sources (simple main root = src/, recursive) without the test
        // classpath — the exact failure the src/test probes exist to prevent.
        Files.createDirectories(tmp.resolve("src/test/java"));
        assertThat(SourceLayout.isSimpleLayout(tmp)).isFalse();
        assertThat(SourceLayout.isSimpleLayout(autoProject(), tmp)).isFalse();
    }

    @Test
    void explicit_simple_wins_even_with_maven_dirs(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("src/main/resources"));
        JkBuild.Project project = JkBuild.Project.builder("t", "app", "1")
                .jdkMajor(25)
                .java(25)
                .layout(JkBuild.Layout.SIMPLE)
                .build();
        assertThat(SourceLayout.isSimpleLayout(project, tmp)).isTrue();
    }

    @Test
    void explicit_traditional_wins_on_mill_tree(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("src"));
        Files.writeString(tmp.resolve("src/Main.java"), "class Main {}");
        JkBuild.Project project = JkBuild.Project.builder("t", "app", "1")
                .jdkMajor(25)
                .java(25)
                .layout(JkBuild.Layout.TRADITIONAL)
                .build();
        assertThat(SourceLayout.isSimpleLayout(project, tmp)).isFalse();
    }

    @Test
    void module_layout_honors_toml_layout_override(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("src/main/resources"));
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "t"
                name = "app"
                version = "1"
                java = 25
                layout = "simple"
                """);
        assertThat(ModuleLayout.isCompact(tmp)).isTrue();
    }

    private static JkBuild.Project autoProject() {
        return JkBuild.Project.builder("t", "app", "1")
                .jdkMajor(25)
                .java(25)
                .layout(JkBuild.Layout.AUTO)
                .build();
    }
}
