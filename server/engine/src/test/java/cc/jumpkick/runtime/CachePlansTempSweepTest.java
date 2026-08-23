// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.util.Hashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Leftover {@code .put-} temps are interrupted downloads. Since the tier split a plain
 * {@code jk cache clean} reclaims the <strong>cache</strong> CAS only — the store's temps belong to
 * {@code jk storage clean} — and the {@code cacheFiles} summary must equal what it actually swept.
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
    void prune_reports_every_temp_it_removed(@TempDir Path root) throws Exception {
        Path cacheTemp = seed(root.resolve("sha256/ab/.put-1234"), "partial");

        BuildPlan plan = CachePlans.pruneBuildPlan(root, false, false);
        plan.run();

        assertThat(cacheTemp).doesNotExist();
        assertThat(plan.get(CachePlans.FILES).orElse(-1L)).isEqualTo(1);
    }

    /**
     * Age alone no longer condemns an action key — only the size budget does, and a {@code @TempDir}
     * cache is nowhere near the machine's.
     */
    @Test
    void prune_leaves_old_action_keys_alone_when_under_budget(@TempDir Path root) throws Exception {
        byte[] payload = "cached class bytes".getBytes(StandardCharsets.UTF_8);
        Path blob = new Cas(root).put(payload);
        Path key = seed(
                root.resolve("actions/keys/action-key"),
                "TASK compile-main@mod\nOUTPUT " + Hashing.sha256Hex(payload) + " classes/A.class\n");
        long ninetyDaysAgo = System.currentTimeMillis() - Duration.ofDays(90).toMillis();
        Files.setLastModifiedTime(key, FileTime.fromMillis(ninetyDaysAgo));
        Files.setLastModifiedTime(blob, FileTime.fromMillis(ninetyDaysAgo));

        CachePlans.pruneBuildPlan(root, false, false).run();

        assertThat(key).exists();
        assertThat(blob).exists();
    }

    /** The tier split is the contract: a plain prune must not reach into the artifact store. */
    @Test
    void plain_prune_leaves_store_temps_to_repo_prune(@TempDir Path root) throws Exception {
        Path storeTemp = cc.jumpkick.cache.JkStores.resolve(root, "sha256").resolve("ab/.put-jk1531");
        seed(storeTemp, "partial");
        try {
            CachePlans.pruneBuildPlan(root, false, false).run();

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
