// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.ActionRecordText;
import cc.jumpkick.cache.Cas;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.repo.ArtifactMemo;
import cc.jumpkick.repo.RepoArtifactStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code repos/jk-local} is a publish destination. A leftover store-CAS copy of those bytes is a
 * sweep root (via the {@code .jk} memo) so GC does not eat a just-installed worker. Maven-layout
 * jars themselves are independent copies and survive the CAS sweep even when the blob is
 * unreferenced.
 */
class CacheRootsLocalRepoTest {

    @Test
    void locally_published_artifacts_are_sweep_roots(@TempDir Path cacheRoot) throws IOException {
        Cas cas = new Cas(cacheRoot);
        byte[] jar = "worker jar bytes".getBytes();
        Path blob = cas.put(jar);
        String hex = Hashing.sha256Hex(jar);
        String rel = "cc/jumpkick/jk-test-runner/1.0/jk-test-runner-1.0.jar";
        Path artifact = cacheRoot.resolve("repos/jk-local").resolve(rel);
        Files.createDirectories(artifact.getParent());
        Files.write(artifact, jar);
        ArtifactMemo.ofBlob(artifact, "cc.jumpkick:jk-test-runner:1.0", hex)
                .write(ArtifactMemo.jkPath(cacheRoot.resolve("repos/jk-local"), rel));
        Files.setLastModifiedTime(blob, FileTime.fromMillis(System.currentTimeMillis() - 24L * 60 * 60 * 1000));

        Set<String> roots = CacheRoots.collect(cas, cacheRoot.resolve("actions"));
        assertThat(roots).contains(hex);

        var report = CasSweep.sweep(cas, roots, false);
        assertThat(Files.exists(blob)).as("published blob survives the sweep").isTrue();
        assertThat(Files.exists(artifact))
                .as("published jar survives the repo GC")
                .isTrue();
        assertThat(report.deleted()).isZero();
    }

    @Test
    void an_output_a_record_names_with_its_size_is_a_sweep_root(@TempDir Path cacheRoot) throws IOException {
        Cas cas = new Cas(cacheRoot);
        byte[] classes = "compiled classes".getBytes();
        Path blob = cas.put(classes);
        String hex = Hashing.sha256Hex(classes);
        Files.setLastModifiedTime(blob, FileTime.fromMillis(System.currentTimeMillis() - 24L * 60 * 60 * 1000));
        Path record = cacheRoot.resolve("actions/keys/compile-key");
        Files.createDirectories(record.getParent());
        ActionRecordText.Meta sized = new ActionRecordText.Meta(
                0, null, null, null, null, null, null, null, -1, Map.of("classes.jar", (long) classes.length));
        String text = ActionRecordText.render(
                "compile@app", "compile-key", Map.of(), Map.of("classes.jar", hex), Set.of(), List.of(), sized);
        assertThat(text).as("the record names the blob with its size").contains("OUTPUT " + hex + "/" + classes.length);
        Files.writeString(record, text);

        Set<String> roots = CacheRoots.collect(cas, cacheRoot.resolve("actions"));
        assertThat(roots).contains(hex);
        assertThat(CasSweep.sweep(cas, roots, false).deleted()).isZero();
        assertThat(Files.exists(blob))
                .as("a recorded output survives the sweep")
                .isTrue();
    }

    @Test
    void cas_sweep_does_not_delete_maven_layout_jars(@TempDir Path cacheRoot) throws IOException {
        Cas cas = new Cas(cacheRoot);
        byte[] dep = "central mirror bytes".getBytes();
        Path blob = cas.put(dep);
        String hex = Hashing.sha256Hex(dep);
        String rel = "com/example/widget/1.0/widget-1.0.jar";
        RepoArtifactStore.forStoreId(cacheRoot, "central").materialize(rel, blob, hex);
        Path artifact = cacheRoot.resolve("repos/central").resolve(rel);
        Files.setLastModifiedTime(blob, FileTime.fromMillis(System.currentTimeMillis() - 24L * 60 * 60 * 1000));

        Set<String> roots = CacheRoots.collect(cas, cacheRoot.resolve("actions"));
        assertThat(roots).doesNotContain(hex);

        CasSweep.sweep(cas, roots, false);
        assertThat(Files.exists(blob))
                .as("unreferenced store-CAS blob is swept")
                .isFalse();
        assertThat(Files.exists(artifact))
                .as("Maven-layout jar is an independent copy")
                .isTrue();
        assertThat(ArtifactMemo.jkPath(cacheRoot.resolve("repos/central"), rel)).exists();
    }
}
