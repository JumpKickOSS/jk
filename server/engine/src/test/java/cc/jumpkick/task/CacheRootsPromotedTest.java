// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.ActionPromote;
import cc.jumpkick.cache.Cas;
import cc.jumpkick.util.Hashing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JK-1769: a release-promoted blob's only other root is the Class-C action key, which
 * {@code jk cache clean} unconditionally drops — the promotion marker must keep the store blob
 * alive through the documented {@code cache clean} → {@code storage clean} sequence, and expire
 * by its own TTL rather than living forever.
 */
class CacheRootsPromotedTest {

    @Test
    void promoted_blobs_survive_the_sweep_after_all_action_keys_are_gone(@TempDir Path tmp) throws IOException {
        Cas cacheCas = new Cas(tmp.resolve("cache"));
        Cas storeCas = new Cas(tmp.resolve("store"));
        byte[] release = "native binary bytes".getBytes();
        cacheCas.put(release);
        String hex = Hashing.sha256Hex(release);

        ActionPromote.promoteShas(cacheCas, storeCas, Set.of(hex));
        assertThat(Files.isRegularFile(storeCas.pathFor(hex))).isTrue();
        // Simulate `jk cache clean` having dropped every Class-C key: no actions/ roots at all.
        // Blob old enough to clear the sweep's min-age guard.
        Files.setLastModifiedTime(
                storeCas.pathFor(hex), FileTime.fromMillis(System.currentTimeMillis() - 24L * 60 * 60 * 1000));

        Set<String> roots = CacheRoots.collect(
                storeCas,
                tmp.resolve("store").resolve("actions"),
                tmp.resolve("store").resolve("tools"));
        assertThat(roots).contains(hex);
        CasSweep.sweep(storeCas, roots, false);
        assertThat(Files.isRegularFile(storeCas.pathFor(hex))).isTrue();
    }

    @Test
    void expired_markers_release_the_blob(@TempDir Path tmp) throws IOException {
        Cas storeCas = new Cas(tmp.resolve("store"));
        Cas cacheCas = new Cas(tmp.resolve("cache"));
        byte[] release = "old release bytes".getBytes();
        cacheCas.put(release);
        String hex = Hashing.sha256Hex(release);
        ActionPromote.promoteShas(cacheCas, storeCas, Set.of(hex));

        Path marker = tmp.resolve("store").resolve(ActionPromote.PROMOTED_DIR).resolve(hex);
        Files.setLastModifiedTime(
                marker, FileTime.fromMillis(System.currentTimeMillis() - CacheRoots.PROMOTED_MARKER_TTL_MILLIS - 1));

        assertThat(CacheRoots.pruneExpiredPromotedMarkers(storeCas)).isEqualTo(1);
        Set<String> roots = CacheRoots.collect(
                storeCas,
                tmp.resolve("store").resolve("actions"),
                tmp.resolve("store").resolve("tools"));
        assertThat(roots).doesNotContain(hex);
    }
}
