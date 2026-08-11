// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerClasspathTest {

    @Test
    void resolve_jar_only_when_no_sidecar(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("worker.jar");
        Files.writeString(jar, "x");
        String cp = WorkerClasspath.resolve(jar);
        assertThat(cp).isEqualTo(jar.toAbsolutePath().normalize().toString());
    }

    @Test
    void resolve_includes_sidecar_entries(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("worker.jar");
        Path dep = dir.resolve("dep.jar");
        Files.writeString(jar, "w");
        Files.writeString(dep, "d");
        WorkerClasspath.writeSidecar(jar, List.of(dep));
        String cp = WorkerClasspath.resolve(jar);
        String sep = System.getProperty("path.separator");
        assertThat(cp)
                .isEqualTo(jar.toAbsolutePath().normalize()
                        + sep
                        + dep.toAbsolutePath().normalize());
    }

    @Test
    void resolve_skips_missing_sidecar_paths(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("worker.jar");
        Files.writeString(jar, "w");
        Files.writeString(WorkerClasspath.sidecarPath(jar), "# comment\n" + dir.resolve("gone.jar") + "\n");
        // No PluginMain in a fake jar text file; findPluginSdk may still return null.
        assertThat(WorkerClasspath.paths(jar))
                .containsExactly(jar.toAbsolutePath().normalize());
    }

    @Test
    void find_plugin_sdk_from_workspace_target_layout(@TempDir Path root) throws Exception {
        // …/target/plugins/kotlin-compiler/worker.jar + …/target/shared/plugin-sdk/lib/jk-plugin-sdk-1.jar
        Path workerDir = root.resolve("target/plugins/kotlin-compiler");
        Path sdkDir = root.resolve("target/shared/plugin-sdk/lib");
        Files.createDirectories(workerDir);
        Files.createDirectories(sdkDir);
        Path worker = workerDir.resolve("jk-kotlin-compiler-1.jar");
        Path sdk = sdkDir.resolve("jk-plugin-sdk-0.12.0.jar");
        Files.writeString(worker, "w");
        Files.writeString(sdk, "sdk");
        assertThat(WorkerClasspath.findPluginSdk(worker))
                .isEqualTo(sdk.toAbsolutePath().normalize());
        assertThat(WorkerClasspath.paths(worker))
                .contains(
                        worker.toAbsolutePath().normalize(),
                        sdk.toAbsolutePath().normalize());
    }
}
