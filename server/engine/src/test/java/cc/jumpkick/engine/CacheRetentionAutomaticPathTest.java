// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkHistoryConfig;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Retention has to be reached by the engine on its own, from the boundary it actually crosses.
 *
 * <p>Every bound this tree has lost was lost this way and not by being wrong: {@code
 * AccessLedger.compactIfLarge()} was correct and had no callers, and {@code RunLogGc}'s TTL was
 * correct and sat behind a verb a human types. Both had tests. A test of the pass proves the pass;
 * only a test that starts at {@link IdleHousekeeping} proves the engine runs it, which is why this
 * one asserts through {@code run()} rather than through {@code CacheRetention.sweep}.
 */
class CacheRetentionAutomaticPathTest {

    private static final long OLD = Duration.ofDays(400).toMillis();

    @Test
    void the_idle_boundary_reclaims_a_tier_over_its_cap_and_a_directory_nothing_writes(
            @TempDir Path home, @TempDir Path cache) throws Exception {
        // Test JVMs run with JK_AUTO_PRUNE=false so no suite can prune a developer's real cache;
        // the gate is not this test's subject, and JK_HOME keeps every path it reads inside tmp.
        Path overBudget = overStoreBudget(cache, 32L * 1024 * 1024);
        Path abandoned = abandonedTier(cache.resolve("runs"), "2026-05-01");
        Path stamp = file(cache.resolve("format/stamps/ab/cd/Src.java.stamp"), "");
        List<String> log = new CopyOnWriteArrayList<>();

        System.setProperty("jk.env.JK_HOME", home.toAbsolutePath().toString());
        System.setProperty("jk.env.JK_AUTO_PRUNE", "true");
        try {
            IdleHousekeeping housekeeping = idle(cache, log);
            housekeeping.maybeEnqueuePrune(cache);

            housekeeping.run();
        } finally {
            System.clearProperty("jk.env.JK_HOME");
            System.clearProperty("jk.env.JK_AUTO_PRUNE");
        }

        assertThat(overBudget).as("a cap is reachable from the boundary").doesNotExist();
        assertThat(abandoned)
                .as("a tier no constant names is reclaimed, not merely unbounded")
                .doesNotExist();
        assertThat(stamp).as("a window is reachable too").doesNotExist();
        assertThat(cache.resolve(".last-pruned"))
                .as("the pass ran to completion and recorded its cadence")
                .exists();
        assertThat(log).anyMatch(line -> line.contains("idle-boundary cache prune removed"));
    }

    /**
     * Draining, so the warmup that would otherwise follow the prune stays out of the way; the
     * prune itself is the first thing {@code run()} does and is unaffected.
     */
    private static IdleHousekeeping idle(Path cache, List<String> log) {
        return new IdleHousekeeping(
                new AtomicInteger(0),
                new ReentrantReadWriteLock(),
                new JkHistoryConfig(false, 30, 512),
                null,
                () -> cache.resolve("metrics.jsonl"),
                System::currentTimeMillis,
                log::add,
                () -> false,
                () -> true,
                () -> {});
    }

    /**
     * The {@code hash-memo} store past its byte budget, so the whole tier resets. Sparse: the
     * instrument sums apparent bytes, the same figure it uses in production.
     */
    private static Path overStoreBudget(Path cache, long budgetBytes) throws IOException {
        Path store = cache.resolve("hash-memo/memo.v1");
        Files.createDirectories(store.getParent());
        try (var raf = new RandomAccessFile(store.toFile(), "rw")) {
            raf.setLength(budgetBytes + 1);
        }
        Files.setLastModifiedTime(store, FileTime.fromMillis(System.currentTimeMillis() - OLD));
        return store;
    }

    /**
     * A tier the table no longer names, aged as one abandoned by an older jk would be — the top
     * entry included, since that is the mtime the residue sweep reads.
     */
    private static Path abandonedTier(Path tier, String child) throws IOException {
        Path dir = tier.resolve(child);
        Files.createDirectories(dir);
        file(dir.resolve("events.jsonl"), "{}");
        backdate(dir);
        backdate(tier);
        return dir;
    }

    private static Path file(Path path, String body) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, body, StandardCharsets.UTF_8);
        backdate(path);
        return path;
    }

    private static void backdate(Path path) throws IOException {
        Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis() - OLD));
    }
}
