// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * {@code jk cache clean} GC: mark reachable ({@link CacheRoots}), delete unreferenced blobs idle
 * longer than {@link #MAX_AGE}, drop matching {@code repos/} hard-links, compact the access log.
 *
 * <p>CAS and {@code repos/<name>/} share inodes via hard link. Purging must remove <strong>every
 * directory entry</strong> for a sha (repo view first, then {@code sha256/…}) or the bytes stay
 * allocated and GC fails its only job.
 */
public final class CacheGc {

    /** Unreferenced blobs idle longer than this are collectible. */
    public static final Duration MAX_AGE = Duration.ofDays(90);

    private CacheGc() {}

    public record Report(int purgedBlobs, long freedBytes, int repoLinksRemoved) {}

    /** Collect against the store this cache root is paired with. */
    public static Report run(Path cacheRoot, boolean dryRun) throws IOException {
        return run(cacheRoot, JkStores.storeRootFor(cacheRoot), dryRun);
    }

    /**
     * Collect blobs in {@code storeRoot} that nothing in {@code cacheRoot} still references.
     *
     * <p>Both roots are explicit because GC is maintenance over one specific pair, not over whatever the
     * ambient environment currently points at. Resolving the store internally left this method reading
     * reachability from the given cache while deleting from the global store — which, for a caller that
     * supplied its own directory, meant collecting somebody else's blobs.
     */
    public static Report run(Path cacheRoot, Path storeRoot, boolean dryRun) throws IOException {
        Cas cas = new Cas(storeRoot);
        Path shaRoot = storeRoot.resolve("sha256");
        Set<String> reachable =
                CacheRoots.collect(cas, cacheRoot.resolve("actions"), JkStores.resolve(cacheRoot, "tools"));

        Path logFile = cacheRoot.resolve(AccessLedger.FILE_NAME);
        AccessLedger ledger = new AccessLedger(logFile);
        Map<String, AccessLedger.Entry> access = ledger.entries();

        long now = System.currentTimeMillis();
        long maxAgeMillis = MAX_AGE.toMillis();

        Set<String> purged = new HashSet<>();
        ArrayList<Path> casPaths = new ArrayList<>();
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
                    purged.add(hex);
                    casPaths.add(file);
                    freed += size;
                }
            }
        }

        // Unlink repos/ first (hard links), then CAS — both required to free the inode.
        int repoLinks = cc.jumpkick.repo.RepoArtifactStore.removeShasFromAll(storeRoot, purged, dryRun);
        if (!dryRun) {
            for (Path file : casPaths) {
                Files.deleteIfExists(file);
            }
        }

        // Compact the access log: sum each sha's counts, dedupe to the latest
        // entry, and drop entries for anything we just purged.
        if (!dryRun && Files.exists(logFile)) {
            ledger.rewriteDropping(purged);
        }
        return new Report(purged.size(), freed, repoLinks);
    }
}
