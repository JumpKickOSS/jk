// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.wire.runtime.TaskForecast;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code jk explain}'s {@code node-package} row comes from the key the step stores under. */
class NodePackageForecastTest {

    @Test
    void the_package_is_cached_once_its_record_is_stored_and_a_server_names_its_start(@TempDir Path dir)
            throws Exception {
        Path web = Files.createDirectories(dir.resolve("web"));
        Files.writeString(web.resolve("jk.toml"), """
                group = "com.example"
                name = "web"
                version = "1.0.0"

                [node]
                version = 24
                classpath-root = "static"
                start = "node server.js"
                """);
        Files.writeString(web.resolve("package.json"), "{\"name\":\"web\",\"scripts\":{\"build\":\"node build.js\"}}");
        Files.createDirectories(web.resolve("dist"));
        Files.writeString(web.resolve("dist/index.html"), "<h1>web</h1>");
        JkBuild project = JkBuildParser.parse(web.resolve("jk.toml"));
        Path cache = dir.resolve("cache");

        TaskForecast.Task before = PlannerNode.forecastPackage(project, web, web, cache, false);
        assertThat(before.name()).isEqualTo(TaskNames.NODE_PACKAGE);
        assertThat(before.status()).isEqualTo(TaskForecast.Status.RUN);
        assertThat(before.text()).contains("package web-1.0.0.jar").endsWith("· start: node server.js");

        Path jar = BuildLayout.of(web, project).mainJar();
        Path jarDir = Files.createDirectories(web.resolve("target"));
        Files.writeString(jar, "jar");
        NodeKeys.Keyed keyed = NodeKeys.pkg(web, web.resolve("dist"), "static", project.manifest());
        PlannerSupport.storePackagedForTest(cache, keyed.taskId(), keyed.key(), List.of(), jarDir, List.of(jar), true);

        TaskForecast.Task after = PlannerNode.forecastPackage(project, web, web, cache, false);
        assertThat(after.status()).isEqualTo(TaskForecast.Status.CACHED);
        assertThat(after.text()).isEqualTo("web-1.0.0.jar up-to-date · start: node server.js");

        assertThat(PlannerNode.forecastPackage(project, web, web, cache, true).status())
                .as("a build that runs repackages")
                .isEqualTo(TaskForecast.Status.RUN);
    }
}
