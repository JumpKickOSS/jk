// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
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
    void recover_lib_deps_when_sidecar_basenames_went_stale() throws Exception {
        // Gradle installLocal rematerializes store/lib/<id>/ under new filenames while a
        // pure-jk target/plugins sidecar still lists the old basenames — launch must still
        // find jsonl/plugin-sdk from the live lib order file.
        String id = "jk-wcrecover-" + System.nanoTime();
        Path src = WorkerLib.root().resolve(".test-src-" + id);
        // m2 layout so idFromWorkerJar agrees with the materialize id.
        Path installed = src.resolve("repos/local/cc/jumpkick/" + id + "/0.1.0/" + id + "-0.1.0.jar");
        Path override = src.resolve("override/cc/jumpkick/" + id + "/0.1.0/" + id + "-0.1.0.jar");
        Path depNewName = src.resolve("jsonl.jar");
        Files.createDirectories(installed.getParent());
        Files.createDirectories(override.getParent());
        // Real zip with PluginMain so findPluginSdk does not walk into the ambient store.
        writePluginMainJar(installed);
        writePluginMainJar(override);
        Files.writeString(depNewName, "jsonl-new-name");
        try {
            assertThat(WorkerLib.idFromWorkerJar(installed)).isEqualTo(id);
            assertThat(WorkerLib.idFromWorkerJar(override)).isEqualTo(id);
            WorkerLib.materialize(id, installed, List.of(depNewName));
            // Sidecars are written while lib basenames exist; a later rematerialize renames
            // them. writeSidecar skips missing paths, so plant the stale line by hand.
            Path stale = WorkerLib.dir(id).resolve("jk-jsonl-0.12.0.jar");
            assertThat(stale).doesNotExist();
            Files.writeString(
                    WorkerClasspath.sidecarPath(override),
                    "# stale after rematerialize\n" + stale.toAbsolutePath().normalize() + "\n");
            assertThat(WorkerLib.pathsIfPresent(override)).isNull();
            List<Path> cp = WorkerClasspath.paths(override);
            assertThat(cp.get(0)).isEqualTo(override.toAbsolutePath().normalize());
            assertThat(cp.stream().map(p -> p.getFileName().toString())).contains("jsonl.jar");
        } finally {
            try {
                WorkerLib.remove(id);
            } catch (Exception ignored) {
                /* cleanup */
            }
            try (var walk = Files.walk(src)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                        /* cleanup */
                    }
                });
            }
        }
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

    /** Minimal jar that contains {@code PluginMain} so findPluginSdk is not consulted. */
    private static void writePluginMainJar(Path jar) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(bos)) {
            jos.putNextEntry(new JarEntry("cc/jumpkick/plugin/process/PluginMain.class"));
            jos.write("not-a-real-class".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
        Files.write(jar, bos.toByteArray());
    }
}
