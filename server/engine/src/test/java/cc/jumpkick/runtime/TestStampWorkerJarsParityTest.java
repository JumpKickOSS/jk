// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * nested-engine CLI modules must fingerprint worker/engine jars in the TestStamp extras
 * that both forecast and live run-tests use.
 */
class TestStampWorkerJarsParityTest {

    @TempDir
    Path tmp;

    @Test
    void cli_module_enriches_worker_jars_into_stamp_extras() throws Exception {
        Path cli = tmp.resolve("clients/cli");
        Files.createDirectories(cli);
        Files.writeString(cli.resolve("jk.toml"), """
                group = "cc.jumpkick"
                name = "jk-cli"
                version = "0.0.1"
                java = 25

                [application]
                main = "cc.jumpkick.cli.Jk"
                """);
        // Sibling engine is a library (no [application].main) → workspace layout puts the
        // main jar at target/<module-rel>/lib/<name>-<ver>.jar (not module-local target/).
        Path engine = tmp.resolve("server/engine");
        Files.createDirectories(engine);
        Files.writeString(engine.resolve("jk.toml"), """
                group = "cc.jumpkick"
                name = "jk-engine"
                version = "0.0.1"
                java = 25
                """);
        // The root manifest needs identity keys: JkBuildParser rejects a name-less jk.toml, so
        // a workspace-only root would make WorkspaceLocator.findRoot silently fail and sibling
        // discovery return nothing.
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "cc.jumpkick"
                name = "ws"
                version = "0.0.1"

                [workspace]
                modules = ["clients/cli", "server/engine"]
                """);
        Path engineJar = tmp.resolve("target/server/engine/lib/jk-engine-0.0.1.jar");
        Files.createDirectories(engineJar.getParent());
        writeMinimalJar(engineJar);

        JkBuild project = JkBuildParser.parse(cli.resolve("jk.toml"));
        assertThat(PlannerSupport.needsNestedEngineIsolation(project)).isTrue();

        // Confine host fallback to the empty tmp tree so a warm dev checkout cannot mask a
        // broken sibling-discovery path.
        BuildPlanner.hostEngineSearchOverride = tmp;
        Map<String, String> workers;
        try {
            workers = PlannerSupport.testStampWorkerJars(cli, project);
        } finally {
            BuildPlanner.hostEngineSearchOverride = null;
        }
        // Workspace sibling path wins when present; cold CI has no host install/dist.
        assertThat(workers).containsKey("jk.engine.jar");
        assertThat(Path.of(workers.get("jk.engine.jar")))
                .as("the workspace sibling jar must win")
                .isEqualTo(engineJar.normalize());
        var extras = PlannerSupport.testStampExtras(cli, project);
        assertThat(extras).anyMatch(s -> s.startsWith("sel:"));
        assertThat(extras).anyMatch(s -> s.startsWith("jk:"));
        assertThat(extras).anyMatch(s -> s.startsWith("worker:"));
    }

    @Test
    void nested_engine_env_does_not_point_cache_or_store_at_host() throws Exception {
        // Nested engine env must not point JK_CACHE_DIR / JK_STORE_DIR at the host trees.
        Path cli = tmp.resolve("clients/cli");
        Files.createDirectories(cli);
        Map<String, String> env = PlannerSupport.nestedEngineTestEnv(cli);
        assertThat(env).containsKey("JK_HOME");
        assertThat(env.get("JK_HTTP_ENABLED")).isEqualTo("false");
        assertThat(env.get("JK_HTTP_PORT")).isEqualTo("0");
        assertThat(env).doesNotContainKey("JK_CACHE_DIR");
        assertThat(env).doesNotContainKey("JK_STORE_DIR");
        Path hostCache = JkDirs.cache().toAbsolutePath().normalize();
        Path hostStore = JkDirs.store().toAbsolutePath().normalize();
        Path jkHome = Path.of(env.get("JK_HOME")).toAbsolutePath().normalize();
        assertThat(jkHome).isAbsolute();
        assertThat(jkHome).isNotEqualTo(hostCache.getParent()); // not ambient product root
        for (String v : env.values()) {
            if (v == null || v.isBlank()) continue;
            Path p;
            try {
                p = Path.of(v).toAbsolutePath().normalize();
            } catch (Exception e) {
                continue; // non-path values (TERM, CI, …)
            }
            assertThat(p).as("nested env must not equal host action-cache root").isNotEqualTo(hostCache);
            assertThat(p)
                    .as("nested env must not equal host artifact store root")
                    .isNotEqualTo(hostStore);
        }
    }

    @Test
    void library_module_without_test_plugin_jars_has_no_workers() throws Exception {
        Path lib = tmp.resolve("lib");
        Files.createDirectories(lib);
        Files.writeString(lib.resolve("jk.toml"), """
                group = "ex"
                name = "lib"
                version = "1.0"
                java = 25
                """);
        JkBuild project = JkBuildParser.parse(lib.resolve("jk.toml"));
        assertThat(PlannerSupport.needsNestedEngineIsolation(project)).isFalse();
        assertThat(PlannerSupport.testStampWorkerJars(lib, project)).isEmpty();
        var extras = PlannerSupport.testStampExtras(lib, project);
        assertThat(extras).noneMatch(s -> s.startsWith("worker:"));
    }

    @Test
    void cli_module_gets_engine_jar_prop_via_host_fallback_when_assembly_missing() throws Exception {
        Path cli = tmp.resolve("clients/cli");
        Files.createDirectories(cli);
        Files.writeString(cli.resolve("jk.toml"), """
                group = "cc.jumpkick"
                name = "jk-cli"
                version = "0.0.1"
                java = 25

                [application]
                main = "cc.jumpkick.cli.Jk"
                """);
        // Engine module exists but has no assembly jar on disk (jk test does not package it).
        Path engine = tmp.resolve("server/engine");
        Files.createDirectories(engine);
        Files.writeString(engine.resolve("jk.toml"), """
                group = "cc.jumpkick"
                name = "jk-engine"
                version = "0.0.1"
                java = 25

                [application]
                main = "cc.jumpkick.engine.EngineMain"
                assembly = true
                """);
        // The root manifest needs identity keys: JkBuildParser rejects a name-less jk.toml, so
        // a workspace-only root would make WorkspaceLocator.findRoot silently fail and sibling
        // discovery return nothing.
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "cc.jumpkick"
                name = "ws"
                version = "0.0.1"

                [workspace]
                modules = ["clients/cli", "server/engine"]
                """);

