// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.run.TaskNames;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The step that writes a module's jar is the one install shelves and publish uploads after. */
class ModuleJarTest {

    @Test
    void a_node_module_with_a_classpath_root_ships_the_jar_node_package_writes(@TempDir Path dir) throws Exception {
        Path web = node(dir, "classpath-root = \"static\"\n");
        assertThat(ModuleJar.packagingStep(JkBuildParser.parse(web.resolve("jk.toml")), web))
                .isEqualTo(TaskNames.NODE_PACKAGE);
    }

    @Test
    void a_node_module_nothing_reads_ships_no_jar(@TempDir Path dir) throws Exception {
        Path web = node(dir, "");
        assertThat(ModuleJar.packagingStep(JkBuildParser.parse(web.resolve("jk.toml")), web))
                .isNull();
    }

    @Test
    void a_jvm_module_ships_the_jar_package_jar_writes(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("src/main/java/ex"));
        Files.writeString(dir.resolve("src/main/java/ex/A.java"), "package ex; class A {}\n");
        Files.writeString(dir.resolve("jk.toml"), """
                group = "ex"
                name = "lib"
                version = "1.0"
                java = 25
                """);
        assertThat(ModuleJar.packagingStep(JkBuildParser.parse(dir.resolve("jk.toml")), dir))
                .isEqualTo(TaskNames.PACKAGE_JAR);
    }

    private static Path node(Path dir, String extra) throws Exception {
        Path web = Files.createDirectories(dir.resolve("web"));
        Files.writeString(web.resolve("jk.toml"), """
                group = "com.example"
                name = "web"
                version = "1.0.0"

                [node]
                version = 24
                """ + extra);
        Files.writeString(web.resolve("package.json"), "{\"name\":\"web\",\"scripts\":{\"build\":\"node build.js\"}}");
        return web;
    }
}
