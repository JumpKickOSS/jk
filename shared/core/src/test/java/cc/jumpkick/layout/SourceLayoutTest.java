// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceLayoutTest {

    @Test
    void empty_tree_is_simple(@TempDir Path tmp) {
        assertThat(SourceLayout.isSimpleLayout(tmp)).isTrue();
        assertThat(SourceLayout.looksTraditional(tmp)).isFalse();
    }

    @Test
    void mill_src_is_simple(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("src"));
        Files.writeString(tmp.resolve("src/Main.java"), "class Main {}");
        Files.createDirectories(tmp.resolve("test/src"));
        assertThat(SourceLayout.isSimpleLayout(tmp)).isTrue();
    }

    @Test
    void src_main_java_dir_is_traditional(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("src/main/java"));
        assertThat(SourceLayout.looksTraditional(tmp)).isTrue();
        assertThat(SourceLayout.isSimpleLayout(tmp)).isFalse();
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
    }
}
