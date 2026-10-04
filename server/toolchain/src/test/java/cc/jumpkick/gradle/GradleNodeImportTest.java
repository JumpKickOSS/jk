// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.model.JkBuild;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** gradle-node-plugin: its Node.js and its {@code package.json} become the module's node build. */
class GradleNodeImportTest {

    private static String model(String plugins) {
        return """
                {"gradle":"9.5.1","rootName":"shop","settingsRepositories":[],"projects":[
                  {"path":":","projectName":"shop","dir":"","group":"com.acme","version":"0.1.0","plugins":[%s],
                   "pluginClasses":[],"pluginVersions":{},"bootBuildInfo":false,"repositories":[],
                   "configurations":[],"tasks":[]}]}
                """.formatted(plugins);
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    @Test
    void a_project_with_no_jvm_sources_is_a_node_module_pinned_to_the_plugins_version(@TempDir Path root)
            throws IOException {
        write(root.resolve("build.gradle.kts"), """
                plugins { id("com.github.node-gradle.node") version "7.1.0" }
                node {
                    download.set(true)
                    version.set("20.11.1")
                }
                """);
        write(root.resolve("package.json"), "{\"name\":\"shop\"}");

        GradleBuildImport.Result result =
                GradleModelImporter.importModel(model("\"com.github.node-gradle.node\""), root, RefreshVersions.NONE);

        JkBuild build = result.root();
        assertThat(build.project().nodeSpec().requiredVersion()).isEqualTo("20.11.1");
        assertThat(build.declaresNodeTable()).isFalse();
        assertThat(result.report().issues())
                .extracting(ImportReport.Issue::message)
                .noneMatch(m -> m.contains("com.github.node-gradle.node"));
    }

    @Test
    void a_front_end_in_a_subdirectory_of_a_jvm_project_is_a_side_by_side_build(@TempDir Path root) throws IOException {
        write(root.resolve("build.gradle"), """
                plugins { id 'java'; id 'com.github.node-gradle.node' version '7.1.0' }
                node {
                    version = '22'
                    nodeProjectDir = file("frontend")
                }
                """);
        write(root.resolve("src/main/java/acme/App.java"), "package acme; class App {}");
        write(root.resolve("frontend/package.json"), "{\"name\":\"ui\"}");

        JkBuild build = GradleModelImporter.importModel(
                        model("\"java\",\"com.github.node-gradle.node\""), root, RefreshVersions.NONE)
                .root();

        assertThat(build.project().nodeSpec().suggestedVersion()).isEqualTo("22");
        assertThat(build.node().dir()).isEqualTo("frontend");
    }

    @Test
    void the_node_block_is_read_with_its_braces_balanced() {
        assertThat(GradleNodeImport.nodeBlock("x { }\nnode {\n  a { b }\n  version = '1'\n}\ny {}"))
                .contains("version = '1'")
                .doesNotContain("y {}");
        assertThat(GradleNodeImport.nodeBlock("plugins {}")).isEmpty();
    }
}
