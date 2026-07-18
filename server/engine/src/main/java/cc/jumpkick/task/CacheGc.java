// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.cache.Cas;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * {@code jk clean --cache} GC: mark reachable ({@link CacheRoots}), delete unreferenced blobs idle
 * longer than {@link #MAX_AGE} (and matching {@code repo/} links), compact the access log.
 */
public final class CacheGc {

    /** Unreferenced blobs idle longer than this are collectible. */
    public static final Duration MAX_AGE = Duration.ofDays(90);

    private CacheGc() {}

    public record Report(int purgedBlobs, long freedBytes, int repoLinksRemoved) {}

    public static Report run(Path cacheRoot, boolean dryRun) throws IOException {
        Cas cas = new Cas(cacheRoot);
        Path shaRoot = cacheRoot.resolve("sha256");
        Set<String> reachable = CacheRoots.collect(cas, cacheRoot.resolve("actions"), cacheRoot.resolve("tools"));

        Path logFile = cacheRoot.resolve(AccessLedger.FILE_NAME);
        AccessLedger ledger = new AccessLedger(logFile);
        Map<String, AccessLedger.Entry> access = ledger.entries();

        long now = System.currentTimeMillis();
        long maxAgeMillis = MAX_AGE.toMillis();

        Set<String> purged = new HashSet<>();
        long freed = 0;
        if (Files.isDirectory(shaRoot)) {
            try (Stream<Path> stream = Files.walk(shaRoot)) {
                for (Path file : (Iterable<Path>) stream::iterator) {
                    if (!Files.isRegularFile(file)) continue;
                    if (file.getFileName().toString().startsWith(".put-")) continue;
                    var hexOpt = cas.hashFromPath(file);
                    if (hexOpt.isEmpty()) continue;
                    String hex = hexOpt.get();
                    if (reachable.contains(hex)) continue; // marked — always kept

                    AccessLedger.Entry e = access.get(hex);
                    long last = e != null
                            ? e.latestMillis()
                            : Files.getLastModifiedTime(file).toMillis();
                    if (now - last < maxAgeMillis) continue; // still warm

                    long size = Files.size(file);
                    if (!dryRun) Files.deleteIfExists(file);
                    purged.add(hex);
                    freed += size;
                }
            }
        }

        int repoLinks = cc.jumpkick.repo.RepoArtifactStore.removeShasFromAll(cacheRoot, purged, dryRun);

        // Compact the access log: sum each sha's counts, dedupe to the latest
        // entry, and drop entries for anything we just purged.
        if (!dryRun && Files.exists(logFile)) {
            ledger.rewriteDropping(purged);
        }
        return new Report(purged.size(), freed, repoLinks);
    }
}
