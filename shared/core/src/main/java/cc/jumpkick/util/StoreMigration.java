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
    static final List<String> STORE_ENTRIES = List.of(
            "sha256",
            "repos",
            "metadata",
            "git",
            "git-artifacts",
            "jdks.json",
            "tools",
            // Library short-name registry + ETag sidecar (were under cache/ before the store split).
            "libs.global.toml",
            ".libs.global.toml.etag");

    /** Marker recording that the move already ran, so a warm start does no filesystem probing. */
    private static final String DONE_MARKER = ".migrated-from-cache";

    /** Marker line naming the entries a completed migration covered (see {@link #coveredBy}). */
    private static final String ENTRIES_LINE = "entries: ";

    /**
     * What a marker without an {@code entries:} line covered: the original split set. Entries added
     * to {@link #STORE_ENTRIES} later (the library registry pair) must still be probed on installs
     * that migrated before those entries existed — a bare marker must not vouch for them.
     */
    private static final List<String> LEGACY_MARKER_ENTRIES =
            List.of("sha256", "repos", "metadata", "git", "git-artifacts", "jdks.json", "tools");

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
            // The marker only vouches for the entries it names — a set that grows in a later
            // release must still be probed on installs whose migration predates the growth,
            // or the new entries stay in prunable cache/ forever.
            List<String> pending = STORE_ENTRIES.stream()
                    .filter(e -> !coveredBy(store.resolve(DONE_MARKER)).contains(e))
                    .toList();
            if (pending.isEmpty()) return 0;
            if (store.equals(legacy) || !Files.isDirectory(legacy)) return 0;

            int moved = 0;
            boolean leftovers = false;
            for (String entry : pending) {
                Path from = legacy.resolve(entry);
                Path to = store.resolve(entry);
                if (!Files.exists(from)) continue;
                try {
                    Files.createDirectories(store);
                    moveMerging(from, to);
                    moved++;
                } catch (IOException e) {
                    leftovers = true; // resolveForRead still finds it where it is
                }
                if (Files.exists(from)) leftovers = true;
            }
            // Only claim completion when nothing was left behind. Writing the marker after a partial
            // move would strand whatever failed: the next run would skip straight past it, and the CAS
            // in particular would then be split across two roots with blob lookups missing.
            if (!leftovers) {
                Files.createDirectories(store);
                Files.writeString(
                        store.resolve(DONE_MARKER),
                        "jk moved its fetched artifacts here from cache/ (see JkDirs#storeDir).\n"
                                + "Delete this file to let jk look in the old location again.\n"
                                + ENTRIES_LINE + String.join(",", STORE_ENTRIES) + "\n");
            }
            return moved;
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    /**
     * The entries a completed migration marker vouches for: the {@code entries:} line when present,
     * the original pre-growth set for a bare legacy marker, nothing when there is no marker.
     */
    private static List<String> coveredBy(Path marker) {
        if (!Files.isRegularFile(marker)) return List.of();
        try {
            for (String line : Files.readAllLines(marker)) {
                if (line.startsWith(ENTRIES_LINE)) {
                    return List.of(line.substring(ENTRIES_LINE.length()).split(","));
                }
            }
            return LEGACY_MARKER_ENTRIES;
        } catch (IOException e) {
            return LEGACY_MARKER_ENTRIES; // unreadable marker — assume the original set only
        }
    }

    /**
     * Move {@code from} onto {@code to}, merging rather than failing when the destination already
     * exists.
     *
     * <p>The plain rename is the fast path and handles ~1.6 GB in milliseconds. But the destination is
     * routinely already there: the client process touches the store before the engine gets a chance to
     * migrate, so {@code store/sha256/} exists with a handful of fresh blobs in it while the bulk of the
     * CAS is still under {@code cache/}. Refusing to move in that case splits the CAS across two roots,
     * and every blob lookup for the older half then misses — which fails builds outright rather than
     * merely costing a re-download.
     *
     * <p>So when the destination exists, recurse a level and move the children that are not there yet.
     * For the CAS that means whole {@code ab/} shards move as single renames, keeping it cheap.
     */
    private static void moveMerging(Path from, Path to) throws IOException {
        if (!Files.exists(to)) {
            try {
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (AtomicMoveNotSupportedException e) {
                // Different filesystems: a plain move degrades to copy+delete. Slow, still beats
                // re-downloading.
                Files.move(from, to);
                return;
            }
        }
        if (!Files.isDirectory(from) || !Files.isDirectory(to)) {
            // The destination wins — never overwrite. But the source has to go, or it counts as a
            // leftover forever: the marker would never be written and every run would re-attempt a move
            // that cannot succeed. Discarding it is safe because both sides are re-fetchable caches, and
            // for the CAS they are identical by construction — the path *is* the content hash.
            if (Files.isRegularFile(from) && Files.isRegularFile(to)) {
                Files.deleteIfExists(from);
            }
            return;
        }
        try (var children = Files.list(from)) {
            for (Path child : children.toList()) {
                moveMerging(child, to.resolve(child.getFileName().toString()));
            }
        }
        try (var remaining = Files.list(from)) {
            if (remaining.findAny().isEmpty()) Files.deleteIfExists(from);
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
