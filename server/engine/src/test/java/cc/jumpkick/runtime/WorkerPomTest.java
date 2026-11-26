// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A worker's jar holds its own module's classes only, so the POM it is installed with names the
 * workspace libraries it runs on as dependencies, beside its third-party ones: the launch walks
 * that POM to rebuild the worker's classpath.
 */
class WorkerPomTest {

    @Test
    void a_worker_s_pom_names_its_sibling_libraries_and_its_external_deps(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("ws");
        writeWorkspace(root);
        Path worker = root.resolve("plugins/worker");
        JkBuild project = JkBuildParser.parse(worker.resolve("jk.toml"));

        String pom = new String(
                InstallPlans.renderedPomBytes(project, BuildLayout.of(worker, project)), StandardCharsets.UTF_8);

        assertThat(pom)
                .contains("<artifactId>jk-plugin-sdk</artifactId>")
                .contains("<artifactId>zinc_3</artifactId>")
                .as("the sibling's own dependencies stay in the sibling's POM")
                .doesNotContain("<artifactId>tomlj</artifactId>")
                .doesNotContain("<artifactId>jk-host</artifactId>");
    }

    @Test
    void the_worker_jar_packs_no_sibling_classes(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("ws");
        writeWorkspace(root);
        Path worker = root.resolve("plugins/worker");
        JkBuild project = JkBuildParser.parse(worker.resolve("jk.toml"));

        assertThat(PackagingKeys.packagedContributions(null, BuildLayout.of(worker, project), project))
                .isEmpty();
    }

    private static void writeWorkspace(Path root) throws Exception {
        Files.createDirectories(root);
        Files.writeString(root.resolve("jk.toml"), """
                group = "cc.jumpkick"
                name = "jk"
                version = "0.12.0"
                [workspace]
                modules = ["shared/plugin-sdk", "shared/host", "plugins/worker"]
                """);

        Path host = root.resolve("shared/host");
        Files.createDirectories(host);
        Files.writeString(host.resolve("jk.toml"), """
                name = "jk-host"
                [dependencies]
                tomlj = { group = "org.tomlj", name = "tomlj", version = "1.1.1" }
                """);

        Path sdk = root.resolve("shared/plugin-sdk");
        Files.createDirectories(sdk);
        Files.writeString(sdk.resolve("jk.toml"), """
                name = "jk-plugin-sdk"
                [dependencies]
                jk-host.workspace = true
                """);

        Path worker = root.resolve("plugins/worker");
        Files.createDirectories(worker.resolve("src/main/resources/META-INF/services"));
        Files.writeString(worker.resolve("jk.toml"), """
                name = "jk-demo-worker"
                [dependencies]
                jk-plugin-sdk.workspace = true
                zinc = { group = "org.scala-sbt", name = "zinc_3", version = "2.0.4" }
                """);
        Files.writeString(
                worker.resolve("src/main/resources/META-INF/services/cc.jumpkick.plugin.Plugin"), "com.example.Worker");
    }
}
