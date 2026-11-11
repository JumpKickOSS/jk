// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import cc.jumpkick.host.time.Clock;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Sweeps the scratch an install left beside its target when its process died before cleaning up: a
 * kill, an OOM or a halt runs no {@code finally}. Only entries older than {@link #AGE} go, so a
 * concurrent install's scratch is never pulled from under it.
 */
public final class StaleScratch {

    /** How old an entry must be before it counts as orphaned. */
    public static final Duration AGE = Duration.ofHours(6);

    private StaleScratch() {}

    /**
     * Delete every direct child of {@code dir} whose name starts with {@code prefix} and that was
     * last modified more than {@link #AGE} ago, file or tree; no directory sweeps nothing. Never
     * throws; returns how many went.
     */
    public static int sweep(@Nullable Path dir, String prefix) {
        if (dir == null) return 0;
        long cutoff = Clock.SYSTEM.millis() - AGE.toMillis();
        List<Path> stale = new ArrayList<>();
        try {
            PathUtil.forEachChild(dir, (child, attrs) -> {
                if (child.getFileName().toString().startsWith(prefix)
                        && attrs.lastModifiedTime().toMillis() < cutoff) {
                    stale.add(child);
                }
                return true;
            });
        } catch (IOException unreadable) {
            // nothing here is load-bearing; the next sweep tries again
        }
        for (Path p : stale) PathUtil.deleteRecursively(p);
        return stale.size();
    }
}
