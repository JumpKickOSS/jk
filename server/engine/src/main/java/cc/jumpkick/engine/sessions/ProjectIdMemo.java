// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.sessions;

import cc.jumpkick.builds.ProjectIds;
import cc.jumpkick.lock.ManifestPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Each project directory's id, remembered for the {@link #KEPT} directories asked for last. Asking
 * for one not remembered may fork git; no lock of this memo is held while it does, so a caller
 * reading a remembered id never waits behind another directory's fork.
 */
final class ProjectIdMemo {

    /** Directories whose id is remembered; one past it is asked of git again. */
    static final int KEPT = 256;

    private final Function<String, @Nullable String> resolve;

    private final Map<String, @Nullable String> ids = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, @Nullable String> eldest) {
            return size() > KEPT;
        }
    };

    ProjectIdMemo() {
        this(ProjectIdMemo::resolve);
    }

    /** Test seam: {@code resolve} answers a directory's id in place of its manifest and git. */
    ProjectIdMemo(Function<String, @Nullable String> resolve) {
        this.resolve = Objects.requireNonNull(resolve, "resolve");
    }

    /** Whether {@code dir}'s id is remembered, without asking for it. */
    synchronized boolean knows(String dir) {
        return ids.containsKey(dir);
    }

    /** {@code dir}'s id when it is remembered; {@code null} when it is not, or it has none. Never asks git. */
    synchronized @Nullable String known(String dir) {
        return ids.get(dir);
    }

    /** {@code dir}'s project id; {@code null} for a directory with no jk manifest or none to read. */
    @Nullable
    String idOf(String dir) {
        synchronized (this) {
            if (ids.containsKey(dir)) return ids.get(dir);
        }
        String id;
        try {
            id = resolve.apply(dir);
        } catch (RuntimeException e) {
            id = null;
        }
        synchronized (this) {
            ids.put(dir, id);
        }
        return id;
    }

    /** Off a caller's monitor: ask for each of {@code dirs}' ids, so they are remembered. */
    void ask(@Nullable String... dirs) {
        for (String dir : dirs) if (dir != null && !dir.isBlank()) idOf(dir);
    }

    /** {@code e} with its project's id, asked for when it is not remembered. */
    Episode named(Episode e) {
        return e.projectId() != null || e.project().isBlank() ? e : e.withProjectId(idOf(e.project()));
    }

    /** {@code e} with its project's id when it is remembered; never asks git. */
    Episode namedIfKnown(Episode e) {
        return e.projectId() != null || e.project().isBlank() ? e : e.withProjectId(known(e.project()));
    }

    /** The id of {@code project} as remembered, or the one {@code r} already carries for it once this memo let it go. */
    @Nullable
    String knownFor(SessionRecord r, @Nullable String project) {
        if (project == null) return null;
        synchronized (this) {
            if (ids.containsKey(project)) return ids.get(project);
        }
        return project.equals(r.project()) ? r.projectId() : null;
    }

    synchronized int size() {
        return ids.size();
    }

    private static @Nullable String resolve(String dir) {
        // A directory with no jk manifest has no project identity; asking git costs a fork.
        return Files.isRegularFile(ManifestPaths.manifestIn(Path.of(dir))) ? ProjectIds.idOf(dir) : null;
    }
}
