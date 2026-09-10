// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.time.Clock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The {@code JK_HOME} a forked test JVM runs against: jk's whole layout, throwaway, and
 * <b>outside the project under test</b>.
 *
 * <p><b>Why not under the project.</b> This home carries jk's whole layout, {@code
 * store/templates/<key>/} among the rest. A {@code git} command handed a path inside a source tree
 * that has stopped existing resolves to the repository enclosing it, so a cache refresh there is a
 * {@code fetch --depth 1} and {@code reset --hard} against the developer's checkout — a shallow
 * repository with work missing. Pinning {@code --git-dir} is what stops the command; keeping the
 * home out of the tree is what leaves such a command nothing to aim at. "Not inside the project" is
 * the property, and a sibling of the project does not satisfy it: a workspace member's enclosing
 * repository is the workspace root.
 *
 * <p><b>Why the user home and not the platform temp root.</b> {@link cc.jumpkick.testing
 * ShortTempDirs} reached this conclusion first and for a measurable reason: on macOS {@code TMPDIR}
 * is already {@code /var/folders/xx/yy…/T/}, deep before anything is added to it, and it resolves
 * through {@code /var} → {@code /private/var} so a path compared against its own real form does not
 * match. Windows spends the same budget differently and has a hard 260-character ceiling. A
 * directory under {@code user.home} is short on all three, is never a drive root, and survives a
 * reboot — which matters here because this home is <b>warm on purpose</b>: it holds a fetched store,
 * and making it per-run would re-download every dependency on every {@code jk test}.
 *
 * <p><b>Cleanup, three ways.</b> Warm is not unbounded. {@link #prepare} stamps the slot it hands
 * out, and the first call in a JVM reaps every slot not stamped within {@link #KEEP_DAYS} days —
 * before the run, so a project that was deleted or renamed cannot leave one behind for ever. {@code
 * jk clean} deletes this module's slot outright, so a full clean still reaches the sandbox. Nothing
 * is deleted after a run: that is the warmth.
 *
 * <p><b>A slot, not just a home.</b> {@code JK_HOME} is {@code <slot>/home} rather than the slot
 * itself because the launcher derives the shared test cache as a <em>sibling</em> of {@code JK_HOME},
 * deliberately, so wiping the home does not wipe the cache. Flattening this would have turned a
 * per-module cache into one shared by every module on the machine.
 */
public final class TestHomes {

    /** Namespaced under {@code user.home}, beside {@code .jk} rather than inside it. */
    public static final String DIR = ".jk-test-homes";

    /** A slot untouched for this long belongs to a project that has moved on. */
    static final int KEEP_DAYS = 14;

    private static final String STAMP = ".used-at";
    private static final AtomicBoolean REAPED = new AtomicBoolean();

    private TestHomes() {}

    /** {@code ~/.jk-test-homes}, or {@code JK_TEST_HOMES_DIR} when a caller relocates the lot. */
    public static Path root() {
        String override = System.getenv("JK_TEST_HOMES_DIR");
        if (override != null && !override.isBlank()) {
            Path p = Path.of(override.trim());
            if (!p.isAbsolute()) {
                throw new IllegalStateException("JK_TEST_HOMES_DIR must be an absolute path: " + override);
            }
            return p;
        }
        return Path.of(System.getProperty("user.home")).resolve(DIR);
    }

    /**
     * This module's slot: {@code <root>/<key>}, holding the home and its sibling caches.
     *
     * <p>Keyed by a digest of the module's absolute path and nothing else: readable names spend the
     * Windows path budget this move exists to save, and two checkouts of one repository have the same
     * module names but must not share a store.
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
            reapStale(root(), clock.millis());
        }
        Path home = pathFor(moduleDir);
        Files.createDirectories(home);
        stamp(slotFor(moduleDir));
        return home;
    }

    /** The 12-hex key {@link #slotFor} uses; exposed so {@code jk clean} can find the same one. */
    public static String keyFor(Path moduleDir) {
        Path abs = moduleDir.toAbsolutePath().normalize();
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

    /**
     * Delete every slot whose stamp is older than {@link #KEEP_DAYS}, and every entry that carries no
     * stamp at all — nothing else writes here, so an unstamped directory is a crashed run's leftover.
     * Returns how many were removed. Best effort: a slot another process still holds is left for the
     * run after this one.
     */
    static int reapStale(Path root, long nowMillis) {
        long cutoff = nowMillis - KEEP_DAYS * 24L * 60 * 60 * 1000;
        int[] removed = {0};
        try {
            PathUtil.forEachChild(root, (slot, attrs) -> {
                // A symlink arrives as a non-directory here, so a link planted in the root is skipped
                // rather than followed out of it — the property that matters in code which deletes.
                // The listing attributes also answer the unstamped case without a second stat.
                if (!attrs.isDirectory()) return true;
                long used = PathUtil.stat(slot.resolve(STAMP))
                        .map(st -> st.lastModifiedTime().toMillis())
                        .orElseGet(() -> attrs.lastModifiedTime().toMillis());
                if (used > cutoff) return true;
                try {
                    PathUtil.deleteRecursivelyOrThrow(slot);
                    removed[0]++;
                } catch (IOException | RuntimeException ignored) {
                    // Held by a concurrent run, or a permission we do not have. Next time.
                }
                return true;
            });
        } catch (IOException | RuntimeException ignored) {
            // The reap is hygiene, never the reason a build fails.
        }
        return removed[0];
    }
}
