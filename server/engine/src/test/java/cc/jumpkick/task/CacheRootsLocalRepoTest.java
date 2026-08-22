// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.repo.ArtifactMemo;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.util.Hashing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code repos/local} is a publish destination. A leftover store-CAS copy of those bytes is a
 * sweep root (via the {@code .jk} memo) so GC does not eat a just-installed worker. Maven-layout
 * jars themselves are independent copies and survive CAS sweep / LRU even when the blob is
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
        Path artifact = cacheRoot.resolve("repos/local").resolve(rel);
        Files.createDirectories(artifact.getParent());
        Files.write(artifact, jar);
        ArtifactMemo.ofBlob(artifact, "cc.jumpkick:jk-test-runner:1.0", hex)
                .write(ArtifactMemo.jkPath(cacheRoot.resolve("repos/local"), rel));
        Files.setLastModifiedTime(blob, FileTime.fromMillis(System.currentTimeMillis() - 24L * 60 * 60 * 1000));

        Set<String> roots = CacheRoots.collect(cas, cacheRoot.resolve("actions"), cacheRoot.resolve("tools"));
        assertThat(roots).contains(hex);

        var report = CasSweep.sweep(cas, roots, false);
        assertThat(Files.exists(blob)).as("published blob survives the sweep").isTrue();
        assertThat(Files.exists(artifact))
                .as("published jar survives the repo GC")
                .isTrue();
        assertThat(report.deleted()).isZero();
    }

    @Test
    void cas_sweep_does_not_delete_maven_layout_jars(@TempDir Path cacheRoot) throws IOException {
        Cas cas = new Cas(cacheRoot);
        byte[] dep = "central mirror bytes".getBytes();
        Path blob = cas.put(dep);
        String hex = Hashing.sha256Hex(dep);
        String rel = "com/example/widget/1.0/widget-1.0.jar";
        RepoArtifactStore.forRepoName(cacheRoot, "central").materialize(rel, blob, hex);
        Path artifact = cacheRoot.resolve("repos/central").resolve(rel);
        Files.setLastModifiedTime(blob, FileTime.fromMillis(System.currentTimeMillis() - 24L * 60 * 60 * 1000));

        Set<String> roots = CacheRoots.collect(cas, cacheRoot.resolve("actions"), cacheRoot.resolve("tools"));
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

    @Test
    void lru_eviction_drops_cas_blob_not_the_named_jar(@TempDir Path store) throws IOException {
        Cas cas = new Cas(store);
        byte[] dep = "lru victim bytes".getBytes();
        Path blob = cas.put(dep);
        String hex = Hashing.sha256Hex(dep);
        String rel = "com/example/lru/1.0/lru-1.0.jar";
        RepoArtifactStore.forRepoName(store, "central").materialize(rel, blob, hex);
        Path artifact = store.resolve("repos/central").resolve(rel);

        AccessLedger ledger = new AccessLedger(store.resolve(".access.log"));
        var report = LruEvictor.evictDownTo(cas, 0L, Set.of(), ledger, false);
        assertThat(report.deleted()).isGreaterThanOrEqualTo(1);
        assertThat(Files.exists(blob)).isFalse();
        assertThat(Files.exists(artifact)).isTrue();
        assertThat(ArtifactMemo.jkPath(store.resolve("repos/central"), rel)).exists();
    }
}
