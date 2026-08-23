// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The two tiers that are cleared rather than ranked. Neither has a clock worth reading — a Kotlin
 * ABI snapshot is written once and never rewritten on reuse, and a {@code jk jshell} alias is a
 * hard link — so the instrument either does nothing at all or takes the tier, and the cost of
 * taking it is one rebuild of something derived.
 */
class CacheRetentionResetTest {

    private static final long MIB = 1024L * 1024L;

    @Test
    void under_its_budget_the_snapshot_tier_is_untouched_and_unranked(@TempDir Path root) throws IOException {
        Path snapshot = sparse(root, "kotlin-cp-snapshots/abi-0", 127 * MIB);
        CountingProbe probe = new CountingProbe();

        CacheRetention.sweep(root, new Cas(root), Set.of(), false, probe, Map.of());

        assertThat(snapshot).exists();
        assertThat(probe.clockQuestions())
                .as("age cannot inform a decision this tier does not make")
                .isZero();
    }

    @Test
    void over_its_budget_the_snapshot_tier_goes_whole(@TempDir Path root) throws IOException {
        Path snapshot = sparse(root, "kotlin-cp-snapshots/abi-0", 129 * MIB);

        CacheRetention.sweep(root, new Cas(root), Set.of(), false);

        assertThat(snapshot).doesNotExist();
        assertThat(root.resolve("kotlin-cp-snapshots")).doesNotExist();
    }

    /** Aliases are recreated on demand, so there is no budget to be under. */
    @Test
    void the_jshell_alias_tier_goes_every_pass(@TempDir Path root) throws IOException {
        Path alias = sparse(root, "jshell-cp/lib.jar", 1024);

        CacheRetention.sweep(root, new Cas(root), Set.of(), false);

        assertThat(alias).doesNotExist();
        assertThat(root.resolve("jshell-cp")).doesNotExist();
    }

    /**
     * Sparse: the instrument sums apparent bytes, the same figure it uses in production, so a tier
     * of the size that matters costs no disk here.
     */
    private static Path sparse(Path root, String rel, long bytes) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        try (var raf = new RandomAccessFile(p.toFile(), "rw")) {
            raf.setLength(bytes);
        }
        return p;
    }
}
