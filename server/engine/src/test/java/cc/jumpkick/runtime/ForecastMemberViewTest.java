// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.lock.LockManifestDigest;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.wire.runtime.TaskForecast;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The forecast reads the workspace lock as the member does, the view the build compiles against.
 * A partition row another member holds stays off this module's processor path; when it did not,
 * every module's compile forecast a full rebuild for a {@code -processorpath} the build never
 * passed.
 */
class ForecastMemberViewTest {

    private static final String PROC = "com.example:proc:jar:";

    @Test
    void a_member_forecasts_the_processor_path_the_build_compiles_with(@TempDir Path tmp) throws Exception {
        Path store = Files.createDirectories(tmp.resolve("store"));
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        Path root = Files.createDirectories(tmp.resolve("ws")).toRealPath();
        Files.writeString(root.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"
                java    = 25

                [workspace]
                modules = ["pinned", "other"]
                """);
        Path pinned = module(root, "pinned");
        Path other = module(root, "other");
        // The partition row first, as the workspace lock writes it: read raw, the lock hands every
        // member the first processor row; read through the member view, "other" gets the plain one.
        Lockfile.Artifact partition = materialized(tmp, store, "2.0", "com.example.ProcTwo", Scope.PROCESSOR)
                .withMembers(List.of("pinned"));
        Lockfile.Artifact plain = materialized(tmp, store, "1.0", "com.example.ProcOne", Scope.MAIN, Scope.PROCESSOR);
        LockfileWriter.write(
                new Lockfile(
                        Lockfile.CURRENT_VERSION,
                        "jk test",
                        Lockfile.RESOLUTION_ALGORITHM,
                        null,
                        null,
                        List.of(partition, plain),
                        List.of(),
                        List.of(),
                        List.of(),
                        null,
                        LockManifestDigest.compute(root),
                        null),
                root.resolve("jk-lock.toml"));

        JkBuild project = JkBuildParser.parse(root.resolve("jk.toml"));
        BuildGraph.Result graph = BuildGraph.resolve(root, project);
        assertThat(graph.hasErrors()).isFalse();
        ActionCache actionCache = new ActionCache(JkStores.cacheCas(cache), CacheTree.ACTIONS.under(cache));
        List<TaskForecast.Module> modules = TaskForecaster.of(graph, new Cas(store), actionCache, cache, true);

        assertThat(compileMain(modules, other).text())
                .as("the member the partition does not name reads the workspace's row")
                .contains("com.example.ProcOne (proc-1.0.jar)")
                .doesNotContain("ProcTwo");
        assertThat(compileMain(modules, pinned).text())
                .as("the member the partition names reads its own row")
                .contains("com.example.ProcTwo (proc-2.0.jar)")
                .doesNotContain("ProcOne");
    }

    private static TaskForecast.Task compileMain(List<TaskForecast.Module> modules, Path dir) {
        return modules.stream()
                .filter(m -> m.dir()
                        .toAbsolutePath()
                        .normalize()
                        .equals(dir.toAbsolutePath().normalize()))
                .flatMap(m -> m.steps().stream())
                .filter(s -> s.name().equals(TaskNames.COMPILE_MAIN))
                .findFirst()
                .orElseThrow();
    }

    private static Path module(Path root, String name) throws Exception {
        Path dir = Files.createDirectories(root.resolve(name));
        Files.writeString(dir.resolve("jk.toml"), """
                group   = "com.example"
                name    = "%s"
                version = "1.0.0"
                java    = 25
                """.formatted(name));
        Path src = Files.createDirectories(dir.resolve("src/main/java/" + name));
        Files.writeString(src.resolve("App.java"), "package " + name + ";\npublic class App {}\n");
        return dir;
    }

    /** One checksummed processor row whose jar registers {@code processor} and sits in the store. */
    private static Lockfile.Artifact materialized(
            Path tmp, Path store, String version, String processor, Scope... scopes) throws Exception {
        Path jar = tmp.resolve("proc-" + version + ".jar");
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream zip = new JarOutputStream(out)) {
            zip.putNextEntry(new JarEntry("META-INF/services/javax.annotation.processing.Processor"));
            zip.write((processor + "\n").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        String hex = Hashing.sha256Hex(jar);
        String relative = "com/example/proc/" + version + "/proc-" + version + ".jar";
        RepoArtifactStore.forStoreId(store, "central").materialize(relative, jar, hex);
        return new Lockfile.Artifact(
                PROC,
                version,
                "central+https://repo.maven.apache.org/maven2/",
                "sha256:" + hex,
                null,
                List.of(scopes),
                List.of());
    }
}
