// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.host.time.Clock;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.ManifestPaths;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * When each project was last worked on through the engine: any request that names it — a CLI verb
 * of any kind, an MCP tool call, a job the web UI starts. A warm worker that compiled for a project
 * reads this to know the project is still being worked on even when nothing was compiled: a build
 * whose compiles all hit the cache, a test run, an agent asking for status. Projects are keyed by
 * the directory that owns their lock, so a request from a member module counts for its workspace.
 */
public final class ProjectActivity {

    private ProjectActivity() {}

    /** Projects remembered at most; past this the oldest knowledge is dropped wholesale. */
    private static final int MAX_PROJECTS = 1_024;

    private static final Map<Path, Long> LAST = new ConcurrentHashMap<>();

    private static volatile Clock clock = Clock.SYSTEM;

    /** A request named {@code dir}: its project was worked on now. Never throws. */
    public static void touch(@Nullable String dir) {
        if (dir == null || dir.isBlank()) return;
        try {
            touch(Path.of(dir));
        } catch (InvalidPathException e) {
            // not a path: nothing to credit
        }
    }

    /** As {@link #touch(String)}. */
    public static void touch(Path dir) {
        Path root = root(dir);
        if (LAST.size() >= MAX_PROJECTS && !LAST.containsKey(root)) LAST.clear();
        LAST.put(root, clock.nanos());
    }

    /** The latest activity, in {@link Clock#nanos()}, across {@code projects}; {@link Long#MIN_VALUE} for none. */
    public static long lastNanos(Collection<Path> projects) {
        long last = Long.MIN_VALUE;
        for (Path p : projects) {
            Long at = LAST.get(p);
            if (at != null && at > last) last = at;
        }
        return last;
    }

    /**
     * The project a source directory belongs to, as {@link #touch} keys it: the nearest ancestor
     * holding a jk manifest, then the directory owning its lock. Null when there is none.
     */
    public static @Nullable Path projectOf(Path sourceDir) {
        for (Path d = sourceDir.toAbsolutePath().normalize(); d != null; d = d.getParent()) {
            if (Files.isRegularFile(ManifestPaths.manifestIn(d))) return root(d);
        }
        return null;
    }

    static Path root(Path dir) {
        Path abs = dir.toAbsolutePath().normalize();
        try {
            return LockPaths.lockOwnerDir(abs).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return abs;
        }
    }

    /** Test seam: the clock activity is stamped with, and forget every project. */
    static void forTests(Clock testClock) {
        clock = testClock;
        LAST.clear();
    }
}
