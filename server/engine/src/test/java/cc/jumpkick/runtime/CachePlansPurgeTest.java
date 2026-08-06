// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk cache purge} wipes the cache tier ({@code actions/}, {@code format-stamps/}, cache
 * {@code sha256/}) and must keep collocated store-ish trees ({@code repos/}, {@code runs/}).
 */
class CachePlansPurgeTest {

    @Test
    void purge_deletes_cache_tier_trees(@TempDir Path root) throws IOException {
        Path actionKey = seed(root.resolve("actions/keys/task1"));
        Path actionTask = seed(root.resolve("actions/tasks/compile-main@abc"));
        Path stamp = seed(root.resolve("format-stamps/ab/stamp1"));
        Path cacheBlob = seed(root.resolve("sha256/ab/cd/deadbeef"));
        Path repoJar = seed(root.resolve("repos/central/com/example/lib/1.0/lib-1.0.jar"));
        Path runLog = seed(root.resolve("runs/build-1.jsonl"));

        CachePlans.purgeActionCache(root);

        assertThat(actionKey).doesNotExist();
        assertThat(actionTask).doesNotExist();
        assertThat(stamp).doesNotExist();
        assertThat(cacheBlob).doesNotExist();
        assertThat(repoJar).exists();
        assertThat(runLog).exists();
        assertThat(root).exists();
    }

    @Test
    void purge_with_only_cache_cas_clears_blobs(@TempDir Path root) throws IOException {
        Path casBlob = seed(root.resolve("sha256/ab/cd/deadbeef"));

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
