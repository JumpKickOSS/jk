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
        writeToml(tmp);
        Files.createDirectories(tmp.resolve("resources"));
        assertThat(ModuleLayout.mainResourcesDir(tmp, true)).isEqualTo(tmp.resolve("resources"));
        assertThat(ModuleLayout.roots(tmp).stream().map(ModuleLayout.Root::relative))
                .contains("resources");
    }

    @Test
    void fingerprint_dirs_include_compact_named_suite(@TempDir Path tmp) throws Exception {
        writeToml(tmp);
        Files.createDirectories(tmp.resolve("src"));
        Files.writeString(tmp.resolve("src/Main.java"), "class Main {}");
        Files.createDirectories(tmp.resolve("test/src"));
        Files.writeString(tmp.resolve("test/src/T.java"), "class T {}");
        Files.createDirectories(tmp.resolve("integration/src"));
        Files.writeString(tmp.resolve("integration/src/I.java"), "class I {}");
        Files.createDirectories(tmp.resolve("resources"));
        Files.writeString(tmp.resolve("resources/a.txt"), "a");
        Files.createDirectories(tmp.resolve("integration/resources"));
        Files.writeString(tmp.resolve("integration/resources/f.txt"), "f");

        List<Path> dirs = ModuleLayout.fingerprintDirs(tmp, false);
        assertThat(dirs).anyMatch(p -> p.endsWith("integration") || p.toString().contains("integration"));
        assertThat(dirs).anyMatch(p -> p.endsWith("resources"));
        assertThat(dirs).anyMatch(p -> p.endsWith("test") || p.toString().contains("test/src"));
        assertThat(dirs)
                .anyMatch(p ->
                        p.toString().contains("integration") && p.toString().contains("resources"));
    }

    @Test
    void traditional_main_resources_under_src_main(@TempDir Path tmp) throws Exception {
        writeToml(tmp);
        Files.createDirectories(tmp.resolve("src/main/resources"));
        assertThat(ModuleLayout.mainResourcesDir(tmp, false).endsWith("src/main/resources"))
                .isTrue();
    }

    @Test
    void named_suite_resources_convention(@TempDir Path tmp) throws Exception {
        writeToml(tmp);
        assertThat(ModuleLayout.suiteResourcesDir(tmp, true, "test")).isEqualTo(tmp.resolve("test/resources"));
        assertThat(ModuleLayout.suiteResourcesDir(tmp, true, "integration"))
                .isEqualTo(tmp.resolve("integration/resources"));
        Files.createDirectories(tmp.resolve("integration/src"));
        Files.writeString(tmp.resolve("integration/src/ITest.java"), "class ITest {}");
        Files.createDirectories(tmp.resolve("integration/resources"));
        Files.writeString(tmp.resolve("integration/resources/f.txt"), "x");
        assertThat(ModuleLayout.suiteResourceDirs(tmp, true, List.of("integration")))
                .anyMatch(p -> p.endsWith("resources") && p.toString().contains("integration"));
        assertThat(ModuleLayout.roots(tmp).stream().map(ModuleLayout.Root::relative))
                .contains("integration/src", "integration/resources");
    }

    @Test
    void traditional_main_scala_roots(@TempDir Path tmp) {
        assertThat(ModuleLayout.mainScalaRoots(tmp, false))
                .containsExactly(tmp.resolve("src/main/scala"), tmp.resolve("src/main/java"));
        assertThat(ModuleLayout.mainScalaRoots(tmp, true)).containsExactly(tmp.resolve("src"));
    }

    @Test
    void member_inherits_the_workspace_root_layout(@TempDir Path tmp) throws Exception {
        // : a workspace member that omits `layout` inherits the root's — isCompact must honor
        // that, or raw-scan call sites disagree with compile on an ambiguous tree.
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "t"
                name = "root"
                version = "1.0.0"
                layout = "simple"
                [workspace]
                modules = ["mod"]
                """);
        Path mod = Files.createDirectories(tmp.resolve("mod"));
        Files.writeString(mod.resolve("jk.toml"), """
                name = "mod"
                """);
        // A traditional-looking tree that would otherwise probe to non-compact.
        Files.createDirectories(mod.resolve("src/main/java"));
        Files.writeString(mod.resolve("src/main/java/Foo.java"), "class Foo {}");

        assertThat(ModuleLayout.isCompact(mod))
                .as("member inherits root layout = simple")
                .isTrue();

        // A member with its own explicit layout wins over the root.
        Files.writeString(mod.resolve("jk.toml"), """
                name = "mod"
                layout = "traditional"
                """);
        assertThat(ModuleLayout.isCompact(mod)).isFalse();
    }

    private static void writeToml(Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "t"
                name = "app"
                version = "1.0.0"
                jdk = 25
                java = 25
                """);
    }
}
