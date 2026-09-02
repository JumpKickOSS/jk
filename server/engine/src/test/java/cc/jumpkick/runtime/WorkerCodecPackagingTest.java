// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Self-host workers vendor workspace MAIN siblings (plugin-sdk / host) into the jar and omit them
 * from the sidecar POM. {@code JkBuildParser.parse} rewrites {@code workspace:} placeholders before
 * packaging, so lookup must follow real coordinates.
 */
class WorkerCodecPackagingTest {

    @Test
    void workerCodecClassDirs_follows_rewritten_sibling_coordinates(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("ws");
        writeWorkspace(root);

        Path hostLeaf = root.resolve("shared/host");
        Path sdk = root.resolve("shared/plugin-sdk");
        Path worker = root.resolve("plugins/worker");

        JkBuild hostBuild = JkBuildParser.parse(hostLeaf.resolve("jk.toml"));
        Path hostClasses = BuildLayout.of(hostLeaf, hostBuild).classesDir();
        Files.createDirectories(hostClasses);
        Files.writeString(hostClasses.resolve("Jsonl.class"), "jsonl");

        JkBuild sdkBuild = JkBuildParser.parse(sdk.resolve("jk.toml"));
        Path sdkClasses = BuildLayout.of(sdk, sdkBuild).classesDir();
        Files.createDirectories(sdkClasses);
        Files.writeString(sdkClasses.resolve("Sdk.class"), "sdk");

        // parse() rewrites workspace: → cc.jumpkick:jk-plugin-sdk before packaging.
        JkBuild project = JkBuildParser.parse(worker.resolve("jk.toml"));
        assertThat(project.dependencies().of(Scope.MAIN)).anyMatch(d -> "cc.jumpkick:jk-plugin-sdk".equals(d.module()));

        List<Path> codec = PlannerSupport.workerCodecClassDirs(worker, project).stream()
                .map(p -> p.toAbsolutePath().normalize())
                .toList();
        assertThat(codec)
                .containsExactly(
                        sdkClasses.toAbsolutePath().normalize(),
                        hostClasses.toAbsolutePath().normalize());
    }

    @Test
    void worker_pom_omits_vendored_siblings_keeps_external_deps(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("ws");
        writeWorkspace(root);
        Path worker = root.resolve("plugins/worker");

        JkBuild project = JkBuildParser.parse(worker.resolve("jk.toml"));
        JkBuild forPom = InstallPlans.omitVendoredWorkerSiblings(project, worker);
        assertThat(forPom.dependencies().of(Scope.MAIN))
                .noneMatch(d -> d.module().contains("jk-plugin-sdk"))
                .anyMatch(d -> "org.scala-sbt:zinc_3".equals(d.module()));
    }

    @Test
    void worker_pom_inherits_what_a_vendored_sibling_needed_from_outside(@TempDir Path tmp) throws Exception {
        // The jar carries the sibling's CLASSES; the POM has to carry the sibling's own
        // third-party deps in its place. Without this, jk-auditor shipped jk-core's LockfileReader
        // with nothing declaring tomlj and died on the first lockfile it read.
        Path root = tmp.resolve("ws");
        writeWorkspace(root);
        Path worker = root.resolve("plugins/worker");

        JkBuild forPom =
                InstallPlans.omitVendoredWorkerSiblings(JkBuildParser.parse(worker.resolve("jk.toml")), worker);

        assertThat(forPom.dependencies().of(Scope.MAIN))
                .as("hoisted transitively: worker → plugin-sdk → host → tomlj")
                .anyMatch(d -> "org.tomlj:tomlj".equals(d.module()))
                .as("and the vendored siblings themselves stay out — they are inside the jar")
                .noneMatch(d -> d.module().contains("jk-plugin-sdk"))
                .noneMatch(d -> d.module().contains("jk-host"));
    }

    @Test
    void a_library_keeps_its_sibling_edges(@TempDir Path tmp) throws Exception {
        // Only workers vendor. A library's consumers resolve its siblings normally, so its POM
        // must still name them.
        Path root = tmp.resolve("ws");
        writeWorkspace(root);
        Path sdk = root.resolve("shared/plugin-sdk");

        JkBuild forPom = InstallPlans.omitVendoredWorkerSiblings(JkBuildParser.parse(sdk.resolve("jk.toml")), sdk);

        assertThat(forPom.dependencies().of(Scope.MAIN))
                .anyMatch(d -> d.module().contains("jk-host"));
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

        Path hostLeaf = root.resolve("shared/host");
        Files.createDirectories(hostLeaf);
        // A third-party dep two siblings deep: the worker vendors plugin-sdk, which vendors host,
        // which is the only thing that declares tomlj. Nothing but hoisting can surface it.
        Files.writeString(hostLeaf.resolve("jk.toml"), """
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
