// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.time.Clock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * The sliding linger of a warm worker: its window restarts at its last use, at the last request
 * about a project it worked for ({@link ProjectActivity}), and at the last edit found in that
 * project's sources. The edit check runs only when a deadline arrives, never continuously, so its
 * cost is one bounded walk per would-be retirement.
 */
public final class KeepWarm {

    private KeepWarm() {}

    /** How long one edit check may walk; a check that runs out counts as an edit, keeping the worker. */
    public static final long EDIT_CHECK_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(100);

    /** Nanoseconds until the window that began at {@code lastActiveNanos} closes; at most zero when it has. */
    public static long untilDeadline(long nowNanos, long lastActiveNanos, long lingerNanos) {
        return lingerNanos - (nowNanos - lastActiveNanos);
    }

    /**
     * Whether a regular file under {@code roots} was modified after {@code sinceMillis} (wall
     * clock). Stops at the first such file; a walk that outlasts {@code budgetNanos} answers true,
     * since a tree too large to check in time is not evidence of idleness. Hidden directories are
     * not walked; nothing else is pruned by name (a source package may be called {@code build}).
     */
    public static boolean editedSince(Collection<Path> roots, long sinceMillis, long budgetNanos) {
        return editedSince(roots, sinceMillis, budgetNanos, Clock.SYSTEM);
    }

    static boolean editedSince(Collection<Path> roots, long sinceMillis, long budgetNanos, Clock clock) {
        long deadline = clock.nanos() + budgetNanos;
        boolean[] edited = {false};
        boolean[] overBudget = {false};
        for (Path root : roots) {
            if (edited[0] || overBudget[0]) break;
            if (!Files.isDirectory(root)) continue;
            try {
                PathUtil.forEachRegularFile(
                        root,
                        dir -> {
                            if (edited[0] || overBudget[0]) return true;
                            if (clock.nanos() - deadline > 0) {
                                overBudget[0] = true;
                                return true;
                            }
                            Path name = dir.getFileName();
                            return name != null && name.toString().startsWith(".");
                        },
                        (file, attrs) -> {
                            if (!edited[0] && attrs.lastModifiedTime().toMillis() > sinceMillis) edited[0] = true;
                        });
            } catch (IOException e) {
                // an unreadable root says nothing either way
            }
        }
        return edited[0] || overBudget[0];
    }

    /**
     * The directory a compile's own sources live under: their deepest common ancestor, leaving out
     * any under {@code generated} (sources a build writes, which move whenever it runs). Null when
     * there is none, or when the ancestor is too shallow to be a project's.
     */
    public static @Nullable Path sourceRoot(List<Path> sources, List<@Nullable Path> generated) {
        Path common = null;
        for (Path source : sources) {
            Path abs = source.toAbsolutePath().normalize();
            if (under(abs, generated)) continue;
            Path dir = abs.getParent();
            if (dir == null) continue;
            if (common == null) {
                common = dir;
                continue;
            }
            while (common != null && !dir.startsWith(common)) common = common.getParent();
            if (common == null) return null;
        }
        return common == null || common.getNameCount() < 2 ? null : common;
    }

    private static boolean under(Path file, List<@Nullable Path> dirs) {
        for (Path d : dirs) {
            if (d != null && file.startsWith(d.toAbsolutePath().normalize())) return true;
        }
        return false;
    }
}
