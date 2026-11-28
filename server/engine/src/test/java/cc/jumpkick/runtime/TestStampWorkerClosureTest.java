// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.repo.PomRuntimeClasspath;
import cc.jumpkick.repo.RepoArtifactResolver;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ClasspathFingerprint;
import cc.jumpkick.task.TestStamp;
import cc.jumpkick.task.WorkerClosure;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A suite that forks a worker keys the worker with every library it launches on: a sibling library
 * the suite's own classpath never names still decides what the worker does.
 */
class TestStampWorkerClosureTest {

    private static final String PROPERTY = PluginJar.GENERATOR.jarProperty();

    @BeforeEach
    @AfterEach
    void tidy() {
        PomRuntimeClasspath.clearResolveCacheForTests();
        SessionContext.reset();
    }

    @Test
    void a_library_of_the_worker_moves_the_stamp(@TempDir Path tmp) throws Exception {
        Path ws = workspace(tmp);
        Path host = ws.resolve("host");
        JkBuild project = JkBuildParser.parse(host.resolve("jk.toml"));

        assertThat(WorkerClosure.pathsOrJar(generatorJar(ws))).contains(libJar(ws));
        String before =
                workerToken(TestStampInputs.of(TestStampInputs.Facts.of(host, project), ClasspathFingerprint.ON_DISK)
                        .extras());
        assertThat(before).startsWith(TestStamp.WORKER_JAR + PROPERTY + "=closure:");

        jar(libJar(ws), "com/example/lib/Lib.class", "edited library, longer than before");
        String after =
                workerToken(TestStampInputs.of(TestStampInputs.Facts.of(host, project), ClasspathFingerprint.ON_DISK)
                        .extras());

        assertThat(after).as("the worker jar is unchanged, its library is not").isNotEqualTo(before);
    }

    @Test
    void the_forecast_keys_the_worker_as_the_run_does(@TempDir Path tmp) throws Exception {
        Path ws = workspace(tmp);
        Path host = ws.resolve("host");
        JkBuild project = JkBuildParser.parse(host.resolve("jk.toml"));
        Path cache = tmp.resolve("cache");
        RestoredOutputs restored = new RestoredOutputs(
                new ActionCache(JkStores.cacheCas(cache), CacheTree.ACTIONS.under(cache), JkStores.storeCas()));

        for (String edit : List.of("first", "an edit of the library")) {
            jar(libJar(ws), "com/example/lib/Lib.class", edit);
            List<String> run = TestStampInputs.of(TestStampInputs.Facts.of(host, project), ClasspathFingerprint.ON_DISK)
                    .extras();
            List<String> forecast = TestStampInputs.of(TestStampInputs.Facts.of(host, project), restored.identity())
                    .extras();
            assertThat(workerToken(forecast)).isEqualTo(workerToken(run));
            assertThat(forecast).isEqualTo(run);
        }
    }

    @Test
    void a_library_packaged_after_the_first_stamp_replaces_the_shelf_copy(@TempDir Path tmp) throws Exception {
        Path ws = workspace(tmp);
        Path host = ws.resolve("host");
        JkBuild project = JkBuildParser.parse(host.resolve("jk.toml"));
        Path pom = ws.resolve("lib/target/lib-1.0.0-SNAPSHOT.pom");
        String libPom = Files.readString(pom);
        Path shelf = tmp.resolve("host-store");
        String rel = "com/example/lib/1.0.0-SNAPSHOT/lib-1.0.0-SNAPSHOT";
        shelve(shelf, rel + ".jar", Files.readAllBytes(libJar(ws)));
        shelve(shelf, rel + ".pom", libPom.getBytes(StandardCharsets.UTF_8));
        Files.delete(libJar(ws));
        Files.delete(pom);

        String prevHost = System.getProperty(PomRuntimeClasspath.HOST_STORE_PROPERTY);
        try {
            System.setProperty(
                    PomRuntimeClasspath.HOST_STORE_PROPERTY,
                    shelf.toAbsolutePath().toString());
            assertThat(WorkerClosure.pathsOrJar(generatorJar(ws))).hasSize(2).doesNotContain(libJar(ws));
            String before = workerToken(
                    TestStampInputs.of(TestStampInputs.Facts.of(host, project), ClasspathFingerprint.ON_DISK)
                            .extras());

            jar(libJar(ws), "com/example/lib/Lib.class", "the library this build packaged");
            Files.writeString(pom, libPom);

            assertThat(WorkerClosure.pathsOrJar(generatorJar(ws))).contains(libJar(ws));
            String after = workerToken(
                    TestStampInputs.of(TestStampInputs.Facts.of(host, project), ClasspathFingerprint.ON_DISK)
                            .extras());
            assertThat(after).isNotEqualTo(before);
        } finally {
            if (prevHost == null) System.clearProperty(PomRuntimeClasspath.HOST_STORE_PROPERTY);
            else System.setProperty(PomRuntimeClasspath.HOST_STORE_PROPERTY, prevHost);
        }
    }

