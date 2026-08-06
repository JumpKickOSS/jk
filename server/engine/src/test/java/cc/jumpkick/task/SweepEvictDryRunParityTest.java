// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JK-1526: a prune pass runs {@link CasSweep} then {@link LruEvictor} over the same pool. In a
 * real run the sweep's victims are gone before the evictor walks; in a dry run they are still on
 * disk, so without the excluded-shas hand-off the same blob was counted by both and dry-run
 * FILES/BYTES over-reported.
 */
class SweepEvictDryRunParityTest {

    @Test
    void dry_run_totals_match_a_real_run(@TempDir Path tempDir) throws IOException {
        long[] dry = runPass(tempDir.resolve("dry"), true);
        long[] real = runPass(tempDir.resolve("real"), false);
        assertThat(dry[0]).as("files").isEqualTo(real[0]);
        assertThat(dry[1]).as("bytes").isEqualTo(real[1]);
    }

    @Test
    void evictor_skips_shas_the_sweep_already_claimed(@TempDir Path tempDir) throws IOException {
        Cas cas = new Cas(tempDir);
        Path dead = cas.put(new byte[600]); // unreachable — sweep victim
        Path live1 = cas.put("live-one".getBytes());
        Path live2 = cas.put("live-two-x".getBytes());
        for (Path p : new Path[] {dead, live1, live2}) backdate(p);
        String deadHex = cas.hashFromPath(dead).orElseThrow();
        Set<String> reachable =
                Set.of(cas.hashFromPath(live1).orElseThrow(), cas.hashFromPath(live2).orElseThrow());

        var sweep = CasSweep.sweep(cas, reachable, true);
        assertThat(sweep.deletedShas()).containsExactly(deadHex);

        // Tight budget: without the exclusion the dead blob's 600 bytes would sit in the
        // evictor's total and be double-counted as its cheapest victim.
        AccessLedger ledger = new AccessLedger(tempDir.resolve(".access.log"));
        var evict = LruEvictor.evictDownTo(cas, 10_000, reachable, ledger, true, sweep.deletedShas());
        assertThat(evict.deleted()).isZero(); // live blobs are tiny — post-sweep pool fits
    }

    /** One sweep+evict pass; returns {files, bytes} totals the way prune sums them. */
    private static long[] runPass(Path root, boolean dryRun) throws IOException {
        Files.createDirectories(root);
        Cas cas = new Cas(root);
        Path dead = cas.put(new byte[600]);
        Path oldLive = cas.put(new byte[500]);
        Path newLive = cas.put(new byte[400]);
        backdate(dead);
        backdate(oldLive);
        backdate(newLive);
        String oldHex = cas.hashFromPath(oldLive).orElseThrow();
        String newHex = cas.hashFromPath(newLive).orElseThrow();
        Set<String> reachable = Set.of(oldHex, newHex);

        long files = 0;
        long bytes = 0;
        var sweep = CasSweep.sweep(cas, reachable, dryRun);
        files += sweep.deleted();
        bytes += sweep.freedBytes();
        // Budget forces one reachable eviction even after the sweep (900 live bytes vs 500 budget).
        AccessLedger ledger = new AccessLedger(root.resolve(".access.log"));
        ledger.touch(newHex); // newer atime — evictor takes oldLive first, deterministically
        var evict = LruEvictor.evictDownTo(cas, 500, reachable, ledger, dryRun, sweep.deletedShas());
        files += evict.deleted();
        bytes += evict.freedBytes();
        return new long[] {files, bytes};
    }

    private static void backdate(Path p) throws IOException {
        Files.setLastModifiedTime(p, FileTime.from(Instant.now().minus(60, ChronoUnit.DAYS)));
    }
}
