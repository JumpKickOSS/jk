// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.builds.ProjectBuilds;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Process-wide memo of checkout dir → durable project id.
 *
 * <p>{@link cc.jumpkick.builds.ProjectIdentity#resolve} parses jk.toml twice and, for a checkout
 * whose lock predates {@code project-id}, spawns git subprocesses — fine once per build, ruinous
 * once per journal row per {@code /api/history} request. Identity is stable for a
 * checkout except when a lock first mints an id, so a short TTL plus an explicit
 * {@link #refresh} at build admission keeps the memo honest.
 */
public final class ProjectIds {

    private static final long TTL_NANOS = TimeUnit.SECONDS.toNanos(60);
    private static final int MAX_ENTRIES = 4_096;
    private static final ConcurrentHashMap<String, Entry> CACHE = new ConcurrentHashMap<>();

    private record Entry(String id, long expiresAtNanos) {}

    private ProjectIds() {}

    /** The durable project id for {@code dir}, or null when it cannot be resolved. */
    public static String idOf(String dir) {
        if (dir == null || dir.isBlank()) return null;
        Entry e = CACHE.get(dir);
        if (e != null && System.nanoTime() - e.expiresAtNanos() < 0) return e.id();
        return refresh(dir);
    }

    /** Recompute and re-memoize {@code dir}'s id (build admission resolves identity anyway). */
    public static String refresh(String dir) {
        if (dir == null || dir.isBlank()) return null;
        try {
            String id = ProjectBuilds.key(Path.of(dir));
            if (CACHE.size() >= MAX_ENTRIES) CACHE.clear();
            CACHE.put(dir, new Entry(id, System.nanoTime() + TTL_NANOS));
            return id;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Test seam. */
    public static void clear() {
        CACHE.clear();
    }
}
