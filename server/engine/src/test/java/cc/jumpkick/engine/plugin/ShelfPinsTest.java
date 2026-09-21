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
import cc.jumpkick.repo.MavenLayout;
import cc.jumpkick.repo.RepoArtifactResolver;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.testing.FakeClock;
import cc.jumpkick.testing.SysProps;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        ShelfManifest.record(manifest, ENGINE_B, tmp, Map.of(), new FakeClock());
        assertThat(engine.manifest()).isSameAs(adopted);
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
        ShelfManifest.record(manifest, engine, tree, jars, new FakeClock());
        return jars;
    }
}
