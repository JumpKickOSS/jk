// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.CacheTree;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The engine half of {@code jk cache nuke}: empty the cache root, whether or not {@link CacheTree}
 * names the entry, and leave standing only what the caller still holds open — the root itself and
 * the {@code .prune.lock} the maintenance pass is running under. Removing the root is the client's
 * last step ({@code CacheCommand.removeCacheRoot}), once nothing has a handle in it.
 */
class CachePlansPurgeTest {

    @Test
    void purge_deletes_every_entry_under_the_cache_root(@TempDir Path root) throws IOException {
        Path actionKey = seed(CacheTree.ACTIONS.under(root).resolve("keys/task1"));
        Path actionTask = seed(CacheTree.ACTIONS.under(root).resolve("tasks/compile-main@abc"));
        Path stamp = seed(CacheTree.FORMAT_STAMPS.under(root).resolve("ab/stamp1"));
        Path cacheBlob = seed(CacheTree.CACHE_CAS.under(root).resolve("ab/cd/deadbeef"));
        Path memo = seed(CacheTree.HASH_MEMO.under(root).resolve("aa/memo1"));
        // Entries the table does NOT name. The retention sweep already reclaims these on a plain
        // `jk cache clean`, so a nuke that spared them would be the weaker of the two commands.
        Path leftoverRepoJar = seed(root.resolve("repos/central/com/example/lib/1.0/lib-1.0.jar"));
        Path leftoverRunLog = seed(root.resolve("runs/build-1.jsonl"));
        Path cadence = seed(CacheTree.LAST_PRUNED.under(root));

        CachePlans.purgeActionCache(root);

        assertThat(actionKey).doesNotExist();
        assertThat(actionTask).doesNotExist();
        assertThat(stamp).doesNotExist();
        assertThat(cacheBlob).doesNotExist();
        assertThat(memo).doesNotExist();
        assertThat(leftoverRepoJar).doesNotExist();
        assertThat(leftoverRunLog).doesNotExist();
        assertThat(cadence).doesNotExist();
        assertThat(root.resolve("repos")).doesNotExist();
        assertThat(root.resolve("runs")).doesNotExist();
    }

    /**
     * {@code CacheMaintenanceLocks} holds this file open for the length of the pass that calls us.
     * Unlinking it would let a second process mint a fresh lock inode and prune concurrently, and
     * on Windows the open handle refuses the delete outright.
     */
    @Test
    void purge_spares_the_prune_lock_the_caller_holds(@TempDir Path root) throws IOException {
        Path lock = seed(CacheTree.PRUNE_LOCK.under(root));
        seed(CacheTree.ACTIONS.under(root).resolve("keys/task1"));

        CachePlans.purgeActionCache(root);

        assertThat(lock).exists();
        assertThat(CacheTree.ACTIONS.under(root)).doesNotExist();
    }

    @Test
    void purge_with_only_cache_cas_clears_blobs(@TempDir Path root) throws IOException {
        Path casBlob = seed(CacheTree.CACHE_CAS.under(root).resolve("ab/cd/deadbeef"));

        CachePlans.purgeActionCache(root);

        assertThat(casBlob).doesNotExist();
    }

    @Test
    void purge_tolerates_a_missing_root() {
        Path root = Path.of("/nonexistent/jk-test-cache-root");
        org.assertj.core.api.Assertions.assertThatCode(() -> CachePlans.purgeActionCache(root))
                .doesNotThrowAnyException();
    }

    private static Path seed(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[64]);
        return file;
    }
}
