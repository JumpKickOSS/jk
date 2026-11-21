// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.repo.M2Dirs;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.runtime.workspace.SyncPlans;
import cc.jumpkick.testing.FakeJdk;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk sync} over a rule pack the lock pins from the {@code jk-local} shelf: a pack is rules
 * text, never a worker, so it needs no POM, and the shelf's copy is the pinned jar even when the
 * project's repositories cannot serve it (a first-party snapshot the official repository never
 * publishes).
 */
class SyncGuardPackTest {

    private static final String GA = "cc.jumpkick.guards:demo";
    private static final String VERSION = "0.1.0-SNAPSHOT";

    @TempDir
    Path tmp;

    @BeforeEach
    void isolate() {
        System.setProperty("jk.env.JK_STORE_DIR", tmp.resolve("store").toString());
        System.setProperty(M2Dirs.LOCAL_PROPERTY, tmp.resolve("m2").toString());
        RepoGroup.clearProcessFetchCache();
        RepoGroup.clearProcessVersionsCache();
    }

    @AfterEach
    void restore() {
        System.clearProperty("jk.env.JK_STORE_DIR");
        System.clearProperty(M2Dirs.LOCAL_PROPERTY);
        RepoGroup.clearProcessFetchCache();
    }

    @Test
    void a_pack_in_the_store_syncs_without_asking_for_a_pom() throws Exception {
        Path project = project(true);

        BuildPlanResult result = sync(project);

        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings().toString()).doesNotContain("POM");
    }

    @Test
    void a_pack_the_shelf_holds_syncs_when_the_artifact_store_lost_it() throws Exception {
        Path project = project(false);

        BuildPlanResult result = sync(project);

        assertThat(result.errors()).isEmpty();
        assertThat(JkStores.storeCas().contains(hex(project))).isTrue();
    }

    private static String hex(Path project) throws IOException {
        return Objects.requireNonNull(LockfileReader.read(project.resolve("jk-lock.toml"))
                .plugins()
                .getFirst()
                .sha256Hex());
    }

    /** A project extending the shelf's pack, its lock pinning that pack; {@code inCas} also puts the jar in the CAS. */
    private Path project(boolean inCas) throws IOException {
        Path project = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(project.resolve("jk.toml"), """
                name    = "app"
                group   = "com.example"
                version = "0.1.0"
                java    = 25
                """);
        Files.writeString(project.resolve("jk-guards.toml"), """
                [guards]
                extends = ["%s:%s"]
                """.formatted(GA, VERSION));
        Path versionDir = JkStores.store()
                .resolve("repos/jk-local/cc/jumpkick/guards/demo")
                .resolve(VERSION);
        Files.createDirectories(versionDir);
        Path jar = versionDir.resolve("demo-" + VERSION + ".jar");
        try (OutputStream out = Files.newOutputStream(jar);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("jk-guards.toml"));
            zip.write("[guards]\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Files.writeString(versionDir.resolve("demo-" + VERSION + ".pom"), "<project/>");
        String hex = Hashing.sha256Hex(jar);
        if (inCas) JkStores.storeCas().putFile(jar, hex);
        LockfileWriter.write(
                Lockfile.empty("test")
                        .withPlugins(List.of(Lockfile.PluginEntry.fetched(GA, VERSION, "sha256:" + hex, "jk-local"))),
                project.resolve("jk-lock.toml"));
        return project;
    }

    private BuildPlanResult sync(Path project) throws IOException {
        FakeJdk.create(tmp.resolve("jdks").resolve("temurin-25.0.4"), "25.0.4");
        return SyncPlans.syncBuildPlan(
                        project,
                        tmp.resolve("cache"),
                        tmp.resolve("jdks"),
                        null,
                        false,
                        new AtomicInteger(),
                        new AtomicInteger(),
                        null,
                        false)
                .run();
    }
}
