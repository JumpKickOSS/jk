// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.cache.ShelfManifest;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.repo.EffectivePomBuilder;
import cc.jumpkick.repo.MavenLayout;
import cc.jumpkick.repo.PomRuntimeClasspath;
import cc.jumpkick.repo.RepoArtifactResolver;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.testing.FakeClock;
import cc.jumpkick.testing.SysProps;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two checkouts at one version install in turn; each engine keeps forking the workers it was
 * installed with, whatever the shelf holds now.
 */
@SysProps.TempRoots("jk.env.JK_STORE_DIR")
class ShelfPinsTest {

    private static final String ENGINE_A = "a".repeat(64);
    private static final String ENGINE_B = "b".repeat(64);
    private static final Coordinate COMPILER = Coordinate.of("cc.jumpkick", "jk-java-compiler", "1.0");
    private static final Coordinate RUNNER = Coordinate.of("cc.jumpkick", "jk-test-runner", "1.0");

    @BeforeEach
    void clearCaches() {
        EffectivePomBuilder.clearProcessCache();
        RepoGroup.clearProcessFetchCache();
        PomRuntimeClasspath.clearResolveCacheForTests();
    }

    @Test
    void each_engine_forks_the_worker_bytes_its_own_install_shelved(@TempDir Path tmp) throws Exception {
        Path manifest = tmp.resolve("lib/jk-engine/" + ShelfManifest.FILE_NAME);
        ShelfPins engineA = new ShelfPins(() -> ENGINE_A, () -> manifest);
        ShelfPins engineB = new ShelfPins(() -> ENGINE_B, () -> manifest);
        Path shelf = JkStores.store().resolve("repos").resolve(RepoArtifactResolver.JK_LOCAL);
        Path compiler = shelf.resolve(MavenLayout.artifactPath(COMPILER));
        Path runner = shelf.resolve(MavenLayout.artifactPath(RUNNER));

        // Tree A installs: both workers shelved, pinned to engine A.
        Map<String, String> treeA = install(tmp.resolve("tree-a"), manifest, ENGINE_A, "compiler A", "runner A");
        ShelfManifest pinsA = requireNonNull(engineA.manifest());
        assertThat(pinsA.jars()).containsExactlyInAnyOrderEntriesOf(treeA);
        assertThat(engineB.manifest()).as("engine B has no install yet").isNull();
        List<Path> forkA = WorkerLaunchClasspath.pinJkLocal(List.of(compiler, runner), pinsA);
        assertThat(forkA).allSatisfy(p -> assertThat(Cas.isBlobPath(p)).isTrue());
        assertThat(forkA.get(0)).hasContent("compiler A");
        assertThat(forkA.get(1)).hasContent("runner A");

        // Tree B, one worker changed, installs over the same shelf slots for engine B.
        Map<String, String> treeB = install(tmp.resolve("tree-b"), manifest, ENGINE_B, "compiler B", "runner A");
        assertThat(compiler).hasContent("compiler B");

        // The displaced engine A keeps its own manifest and forks A's compiler from the store.
        ShelfManifest stillA = engineA.manifest();
        assertThat(stillA).isSameAs(pinsA);
        List<Path> laterForkA = WorkerLaunchClasspath.pinJkLocal(List.of(compiler, runner), stillA);
        assertThat(laterForkA.get(0)).hasContent("compiler A");
        assertThat(laterForkA.get(1)).hasContent("runner A");
        for (Path p : laterForkA) {
            assertThat(pinsA.jars()).containsValue(Hashing.sha256Hex(p));
        }

        // Engine B forks B's workers, every one at a sha its manifest names.
        ShelfManifest pinsB = requireNonNull(engineB.manifest());
        assertThat(pinsB.jars()).containsExactlyInAnyOrderEntriesOf(treeB);
        assertThat(pinsB.source())
                .isEqualTo(tmp.resolve("tree-b").toAbsolutePath().normalize().toString());
        List<Path> forkB = WorkerLaunchClasspath.pinJkLocal(List.of(compiler, runner), pinsB);
        assertThat(forkB.get(0)).hasContent("compiler B");
        assertThat(forkB.get(1)).hasContent("runner A");
        for (Path p : forkB) {
            assertThat(pinsB.jars()).containsValue(Hashing.sha256Hex(p));
        }
    }

    @Test
    void a_reinstall_of_the_same_engine_moves_its_pins(@TempDir Path tmp) throws Exception {
        Path manifest = tmp.resolve(ShelfManifest.FILE_NAME);
        ShelfPins engine = new ShelfPins(() -> ENGINE_A, () -> manifest);
        install(tmp.resolve("tree"), manifest, ENGINE_A, "compiler v1", "runner v1");
        ShelfManifest first = requireNonNull(engine.manifest());

        Map<String, String> second = install(tmp.resolve("tree"), manifest, ENGINE_A, "compiler v2", "runner v1");
        ShelfManifest moved = requireNonNull(engine.manifest());
        assertThat(moved).isNotSameAs(first);
        assertThat(moved.jars()).containsExactlyInAnyOrderEntriesOf(second);

        Path compiler = JkStores.store()
                .resolve("repos")
                .resolve(RepoArtifactResolver.JK_LOCAL)
                .resolve(MavenLayout.artifactPath(COMPILER));
        assertThat(WorkerLaunchClasspath.pinJkLocal(List.of(compiler), moved).get(0))
                .hasContent("compiler v2");
    }

