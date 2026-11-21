// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.sessions;

import cc.jumpkick.builds.ProjectIds;
import cc.jumpkick.lock.ManifestPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Each project directory's id, remembered for the {@link #KEPT} directories asked for last. */
final class ProjectIdMemo {

    /** Directories whose id is remembered; one past it is asked of git again. */
    static final int KEPT = 256;

    private final Map<String, @Nullable String> ids = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, @Nullable String> eldest) {
            return size() > KEPT;
        }
    };

    /** Whether {@code dir}'s id is remembered, without asking for it. */
    synchronized boolean knows(String dir) {
        return ids.containsKey(dir);
    }

    /** {@code dir}'s project id; {@code null} for a directory with no jk manifest or none to read. */
    synchronized @Nullable String idOf(String dir) {
        if (ids.containsKey(dir)) return ids.get(dir);
        String id;
        try {
            // A directory with no jk manifest has no project identity; asking git costs a fork.
            id = Files.isRegularFile(ManifestPaths.manifestIn(Path.of(dir))) ? ProjectIds.idOf(dir) : null;
        } catch (RuntimeException e) {
            id = null;
        }
        ids.put(dir, id);
        return id;
    }

    synchronized int size() {
        return ids.size();
    }
}
