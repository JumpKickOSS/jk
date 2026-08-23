// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import cc.jumpkick.util.Hashing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Move Class-C action-cache blobs into the long-lived artifact store CAS so releases outlive
 * aggressive cache eviction. Prefer hard-link (same filesystem) then delete the cache path; fall
 * back to copy+delete across volumes.
 */
public final class ActionPromote {

    private ActionPromote() {}

    public record Report(int promoted, int alreadyInStore, long bytes) {}

    /**
     * Promote every CAS blob named by {@code shas} from {@code cacheCas} into {@code storeCas}.
     * Blobs missing from the cache are skipped (they may already live only in the store).
     */
    public static Report promoteShas(Cas cacheCas, Cas storeCas, Collection<String> shas) throws IOException {
        if (shas == null || shas.isEmpty()) return new Report(0, 0, 0L);
        int promoted = 0;
        int already = 0;
        long bytes = 0L;
        for (String sha : shas) {
            if (sha == null || sha.length() != 64) continue;
            Path cachePath = cacheCas.pathFor(sha);
            Path storePath = storeCas.pathFor(sha);
            boolean cacheHit = Files.isRegularFile(cachePath);
            boolean storeHit = Files.isRegularFile(storePath);
            if (!cacheHit && !storeHit) continue;
            if (storeHit && !cacheHit) {
                already++;
                continue;
            }
            if (storeHit && cacheHit) {
                // Already in the store — drop the cache copy so its bytes leave the cache budget.
                long sz = Files.size(cachePath);
                Files.deleteIfExists(cachePath);
                already++;
                bytes += sz;
                continue;
            }
            // cache hit, store miss
            long sz = Files.size(cachePath);
            Linking.linkOrCopy(cachePath, storePath);
            Files.deleteIfExists(cachePath);
            promoted++;
            bytes += sz;
        }
        return new Report(promoted, already, bytes);
    }

    /**
     * Hash each existing file and ensure it is in the store CAS; if a matching blob is only in
     * the cache CAS, promote it. Used when staging natives/jars that may not
     * still be indexed under a live action key.
     */
    public static Report promoteFiles(Cas cacheCas, Cas storeCas, Collection<Path> files) throws IOException {
        Set<String> shas = new LinkedHashSet<>();
        for (Path f : files) {
            if (f == null || !Files.isRegularFile(f)) continue;
            String hex = Hashing.sha256Hex(f);
            shas.add(hex);
            // Ensure store has the bytes even when never cached (put from file).
            if (!storeCas.contains(hex)) {
                storeCas.putFile(f, hex);
            }
        }
        return promoteShas(cacheCas, storeCas, shas);
    }
}
