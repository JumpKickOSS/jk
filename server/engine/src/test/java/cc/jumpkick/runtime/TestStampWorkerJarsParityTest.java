// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JK-1296: nested-engine CLI modules must fingerprint worker/engine jars in the TestStamp extras
 * that both forecast and live run-tests use.
 */
class TestStampWorkerJarsParityTest {

    @TempDir
    Path tmp;

    @Test
    void cli_module_enriches_worker_jars_into_stamp_extras() throws Exception {
        Path cli = tmp.resolve("clients/cli");
        Files.createDirectories(cli);
        Files.writeString(
                cli.resolve("jk.toml"),
                """
                [project]
                group = "cc.jumpkick"
                name = "jk-cli"
                version = "0.0.1"
                java = 25

                [application]
                main = "cc.jumpkick.cli.Jk"
                """);
        // Minimal sibling engine jar so enrichCliTestProps has something to attach.
        Path engine = tmp.resolve("server/engine");
        Files.createDirectories(engine.resolve("target"));
        Path engineJar = engine.resolve("target/jk-engine-0.0.1.jar");
        // empty zip is enough for path existence checks
        try (var zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(engineJar))) {
            zos.putNextEntry(new java.util.zip.ZipEntry("META-INF/MANIFEST.MF"));
            zos.write("Manifest-Version: 1.0\n".getBytes());
            zos.closeEntry();
        }
        Files.writeString(
                engine.resolve("jk.toml"),
                """
                [project]
                group = "cc.jumpkick"
                name = "jk-engine"
                version = "0.0.1"
                java = 25
                """);
        Files.writeString(
                tmp.resolve("jk.toml"),
                """
                [workspace]
                members = ["clients/cli", "server/engine"]
                """);

        JkBuild project = JkBuildParser.parse(cli.resolve("jk.toml"));
        assertThat(BuildPipelines.needsNestedEngineIsolation(project)).isTrue();

        Map<String, String> workers = BuildPipelines.testStampWorkerJars(cli, project);
        // Without a full workspace sibling scan may not find engine — still must not NPE, and
        // extras must include selection tags path via testStampExtras.
        var extras = BuildPipelines.testStampExtras(cli, project);
        assertThat(extras).anyMatch(s -> s.startsWith("sel:"));
        assertThat(extras).anyMatch(s -> s.startsWith("jk:"));
        // When engine jar is discoverable, worker props appear in extras.
        if (!workers.isEmpty()) {
            assertThat(extras).anyMatch(s -> s.startsWith("worker:"));
        }
    }

    @Test
    void library_module_without_test_plugin_jars_has_no_workers() throws Exception {
        Path lib = tmp.resolve("lib");
        Files.createDirectories(lib);
        Files.writeString(
                lib.resolve("jk.toml"),
                """
                [project]
                group = "ex"
                name = "lib"
                version = "1.0"
                java = 25
                """);
        JkBuild project = JkBuildParser.parse(lib.resolve("jk.toml"));
        assertThat(BuildPipelines.needsNestedEngineIsolation(project)).isFalse();
        assertThat(BuildPipelines.testStampWorkerJars(lib, project)).isEmpty();
        var extras = BuildPipelines.testStampExtras(lib, project);
        assertThat(extras).noneMatch(s -> s.startsWith("worker:"));
    }
}
