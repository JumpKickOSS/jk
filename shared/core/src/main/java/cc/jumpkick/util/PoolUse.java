// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import cc.jumpkick.host.time.Clock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Use stamps for a tree that several processes share and a reaper bounds by least recent use. A tree
 * that holds {@link #MARKER} at its root has a unit's directory mtime set to now when the unit is
 * used: an artifact's version directory, a tool version, an Android SDK component. Reads leave no
 * other trace jk can rely on, since access times are off on many filesystems.
 *
 * <p>A unit is stamped at most once per {@link #EVERY_MILLIS} by one process, so a hot path pays a
 * map lookup. A tree without the marker is never written to.
 */
public final class PoolUse {

    /** The file at a tree's root that asks for use stamps. */
    public static final String MARKER = ".jk-pool";

    /** A unit this process stamped this recently is not stamped again. */
    public static final long EVERY_MILLIS = 60L * 60 * 1000;

    private static final ConcurrentHashMap<Path, Boolean> POOLED = new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<Path, Long> STAMPED = new ConcurrentHashMap<>();

    private PoolUse() {}

    /** Ask for use stamps in {@code tree}: writes {@link #MARKER} at its root, created. */
    public static void mark(Path tree) throws IOException {
        Path marker = Files.createDirectories(tree).resolve(MARKER);
        if (!Files.exists(marker)) Files.writeString(marker, "");
        POOLED.put(tree, true);
    }

    /** Record that {@code unit}, a directory under {@code tree}, is in use, if {@code tree} is marked. */
    public static void touch(Path tree, @Nullable Path unit) {
        if (unit == null || !POOLED.computeIfAbsent(tree, t -> Files.isRegularFile(t.resolve(MARKER)))) return;
        long now = Clock.SYSTEM.millis();
        Long last = STAMPED.get(unit);
        if (last != null && now - last < EVERY_MILLIS) return;
        STAMPED.put(unit, now);
        try {
            Files.setLastModifiedTime(unit, FileTime.fromMillis(now));
        } catch (IOException | RuntimeException absent) {
            // Not there yet, or already evicted: the write that lands it stamps it anew.
            STAMPED.remove(unit);
        }
    }
}
