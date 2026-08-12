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
                [project]
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
                [project]
                group = "cc.jumpkick"
                name = "jk-engine"
                version = "0.0.1"
                java = 25
                """);
        Files.writeString(tmp.resolve("jk.toml"), """
                [workspace]
                modules = ["clients/cli", "server/engine"]
                """);
        Path engineJar = tmp.resolve("target/server/engine/lib/jk-engine-0.0.1.jar");
        Files.createDirectories(engineJar.getParent());
        writeMinimalJar(engineJar);

        JkBuild project = JkBuildParser.parse(cli.resolve("jk.toml"));
        assertThat(BuildPlanner.needsNestedEngineIsolation(project)).isTrue();

        Map<String, String> workers = BuildPlanner.testStampWorkerJars(cli, project);
        // Workspace sibling path wins when present; cold CI has no host install/dist.
        assertThat(workers).containsKey("jk.engine.jar");
        assertThat(Path.of(workers.get("jk.engine.jar"))).isRegularFile();
        var extras = BuildPlanner.testStampExtras(cli, project);
        assertThat(extras).anyMatch(s -> s.startsWith("sel:"));
        assertThat(extras).anyMatch(s -> s.startsWith("jk:"));
        assertThat(extras).anyMatch(s -> s.startsWith("worker:"));
    }

    @Test
    void nested_engine_env_does_not_point_cache_or_store_at_host() throws Exception {
        // Regression: JK_CACHE_DIR / JK_STORE_DIR used to be the host trees, so SelfPurge
        // during pure-jk monorepo tests wiped the developer's real action cache and
        // install-local workers mid-build.
        Path cli = tmp.resolve("clients/cli");
        Files.createDirectories(cli);
        Map<String, String> env = BuildPlanner.nestedEngineTestEnv(cli);
        assertThat(env).containsKey("JK_HOME");
        assertThat(env).doesNotContainKey("JK_CACHE_DIR");
        assertThat(env).doesNotContainKey("JK_STORE_DIR");
        Path hostCache = cc.jumpkick.util.JkDirs.cache().toAbsolutePath().normalize();
        Path hostStore = cc.jumpkick.util.JkDirs.store().toAbsolutePath().normalize();
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
                [project]
                group = "ex"
                name = "lib"
                version = "1.0"
                java = 25
                """);
        JkBuild project = JkBuildParser.parse(lib.resolve("jk.toml"));
        assertThat(BuildPlanner.needsNestedEngineIsolation(project)).isFalse();
        assertThat(BuildPlanner.testStampWorkerJars(lib, project)).isEmpty();
        var extras = BuildPlanner.testStampExtras(lib, project);
        assertThat(extras).noneMatch(s -> s.startsWith("worker:"));
    }

    @Test
    void cli_module_gets_engine_jar_prop_via_host_fallback_when_assembly_missing() throws Exception {
        Path cli = tmp.resolve("clients/cli");
        Files.createDirectories(cli);
        Files.writeString(cli.resolve("jk.toml"), """
                [project]
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
                [project]
                group = "cc.jumpkick"
                name = "jk-engine"
                version = "0.0.1"
                java = 25

                [application]
                main = "cc.jumpkick.engine.EngineMain"
                assembly = true
                """);
        Files.writeString(tmp.resolve("jk.toml"), """
                [workspace]
                modules = ["clients/cli", "server/engine"]
                """);

        // Seed a monorepo-shaped host jar so cold CI (no install / no prior dist) still
        // exercises locateHostEngineJar. Prefer an existing shadow/dist jar when present.
        seedMonorepoHostEngineJar();

        JkBuild project = JkBuildParser.parse(cli.resolve("jk.toml"));
        Map<String, String> workers = BuildPlanner.testStampWorkerJars(cli, project);
        // Host process / VersionStore / monorepo product path must supply a fat jar so pure-jk
        // nested isolation still gets -Djk.engine.jar without a workspace *-all.jar.
        assertThat(workers)
                .as("jk.engine.jar must be set even when workspace assembly is absent")
                .containsKey("jk.engine.jar");
        assertThat(Path.of(workers.get("jk.engine.jar"))).isRegularFile();
    }

    /** Minimal zip so path existence / monorepo product discovery has a real file. */
    private static void writeMinimalJar(Path jar) throws Exception {
        try (var zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
            zos.putNextEntry(new java.util.zip.ZipEntry("META-INF/MANIFEST.MF"));
            zos.write("Manifest-Version: 1.0\n".getBytes());
            zos.closeEntry();
        }
    }

    /**
     * Place {@code jk-engine-<VERSION>.jar} where {@link BuildPlanner#findMonorepoEngineJar}
     * looks, without requiring a prior dogfood install. Prefer an existing shadow/dist jar.
     */
    private static Path seedMonorepoHostEngineJar() throws Exception {
        String ver = cc.jumpkick.model.JkVersion.VERSION;
        Path cwd = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        for (Path walk = cwd; walk != null; walk = walk.getParent()) {
            for (String rel : java.util.List.of(
                    "server/engine/build/libs/jk-engine-" + ver + ".jar",
                    "build/dist/lib/jk-engine-" + ver + ".jar",
                    "build/libs/jk-engine-" + ver + ".jar")) {
                Path p = walk.resolve(rel);
                if (Files.isRegularFile(p)) return p.normalize();
            }
        }
        // Cold tree: plant under the first monorepo-shaped root we can find (or cwd).
        Path root = cwd;
        for (Path walk = cwd; walk != null; walk = walk.getParent()) {
            if (Files.isRegularFile(walk.resolve("settings.gradle.kts"))
                    || Files.isRegularFile(walk.resolve("jk.toml"))) {
                root = walk;
                break;
            }
        }
        Path seed = root.resolve("server/engine/build/libs/jk-engine-" + ver + ".jar");
        Files.createDirectories(seed.getParent());
        writeMinimalJar(seed);
        return seed.normalize();
    }
}
