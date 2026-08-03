// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk cache purge} boundary (JK-1435): the purge is scoped to the action cache
 * ({@code actions/} + {@code format-stamps/}) and must keep store-side trees that share the root in
 * an explicit {@code --cache-dir} layout ({@code sha256/}, {@code repos/}, {@code runs/}), matching
 * what the confirm prompt claims.
 */
class CachePipelinesPurgeTest {

    @Test
    void purge_deletes_only_action_cache_trees(@TempDir Path root) throws IOException {
        Path actionKey = seed(root.resolve("actions/keys/task1"));
        Path actionTask = seed(root.resolve("actions/tasks/compile-main@abc"));
        Path stamp = seed(root.resolve("format-stamps/ab/stamp1"));
        // Store-side trees under the same root — the explicit --cache-dir layout.
        Path casBlob = seed(root.resolve("sha256/ab/cd/deadbeef"));
        Path repoJar = seed(root.resolve("repos/central/com/example/lib/1.0/lib-1.0.jar"));
        Path runLog = seed(root.resolve("runs/build-1.jsonl"));

        CachePipelines.purgeActionCache(root);

        assertThat(actionKey).doesNotExist();
        assertThat(actionTask).doesNotExist();
        assertThat(stamp).doesNotExist();
        assertThat(casBlob).exists();
        assertThat(repoJar).exists();
        assertThat(runLog).exists();
        // The root itself survives (empty action-cache dirs may remain).
        assertThat(root).exists();
    }

    @Test
    void purge_with_no_action_cache_is_a_noop(@TempDir Path root) throws IOException {
        Path casBlob = seed(root.resolve("sha256/ab/cd/deadbeef"));

        CachePipelines.purgeActionCache(root);

        assertThat(casBlob).exists();
    }

    @Test
    void purge_tolerates_a_missing_root() {
        Path root = Path.of("/nonexistent/jk-test-cache-root");
        org.assertj.core.api.Assertions.assertThatCode(() -> CachePipelines.purgeActionCache(root))
                .doesNotThrowAnyException();
    }

    private static Path seed(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[64]);
        return file;
    }
}
