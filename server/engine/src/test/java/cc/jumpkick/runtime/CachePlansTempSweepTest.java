// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.BuildPlan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Leftover {@code .put-} temps are interrupted downloads. Since the JK-1531 tier split a plain
 * {@code jk cache prune} reclaims the <strong>cache</strong> CAS only — the store's temps belong to
 * {@code jk repo prune} — and the {@code cacheFiles} summary must equal what it actually swept.
 */
class CachePlansTempSweepTest {

    @Test
    void sweeps_and_counts_temps_under_a_cas_tree(@TempDir Path root) throws IOException {
        Path shaDir = root.resolve("sha256");
        Path temp = seed(shaDir.resolve("ab/.put-1234"), "partial");
        Path blob = seed(shaDir.resolve("ab/cd/deadbeef"), "real");

        CachePlans.TempSweep swept = CachePlans.sweepCasTemps(shaDir, false);

        assertThat(swept.files()).isEqualTo(1);
        assertThat(swept.bytes()).isEqualTo("partial".length());
        assertThat(temp).doesNotExist();
        assertThat(blob).exists();
    }

    @Test
    void dry_run_counts_without_deleting(@TempDir Path root) throws IOException {
        Path shaDir = root.resolve("sha256");
        Path temp = seed(shaDir.resolve("ab/.put-1234"), "partial");

        CachePlans.TempSweep swept = CachePlans.sweepCasTemps(shaDir, true);

        assertThat(swept.files()).isEqualTo(1);
        assertThat(temp).exists();
    }

    @Test
    void a_missing_cas_tree_is_not_an_error(@TempDir Path root) throws IOException {
        CachePlans.TempSweep swept = CachePlans.sweepCasTemps(root.resolve("nope"), false);

        assertThat(swept.files()).isZero();
        assertThat(swept.bytes()).isZero();
    }

    @Test
    void prune_reports_every_file_it_removed(@TempDir Path root) throws Exception {
        // Cache tier: a stale action key past the threshold, plus a cache CAS temp.
        Path staleKey = seed(root.resolve("actions/keys/stale"), "INPUT deadbeef /x");
        Files.setLastModifiedTime(
                staleKey,
                FileTime.fromMillis(
                        System.currentTimeMillis() - Duration.ofDays(90).toMillis()));
        Path cacheTemp = seed(root.resolve("sha256/ab/.put-1234"), "partial");

        BuildPlan plan = CachePlans.pruneBuildPlan(root, 30, false, false, false);
        plan.run();

        assertThat(cacheTemp).doesNotExist();
        assertThat(staleKey).doesNotExist();
        // The summary is the sweep, not a subset of it: both files are counted.
        assertThat(plan.get(CachePlans.FILES).orElse(-1L)).isGreaterThanOrEqualTo(2);
    }

    /** The tier split is the contract: a plain prune must not reach into the artifact store. */
    @Test
    void plain_prune_leaves_store_temps_to_repo_prune(@TempDir Path root) throws Exception {
        Path storeTemp = cc.jumpkick.cache.JkStores.resolve(root, "sha256").resolve("ab/.put-jk1531");
        seed(storeTemp, "partial");
        try {
            CachePlans.pruneBuildPlan(root, 30, false, false, false).run();

            assertThat(storeTemp).exists();
        } finally {
            Files.deleteIfExists(storeTemp); // the store is machine-shared; do not leave litter
        }
    }

    private static Path seed(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }
}
