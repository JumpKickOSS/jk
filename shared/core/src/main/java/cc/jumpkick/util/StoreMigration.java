// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Moves the fetched-artifact set from its pre-split home under {@code cache/} into {@code store/}
 * (see {@link JkDirs#storeDir()}).
 *
 * <p>A directory rename on one filesystem, so ~1.6 GB moves in milliseconds and nothing is copied.
 * Idempotent: an entry already present in the store is left alone, and an entry missing from both is
 * simply not there yet.
 *
 * <p>Deliberately paired with a read fallback rather than trusted on its own. If this is interrupted
 * halfway — or the two directories turn out to be on different filesystems, or the process cannot
 * write to {@code ~/.jk} — some entries stay behind, and a build that then silently re-downloaded
 * them would trip exactly the rate limit the split exists to avoid. Callers resolve through {@link
 * #resolveForRead} so a straggler is still found where it actually is.
 */
public final class StoreMigration {

    private StoreMigration() {}

    /**
     * What lives in the store: everything jk fetched from somewhere else. {@code sha256} and {@code
     * repos} move together because {@code repos/} entries are hard links into the CAS — separating
     * them across filesystems would turn every link into a copy.
     */
    static final List<String> STORE_ENTRIES =
            List.of("sha256", "repos", "metadata", "git", "git-artifacts", "jdks.json");

    /** Marker recording that the move already ran, so a warm start does no filesystem probing. */
    private static final String DONE_MARKER = ".migrated-from-cache";

    private static volatile boolean checkedThisProcess;

    /**
     * Move any pre-split entries into the store, once per process. Never throws: a failure here costs
     * re-downloads at worst, and {@link #resolveForRead} still finds whatever stayed behind.
     *
     * @return the number of entries moved
     */
    public static int migrateIfNeeded() {
        if (checkedThisProcess) return 0;
        checkedThisProcess = true;
        JkDirs dirs = JkDirs.current();
        return migrate(dirs.storeDir(), dirs.legacyStoreDir());
    }

    /** Testable core: {@code legacy} → {@code store}. */
    static int migrate(Path store, Path legacy) {
        try {
            if (Files.exists(store.resolve(DONE_MARKER))) return 0;
            if (store.equals(legacy) || !Files.isDirectory(legacy)) return 0;

            int moved = 0;
            for (String entry : STORE_ENTRIES) {
                Path from = legacy.resolve(entry);
                Path to = store.resolve(entry);
                if (!Files.exists(from) || Files.exists(to)) continue;
                try {
                    Files.createDirectories(store);
                    try {
                        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException e) {
                        // Different filesystems: a plain move falls back to copy+delete, which is slow
                        // but still beats re-downloading.
                        Files.move(from, to);
                    }
                    moved++;
                } catch (IOException e) {
                    // Leave it where it is; resolveForRead will still find it.
                }
            }
            if (moved > 0 || Files.isDirectory(store)) {
                Files.createDirectories(store);
                Files.writeString(
                        store.resolve(DONE_MARKER),
                        "jk moved its fetched artifacts here from cache/ (see JkDirs#storeDir).\n"
                                + "Delete this file to let jk look in the old location again.\n");
            }
            return moved;
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    /**
     * Where to read {@code entry} from: the store when it is there, otherwise the pre-split location
     * when that still holds it, otherwise the store path (so a caller creating it writes to the new
     * layout).
     */
    public static Path resolveForRead(String entry) {
        JkDirs dirs = JkDirs.current();
        return resolveForRead(entry, dirs.storeDir(), dirs.legacyStoreDir());
    }

    /** Testable core of {@link #resolveForRead(String)}. */
    static Path resolveForRead(String entry, Path store, Path legacy) {
        Path preferred = store.resolve(entry);
        if (Files.exists(preferred)) return preferred;
        Path fallback = legacy.resolve(entry);
        if (!store.equals(legacy) && Files.exists(fallback)) return fallback;
        return preferred;
    }

    /** Test seam: forget that this process already checked. */
    static void resetForTests() {
        checkedThisProcess = false;
    }
}