    private static void shelve(Path store, String rel, byte[] bytes) throws IOException {
        Path f = store.resolve("repos").resolve(RepoArtifactResolver.JK_LOCAL).resolve(rel);
        Files.createDirectories(f.getParent());
        Files.write(f, bytes);
        RepoArtifactStore.forStoreId(store, RepoArtifactResolver.JK_LOCAL).writeMemo(rel, f, Hashing.sha256Hex(bytes));
    }

    @Test
    void a_rebuilding_library_of_the_worker_forecasts_the_suite(@TempDir Path tmp) throws Exception {
        Path ws = workspace(tmp);
        Path host = ws.resolve("host");
        JkBuild project = JkBuildParser.parse(host.resolve("jk.toml"));

        assertThat(TestStampInputs.rebuildingWorker(host, project, List.of(ws.resolve("lib"))))
                .isEqualTo(PROPERTY);
        assertThat(TestStampInputs.rebuildingWorker(host, project, List.of(ws.resolve("other"))))
                .isNull();
    }

    private static String workerToken(List<String> extras) {
        return extras.stream()
                .filter(t -> t.startsWith(TestStamp.WORKER_JAR + PROPERTY + "="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no worker token in " + extras));
    }

    /**
     * {@code generator}, named as the first-party worker {@code jk-generator} is, on the workspace
     * library {@code lib}, both packaged; {@code host}, whose tests fork the worker; {@code other},
     * a module neither reaches.
     */
    private static Path workspace(Path tmp) throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"
                java    = 25

                [workspace]
                modules = ["lib", "generator", "other", "host"]
                """);
        module(ws, "lib", "lib", "");
        module(ws, "generator", "jk-generator", "");
        module(ws, "other", "other", "");
        module(ws, "host", "host", """

                [build]
                test-plugin-jars = ["generator"]
                """);
        jar(libJar(ws), "com/example/lib/Lib.class", "library");
        pom(ws.resolve("lib/target/lib-1.0.0-SNAPSHOT.pom"), "lib", "");
        jar(generatorJar(ws), "com/example/gen/Gen.class", "worker");
        pom(ws.resolve("generator/target/jk-generator-1.0.0-SNAPSHOT.pom"), "jk-generator", """
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId><artifactId>lib</artifactId><version>1.0.0-SNAPSHOT</version>
                    </dependency>
                  </dependencies>
                """);
        return ws;
    }

    private static void module(Path ws, String dir, String name, String extra) throws IOException {
        Path module = Files.createDirectories(ws.resolve(dir));
        Files.writeString(module.resolve("jk.toml"), """
                group   = "com.example"
                name    = "%s"
                version = "1.0.0"
                java    = 25
                """.formatted(name) + extra);
        Files.createDirectories(module.resolve("target/classes"));
    }

    private static Path libJar(Path ws) {
        return ws.resolve("lib/target/lib-1.0.0-SNAPSHOT.jar").toAbsolutePath().normalize();
    }

    private static Path generatorJar(Path ws) {
        return ws.resolve("generator/target/jk-generator-1.0.0-SNAPSHOT.jar")
                .toAbsolutePath()
                .normalize();
    }

    private static void pom(Path pom, String artifact, String dependencies) throws IOException {
        Files.writeString(pom, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId><artifactId>%s</artifactId><version>1.0.0-SNAPSHOT</version>
                %s</project>
                """.formatted(artifact, dependencies));
    }

    private static void jar(Path jar, String entry, String body) throws IOException {
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream zip = new JarOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entry));
            zip.write(body.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
}
