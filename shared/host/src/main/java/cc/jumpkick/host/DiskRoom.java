// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The free space jk leaves on a volume: a floor it does not write past where it can avoid it. A
 * build refuses to start below it, and a slow client's stream stops spilling to disk at it.
 */
public final class DiskRoom {

    private static final long GIB = 1L << 30;

    private DiskRoom() {}

    /**
     * The floor for a volume of {@code totalBytes}: at least 1 GiB, at least 2% of the volume, and
     * at most 2 GiB.
     */
    public static long floorBytes(long totalBytes) {
        if (totalBytes <= 0) return GIB;
        long twoPercent = Math.max(0, totalBytes / 50);
        return Math.min(2 * GIB, Math.max(GIB, twoPercent));
    }

    /**
     * Whether the volume holding {@code path} (or its nearest existing ancestor) stays at or above
     * its floor after {@code bytes} more; true when the volume cannot be read.
     */
    public static boolean fits(Path path, long bytes) {
        Path cur = path;
        while (cur != null && !Files.exists(cur)) cur = cur.getParent();
        if (cur == null) return true;
        try {
            FileStore store = Files.getFileStore(cur);
            return store.getUsableSpace() - Math.max(0, bytes) >= floorBytes(store.getTotalSpace());
        } catch (IOException | RuntimeException e) {
            return true;
        }
    }
}
