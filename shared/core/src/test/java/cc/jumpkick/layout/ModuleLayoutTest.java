// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModuleLayoutTest {

    @Test
    void simple_main_resources_is_top_level_resources(@TempDir Path tmp) throws Exception {
        writeToml(tmp, "simple");
        Files.createDirectories(tmp.resolve("resources"));
        assertThat(ModuleLayout.mainResourcesDir(tmp, true)).isEqualTo(tmp.resolve("resources"));
        assertThat(ModuleLayout.roots(tmp).stream().map(ModuleLayout.Root::relative)).contains("resources");
    }

    @Test
    void fingerprint_dirs_include_compact_named_suite(@TempDir Path tmp) throws Exception {
        writeToml(tmp, "simple");
        Files.createDirectories(tmp.resolve("src"));
        Files.writeString(tmp.resolve("src/Main.java"), "class Main {}");
        Files.createDirectories(tmp.resolve("test"));
        Files.writeString(tmp.resolve("test/T.java"), "class T {}");
        Files.createDirectories(tmp.resolve("integration"));
        Files.writeString(tmp.resolve("integration/I.java"), "class I {}");
        Files.createDirectories(tmp.resolve("resources"));
        Files.writeString(tmp.resolve("resources/a.txt"), "a");
        Files.createDirectories(tmp.resolve("integration-resources"));
        Files.writeString(tmp.resolve("integration-resources/f.txt"), "f");

        List<Path> dirs = ModuleLayout.fingerprintDirs(tmp, false);
        assertThat(dirs).anyMatch(p -> p.endsWith("integration"));
        assertThat(dirs).anyMatch(p -> p.endsWith("resources"));
        assertThat(dirs).anyMatch(p -> p.endsWith("test"));
        assertThat(dirs).anyMatch(p -> p.endsWith("integration-resources"));
    }

    @Test
    void traditional_main_resources_under_src_main(@TempDir Path tmp) throws Exception {
        writeToml(tmp, "traditional");
        Files.createDirectories(tmp.resolve("src/main/resources"));
        assertThat(ModuleLayout.mainResourcesDir(tmp, false).endsWith("src/main/resources")).isTrue();
    }

    @Test
    void named_suite_resources_convention(@TempDir Path tmp) throws Exception {
        writeToml(tmp, "simple");
        assertThat(ModuleLayout.suiteResourcesDir(tmp, true, "test").endsWith("test-resources")).isTrue();
        assertThat(ModuleLayout.suiteResourcesDir(tmp, true, "integration").endsWith("integration-resources"))
                .isTrue();
        // Suite sources discover "integration"; resource dir is then surfaced as TEST_RESOURCE.
        Files.createDirectories(tmp.resolve("integration"));
        Files.writeString(tmp.resolve("integration/ITest.java"), "class ITest {}");
        Files.createDirectories(tmp.resolve("integration-resources"));
        Files.writeString(tmp.resolve("integration-resources/f.txt"), "x");
        assertThat(ModuleLayout.suiteResourceDirs(tmp, true, List.of("integration")))
                .anyMatch(p -> p.endsWith("integration-resources"));
        assertThat(ModuleLayout.roots(tmp).stream().map(ModuleLayout.Root::relative))
                .contains("integration", "integration-resources");
    }

    private static void writeToml(Path dir, String layout) throws Exception {
        Files.writeString(
                dir.resolve("jk.toml"),
                """
                [project]
                group = "t"
                name = "app"
                version = "1.0.0"
                jdk = 21
                java = 21
                layout = "%s"
                """
                        .formatted(layout));
    }
}