        // Seed a monorepo-shaped host jar INSIDE @TempDir and confine discovery to it. Never
        // write into the real checkout: a planted near-empty jar under target/dist/lib is a
        // production discovery path — a dogfooded build would hand it to nested engine workers.
        // The override also makes this deterministic on warm developer trees, where the
        // process/EngineInstall probes would otherwise satisfy the assertion even if monorepo
        // fallback broke.
        String ver = JkVersion.VERSION;
        Path seed = tmp.resolve("target/dist/lib/jk-engine-" + ver + ".jar");
        Files.createDirectories(seed.getParent());
        writeMinimalJar(seed);
        BuildPlanner.hostEngineSearchOverride = tmp;
        try {
            JkBuild project = JkBuildParser.parse(cli.resolve("jk.toml"));
            Map<String, String> workers = PlannerSupport.testStampWorkerJars(cli, project);
            // The monorepo product path must supply a jar so pure-jk nested isolation still
            // gets -Djk.engine.jar without a workspace *-all.jar.
            assertThat(workers)
                    .as("jk.engine.jar must be set even when workspace assembly is absent")
                    .containsKey("jk.engine.jar");
            assertThat(Path.of(workers.get("jk.engine.jar")))
                    .as("fallback must resolve the seeded monorepo product jar, not an ambient host jar")
                    .isEqualTo(seed.normalize());
        } finally {
            BuildPlanner.hostEngineSearchOverride = null;
        }
    }

    /** Minimal zip so path existence / monorepo product discovery has a real file. */
    private static void writeMinimalJar(Path jar) throws Exception {
        try (var zos = new ZipOutputStream(Files.newOutputStream(jar))) {
            zos.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zos.write("Manifest-Version: 1.0\n".getBytes());
            zos.closeEntry();
        }
    }
}
