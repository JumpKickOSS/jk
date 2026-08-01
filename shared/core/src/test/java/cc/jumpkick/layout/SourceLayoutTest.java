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
    void auto_is_simple_when_tree_is_empty(@TempDir Path tmp) {
        assertThat(SourceLayout.isSimpleLayout(autoProject(), tmp)).isTrue();
    }

    @Test
    void auto_is_simple_for_mill_style_src(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("src"));
        Files.writeString(tmp.resolve("src/Main.java"), "class Main {}");
        Files.createDirectories(tmp.resolve("test/src"));
        Files.writeString(tmp.resolve("test/src/T.java"), "class T {}");
        assertThat(SourceLayout.isSimpleLayout(autoProject(), tmp)).isTrue();
    }

    @Test
    void auto_is_traditional_when_main_java_sources_exist(@TempDir Path tmp) throws Exception {
        Path main = tmp.resolve("src/main/java");
        Files.createDirectories(main);
        Files.writeString(main.resolve("App.java"), "class App {}");
        assertThat(SourceLayout.isSimpleLayout(autoProject(), tmp)).isFalse();
    }

    @Test
    void auto_is_traditional_for_resources_only_module(@TempDir Path tmp) throws Exception {
        // jk-web shape: classpath assets under src/main/resources, tests under src/test/java.
        Files.createDirectories(tmp.resolve("src/main/resources/web"));
        Files.writeString(tmp.resolve("src/main/resources/web/app.js"), "export {}");
        Path test = tmp.resolve("src/test/java/cc/x");
        Files.createDirectories(test);
        Files.writeString(test.resolve("FoldTest.java"), "class FoldTest {}");
        assertThat(SourceLayout.looksTraditional(tmp)).isTrue();
        assertThat(SourceLayout.isSimpleLayout(autoProject(), tmp)).isFalse();
    }

    @Test
    void auto_is_traditional_for_src_main_resources_alone(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("src/main/resources"));
        Files.writeString(tmp.resolve("src/main/resources/a.txt"), "a");
        assertThat(SourceLayout.isSimpleLayout(autoProject(), tmp)).isFalse();
    }

    @Test
    void explicit_simple_wins_even_with_maven_dirs(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("src/main/resources"));
        JkBuild.Project project = JkBuild.Project.builder("t", "app", "1")
                .jdkMajor(21)
                .java(21)
                .layout(JkBuild.Layout.SIMPLE)
                .build();
        assertThat(SourceLayout.isSimpleLayout(project, tmp)).isTrue();
    }

    private static JkBuild.Project autoProject() {
        return JkBuild.Project.builder("t", "app", "1")
                .jdkMajor(21)
                .java(21)
                .layout(JkBuild.Layout.AUTO)
                .build();
    }
}