    @Test
    void an_engine_without_a_jar_identity_pins_nothing(@TempDir Path tmp) throws Exception {
        Path manifest = tmp.resolve(ShelfManifest.FILE_NAME);
        install(tmp.resolve("tree"), manifest, ENGINE_A, "compiler", "runner");
        assertThat(new ShelfPins(() -> "", () -> manifest).manifest()).isNull();
    }

    @Test
    void a_pinned_jar_the_store_no_longer_holds_fails_the_launch_naming_both_shas(@TempDir Path tmp) throws Exception {
        Path manifest = tmp.resolve(ShelfManifest.FILE_NAME);
        ShelfPins engine = new ShelfPins(() -> ENGINE_A, () -> manifest);
        install(tmp.resolve("tree-a"), manifest, ENGINE_A, "compiler A", "runner A");
        ShelfManifest pinsA = requireNonNull(engine.manifest());
        String pinnedCompiler = pinsA.sha(COMPILER.group() + ":" + COMPILER.artifact() + ":" + COMPILER.version())
                .orElseThrow();
        install(tmp.resolve("tree-b"), manifest, ENGINE_B, "compiler B", "runner A");
        Files.delete(JkStores.storeCas().pathFor(pinnedCompiler));

        Path compiler = JkStores.store()
                .resolve("repos")
                .resolve(RepoArtifactResolver.JK_LOCAL)
                .resolve(MavenLayout.artifactPath(COMPILER));
        assertThatThrownBy(() -> WorkerLaunchClasspath.pinJkLocal(List.of(compiler), pinsA))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cc.jumpkick:jk-java-compiler:1.0")
                .hasMessageContaining(pinnedCompiler.substring(0, 12))
                .hasMessageContaining(Hashing.sha256Hex(compiler).substring(0, 12))
                .hasMessageContaining(
                        tmp.resolve("tree-a").toAbsolutePath().normalize().toString())
                .hasMessageContaining("`jk install`");
    }

    @Test
    void a_deleted_or_foreign_manifest_does_not_unpin_a_running_engine(@TempDir Path tmp) throws Exception {
        Path manifest = tmp.resolve(ShelfManifest.FILE_NAME);
        ShelfPins engine = new ShelfPins(() -> ENGINE_A, () -> manifest);
        assertThat(engine.manifest()).as("no file yet").isNull();
        install(tmp.resolve("tree"), manifest, ENGINE_A, "compiler", "runner");
        ShelfManifest adopted = requireNonNull(engine.manifest());
        assertThat(engine.manifest()).as("unchanged file: the same object").isSameAs(adopted);
        Files.delete(manifest);
        assertThat(engine.manifest()).isSameAs(adopted);
        ShelfManifest.record(manifest, ENGINE_B, tmp, Map.of(), Map.of(), new FakeClock());
        assertThat(engine.manifest()).isSameAs(adopted);
    }

    @Test
    void a_pinned_worker_walks_its_closure_from_the_pinned_pom_when_the_shelf_pom_is_another_install_s(
            @TempDir Path tmp) throws Exception {
        Path store = JkStores.store();
        Coordinate libV1 = Coordinate.of("org.example", "lib", "1.0");
        Coordinate libV2 = Coordinate.of("org.example", "lib", "2.0");
        Path libV1Jar = central(store, libV1);
        Path libV2Jar = central(store, libV2);
        Path manifest = tmp.resolve(ShelfManifest.FILE_NAME);
        ShelfPins engineA = new ShelfPins(() -> ENGINE_A, () -> manifest);
        ShelfPins engineB = new ShelfPins(() -> ENGINE_B, () -> manifest);
        Path compiler = store.resolve("repos")
                .resolve(RepoArtifactResolver.JK_LOCAL)
                .resolve(MavenLayout.artifactPath(COMPILER));

        // Tree A: the same compiler bytes as tree B will shelve, depending on lib 1.0.
        installWithPom(tmp.resolve("tree-a"), manifest, ENGINE_A, "compiler", workerPom(libV1));
        ShelfManifest pinsA = requireNonNull(engineA.manifest());
        assertThat(pinsA.pomSha(COMPILER.toGav())).isPresent();
        assertThat(WorkerLaunchClasspath.pinnedPom(compiler, pinsA))
                .as("the shelf POM is the pinned one: walk it in place")
                .isNull();
        assertThat(WorkerLaunchClasspath.paths(compiler, pinsA))
                .contains(libV1Jar)
                .doesNotContain(libV2Jar);

        // Tree B bumps the dependency and installs over the same shelf slot.
        installWithPom(tmp.resolve("tree-b"), manifest, ENGINE_B, "compiler", workerPom(libV2));
        ShelfManifest pinsB = requireNonNull(engineB.manifest());
        assertThat(WorkerLaunchClasspath.paths(compiler, pinsB))
                .contains(libV2Jar)
                .doesNotContain(libV1Jar);

        // Engine A still forks on tree A's dependency list, from the store's copy of its POM.
        Path pinned = requireNonNull(WorkerLaunchClasspath.pinnedPom(compiler, pinsA));
        assertThat(Cas.isBlobPath(pinned)).isTrue();
        assertThat(Hashing.sha256Hex(pinned))
                .isEqualTo(pinsA.pomSha(COMPILER.toGav()).orElseThrow());
        assertThat(WorkerLaunchClasspath.paths(compiler, pinsA))
                .contains(libV1Jar)
                .doesNotContain(libV2Jar);

        // With the store's copy gone, the launch fails naming the coordinate and the checkout.
        Files.delete(pinned);
        assertThatThrownBy(() -> WorkerLaunchClasspath.paths(compiler, pinsA))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("POM of " + COMPILER.toGav())
                .hasMessageContaining(
                        tmp.resolve("tree-a").toAbsolutePath().normalize().toString())
                .hasMessageContaining("`jk install`");
    }

