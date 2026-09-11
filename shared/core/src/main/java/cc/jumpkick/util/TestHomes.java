// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.time.Clock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The {@code JK_HOME} a forked test JVM runs against: jk's whole layout, throwaway, and
 * <b>outside the project under test</b>.
 *
 * <p><b>Why not under the project.</b> This home carries jk's whole layout, {@code
 * store/templates/<key>/} among the rest. A {@code git} command handed a path inside a source tree
 * that has stopped existing resolves to the repository enclosing it, so a cache refresh there is a
 * {@code fetch --depth 1} and {@code reset --hard} against the developer's checkout. Pinning {@code
 * --git-dir} is what stops the command; keeping the home out of the tree is what leaves such a
 * command nothing to aim at. A sibling of the project does not satisfy it either: a workspace
 * member's enclosing repository is the workspace root.
 *
 * <p><b>Why under the product home.</b> {@code <home>/test-homes} is short on every platform (macOS
 * {@code TMPDIR} is deep and resolves through {@code /var} → {@code /private/var}; Windows stops at
 * 260 characters), survives a reboot, and moves with {@code JK_HOME}: an engine already running in a
 * sandbox keeps its own sandboxes inside it rather than writing into the developer's real home. It
 * is <b>warm on purpose</b> — it holds a fetched store, and a per-run home would re-download every
 * dependency on every {@code jk test}.
 *
 * <p><b>Cleanup.</b> Warm is not unbounded. {@link #prepare} stamps the slot it hands out, and the
 * first call in a JVM reaps every slot not stamped within {@link #KEEP_DAYS} days, then the least
 * recently stamped slots until the root fits {@link #KEEP_BYTES} — before the run, so a project that
 * was deleted or renamed cannot leave one behind for ever. {@code jk clean} deletes this module's
 * slot outright. Nothing is deleted after a run: that is the warmth.
 *
 * <p><b>A slot, not just a home.</b> {@code JK_HOME} is {@code <slot>/home} rather than the slot
 * itself because the launcher derives the shared test cache as a <em>sibling</em> of {@code JK_HOME},
 * so wiping the home does not wipe the cache. Flattening this would turn a per-module cache into one
 * shared by every module on the machine.
 */
public final class TestHomes {

    /** Under the product home ({@link JkDirs#home()}), so {@code JK_HOME} relocates the sandboxes too. */
    public static final String DIR = "test-homes";

    /** A slot untouched for this long belongs to a project that has moved on. */
    static final int KEEP_DAYS = 14;

    /** Bytes the root may hold before the least recently used slots go. */
    static final long KEEP_BYTES = 2L << 30;

    private static final String STAMP = ".used-at";
    private static final AtomicBoolean REAPED = new AtomicBoolean();

    private TestHomes() {}

    /** {@code <home>/test-homes}: under {@code JK_HOME} when set, else {@code ~/.jk}. */
    public static Path root() {
        return JkDirs.home().resolve(DIR);
    }

    /**
     * This module's slot: {@code <root>/<key>}, holding the home and its sibling caches.
     *
     * <p>Keyed by a digest of the module's real path and nothing else: readable names spend the
     * Windows path budget, and two checkouts of one repository have the same module names but must
     * not share a store.
     */
    public static Path slotFor(Path moduleDir) {
        return root().resolve(keyFor(moduleDir));
    }

    /**
     * The {@code JK_HOME} for this module. Pure — no directory is created and nothing is stamped, because
     * the caller that asks what the environment <em>is</em> runs long before a worker needs it to exist.
     * {@link #prepare} is the one that touches the disk.
     */
    public static Path pathFor(Path moduleDir) {
        return slotFor(moduleDir).resolve("home");
    }

    /** {@link #pathFor} with the directory created and the slot stamped, stale slots reaped once per JVM. */
    public static Path prepare(Path moduleDir) throws IOException {
        return prepare(moduleDir, Clock.SYSTEM);
    }

    /**
     * {@link #prepare} against a supplied clock. The reap window is the only time this class reads,
     * and it decides whether a slot is deleted — so a test settles it rather than arranging file
     * timestamps around whatever now happens to be.
     */
    public static Path prepare(Path moduleDir, Clock clock) throws IOException {
        if (REAPED.compareAndSet(false, true)) {
            reapStale(root(), clock.millis(), KEEP_BYTES);
        }
        Path home = pathFor(moduleDir);
        Files.createDirectories(home);
        stamp(slotFor(moduleDir));
        return home;
    }

    /**
     * The 12-hex key {@link #slotFor} uses; exposed so {@code jk clean} can find the same one. Of the
     * real path when the module exists, so a module reached through a link keys as the directory itself.
     */
    public static String keyFor(Path moduleDir) {
        Path abs = moduleDir.toAbsolutePath().normalize();
        try {
            abs = abs.toRealPath();
        } catch (IOException notYetOnDisk) {
            // The absolute form is the identity until the directory exists.
        }
        return Hashing.sha256Hex(abs.toString()).substring(0, 12);
    }

    /** Record that this slot is in use, so the next run's reap leaves it alone. Best effort. */
    static void stamp(Path slot) {
        try {
            Files.createDirectories(slot);
            Files.writeString(slot.resolve(STAMP), "jk test sandbox; see cc.jumpkick.util.TestHomes\n");
        } catch (IOException | RuntimeException ignored) {
            // A missing stamp costs this home an early reap, never a failed build.
        }
    }

    /** A slot, when it was last handed out (the stamp's mtime, or the directory's without one), and its size. */
    private record Slot(Path dir, long usedMillis, long bytes) {}

    /**
     * Delete every slot whose stamp is older than {@link #KEEP_DAYS} — and every entry that carries no
     * stamp at all, since nothing else writes here — then the least recently stamped survivors until
     * the rest fit {@code capBytes}. Returns how many were removed. Best effort: a slot another
     * process still holds is left for the run after this one.
     */
    static int reapStale(Path root, long nowMillis, long capBytes) {
        long cutoff = nowMillis - KEEP_DAYS * 24L * 60 * 60 * 1000;
        int removed = 0;
        List<Slot> fresh = new ArrayList<>();
        long total = 0;
        try {
            List<Path> stale = new ArrayList<>();
            PathUtil.forEachChild(root, (slot, attrs) -> {
                // A symlink arrives as a non-directory here, so a link planted in the root is skipped
                // rather than followed out of it. The listing attributes also answer the unstamped case.
                if (!attrs.isDirectory()) return true;
                long used = PathUtil.stat(slot.resolve(STAMP))
                        .map(st -> st.lastModifiedTime().toMillis())
                        .orElseGet(() -> attrs.lastModifiedTime().toMillis());
                if (used <= cutoff) {
                    stale.add(slot);
                } else {
                    fresh.add(new Slot(slot, used, size(slot)));
                }
                return true;
            });
            for (Path slot : stale) {
                if (delete(slot)) removed++;
            }
        } catch (IOException | RuntimeException ignored) {
            // The reap is hygiene, never the reason a build fails.
        }
        for (Slot slot : fresh) total += slot.bytes();
        // Oldest first: the stamp is rewritten on every use, so it is a real use clock.
        fresh.sort(Comparator.comparingLong(Slot::usedMillis));
        for (Slot slot : fresh) {
            if (total <= capBytes) break;
            if (delete(slot.dir())) {
                removed++;
                total -= slot.bytes();
            }
        }
        return removed;
    }

    private static boolean delete(Path slot) {
        try {
            PathUtil.deleteRecursivelyOrThrow(slot);
            return true;
        } catch (IOException | RuntimeException heldOrDenied) {
            // Held by a concurrent run, or a permission we do not have. Next time.
            return false;
        }
    }

    /** Bytes of regular files under {@code slot}; links are counted as themselves, never followed. */
    private static long size(Path slot) {
        long[] total = {0};
        try {
            PathUtil.forEachRegularFile(slot, (file, attrs) -> total[0] += attrs.size());
        } catch (IOException | RuntimeException ignored) {
            // A slot that will not list is sized by what did list.
        }
        return total[0];
    }
}