    private static String workerPom(Coordinate dep) {
        return "<project><modelVersion>4.0.0</modelVersion><groupId>" + COMPILER.group() + "</groupId><artifactId>"
                + COMPILER.artifact() + "</artifactId><version>" + COMPILER.version() + "</version><dependencies>"
                + "<dependency><groupId>" + dep.group() + "</groupId><artifactId>" + dep.artifact()
                + "</artifactId><version>" + dep.version() + "</version></dependency></dependencies></project>";
    }

    /** A dependency-free library in the store's {@code central} mirror, jar and POM with memos; returns the jar. */
    private static Path central(Path store, Coordinate coord) throws Exception {
        String pom = "<project><modelVersion>4.0.0</modelVersion><groupId>" + coord.group() + "</groupId><artifactId>"
                + coord.artifact() + "</artifactId><version>" + coord.version() + "</version></project>";
        RepoArtifactStore repo = RepoArtifactStore.forStoreId(store, "central");
        Path jar = null;
        for (var entry : Map.of(
                        MavenLayout.artifactPath(coord),
                        coord.artifact() + " " + coord.version(),
                        MavenLayout.pomPath(coord),
                        pom)
                .entrySet()) {
            Path f = store.resolve("repos/central").resolve(entry.getKey());
            Files.createDirectories(f.getParent());
            byte[] bytes = entry.getValue().getBytes(StandardCharsets.UTF_8);
            Files.write(f, bytes);
            repo.writeMemo(entry.getKey(), f, Hashing.sha256Hex(bytes));
            if (entry.getKey().endsWith(".jar")) jar = f.toAbsolutePath().normalize();
        }
        return requireNonNull(jar);
    }

    /** One checkout's install of the compiler alone: jar and POM shelved, both pinned to {@code engine}. */
    private static void installWithPom(Path tree, Path manifest, String engine, String jarBytes, String pom)
            throws IOException {
        Files.createDirectories(tree);
        Path builtJar = Files.writeString(tree.resolve(COMPILER.artifact() + ".jar"), jarBytes);
        Path builtPom = Files.writeString(tree.resolve(COMPILER.artifact() + ".pom"), pom);
        String jarRel = MavenLayout.artifactPath(COMPILER);
        String pomRel = MavenLayout.pomPath(COMPILER);
        String jarSha = RepoArtifactStore.writeToLocalStore(JkStores.store(), jarRel, builtJar, engine);
        String pomSha = RepoArtifactStore.writeToLocalStore(JkStores.store(), pomRel, builtPom, engine);
        ShelfManifest.record(
                manifest,
                engine,
                tree,
                Map.of(RepoArtifactStore.inferGav(jarRel), jarSha),
                Map.of(RepoArtifactStore.inferGav(pomRel), pomSha),
                new FakeClock());
    }

    /**
     * What one checkout's {@code jk install} leaves: both workers on the shelf (the store's CAS
     * fed first) and the manifest pinning them to {@code engine}. Returns the pins it recorded.
     */
    private static Map<String, String> install(
            Path tree, Path manifest, String engine, String compilerBytes, String runnerBytes) throws IOException {
        Files.createDirectories(tree);
        Map<String, String> jars = new LinkedHashMap<>();
        for (var entry : Map.of(COMPILER, compilerBytes, RUNNER, runnerBytes).entrySet()) {
            Coordinate c = entry.getKey();
            Path built = Files.writeString(tree.resolve(c.artifact() + ".jar"), entry.getValue());
            String rel = MavenLayout.artifactPath(c);
            String sha = RepoArtifactStore.writeToLocalStore(JkStores.store(), rel, built, engine);
            jars.put(RepoArtifactStore.inferGav(rel), sha);
        }
        ShelfManifest.record(manifest, engine, tree, jars, Map.of(), new FakeClock());
        return jars;
    }
}
